"""Small USB-only adapter around pymobiledevice3's asynchronous services.

All imports of the optional iOS dependency are lazy. Nothing here uses a shell,
network discovery, AirPlay, external project resources, or a phone helper app.
"""
import asyncio
import json
import math
import os
import posixpath
import struct
import sys
import threading
import time
from contextlib import AsyncExitStack, suppress, contextmanager
from functools import wraps
from pathlib import Path, PurePosixPath

CLEANUP_TIMEOUT = 25
SERVICE_CLEANUP_TIMEOUT = 8
LOCKDOWN_CLEANUP_TIMEOUT = 3
_trace_lock = threading.Lock()


def _write_trace(value):
    line = json.dumps({'iosTrace': value})
    try:
        with _trace_lock:
            filename = os.environ.get('BBUI_IOS_TRACE_FILE')
            if filename and Path(filename).is_absolute():
                with Path(filename).open('a', encoding='utf-8') as output:
                    output.write(line + '\n')
            else:
                print(line, file=sys.stderr, flush=True)
    except OSError:
        pass  # Diagnostic output must not change dispatch or cleanup results.


@contextmanager
def trace_stage(stage):
    """Opt-in stage timings; never serialize arguments, results, or error text."""
    if os.environ.get('BBUI_IOS_TRACE') != '1':
        yield
        return
    started = time.perf_counter()
    _write_trace({'method': stage, 'status': 'start'})
    try:
        yield
    except BaseException as error:
        _write_trace({'method': stage, 'status': 'error', 'errorClass': type(error).__name__,
                      'elapsedMs': round((time.perf_counter() - started) * 1000, 2)})
        raise
    else:
        _write_trace({'method': stage, 'status': 'end',
                      'elapsedMs': round((time.perf_counter() - started) * 1000, 2)})


def traced_async(stage):
    def decorate(function):
        @wraps(function)
        async def wrapped(*args, **kwargs):
            with trace_stage(stage):
                return await function(*args, **kwargs)
        return wrapped
    return decorate


class ConnectionFailure(RuntimeError):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def describe_error(error):
    message = str(error).strip()
    if not message:
        if isinstance(error, (EOFError, asyncio.IncompleteReadError, ConnectionError)):
            message = 'iPhone 连接服务已断开，请检查 USB 后明确重新连接'
        elif isinstance(error, TimeoutError):
            message = 'iPhone 服务响应超时'
        elif isinstance(error, asyncio.CancelledError):
            message = '操作已取消'
        else:
            message = 'iPhone 服务调用失败'
    return type(error).__name__ + ': ' + message


def touch_report(points, releasing=False):
    """Apple mainTouchscreen report: five compact contacts, stable identities."""
    if len(points) > 5:
        raise ValueError('最多五个触点')
    report = bytearray((9, len(points), 5))
    identities = bytearray(5)
    for index in range(5):
        if index < len(points):
            x, y = points[index]
            if not (math.isfinite(x) and math.isfinite(y) and 0 <= x <= 1 and 0 <= y <= 1):
                raise ValueError('触点坐标无效')
            identity = index + 2
            identities[index] = identity
            report += struct.pack('<BHH', identity | (0 if releasing else 0xc0),
                                  round(x * 65535), round(y * 65535))
        else:
            report += bytes(5)
    return bytes(report + bytes(12) + identities + struct.pack('<Q', time.perf_counter_ns()) + bytes(5))


def afc_path(value):
    if not isinstance(value, str) or '\x00' in value or '\\' in value or ':' in value:
        raise ValueError('文件路径必须是手机 AFC 媒体目录路径')
    if '..' in PurePosixPath(value).parts:
        raise ValueError('不允许父目录路径')
    return posixpath.normpath('/' + value.lstrip('/'))


class IosTransport:
    def __init__(self, serial, status):
        self.serial, self.status = serial, status
        self.stack = AsyncExitStack()
        self.rsd = self.hid = self.keyboard = self.shot = None
        self.springboard = None
        self.keyboard_id = None
        self.mounted = False
        self.connected = False
        self.shot_lock = asyncio.Lock()
        self.orientation_lock = asyncio.Lock()
        self.before_dispatch = lambda: None
        self.on_dispatch = lambda: None
        self.warnings = []

    async def connect(self):
        from pymobiledevice3 import usbmux, exceptions
        from pymobiledevice3.lockdown import create_using_usbmux
        from pymobiledevice3.services.mobile_image_mounter import MobileImageMounterService, auto_mount_personalized
        from pymobiledevice3.remote.userspace_tunnel import UserspaceRsdTunnel
        from pymobiledevice3.remote.core_device.hid_service import UniversalHIDServiceService
        from pymobiledevice3.services.dvt.instruments.dvt_provider import DvtProvider
        from pymobiledevice3.services.dvt.instruments.screenshot import Screenshot
        self.status('connecting', '正在连接 USB iPhone')
        try:
            devices = await usbmux.list_devices()
        except Exception as error:
            raise ConnectionFailure('driver_missing', '无法访问 Apple 设备服务；请安装 Apple Devices 或 Apple 移动设备支持') from error
        matches = [device for device in devices if device.is_usb and device.matches_udid(self.serial)]
        if len(matches) != 1:
            raise ConnectionFailure('no_device', '未找到选定的 USB iPhone')
        self.serial = matches[0].serial
        lockdown = None
        try:
            lockdown = await create_using_usbmux(serial=self.serial, connection_type='USB')
            if int(lockdown.product_version.split('.')[0]) < 18:
                raise ConnectionFailure('unsupported_version', '当前控制通道需要 iOS 18 或更新版本')
            if not await lockdown.get_developer_mode_status():
                raise ConnectionFailure('developer_mode_off', '请在 iPhone 设置中打开开发者模式并按系统要求重启')
            async with MobileImageMounterService(lockdown=lockdown) as mounter:
                mounted = await mounter.is_image_mounted('Personalized')
            if not mounted:
                self.status('preparing_support', '正在准备 Apple 开发者支持文件，首次可能需要联网')
                try:
                    await asyncio.wait_for(auto_mount_personalized(lockdown), 180)
                    self.mounted = True
                except exceptions.AlreadyMountedError:
                    pass  # Another developer client mounted it; do not claim ownership.
        except exceptions.PasswordRequiredError as error:
            raise ConnectionFailure('device_locked', '请解锁 iPhone') from error
        except exceptions.PairingError as error:
            raise ConnectionFailure('trust_pending', '请在 iPhone 上信任这台电脑') from error
        finally:
            if lockdown:
                await lockdown.close()
        self.status('connecting', '正在建立 USB 控制与截图连接')
        try:
            self.rsd = await self.stack.enter_async_context(UserspaceRsdTunnel(serial=self.serial))
            # This is the directly verified iOS 18 mainTouchscreen service. We
            # deliberately do not request a media stream or a network fallback.
            self.hid = await self.stack.enter_async_context(UniversalHIDServiceService(self.rsd))
            await self.hid.send_report(0x101, touch_report([]))
            dvt = await self.stack.enter_async_context(DvtProvider(self.rsd))
            self.shot = await self.stack.enter_async_context(Screenshot(dvt))
            await self.screenshot()
            # Open and verify this read channel during connection, so the first
            # manual input does not pay a separate SpringBoard handshake.
            await self.orientation()
            self.connected = True
        except BaseException:
            await self.close()
            raise

    @traced_async('transport.screenshot')
    async def screenshot(self):
        async with self.shot_lock:
            return await asyncio.wait_for(self.shot.get_screenshot(), 10)

    @traced_async('transport.usb_present')
    async def usb_present(self):
        from pymobiledevice3 import usbmux
        return any(device.is_usb and device.matches_udid(self.serial) for device in await usbmux.list_devices())

    @traced_async('transport.orientation')
    async def orientation(self):
        from pymobiledevice3.services.springboard import SpringBoardServicesService
        # SpringBoard uses a reusable plist connection. A lock pairs each
        # request with its own reply; every caller still reads current direction.
        async with self.orientation_lock:
            if self.springboard is None:
                self.springboard = await self.stack.enter_async_context(SpringBoardServicesService(self.rsd))
            return int(await asyncio.wait_for(self.springboard.get_interface_orientation(), 5))

    @traced_async('transport.release')
    async def release(self):
        if self.hid:
            with suppress(Exception):
                await asyncio.wait_for(self.hid.send_report(0x101, touch_report([])), 3)
        if self.keyboard and self.keyboard_id:
            with suppress(Exception):
                await asyncio.wait_for(self.keyboard.send_keyboard(self.keyboard_id, []), 3)

    async def gesture(self, paths, seconds, guard, hold=0):
        steps = max(1, round(seconds * 60))
        hold_steps = round(hold * 60)
        last = [start for start, _ in paths]
        try:
            for sample in range(hold_steps + steps + 1):
                index = max(0, sample - hold_steps)
                guard()
                self.before_dispatch()
                fraction = index / steps
                last = [(a[0] + (b[0] - a[0]) * fraction, a[1] + (b[1] - a[1]) * fraction) for a, b in paths]
                await asyncio.wait_for(self.hid.send_report(0x101, touch_report(last)), 5)
                self.on_dispatch()
                if sample < hold_steps:
                    await asyncio.sleep(hold / hold_steps)
                elif index < steps:
                    await asyncio.sleep(seconds / steps)
        finally:
            try:
                await asyncio.wait_for(self.hid.send_report(0x101, touch_report(last, releasing=True)), 3)
            finally:
                await self.release()

    async def key(self, usage, guard, command=False):
        from pymobiledevice3.remote.core_device.hid_service import UniversalHIDServiceService
        if self.keyboard is None:
            self.keyboard = await self.stack.enter_async_context(UniversalHIDServiceService(self.rsd))
            self.keyboard_id = await self.keyboard.create_keyboard_service(product='BBUI')
            await asyncio.sleep(.6)
        sequence = [[227], [227, usage], [227], []] if command else [[usage], []]
        try:
            for keys in sequence:
                guard()
                self.before_dispatch()
                await self.keyboard.send_keyboard(self.keyboard_id, keys)
                self.on_dispatch()
                await asyncio.sleep(.1)
        finally:
            await self.keyboard.send_keyboard(self.keyboard_id, [])

    async def home(self, recent, guard):
        from pymobiledevice3.remote.core_device.hid_service import IndigoHIDService
        async with IndigoHIDService(self.rsd) as service:
            for _ in range(2 if recent else 1):
                guard()
                try:
                    self.before_dispatch()
                    await service.send_button(12, 64, 1)
                    self.on_dispatch()
                    await asyncio.sleep(.06)
                finally:
                    await service.send_button(12, 64, 2)
                await asyncio.sleep(.10)

    async def clipboard(self, operation, value=None):
        from pymobiledevice3.remote.core_device.pasteboard_service import PasteboardService
        # This service closes its XPC session after a request on the tested DDI.
        async with PasteboardService(self.rsd) as service:
            if operation == 'snapshot':
                return await service.get()
            if operation == 'restore':
                board = value.get('pasteboard', value)
                return await service.set(board.get('items', []), source_metadata=board.get('sourceMetadata'))
            if operation == 'read':
                return await service.get_text()
            return await service.set_text(value if operation == 'write' else '')

    async def text(self, value, guard):
        original = await self.clipboard('snapshot')
        board = clipboard_identity(original)
        if any(datum.get('isPromised') for item in board.get('items', []) for datum in item.get('data', {}).values()):
            raise ValueError('原剪贴板含未解析数据，无法保证恢复，未写入')
        temporary = None
        try:
            guard()
            self.before_dispatch()
            written = await self.clipboard('write', value)
            self.on_dispatch()
            # SET_REPLY deliberately omits inline item data. Its metadata is
            # the atomic write version; a later PULL could already be a user's
            # copy, so never adopt that later snapshot as our ownership proof.
            temporary = clipboard_version(written)
            await self.key(25, guard, command=True)
            await asyncio.sleep(.2)
        finally:
            try:
                current = await self.clipboard('snapshot')
                # If SET's reply was lost, ownership cannot be established: do
                # not overwrite a potentially intervening copy by the user.
                if temporary is not None and clipboard_version(current) == temporary:
                    await self.clipboard('restore', original)
                elif temporary is None:
                    self.warnings.append('未能确认临时剪贴板版本，未覆盖当前剪贴板')
            except Exception as error:
                self.warnings.append('恢复原剪贴板失败：' + str(error))

    async def apps(self, operation, params):
        from pymobiledevice3.services.dvt.instruments.dvt_provider import DvtProvider
        from pymobiledevice3.services.dvt.instruments.application_listing import ApplicationListing
        from pymobiledevice3.services.dvt.instruments.process_control import ProcessControl
        async with DvtProvider(self.rsd) as provider:
            if operation in {'list', 'details'}:
                async with ApplicationListing(provider) as service:
                    apps = await service.applist()
                entries = []
                for app in apps:
                    bundle = app.get('CFBundleIdentifier', '')
                    if app.get('Type') == 'PluginKit' or not bundle:
                        continue
                    entries.append({'packageName': bundle, 'label': app.get('DisplayName') or app.get('CFBundleDisplayName') or app.get('CFBundleName') or bundle,
                                    'version': app.get('Version') or app.get('CFBundleShortVersionString'),
                                    'system': (app.get('Type') or app.get('ApplicationType')) == 'System'})
                if operation == 'details':
                    return next((app for app in entries if app['packageName'] == params['packageName']), None)
                keyword = params.get('query', params.get('keyword', '')).casefold()
                return page([app for app in entries if keyword in (app['packageName'] + ' ' + app['label']).casefold()
                             and (params.get('includeSystem', True) or not app['system'])], params)
            async with ProcessControl(provider) as service:
                bundle = params['packageName']
                if operation == 'launch':
                    self.before_dispatch()
                    pid = await service.launch(bundle, kill_existing=False)
                    self.on_dispatch()
                    return {'pid': pid}
                pid = await service.process_identifier_for_bundle_identifier(bundle)
                if pid:
                    self.before_dispatch()
                    await service.kill(pid)
                    self.on_dispatch()
                return {'pid': pid, 'requested': bool(pid)}

    async def files(self, operation, params):
        from pymobiledevice3.lockdown import create_using_usbmux
        from pymobiledevice3.services.afc import AfcService
        lockdown = await create_using_usbmux(serial=self.serial, connection_type='USB')
        try:
            async with AfcService(lockdown) as service:
                return await afc_operation(service, operation, params, self.before_dispatch, self.on_dispatch)
        finally:
            await lockdown.close()

    async def close(self):
        self.connected = False
        try:
            async with asyncio.timeout(CLEANUP_TIMEOUT):
                await self._cleanup()
        except TimeoutError as error:
            raise RuntimeError('iPhone 退出清理超过总期限，尚未确认设备资源释放') from error

    async def _cleanup(self):
        cleanup_error = None
        try:
            await asyncio.wait_for(self.release(), SERVICE_CLEANUP_TIMEOUT)
        except Exception as error:
            cleanup_error = error
        try:
            await asyncio.wait_for(self.stack.aclose(), SERVICE_CLEANUP_TIMEOUT)
        except Exception as error:
            cleanup_error = error
        if self.mounted:
            try:
                # Includes opening lockdown, opening mounter, the unmount RPC
                # and service close. None of these may outlive host shutdown.
                await asyncio.wait_for(self._unmount_owned_image(), SERVICE_CLEANUP_TIMEOUT)
            except Exception as error:
                cleanup_error = error
        if cleanup_error:
            raise RuntimeError('iPhone 退出清理未确认完成：' + describe_error(cleanup_error)) from cleanup_error

    async def _unmount_owned_image(self):
        from pymobiledevice3.lockdown import create_using_usbmux
        from pymobiledevice3.services.mobile_image_mounter import MobileImageMounterService
        lockdown = None
        try:
            lockdown = await create_using_usbmux(serial=self.serial, connection_type='USB', autopair=False)
            async with MobileImageMounterService(lockdown=lockdown) as mounter:
                await mounter.unmount_image('/System/Developer')
                self.mounted = False  # Keep ownership on failure for diagnostics.
        finally:
            if lockdown:
                await asyncio.wait_for(lockdown.close(), LOCKDOWN_CLEANUP_TIMEOUT)


def clipboard_identity(snapshot):
    return snapshot.get('pasteboard', snapshot)


def clipboard_version(snapshot):
    if not isinstance(snapshot, dict):
        return None
    metadata = clipboard_identity(snapshot).get('metadata', {})
    name, nonce, count = (metadata.get(key) for key in ('pasteboardName', 'nonce', 'changeCount'))
    if not isinstance(name, str) or not name or not isinstance(nonce, str) or not nonce or isinstance(count, bool) or not isinstance(count, int) or count < 0:
        return None
    return name, nonce, count


def page(items, params):
    offset, limit = params.get('offset', 0), params.get('limit', 50)
    if type(offset) is not int or offset < 0 or type(limit) is not int or not 1 <= limit <= 200:
        raise ValueError('offset 必须非负，limit 必须为 1～200')
    return {'items': items[offset:offset + limit], 'total': len(items),
            'nextOffset': offset + limit if offset + limit < len(items) else None}


async def afc_operation(service, operation, params, before=lambda: None, receipt=lambda: None):
    path = afc_path(params.get('path'))
    destination = afc_path(params.get('destination')) if operation in {'copy', 'move', 'rename'} else None
    if operation in {'write_text', 'mkdir', 'copy', 'move', 'rename', 'delete'} and path == '/':
        raise ValueError('不能修改 AFC 根目录')
    if operation == 'stat':
        return await service.stat(path)
    if operation == 'list':
        return page([{'name': name, 'path': posixpath.join(path, name), **await service.stat(posixpath.join(path, name))}
                     for name in await service.listdir(path) if name not in {'.', '..'}], params)
    if operation == 'search':
        if not isinstance(params.get('query'), str):
            raise ValueError('搜索需要 query 字符串')
        query = params['query'].casefold()
        result = []
        async for directory, directories, files in service.walk(path):
            for name in directories + files:
                if query in name.casefold():
                    result.append({'name': name, 'path': posixpath.join(directory, name)})
        return page(result, params)
    if operation in {'read_text', 'copy'}:
        info = await service.stat(path)
        if info.get('st_ifmt') == 'S_IFLNK':
            raise ValueError('不支持 AFC 符号链接')
        if operation == 'copy' and info.get('st_ifmt') == 'S_IFDIR':
            if not params.get('recursive'):
                raise ValueError('复制目录需要 recursive=true')
            if destination == '/' or destination == path or destination.startswith(path + '/') or path.startswith(destination + '/'):
                raise ValueError('目标不能位于源目录内或覆盖源祖先')
            if await service.exists(destination) and not params.get('overwrite'):
                raise ValueError('目标已存在，需要 overwrite=true')
            before()
            await service.makedirs(destination)
            receipt()
            for name in await service.listdir(path):
                if name not in {'.', '..'}:
                    await afc_operation(service, 'copy', {**params, 'path': posixpath.join(path, name),
                                                        'destination': posixpath.join(destination, name)}, before, receipt)
            return {'path': destination}
        if operation == 'read_text':
            if int(info.get('st_size', 0)) > 10 * 1024 * 1024:
                raise ValueError('文本读取上限为 10 MiB；请缩小文件范围')
            data = await service.get_file_contents(path)
            text = data.decode('utf-8-sig')
            if '\x00' in text:
                raise ValueError('二进制文件不支持文本读取')
            # Match Android's UTF-16 paging contract, including surrogate bounds.
            raw = text.encode('utf-16-le')
            offset, limit = params.get('offset', 0), params.get('limit', 10000)
            if type(offset) is not int or offset < 0 or type(limit) is not int or not 2 <= limit <= 32768:
                raise ValueError('文本 offset/limit 无效')
            end = min(len(raw), (offset + limit) * 2)
            if end >= 2 and end < len(raw) and 0xd800 <= int.from_bytes(raw[end - 2:end], 'little') <= 0xdbff:
                end -= 2
            text = raw[offset * 2:end].decode('utf-16-le')
            return {'text': text, 'nextOffset': end // 2 if end < len(raw) else None, 'offsetUnit': 'UTF-16 characters'}
        if destination == '/' or destination == path or destination.startswith(path + '/') or (await service.exists(destination) and not params.get('overwrite')):
            raise ValueError('目标文件已存在或无效')
        source_handle = await service.fopen(path, 'r')
        target_handle = None
        copied = 0
        try:
            before()
            target_handle = await service.fopen(destination, 'w')
            receipt()
            while True:
                data = await service.fread(source_handle, 65536)
                if not data:
                    break
                before()
                await service.fwrite(target_handle, data)
                receipt()
                copied += len(data)
        finally:
            await service.fclose(source_handle)
            if target_handle is not None:
                await service.fclose(target_handle)
        return {'path': destination, 'bytes': copied}
    if operation == 'write_text':
        text = params.get('text', params.get('content'))
        if not isinstance(text, str) or len(text.encode('utf-8')) > 10 * 1024 * 1024:
            raise ValueError('文件文本必须是 UTF-8 字符串，最大 10 MiB')
        if await service.exists(path) and not params.get('overwrite'):
            raise ValueError('目标已存在，需要 overwrite=true')
        before()
        await service.set_file_contents(path, text.encode('utf-8'))
        receipt()
    elif operation == 'mkdir':
        if not params.get('recursive') and not await service.isdir(posixpath.dirname(path)):
            raise ValueError('父目录不存在；递归创建需要 recursive=true')
        before()
        await service.makedirs(path)
        receipt()
    elif operation in {'move', 'rename'}:
        if destination == '/' or destination == path or destination.startswith(path + '/') or path.startswith(destination + '/') or (await service.exists(destination) and not params.get('overwrite')):
            raise ValueError('目标路径已存在或无效')
        before()
        await service.rename(path, destination)
        receipt()
    elif operation == 'delete':
        if params.get('recursive'):
            info = await service.stat(path)
            if info.get('st_ifmt') == 'S_IFDIR':
                for name in await service.listdir(path):
                    if name not in {'.', '..'}:
                        await afc_operation(service, 'delete', {'path': posixpath.join(path, name), 'recursive': True}, before, receipt)
        before()
        await service.rm_single(path)
        receipt()
    else:
        raise ValueError('不支持的文件操作')
    return {'path': destination if operation in {'move', 'rename'} else path}
