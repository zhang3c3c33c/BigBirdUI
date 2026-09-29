"""One supervisor per device; independent screen queues and observations."""
from contextlib import contextmanager
import re
import threading
import time
import uuid
from types import SimpleNamespace

import adbutils

from .runtime import PhoneTools
from .scrcpy_display import ScrcpyDisplay
from .mirrors import MirrorManager
from .apps import list_apps
from .app_policy import require_package_allowed
from .unified import UnifiedPhone, screenshot_wait, launch_component


class InputGate:
    """Gestures on different displays share access; clipboard paste is exclusive."""
    def __init__(self):
        self.condition = threading.Condition()
        self.readers = 0
        self.writer = False
        self.waiting_writers = 0

    @contextmanager
    def acquire(self, exclusive=False):
        with self.condition:
            if exclusive:
                self.waiting_writers += 1
                try:
                    self.condition.wait_for(lambda: not self.writer and self.readers == 0)
                    self.writer = True
                finally:
                    self.waiting_writers -= 1
            else:
                self.condition.wait_for(lambda: not self.writer and not self.waiting_writers)
                self.readers += 1
        try:
            yield
        finally:
            with self.condition:
                if exclusive:
                    self.writer = False
                else:
                    self.readers -= 1
                self.condition.notify_all()


def display_context(dump, display_id, width, height):
    match = re.search(rf'^Display #{display_id} .*?:\s*\n(.*?)(?=^Display #|\Z)', dump, re.M | re.S)
    if not match:
        raise RuntimeError(f'屏幕 {display_id} 不存在或没有 Activity，禁止回退到主屏幕')
    activity = re.search(r'(?:topResumedActivity|mResumedActivity)[=:]\s*ActivityRecord\{\S+\s+u\d+\s+(\S+)/(\S+)', match[1])
    rotation = re.search(r'mDisplayRotation=ROTATION_(\d+)', match[1])
    user = re.search(r'(?:topResumedActivity|mResumedActivity)[=:]\s*ActivityRecord\{\S+\s+u(\d+)', match[1])
    return {'display_id': display_id, 'userId': int(user[1]) if user else -1, 'package': activity[1] if activity else None,
            'activity': activity[2] if activity else None,
            'rotation': int(rotation[1]) if rotation else 0, 'width': width, 'height': height}


def display_focus(dump, display_id):
    headers = list(re.finditer(r'^\s*Display: mDisplayId=(\d+)\b[^\r\n]*', dump, re.M))
    for index, header in enumerate(headers):
        if int(header[1]) != display_id:
            continue
        block = dump[header.end():headers[index + 1].start() if index + 1 < len(headers) else len(dump)]
        focus = re.search(r'^\s*mCurrentFocus=Window\{(\S+) u(\d+) (.+?)\s*\}\s*$', block, re.M)
        return {'focusedWindow': focus[1], 'focusedWindowUser': int(focus[2]), 'focusedWindowName': focus[3]} if focus else {}
    return {}


class ScreenDevice:
    def __init__(self, tools):
        self.tools = tools

    def screenshot(self):
        if getattr(self.tools, 'preview', None):
            return self.tools.preview.screenshot()
        if self.tools.display:
            return self.tools.display.screenshot()
        return self.tools.base.d.screenshot()

    def shell(self, args):
        result = self.tools.adb.shell2(args, timeout=20)
        return SimpleNamespace(output=result.output, exit_code=result.returncode)

    def dump_hierarchy(self):
        if self.tools.display:
            raise ValueError('虚拟屏幕暂不提供无障碍树；使用本屏幕截图')
        return self.tools.base.d.dump_hierarchy()


class ScreenTools(PhoneTools):
    def __init__(self, base, name, gate, display=None):
        self.base, self.display, self.name = base, display, name
        self.root, self.config, self.serial = base.root, base.config, base.serial
        self.display_id = display.display_id if display else 0
        self.run = base.run / 'sessions' / (name + '-' + uuid.uuid4().hex)
        self.run.mkdir(parents=True)
        self.adb = adbutils.adb.device(self.serial)
        self._device = ScreenDevice(self)
        self.mutex = threading.RLock()
        self.gate = gate
        self.closed = False

    def lock(self):
        return self.mutex

    def input_lock(self, exclusive=False):
        return self.gate.acquire(exclusive)

    def paused(self):
        if self.closed:
            raise RuntimeError('屏幕会话已关闭，请重新创建并观察')
        self.base.paused()

    def quarantine_input(self, reason):
        self.base.quarantine_input(reason)

    def context(self):
        if self.closed or (self.display and (self.display.closed or self.display.video_error)):
            raise ConnectionError('屏幕连接已断开；禁止自动重建并重放输入')
        if self.display:
            w, h = self.display.width, self.display.height
        elif getattr(self, 'preview', None):
            w, h = self.preview.width, self.preview.height
        else:
            info = self.base.d.info
            w, h = info['displayWidth'], info['displayHeight']
        dump = self.adb.shell(['dumpsys', 'activity', 'activities'], timeout=15)
        context = display_context(dump, self.display_id, w, h)
        windows = self.adb.shell(['dumpsys', 'window', 'displays'], timeout=10)
        context.update(display_focus(windows, self.display_id))
        return context

    def check_input_focus(self, context):
        if not context.get('focusedWindow') or context.get('userId', -1) < 0 or context['userId'] != context.get('focusedWindowUser'):
            raise ValueError('无法确认目标屏幕的焦点窗口与用户，请查看后继续')
        return {'目标窗口': context['focusedWindow'], '用户编号': context['userId']}


class ScreenRegistry:
    def __init__(self, base=None, mirrors=None):
        self.base = base or PhoneTools()
        self.gate = InputGate()
        self.mutex = threading.RLock()
        self.sessions = {}
        self.packages = {}
        self.lease = None
        self.closed = False
        self._mirrors = mirrors

    @property
    def mirrors(self):
        if self._mirrors is None:
            self._mirrors = MirrorManager(self.base.root, self.base.serial)
        return self._mirrors

    def _open_mirror(self, name, display_id):
        if self.base.config.get('embedded_preview') is True:
            return {'状态': '内嵌预览', '只读': True}
        try:
            return self.mirrors.open(name, display_id)
        except (OSError, RuntimeError) as error:
            # A failed observer must not turn successful device work into a retry.
            return {'状态': '启动失败', '只读': True, '错误': str(error)}

    def connect_phone(self):
        with self.mutex:
            self._own_device()
            try:
                state = adbutils.adb.device(self.base.serial).get_state()
            except (adbutils.AdbError, OSError) as error:
                raise RuntimeError(f'无法连接手机 {self.base.serial}：请检查 USB 连接和调试授权。{error}') from error
            if state != 'device':
                raise RuntimeError(f'手机尚未就绪：{state}，请检查 USB 调试授权')
            tool = self._main()
        with tool.phone.lock():
            mirror = self._open_mirror('main', 0)
            return {'状态': '手机已连接', '设备序列号': self.base.serial, '屏幕会话': 'main', '镜像': mirror}

    def _own_device(self):
        if self.closed:
            raise RuntimeError('手机运行时已关闭')
        if self.lease is None:
            lease = self.base.lock()
            try:
                lease.__enter__()
            except OSError as error:
                raise RuntimeError('该手机已有 BBUI 运行时占用，请复用同一 MCP 进程') from error
            self.lease = lease

    def _main(self):
        if 'main' not in self.sessions:
            tools = ScreenTools(self.base, 'main', self.gate)
            self.sessions['main'] = UnifiedPhone(tools)
        return self.sessions['main']

    def create(self, params):
        name, package = params.get('屏幕会话'), params.get('包名')
        if not isinstance(name, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,48}', name) or name == 'main':
            raise ValueError('创建屏幕需提供屏幕会话：1～48位字母数字_-，main保留')
        if package is not None:
            require_package_allowed(self.base.config, package)
        w, h, dpi = params.get('宽度', 1080), params.get('高度', 1920), params.get('密度', 320)
        if any(type(v) is not int for v in (w, h, dpi)) or not (320 <= w <= 2160 and 320 <= h <= 3840 and 120 <= dpi <= 640):
            raise ValueError('宽度320～2160、高度320～3840、密度120～640，必须为整数')
        with self.mutex:
            self._own_device()
            self.base.paused()
            if name in self.sessions:
                raise ValueError('屏幕会话已存在，请查看；不重复创建')
            if package is not None and self.package_owner(package) is not None:
                raise ValueError('同一应用已分配其他虚拟屏幕，避免任务被迁移')
            limit = self.base.config.get('max_virtual_screens', 3)
            if type(limit) is not int or limit < 1:
                raise ValueError('max_virtual_screens 必须为正整数')
            if sum(bool(tool.phone.display) for tool in self.sessions.values()) >= limit:
                raise ValueError(f'已达到配置的虚拟屏幕资源上限 {limit}')
            display = ScrcpyDisplay(self.base.serial, self.base.root, w, h, dpi)
            try:
                display.__enter__()
                tools = ScreenTools(self.base, name, self.gate, display)
                tool = UnifiedPhone(tools, lambda: display)
                tool.control = display
                self.sessions[name] = tool
            except BaseException:
                display.__exit__()
                raise
        # Never keep the registry/device input gate held during page loading.
        with tool.phone.lock():
            mirror = self._open_mirror(name, display.display_id)
            # Allocation is complete. Optional launch failure must preserve the screen.
            launch = None
            if package is not None:
                launch = {'执行': {'状态': '未派发'}}
                try:
                    with self.gate.acquire():
                        resolved = tools.adb.shell(['cmd', 'package', 'resolve-activity', '--brief', package])
                        component = launch_component(package, params.get('启动组件'), resolved)
                        self.base.paused()
                        launch['执行'] = {'状态': '未知'}
                        output = tools.adb.shell2(['am', 'start', '-W', '--display', str(display.display_id), '-n', component], timeout=20)
                        launch.update(执行={'状态': '已派发'}, 输出=output.output[:1500], 返回码=output.returncode)
                        self.packages[package] = name
                except Exception as error:
                    launch['错误'] = str(error)
            result = tool.action('查看', params)
            result.update(状态='屏幕已创建', 屏幕会话=name, 显示屏编号=display.display_id, 镜像=mirror)
            result['执行'] = {'状态': '已派发'}
            if launch is not None:
                result['启动'] = launch
                result['目标应用在前台'] = result.get('屏幕', {}).get('package') == package
            return result

    def package_owner(self, package):
        for name, tool in self.sessions.items():
            try:
                if tool.phone.context().get('package') == package:
                    return name
            except Exception:
                # Unknown context is not evidence that a reserved app has left.
                if self.packages.get(package) == name:
                    return name
        return None

    def screen_info(self, name, tool):
        result = {'屏幕会话': name, '显示屏编号': tool.phone.display_id,
                  '最后启动目标': [p for p, n in self.packages.items() if n == name],
                  '镜像': self._mirrors.status(name) if self._mirrors else {'状态': '未打开', '只读': True}}
        try:
            result['当前前台'] = tool.phone.context()
        except Exception as error:
            result['前台信息错误'] = str(error)
        return result

    def action(self, operation, params=None):
        params = {} if params is None else params
        try:
            result = self._action(operation, params)
        except (ValueError, RuntimeError, OSError) as error:
            result = {'状态': '未执行', '执行': {'状态': '未派发'},
                      '错误': str(error), '观察': {'状态': '未请求'}}
            # A rejected input is local to this invocation. Offer current evidence.
            if isinstance(params, dict):
                tool = self.sessions.get(params.get('屏幕会话', 'main'))
                if tool is not None:
                    with tool.phone.lock():
                        result.update(tool.observe_result())
            return result
        result.setdefault('执行', {'状态': '无需派发' if operation in
            ('查看', '等待', '列出应用', '列出屏幕', '连接手机') else '已派发'})
        result.setdefault('观察', {'状态': '未请求'})
        return result

    def _action(self, operation, params):
        screenshot_wait(params)
        if operation == '列出应用':
            with self.mutex:
                if self.closed:
                    raise RuntimeError('手机运行时已关闭')
            # Package queries neither acquire the input lease nor create a screen.
            return list_apps(self.base.serial, self.base.config, params)
        if operation == '连接手机':
            return self.connect_phone()
        if operation == '创建屏幕':
            return self.create(params)
        if operation == '列出屏幕':
            with self.mutex:
                return {'状态': '已列出', '屏幕会话列表': [
                    self.screen_info(name, tool)
                    for name, tool in self.sessions.items()]}
        name = params.get('屏幕会话', 'main')
        with self.mutex:
            self._own_device()
            tool = self._main() if name == 'main' else self.sessions.get(name)
        if tool is None:
            raise ValueError('未知屏幕会话；先创建屏幕或列出屏幕，禁止回退到主屏幕')
        with tool.phone.lock():
            if tool.phone.closed:
                raise ValueError('屏幕会话已关闭')
            if name == 'main' and params.get('读取节点') and self.packages:
                raise ValueError('存在虚拟屏幕时主屏无障碍树可能指向其他屏幕，请使用截图')
            if operation == '关闭屏幕':
                with self.mutex:
                    if self._mirrors:
                        self._mirrors.close(name)
                    tool.close()
                    tool.phone.closed = True
                    del self.sessions[name]
                    self.packages = {p: n for p, n in self.packages.items() if n != name}
                return {'状态': '屏幕已关闭', '屏幕会话': name}
            if tool.phone.display:
                if params.get('读取节点'):
                    raise ValueError('虚拟屏幕仅支持截图观察，读取节点尚未实现')
                if operation == '系统面板' or (operation == '按键' and params.get('键名') in ('音量加', '音量减', '唤醒', '最近任务')):
                    raise ValueError('该操作影响设备共享状态，请显式使用main会话')
            if operation not in ('查看', '等待'):
                if not params.get('截图编号'):
                    raise ValueError('动作必须携带本屏幕最新截图编号；同屏幕每次观察后再决定动作')
                if operation == '打开应用':
                    owner = self.package_owner(params.get('包名'))
                    if owner is not None and owner != name:
                        raise ValueError(f'该应用当前在屏幕 {owner}，启动可能迁移已有任务')
            result = tool.action(operation, params)
            if operation == '打开应用' and result.get('执行', {}).get('状态') == '已派发':
                with self.mutex:
                    self.packages[params['包名']] = name
            result.update(屏幕会话=name, 显示屏编号=tool.phone.display_id)
            if tool.phone.display:
                result.update(视频帧编号=tool.phone.display.frame_sequence, 视频帧接收时间=tool.phone.display.frame_time)
            return result

    def close(self):
        with self.mutex:
            self.closed = True
            sessions = list(self.sessions.values())
        for tool in sessions:
            with tool.phone.lock():
                if self._mirrors:
                    self._mirrors.close(tool.phone.name)
                tool.close()
                tool.phone.closed = True
        with self.mutex:
            self.sessions.clear()
            if self._mirrors:
                self._mirrors.close_all()
            self.packages.clear()
            if self.lease:
                self.lease.__exit__(None, None, None)
                self.lease = None
