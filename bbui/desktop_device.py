"""Host-owned desktop device. Control RPCs are never registered as model tools."""
import base64
import hashlib
import io
import json
import re
import threading
import time
import uuid
import adbutils
from collections import deque
from contextlib import nullcontext
from .control_gate import ControlGate
from .system_bridge import SystemBridge
from .scrcpy_display import ScrcpyDisplay
from .runtime import write_json
from .app_policy import require_package_allowed
from .unified import KEYS, duration, point
from .channel_errors import ChannelFailure, control_failure

READ_ONLY = {'apps': {'list', 'details', 'launch_entries', 'permissions'},
             'notifications': {'list', 'details'}, 'clipboard': {'read'},
             'files': {'list', 'stat', 'search', 'read_text'}}


def external_displays(dump):
    result = []
    for line in dump.splitlines():
        identity = re.search(r'mBaseDisplayInfo=DisplayInfo\{"[^"]*", displayId (\d+)\b', line)
        unique = re.search(r'\buniqueId "([^"]+)"', line)
        if (not identity or not unique or int(identity[1]) == 0 or 'type VIRTUAL,' not in line
                or ('FLAG_PRIVATE' in line and 'FLAG_PRESENTATION' not in line)):
            continue
        display_id = int(identity[1])
        key = f'external:{display_id}:' + hashlib.sha256(unique[1].encode()).hexdigest()[:16]
        result.append({'屏幕会话': key, '显示屏编号': display_id,
                       'external': True, 'readOnly': True, 'label': f'外部屏幕 {display_id}'})
    return result


class DesktopDevice:
    def __init__(self, registry):
        self.phone = registry
        self.base = registry.base
        self.gate = ControlGate()
        self.base.control_gate = self.gate
        self.system = SystemBridge(self.base)
        self.mutex = threading.RLock()
        self.frames = deque(maxlen=128)
        self.external = {}
        self.external_lock = threading.RLock()
        self.external_refresh_lock = threading.Lock()
        self.external_checked = 0
        self.closed = False

    def host(self, operation, params):
        try:
            return self._host(operation, params)
        except Exception as error:
            if operation in {'input', 'hold', 'stop', 'manual', 'resume'} and control_failure(self.base, error):
                raise ChannelFailure('control', error) from error
            if operation == 'frame' and not isinstance(error, (ValueError, KeyError)):
                raise ChannelFailure('observation', error) from error
            raise

    def _host(self, operation, params):
        if operation == 'status':
            try:
                connected = adbutils.adb.device(self.base.serial).get_state() == 'device'
            except (adbutils.AdbError, OSError):
                connected = False
            failure = getattr(self.base, 'control_error', None)
            return {'connected': connected and not failure,
                    'stopped': any((directory / 'STOP').exists() for directory in {self.base.run, self.base.state_dir}),
                    **({'connectionStatus': {'code': 'control_disconnected', 'message': failure}} if failure else {})}
        if operation == 'stop':
            with self.mutex:
                self.base.stop()
                self.gate.hold()
                self.system.stopped(True)
            return {'stopped': True}
        if operation == 'hold':
            with self.mutex:
                self.gate.hold()
                self.system.stopped(True)
            return {'held': True}
        if operation == 'manual':
            with self.mutex:
                token = self.gate.grant()
                try:
                    if params.get('resumeStopped'):
                        self.phone._own_device()
                        for directory in {self.base.run, self.base.state_dir}:
                            (directory / 'STOP').unlink(missing_ok=True)
                    with self.gate.manual(token):
                        self.base.paused()
                except Exception:
                    self.gate.hold()
                    if params.get('resumeStopped'):
                        self.base.stop()
                    raise
                return {'token': token}
        if operation == 'resume':
            with self.mutex:
                if getattr(self.base, 'control_error', None):
                    raise ChannelFailure('control', self.base.control_error)
                self.phone._own_device()
                for directory in {self.base.run, self.base.state_dir}:
                    (directory / 'STOP').unlink(missing_ok=True)
                self.gate.release()
                self.base.paused()
                self.system.stopped(False)
            return {'stopped': False}
        if operation == 'frame':
            return self.frame(params['screen'], params.get('afterFrameId'))
        if operation == 'previewSources':
            return self.preview_sources()
        if operation == 'input':
            if params['screen'].startswith('external:'):
                raise ValueError('外部屏幕仅供查看，控制权仍属于原应用')
            with self.gate.manual(params['token']):
                if not re.fullmatch(r'[A-Za-z0-9_-]{1,100}', params['actionId']):
                    raise ValueError('动作编号格式无效')
                claim = self.base.state_dir / f"manual-{params['actionId']}.json"
                with self.mutex:
                    if claim.exists():
                        return {**json.loads(claim.read_text('utf-8')), '状态': '重复请求未执行'}
                screen = params['screen']
                with self.phone.mutex:
                    self.phone._own_device()
                    tool = self.phone.sessions.get(screen)
                if tool is None:
                    raise ValueError('屏幕已关闭')
                with tool.phone.lock():
                    frame = next((f for f in tuple(self.frames) if f['id'] == params['frameId'] and f['screen'] == screen), None)
                    self.validate_frame(tool, frame)
                    return self.manual_action(tool, frame, params, claim)
        raise ValueError('未知宿主操作')

    def manual_action(self, tool, frame, request, claim):
        operation, params, action_id = request['operation'], request.get('params', {}), request['actionId']
        if operation not in {'点击', '滑动', '长按', '输入内容', '全选', '删除内容', '按键'}:
            raise ValueError('不支持的人工输入')
        phone = tool.phone
        with phone.input_lock(exclusive=operation == '输入内容'):
            def guard():
                self.gate.check_manual(request['token'])
                phone.paused()
                self.validate_frame(tool, frame)
                if tool.control is not frame['video']:
                    raise ValueError('屏幕控制连接已变化，请刷新后重试')
            guard()
            context = phone.context()
            if (context.get('display_id') != phone.display_id
                    or (context.get('width'), context.get('height')) != frame['size']):
                raise ValueError('目标屏幕或尺寸已变化，请等待刷新')
            require_package_allowed(phone.config, context.get('package'))
            phone.check_input_focus(context)
            width, height = frame['size']
            paths, seconds = None, 0
            if operation in {'点击', '长按'}:
                position = point(params.get('位置'), width, height)
                paths = [(position, position)]
                seconds = duration(params, 700) if operation == '长按' else .035
            elif operation == '滑动':
                paths = [(point(params.get('起点'), width, height), point(params.get('终点'), width, height))]
                seconds = duration(params)
            elif operation == '输入内容':
                limit = phone.config.get('max_input_characters', 2000)
                if not isinstance(params.get('内容'), str) or not 0 < len(params['内容']) <= limit:
                    raise ValueError(f'内容必须为 1～{limit} 字符')
            elif operation == '按键':
                if params.get('键名') not in KEYS:
                    raise ValueError('未知键名')
                if phone.display and params['键名'] in {'音量加', '音量减', '唤醒', '最近任务'}:
                    raise ValueError('该操作影响设备共享状态，请显式使用main会话')
            elif operation == '删除内容' and (type(params.get('次数', 1)) is not int or not 1 <= params.get('次数', 1) <= 100):
                raise ValueError('删除次数必须为1～100')

            def dispatch_guard():
                guard()
                if phone.context() != context:
                    raise ValueError('输入前目标窗口已变化，请等待刷新')
                guard()

            record = {'动作编号': action_id, '操作': operation, '屏幕会话': request['screen'],
                      '显示屏编号': phone.display_id, '状态': '派发中',
                      '执行': {'状态': '未知'}, '观察': {'状态': '未请求'}, '开始时间': time.time()}
            with self.mutex:
                if claim.exists():
                    return {**json.loads(claim.read_text('utf-8')), '状态': '重复请求未执行'}
                write_json(claim, record)
            record['执行'] = {'状态': '未派发'}
            try:
                dispatch_guard()
                # Manual input invalidates the model's prior observation, but does
                # not mint or persist new screenshots. The live video is its feedback.
                tool.latest = None
                record['执行'] = {'状态': '未知'}
                if paths:
                    tool._gesture(paths, width, height, seconds, before_dispatch=guard)
                elif operation == '输入内容':
                    tool.control.paste(params['内容'], dispatch_guard)
                elif operation == '按键':
                    tool._key(KEYS[params['键名']], before_dispatch=guard)
                elif operation == '全选':
                    tool._key(29, 0x1000, before_dispatch=guard)
                elif operation == '删除内容':
                    for index in range(params.get('次数', 1)):
                        guard()
                        tool._key(67, before_dispatch=guard)
                        record['执行'] = {'状态': '部分派发', '已完成按键次数': index + 1}
                record.update(状态='已执行', 执行={'状态': '已派发'})
            except Exception as error:
                record.update(状态='未执行' if record['执行']['状态'] == '未派发' else '结果不确定', 错误=str(error))
                if control_failure(phone, error):
                    record['通道'] = {'控制': '断开'}
                if isinstance(error, OSError) and record['执行']['状态'] != '未派发':
                    try:
                        self.base.quarantine_input(str(error))
                    except OSError as marker_error:
                        record['隔离记录错误'] = str(marker_error)
            record['结束时间'] = time.time()
            try:
                write_json(claim, record)
            except OSError as error:
                record['记录错误'] = str(error)
            return record

    @staticmethod
    def validate_frame(tool, frame):
        video = tool.phone.display or tool.phone.preview
        if (frame is None or frame['video'] is not video
                or not video.matches_frame(frame['generation'], frame['size'])):
            raise ValueError('画面或目标窗口已变化，请等待刷新')

    def preview_sources(self, force=False):
        with self.external_refresh_lock:
            with self.external_lock:
                if self.closed:
                    return []
                if not force and time.monotonic() - self.external_checked < 2:
                    return [entry['metadata'] for entry in self.external.values()]
            dump = adbutils.adb.device(self.base.serial).shell(['dumpsys', 'display'], timeout=3)
            if 'mBaseDisplayInfo=DisplayInfo{' not in dump:
                raise ValueError('未取得可识别的手机显示屏列表')
            with self.phone.mutex:
                owned = {tool.phone.display_id for tool in self.phone.sessions.values()}
            sources = {item['屏幕会话']: item for item in external_displays(dump) if item['显示屏编号'] not in owned}
            with self.external_lock:
                if self.closed:
                    return []
                retired = [self.external.pop(key) for key in self.external.keys() - sources.keys()]
                for key, metadata in sources.items():
                    if key not in self.external:
                        self.external[key] = {'metadata': metadata, 'video': None, 'lock': threading.Lock()}
                self.external_checked = time.monotonic()
            for entry in retired:
                if entry['video']:
                    entry['video'].__exit__()
            return list(sources.values())

    def external_video(self, screen):
        with self.external_lock:
            entry = self.external.get(screen)
        if entry is None:
            raise ValueError('外部屏幕已关闭或身份已变化')
        with entry['lock']:
            if entry['video'] is None:
                # A display ID can be reused. Verify its unique identity at the
                # moment of attachment, not just against the periodic UI list.
                self.preview_sources(force=True)
                with self.external_lock:
                    if self.external.get(screen) is not entry:
                        raise ValueError('外部屏幕已关闭或身份已变化')
                preview = ScrcpyDisplay(self.base.serial, self.base.root,
                    existing_display=entry['metadata']['显示屏编号'], control=False)
                try:
                    preview.__enter__()
                    self.preview_sources(force=True)
                except Exception:
                    preview.__exit__()
                    raise
                with self.external_lock:
                    if self.external.get(screen) is not entry:
                        preview.__exit__()
                        raise ValueError('外部屏幕已关闭或身份已变化')
                    entry['video'] = preview
            return entry['video']

    def close(self):
        with self.external_lock:
            self.closed = True
            entries = list(self.external.values())
            self.external.clear()
        for entry in entries:
            if entry['video']:
                entry['video'].__exit__()
        self.system.close()

    def frame(self, screen, after_frame_id=None):
        if screen.startswith('external:'):
            return self.video_frame(screen, self.external_video(screen), after_frame_id)
        with self.phone.mutex:
            self.phone._own_device()
            tool = self.phone._main() if screen == 'main' else self.phone.sessions.get(screen)
            if tool is None:
                raise ValueError('未知屏幕')
        if screen == 'main' and not getattr(tool.phone, 'preview', None):
            with tool.phone.lock():
                if not getattr(tool.phone, 'preview', None):
                    preview = ScrcpyDisplay(self.base.serial, self.base.root, existing_display=0)
                    preview.__enter__()
                    tool.phone.preview = preview
                    if tool.control:
                        tool.control.__exit__()
                    tool.control = preview
        video = tool.phone.display or tool.phone.preview
        return self.video_frame(screen, video, after_frame_id)

    def video_frame(self, screen, video, after_frame_id=None):
        previous = next((f for f in tuple(self.frames) if f['id'] == after_frame_id and f['screen'] == screen and f['video'] is video), None)
        after = (previous['sequence'], previous['generation']) if previous else None
        snapshot = video.frame_snapshot(after=after)
        if snapshot is None:
            return None
        picture, sequence, generation = snapshot
        width, height = picture.size
        frame = {'id': uuid.uuid4().hex, 'screen': screen, 'video': video,
                 'generation': generation, 'size': picture.size, 'sequence': sequence}
        picture.thumbnail((900, 1200))
        output = io.BytesIO()
        picture.convert('RGB').save(output, 'JPEG', quality=78)
        self.frames.append(frame)
        return {'frameId': frame['id'], 'screen': screen, 'width': width, 'height': height,
                'sequence': sequence,
                'image': 'data:image/jpeg;base64,' + base64.b64encode(output.getvalue()).decode()}

    def execute_system(self, group, operation, params, action_id):
        if group not in READ_ONLY:
            raise ValueError('未知系统工具')
        if group == 'apps' and operation == 'launch':
            if not params.get('screen') or not params.get('observationId'):
                raise ValueError('启动应用必须指定 screen 和 observationId')
            return self.phone.action('打开应用', {'屏幕会话': params['screen'], '截图编号': params['observationId'],
                '包名': params.get('packageName'), '启动组件': params.get('activity'), '动作编号': action_id})
        readonly = operation in READ_ONLY[group]
        with self.phone.mutex:
            self.phone._own_device()
        with self.phone.gate.acquire(exclusive=True) if not readonly else nullcontext():
            record = None
            if not readonly:
                if not re.fullmatch(r'[A-Za-z0-9_-]{1,100}', action_id):
                    raise ValueError('缺少有效动作编号')
                record = self.base.state_dir / f'system-{action_id}.json'
                if record.exists():
                    return json.loads(record.read_text('utf-8'))
                with self.mutex:
                    self.base.paused()
                    if params.get('packageName'):
                        require_package_allowed(self.base.config, params['packageName'])
                    if group == 'notifications' and params.get('key'):
                        parts = params['key'].split('|')
                        if len(parts) < 2:
                            raise ValueError('通知 key 无效')
                        require_package_allowed(self.base.config, parts[1])
                    self.system.start()
                    self.base.paused()
                    self.system.stopped(False)
                    write_json(record, {'执行': {'状态': '未知'}, '观察': {'状态': '未请求'}, '错误': '之前的调用尚无确定回执，禁止重放'})
            try:
                result = self.system.request('system', body={'group': group, 'operation': operation, 'params': params,
                    'blockedPackages': self.base.config.get('blocked_packages', [])})
            except Exception as error:
                if not readonly:
                    self.base.quarantine_input(str(error))
                result = {'执行': {'状态': '无需派发' if readonly else '未知'}, '观察': {'状态': '未请求'}, '错误': str(error)}
            if record:
                write_json(record, result)
                if result.get('执行', {}).get('状态') == '未知':
                    self.base.quarantine_input('系统工具派发结果未知')
            return result
