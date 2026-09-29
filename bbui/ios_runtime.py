"""One host-owned iPhone executor, durable receipts and independent observations."""
import asyncio
import base64
import hashlib
import io
import json
import math
import os
import re
import sys
import threading
import time
import uuid
from collections import deque
from pathlib import Path
from contextlib import suppress

from PIL import Image
from .ios_transport import IosTransport, ConnectionFailure, describe_error
from .channel_errors import ChannelFailure, transport_failed
from .ios_platform import new_event_loop

PHONE_OPERATIONS = ['查看', '等待', '点击', '双击', '长按', '滑动', '拖拽', '放大', '缩小',
                    '输入内容', '删除内容', '全选', '按键', '打开应用', '列出应用', '列出屏幕']
PHONE_KEYS = ['主页', '最近任务', '回车', '删除', '向前删除', '上', '下', '左', '右']
SYSTEM_OPERATIONS = {'apps': ['list', 'details', 'launch', 'force_stop'],
                     'clipboard': ['read', 'write', 'clear'],
                     'files': ['list', 'stat', 'search', 'read_text', 'write_text', 'mkdir', 'copy', 'move', 'rename', 'delete']}
CAPABILITIES = {'devicePlatform': 'ios', 'phoneOperations': PHONE_OPERATIONS,
                'phoneKeys': PHONE_KEYS, 'systemOperations': SYSTEM_OPERATIONS,
                'appRestrictions': False, 'previewMode': 'screenshots', 'previewFps': 15}
READ_ONLY = {'apps': {'list', 'details'}, 'clipboard': {'read'}, 'files': {'list', 'stat', 'search', 'read_text'}}


def device_id(serial):
    return 'ios:' + serial.replace('-', '').upper()


def state_directory(serial):
    # Deliberately independent of portable install and per-chat data directories.
    base = Path(os.environ.get('LOCALAPPDATA', Path.home() / '.local' / 'share')) / 'BBUI' / 'devices'
    return base / hashlib.sha256(device_id(serial).encode()).hexdigest()[:24]


def write_json(path, value):
    temporary = path.with_suffix('.tmp')
    with temporary.open('w', encoding='utf-8') as output:
        json.dump(value, output, ensure_ascii=False, default=str)
        output.flush()
        os.fsync(output.fileno())
    temporary.replace(path)


class DeviceLease:
    def __init__(self, directory):
        self.directory, self.file = Path(directory), None

    def acquire(self):
        self.directory.mkdir(parents=True, exist_ok=True)
        stream = (self.directory / 'executor.lock').open('a+b')
        stream.seek(0, 2)
        if not stream.tell():
            stream.write(b'0')
            stream.flush()
        stream.seek(0)
        try:
            if os.name == 'nt':
                import msvcrt
                msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as error:
            stream.close()
            raise RuntimeError('这台 iPhone 已被另一个大鸟手机助手执行器占用') from error
        self.file = stream

    def close(self):
        if self.file:
            self.file.close()
            self.file = None


def verify_lease_released():
    config = json.loads(Path(os.environ['BBUI_CONFIG_FILE']).read_text('utf-8'))
    lease = DeviceLease(state_directory(config['serial']))
    lease.acquire()
    lease.close()


def milliseconds(params, key='时间', default=400, maximum=5000):
    value = params.get(key, default)
    if type(value) not in (int, float) or not math.isfinite(value) or not 0 <= value <= maximum:
        raise ValueError(f'{key} 必须为 0～{maximum} 毫秒')
    return value / 1000


def point(value, size):
    if not isinstance(value, list) or len(value) != 2 or any(type(v) is not int for v in value):
        raise ValueError('坐标必须是原图整数像素 [x,y]')
    if not all(0 <= v < bound for v, bound in zip(value, size)):
        raise ValueError('坐标超出原始截图范围')
    return tuple(v / bound for v, bound in zip(value, size))


def encode_preview(data):
    """Decode/resize/encode away from the device event loop; retain source size."""
    with Image.open(io.BytesIO(data)) as picture:
        size = picture.size
        picture.thumbnail((900, 1200))
        output = io.BytesIO()
        picture.convert('RGB').save(output, 'JPEG', quality=78)
    return size, 'data:image/jpeg;base64,' + base64.b64encode(output.getvalue()).decode()


class IosDevice:
    def __init__(self, config=None, run=None, state=None, transport_factory=IosTransport):
        root = Path(__file__).resolve().parents[1]
        self.config = config or json.loads(Path(os.environ.get('BBUI_CONFIG_FILE', root / 'config.local.json')).read_text('utf-8'))
        self.serial = self.config['serial']
        self.identity = device_id(self.serial)
        self.owner = os.environ.get('BBUI_BRIDGE_OWNER', 'standalone-' + uuid.uuid4().hex)
        self.state = Path(state) if state else state_directory(self.serial)
        self.run = Path(run) if run else Path(os.environ.get('BBUI_RUNS_DIR', root / 'runs')) / ('ios-' + self.serial.replace('-', ''))
        self.run.mkdir(parents=True, exist_ok=True)
        self.lease = DeviceLease(self.state)
        self.connection_status = {'code': 'connecting', 'message': '正在连接 USB iPhone'}
        self.connected = False
        self.closed = False
        self.held = False
        self.token = None
        self.generation = 0
        self.latest = None
        self.size = None
        self.orientation = None
        self.frames = deque(maxlen=128)
        self.sequence = 0
        self.last_frame = None
        self.last_capture_at = 0
        self.last_capture_started = 0
        self.last_usb_check = 0
        self.preview_timings = os.environ.get('BBUI_IOS_PREVIEW_TIMINGS') == '1'
        self.preview_task = None
        self.connect_task = None
        self.transport_factory = transport_factory
        self.loop = new_event_loop()
        self.thread = threading.Thread(target=self._run_loop, name='bbui-ios', daemon=True)
        self.thread.start()
        self.call(self._initialize(), timeout=5)
        # Keep USB preparation separate so construction returns before device I/O.
        self.connect_future = asyncio.run_coroutine_threadsafe(self._connect(), self.loop)

    def _run_loop(self):
        asyncio.set_event_loop(self.loop)
        self.loop.run_forever()

    def call(self, coroutine, timeout=220):
        future = asyncio.run_coroutine_threadsafe(coroutine, self.loop)
        try:
            return future.result(timeout)
        except TimeoutError:
            future.cancel()
            raise

    async def _initialize(self):
        self.mutation = asyncio.Lock()
        self.capture_lock = asyncio.Lock()
        self.transport = self.transport_factory(self.serial, self._status)

    def _status(self, code, message):
        self.connection_status = {'code': code, 'message': message}

    async def _connect(self):
        self.connect_task = asyncio.current_task()
        try:
            self.lease.acquire()
            await asyncio.wait_for(self.transport.connect(), 205)
            self.connected = True
            self._status('ready', 'USB iPhone 已连接')
        except asyncio.CancelledError:
            await self.transport.close()
            self.lease.close()
            raise
        except Exception as error:
            self._status(getattr(error, 'code', 'connection_failed'), describe_error(error))
            await self.transport.close()
            self.lease.close()

    async def ready(self):
        if self.closed:
            raise RuntimeError('iPhone 执行器已关闭')
        await asyncio.shield(asyncio.wrap_future(self.connect_future))
        if not self.connected:
            raise RuntimeError(self.connection_status['message'])

    def stopped(self):
        return (self.state / 'STOP').exists() or (self.run / 'STOP').exists()

    def status_snapshot(self):
        # Read by the HTTP thread while upstream DDI preparation may occupy the
        # event loop. No device I/O or reconnection occurs on this path.
        return {'connected': self.connected and not self.closed and self.connection_status['code'] != 'control_disconnected', 'stopped': self.stopped(),
                'deviceId': self.identity, 'capabilities': CAPABILITIES,
                'connectionStatus': self.connection_status}

    def control_failed(self, error):
        if not transport_failed(error):
            return False
        self._status('control_disconnected', describe_error(error))
        self.held, self.token = True, None
        self.generation += 1
        self.latest = None
        return True

    def quarantine(self, reason):
        try:
            with (self.state / 'INPUT_UNCERTAIN').open('x', encoding='utf-8') as output:
                json.dump({'owner': self.owner, 'reason': reason, 'at': int(time.time() * 1000)}, output)
                output.flush()
                os.fsync(output.fileno())
        except FileExistsError:
            pass

    def guard(self, token=None, generation=None):
        if self.closed or not self.connected:
            raise RuntimeError('iPhone 未连接')
        if self.stopped():
            raise RuntimeError('设备已停止，模型不能恢复输入')
        if (self.state / 'INPUT_UNCERTAIN').exists():
            raise RuntimeError('旧输入结果不确定，需宿主确认旧执行器退出后恢复连接')
        if generation is not None and self.generation != generation:
            raise RuntimeError('控制权代次已变化')
        if token is not None:
            if not self.held or not token or token != self.token:
                raise ValueError('人工控制权已过期')
        elif self.held:
            raise RuntimeError('设备输入已由宿主暂停')

    def _screen(self, name):
        if name != 'main':
            raise ValueError('iPhone 仅支持 main 主屏')

    def input_guard(self, size, generation, token=None):
        self.guard(token, generation)
        if self.size != size:
            raise ValueError('屏幕尺寸已变化，输入已取消，请等待新画面')

    async def _capture(self, observe=False):
        await self.ready()
        async with self.capture_lock:
            timing_start = time.perf_counter()
            if not observe:
                await asyncio.sleep(max(0, self.last_capture_started + 1 / 15 - time.perf_counter()))
                self.last_capture_started = time.perf_counter()
            capture_start = time.perf_counter()
            try:
                data = await self.transport.screenshot()
            except Exception as error:
                # A screen channel failure does not erase a preceding dispatch.
                raise ChannelFailure('observation', describe_error(error)) from error
            capture_end = time.perf_counter()
            if observe:
                with Image.open(io.BytesIO(data)) as original:
                    size = original.size
            else:
                size, image = await asyncio.to_thread(encode_preview, data)
                if self.preview_timings:
                    print(json.dumps({'iosPreviewTiming': {'rateWaitMs': round((capture_start - timing_start) * 1000, 2),
                        'screenshotMs': round((capture_end - capture_start) * 1000, 2),
                        'encodeMs': round((time.perf_counter() - capture_end) * 1000, 2), 'pngBytes': len(data)}}),
                        file=sys.stderr, flush=True)
            if self.size != size:
                self.size = size
                self.frames.clear()
                self.latest = None
            if not observe:
                self.sequence += 1
                frame_id = uuid.uuid4().hex
                self.frames.append({'id': frame_id, 'size': size, 'generation': self.generation})
                result = {'frameId': frame_id, 'screen': 'main', 'width': size[0], 'height': size[1],
                          'sequence': self.sequence, 'image': image}
                self.last_frame, self.last_capture_at = result, time.perf_counter()
                return result
            orientation = await self.read_orientation()
            self.orientation = orientation
            oid = uuid.uuid4().hex
            folder = self.run / oid
            folder.mkdir()
            path = folder / 'screenshot.png'
            path.write_bytes(data)
            self.latest = {'id': oid, 'size': size, 'orientation': orientation, 'generation': self.generation}
            screen = {'width': size[0], 'height': size[1], 'display_id': 0, 'rotation': orientation,
                      'deviceId': self.identity, 'devicePlatform': 'ios'}
            write_json(folder / 'observation.json', {'observation_id': oid, 'screenshot': str(path), **screen})
            return {'截图编号': oid, '截图': str(path), '屏幕': screen, '屏幕会话': 'main',
                    '显示屏编号': 0, '可用于输入': orientation == 1 and size[0] < size[1],
                    '观察': {'状态': '已取得'}, '通道': {'观察': '正常'}}

    async def observe_result(self):
        try:
            return await self._capture(observe=True)
        except Exception as error:
            return {'观察': {'状态': '失败'}, '观察错误': describe_error(error), '通道': {'观察': '失败'}}

    async def read_orientation(self):
        try:
            return await self.transport.orientation()
        except Exception as error:
            raise ChannelFailure('observation', describe_error(error)) from error

    async def frame(self, params):
        self._screen(params['screen'])
        await self.ready()
        if self.preview_task is None or self.preview_task.done():
            self.preview_task = asyncio.create_task(self._capture())
        result = await asyncio.shield(self.preview_task)
        return None if params.get('afterFrameId') == result['frameId'] else result

    def claim(self, action_id):
        if not isinstance(action_id, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,100}', action_id):
            raise ValueError('缺少有效动作编号')
        return self.state / ('action-' + action_id + '.json')

    async def validate_observation(self, params, token=None):
        if token is None:
            observation = self.latest
            if not observation or params.get('截图编号') != observation['id']:
                raise ValueError('动作必须携带本屏幕最新截图编号')
        else:
            observation = next((frame for frame in self.frames if frame['id'] == params.get('frameId')), None)
            if not observation:
                raise ValueError('预览画面已过期，请等待刷新')
        if observation['generation'] != self.generation or observation['size'] != self.size:
            raise ValueError('画面尺寸或控制权已变化，请等待刷新')
        orientation = await self.read_orientation()
        if orientation != 1 or self.size[0] >= self.size[1]:
            self.frames.clear()
            self.latest = None
            raise ValueError('当前版本仅验收正向竖屏输入，请将手机转回竖屏并重新观察')
        return observation['size']

    def validate_action(self, operation, params, size):
        if operation not in PHONE_OPERATIONS:
            raise ValueError('iPhone 不支持此操作')
        milliseconds(params, '执行后等待毫秒', 0, 30000)
        if operation in {'点击', '双击', '长按'}:
            point(params.get('位置'), size)
        if operation in {'滑动', '拖拽'}:
            point(params.get('起点'), size)
            point(params.get('终点'), size)
            if operation == '拖拽':
                milliseconds(params, '按住时间', 600)
        if operation in {'放大', '缩小'}:
            point(params.get('中心'), size)
            params.setdefault('初始指距', 200)
            params.setdefault('结束指距', 400 if operation == '放大' else 100)
            for key in ('初始指距', '结束指距'):
                value = params.get(key)
                if type(value) not in (int, float) or not math.isfinite(value) or not 0 < value <= min(size):
                    raise ValueError('指距必须是屏幕范围内的正数像素')
                cx, cy = params['中心']
                point([round(cx - value / 2), cy], size)
                point([round(cx + value / 2), cy], size)
            if (operation == '放大' and params['结束指距'] <= params['初始指距']) or (operation == '缩小' and params['结束指距'] >= params['初始指距']):
                raise ValueError('指距方向与操作不一致')
        if operation in {'点击', '双击', '长按', '滑动', '拖拽', '放大', '缩小'}:
            milliseconds(params)
        if operation == '输入内容' and (not isinstance(params.get('内容'), str) or not 0 < len(params['内容']) <= 32768):
            raise ValueError('内容必须为 1～32768 字符')
        if operation == '删除内容' and (type(params.get('次数', 1)) is not int or not 1 <= params.get('次数', 1) <= 1000):
            raise ValueError('次数必须为 1～1000')
        if operation == '按键' and params.get('键名') not in PHONE_KEYS:
            raise ValueError('iPhone 不支持此键')
        if operation == '打开应用':
            self.validate_package(params.get('包名'))

    @staticmethod
    def validate_package(package):
        if not isinstance(package, str) or not re.fullmatch(r'[A-Za-z0-9_.-]+', package):
            raise ValueError('应用 Bundle ID 无效')

    async def dispatch(self, operation, params, size, guard):
        transport = self.transport
        if operation in {'点击', '双击', '长按'}:
            p = point(params['位置'], size)
            for index in range(2 if operation == '双击' else 1):
                await transport.gesture([(p, p)], milliseconds(params, default=700 if operation == '长按' else 35), guard)
                if operation == '双击' and index == 0:
                    await asyncio.sleep(.08)
        elif operation in {'滑动', '拖拽'}:
            a, b = point(params['起点'], size), point(params['终点'], size)
            await transport.gesture([(a, b)], milliseconds(params), guard,
                                    hold=milliseconds(params, '按住时间', 600) if operation == '拖拽' else 0)
        elif operation in {'放大', '缩小'}:
            cx, cy = params['中心']
            start, end = params['初始指距'], params['结束指距']
            paths = [(point([round(cx + sign * start / 2), cy], size), point([round(cx + sign * end / 2), cy], size)) for sign in (-1, 1)]
            await transport.gesture(paths, milliseconds(params), guard)
        elif operation == '输入内容':
            await transport.text(params['内容'], guard)
        elif operation == '全选':
            await transport.key(4, guard, command=True)
        elif operation == '删除内容':
            for _ in range(params.get('次数', 1)):
                await transport.key(42, guard)
        elif operation == '按键':
            key = params['键名']
            if key in {'主页', '最近任务'}:
                await transport.home(key == '最近任务', guard)
            else:
                await transport.key({'回车': 40, '删除': 42, '向前删除': 76, '上': 82, '下': 81, '左': 80, '右': 79}[key], guard)
        elif operation == '打开应用':
            guard()
            await transport.apps('launch', {'packageName': params['包名']})

    async def mutate(self, action_id, operation, work, observe=True, wait=0, guard=None):
        path = self.claim(action_id)
        if path.exists():
            return {**json.loads(path.read_text('utf-8')), '状态': '重复请求未执行'}
        record = {'状态': '未执行', '执行': {'状态': '未派发'}, '观察': {'状态': '未请求'},
                  '动作编号': action_id, '操作': operation, '屏幕会话': 'main'}
        # Persist before any possible write. A crash is never a replay license.
        write_json(path, record)
        self.latest = None
        def prepare():
            (guard or self.guard)()
            if record['执行']['状态'] == '未派发':
                record['执行'] = {'状态': '未知'}
                write_json(path, record)
        def receipt():
            if record['执行']['状态'] == '未知':
                record['执行'] = {'状态': '部分派发'}
                write_json(path, record)
        self.transport.before_dispatch = prepare
        self.transport.on_dispatch = receipt
        self.transport.warnings = []
        try:
            data = await work()
            record.update(状态='已执行', 执行={'状态': '无需派发' if record['执行']['状态'] == '未派发' else '已派发'})
            if data is not None:
                record['结果'] = data
            write_json(path, record)
        except (Exception, asyncio.CancelledError) as error:
            record['错误'] = describe_error(error)
            if operation in PHONE_OPERATIONS and self.control_failed(error):
                record['通道'] = {'控制': '断开'}
            if record['执行']['状态'] != '未派发':
                record['状态'] = '结果不确定'
                # Quarantine uncertain writes, even if the screenshot still works.
                try:
                    self.quarantine('iPhone 操作未取得完整回执，禁止重放')
                except OSError as marker_error:
                    record['隔离记录错误'] = str(marker_error)
                self.held, self.token = True, None
                self.generation += 1
        finally:
            self.transport.before_dispatch = lambda: None
            self.transport.on_dispatch = lambda: None
            if self.transport.warnings:
                record['提示'] = self.transport.warnings
        if observe:
            if wait:
                deadline = time.perf_counter() + wait
                while time.perf_counter() < deadline and not self.held and not self.stopped():
                    await asyncio.sleep(min(.1, deadline - time.perf_counter()))
            observed = await self.observe_result()
            observed['通道'] = {**record.get('通道', {}), **observed.get('通道', {})}
            record.update(observed)
        try:
            write_json(path, record)
        except OSError as error:
            record['记录错误'] = str(error)
            self.held, self.token = True, None
        return record

    async def action(self, operation, params):
        self._screen(params.get('屏幕会话', 'main'))
        await self.ready()
        if operation not in PHONE_OPERATIONS:
            raise ValueError('iPhone 不支持此操作')
        if operation == '列出屏幕':
            return {'状态': '已列出', '执行': {'状态': '无需派发'}, '观察': {'状态': '未请求'},
                    '屏幕会话列表': [{'屏幕会话': 'main', '显示屏编号': 0, 'label': '主屏'}]}
        if operation == '列出应用':
            result = await self.transport.apps('list', {'query': params.get('关键词', ''), 'includeSystem': params.get('包含系统应用', True)})
            return {'状态': '已列出', '执行': {'状态': '无需派发'}, '观察': {'状态': '未请求'}, '应用': result}
        async with self.mutation:
            if operation in {'查看', '等待'}:
                if operation == '等待':
                    await asyncio.sleep(milliseconds(params))
                return {'执行': {'状态': '无需派发'}, **await self.observe_result()}
            path = self.claim(params.get('动作编号'))
            if path.exists():
                return {**json.loads(path.read_text('utf-8')), '状态': '重复请求未执行'}
            self.guard()
            size = await self.validate_observation(params)
            self.validate_action(operation, params, size)
            generation = self.generation
            check = lambda: self.input_guard(size, generation)
            check()
            return await self.mutate(params['动作编号'], operation,
                                     lambda: self.dispatch(operation, params, size, check),
                                     wait=milliseconds(params, '执行后等待毫秒', 0, 30000), guard=check)

    async def system(self, group, operation, params, action_id):
        await self.ready()
        if operation not in SYSTEM_OPERATIONS.get(group, []):
            raise ValueError('iPhone 不支持此系统操作')
        if group == 'apps' and operation == 'launch':
            return await self.action('打开应用', {'屏幕会话': params.get('screen'), '截图编号': params.get('observationId'),
                                              '包名': params.get('packageName'), '动作编号': action_id})
        if group == 'apps' and operation in {'details', 'force_stop'}:
            self.validate_package(params.get('packageName'))
        if group == 'clipboard' and operation == 'write' and not isinstance(params.get('text'), str):
            raise ValueError('剪贴板 text 必须是字符串')
        async def invoke():
            if group == 'clipboard':
                if operation != 'read':
                    self.transport.before_dispatch()
                data = await self.transport.clipboard(operation, params.get('text'))
                if operation != 'read':
                    self.transport.on_dispatch()
                return {'text': data} if operation == 'read' else {'updated': True}
            if group == 'apps':
                return await self.transport.apps(operation, params)
            return {'scope': 'iPhone AFC 媒体目录，不含任意应用私有文件', 'data': await self.transport.files(operation, params)}
        async with self.mutation:
            if operation in READ_ONLY[group]:
                return {'执行': {'状态': '无需派发'}, '观察': {'状态': '未请求'}, '结果': await invoke()}
            path = self.claim(action_id)
            if path.exists():
                return {**json.loads(path.read_text('utf-8')), '状态': '重复请求未执行'}
            self.guard()
            return await self.mutate(action_id, group + '.' + operation, invoke, observe=False)

    async def host(self, operation, params):
        if operation == 'status':
            if self.connected and time.perf_counter() - self.last_usb_check > 2:
                self.last_usb_check = time.perf_counter()
                try:
                    present = await self.transport.usb_present()
                except Exception:
                    present = False
                if not present:
                    self.connected, self.held, self.token = False, True, None
                    self.generation += 1
                    self._status('disconnected', 'USB iPhone 已断开，请明确重新连接；未重放输入')
            return self.status_snapshot()
        if operation == 'previewSources':
            return []
        if operation == 'shutdown':
            await self._close()
            return {'closed': True}
        if operation in {'stop', 'hold'}:
            self.held, self.token = True, None
            self.generation += 1
            if operation == 'stop':
                self.state.mkdir(parents=True, exist_ok=True)
                (self.state / 'STOP').touch()
            async with self.mutation:
                await self.transport.release()
                self.latest = None
            return {'stopped': True} if operation == 'stop' else {'held': True}
        if operation == 'manual':
            async with self.mutation:
                await self.ready()
                if not self.held:
                    raise RuntimeError('必须先暂停并等待在途调用')
                if (self.state / 'INPUT_UNCERTAIN').exists():
                    raise RuntimeError('输入仍被隔离，必须先恢复连接')
                if params.get('resumeStopped'):
                    (self.state / 'STOP').unlink(missing_ok=True)
                    (self.run / 'STOP').unlink(missing_ok=True)
                if self.stopped():
                    raise RuntimeError('设备仍处于 STOP')
                self.generation += 1
                self.token = uuid.uuid4().hex
                return {'token': self.token}
        if operation == 'resume':
            async with self.mutation:
                await self.ready()
                if self.connection_status['code'] == 'control_disconnected':
                    raise ChannelFailure('control', self.connection_status['message'])
                if (self.state / 'INPUT_UNCERTAIN').exists():
                    raise RuntimeError('输入仍被隔离，必须先恢复连接')
                # Only host can restore input; failure leaves STOP/hold intact.
                observed = await self._capture(observe=True)
                if not observed.get('可用于输入'):
                    raise RuntimeError('当前画面不可用于输入')
                (self.state / 'STOP').unlink(missing_ok=True)
                (self.run / 'STOP').unlink(missing_ok=True)
                self.generation += 1
                self.held, self.token = False, None
                self.latest = None  # Model must receive its own fresh observation.
                return {'stopped': False}
        if operation == 'frame':
            return await self.frame(params)
        if operation == 'input':
            self._screen(params['screen'])
            async with self.mutation:
                await self.ready()
                path = self.claim(params['actionId'])
                if path.exists():
                    return {**json.loads(path.read_text('utf-8')), '状态': '重复请求未执行'}
                self.guard(params['token'])
                size = await self.validate_observation(params, params['token'])
                action, values = params['operation'], params.get('params', {})
                if action not in {'点击', '双击', '长按', '滑动', '拖拽', '输入内容', '全选', '删除内容', '按键'}:
                    raise ValueError('不支持的人工操作')
                self.validate_action(action, values, size)
                generation = self.generation
                check = lambda: self.input_guard(size, generation, params['token'])
                check()
                return await self.mutate(params['actionId'], action, lambda: self.dispatch(action, values, size, check), observe=False, guard=check)
        raise ValueError('未知宿主操作')

    async def _close(self):
        self.closed, self.held, self.token = True, True, None
        self.generation += 1
        try:
            async with asyncio.timeout(30):
                if self.connect_task and not self.connect_task.done():
                    self.connect_task.cancel()
                    with suppress(asyncio.CancelledError):
                        await self.connect_task
                if self.preview_task and not self.preview_task.done():
                    self.preview_task.cancel()
                    with suppress(asyncio.CancelledError):
                        await self.preview_task
                async with self.mutation:
                    await self.transport.close()
                    self.connected = False
                    self.lease.close()
        except TimeoutError as error:
            raise RuntimeError('iPhone 在途调用或退出清理超时，尚未确认设备租约释放') from error

    def close(self):
        if not self.thread.is_alive():
            return
        try:
            if not self.closed:
                self.call(self._close(), timeout=35)
        finally:
            self.loop.call_soon_threadsafe(self.loop.stop)
            self.thread.join(3)
            if not self.thread.is_alive():
                self.loop.close()
