"""One human-action tool, persistent connections, post-action screenshot included."""
from .app_policy import require_package_allowed
import json
import math
import re
import sys
import time
import uuid
from pathlib import Path
from .runtime import PhoneTools, write_json, parse_nodes, compact
from .scrcpy_control import ScrcpyControl, touch_packet, key_packet
from .channel_errors import ChannelFailure, control_call, control_failure

OPERATIONS = ['查看', '等待', '点击', '双击', '长按', '滑动', '拖拽', '放大', '缩小',
              '输入内容', '删除内容', '全选', '按键', '打开应用', '系统面板']
KEYS = {'返回': 4, '主页': 3, '最近任务': 187, '回车': 66, '删除': 67,
        '向前删除': 112, '上': 19, '下': 20, '左': 21, '右': 22,
        '音量加': 24, '音量减': 25, '唤醒': 224}


def duration(p, default=400):
    value = p.get('时间', default)
    if not isinstance(value, (int, float)) or not 0 <= value <= 5000:
        raise ValueError('时间必须为 0～5000 毫秒')
    return value / 1000


def screenshot_wait(p):
    if p is None:
        return 0
    if not isinstance(p, dict):
        raise ValueError('参数必须为对象')
    value = p.get('执行后等待毫秒', 0)
    if type(value) is not int or not 0 <= value <= 30000:
        raise ValueError('执行后等待毫秒必须为 0～30000 的整数')
    return value


def launch_component(package, component, output):
    if component is not None:
        if not isinstance(component, str) or not re.fullmatch(r'[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+', component) or not component.startswith(package + '/'):
            raise ValueError('启动组件必须属于目标包名，格式为 包名/Activity')
        return component
    candidates = [s.strip() for s in output.splitlines() if s.strip().startswith(package + '/')]
    if len(candidates) != 1:
        raise ValueError('找不到唯一启动入口；可显式提供启动组件，启动请求未派发')
    return candidates[0]


def point(value, width, height):
    if not isinstance(value, list) or len(value) != 2:
        raise ValueError('位置必须为 [x,y]')
    if not all(isinstance(v, int) for v in value):
        raise ValueError('坐标必须为整数像素')
    if not (0 <= value[0] < width and 0 <= value[1] < height):
        raise ValueError('坐标超出原始截图范围')
    return value


class UnifiedPhone:
    def __init__(self, phone=None, controller_factory=None):
        self.phone = phone if phone is not None else PhoneTools()
        self.controller_factory = controller_factory
        self.control = None
        self.latest = None

    def close(self):
        if self.control:
            self.control.__exit__()
            self.control = None

    def controller(self):
        if self.control is None:
            if self.controller_factory:
                self.control = self.controller_factory()
            else:
                self.control = ScrcpyControl(self.phone.serial, self.phone.root).__enter__()
        return self.control

    def capture(self, nodes=False):
        begin = time.monotonic()
        context_error = None
        stable = False
        after = {}
        for _ in range(3):
            try:
                before = self.phone.context()
            except Exception as error:
                before = None
                context_error = str(error)
            picture = self.phone.d.screenshot()
            try:
                after = self.phone.context()
            except Exception as error:
                after = {}
                context_error = str(error)
            if before is not None and before == after:
                stable = True
                break
        if not stable:
            context_error = context_error or '采集时页面上下文变化，本图不能作为输入凭据'
        oid = uuid.uuid4().hex
        folder = self.phone.run / oid
        folder.mkdir()
        picture.save(folder / 'screen.png')
        obs = {'observation_id': oid, 'captured_at': time.time(), 'context': after,
               'screenshot': str(folder / 'screen.png'), 'nodes': [], 'consumed': not stable}
        node_result = {}
        if nodes:
            try:
                xml = self.phone.d.dump_hierarchy()
                (folder / 'hierarchy.xml').write_text(xml, encoding='utf-8')
                obs['nodes'] = parse_nodes(xml)
                node_result = {'节点': compact(obs)['nodes'], '节点状态': '已取得'}
            except Exception as error:
                node_result = {'节点状态': '失败', '节点错误': str(error)}
        write_json(folder / 'observation.json', obs)
        write_json(self.phone.run / 'latest.json', obs)
        self.latest = obs
        return {'截图编号': oid, '截图': obs['screenshot'], '屏幕': after,
                '可用于输入': stable,
                **({'上下文错误': context_error} if context_error else {}),
                '观察耗时毫秒': round((time.monotonic()-begin)*1000),
                **node_result}

    def _key(self, code, meta=0, before_dispatch=None):
        c = self.controller()
        (before_dispatch or self.phone.paused)()
        control_call(c.socket.sendall, key_packet(0, code, meta) + key_packet(1, code, meta))

    def _gesture(self, paths, width, height, seconds, hold=0, before_dispatch=None):
        c = self.controller()
        check = before_dispatch or self.phone.paused
        active = []
        try:
            for i, (start, end) in enumerate(paths):
                check()
                active.append((i, start))
                control_call(c.socket.sendall, touch_packet(0, *start, width, height, pointer=i))
            until = time.monotonic() + hold
            while time.monotonic() < until:
                check()
                time.sleep(min(.02, max(0, until-time.monotonic())))
            steps = max(1, math.ceil(seconds * 30))
            started = time.monotonic()
            for step in range(1, steps+1):
                check()
                for i, (start, end) in enumerate(paths):
                    pos = [round(a+(b-a)*step/steps) for a, b in zip(start, end)]
                    active[i] = (i, pos)
                    if start != end:
                        control_call(c.socket.sendall, touch_packet(2, *pos, width, height, pointer=i))
                time.sleep(max(0, started+seconds*step/steps-time.monotonic()))
        finally:
            release_errors = []
            for i, pos in reversed(active):
                try:
                    control_call(c.socket.sendall, touch_packet(1, *pos, width, height, pointer=i))
                except OSError as error:
                    release_errors.append(f'触点 {i}: {error}')
            if release_errors:
                reason = '抬手派发未知：' + '; '.join(release_errors)
                try:
                    self.phone.quarantine_input(reason)
                except OSError:
                    # The in-memory quarantine remains set if disk persistence fails.
                    pass
                raise ChannelFailure('control', reason)

    def capture_after(self, wait_ms, nodes=False):
        remaining = wait_ms / 1000
        wait_error = None
        while remaining > 0:
            try:
                self.phone.paused()
            except RuntimeError as error:
                wait_error = str(error)
                break
            step = min(.1, remaining)
            time.sleep(step)
            remaining -= step
        return {'执行后等待毫秒': wait_ms,
                **({'等待错误': wait_error} if wait_error else {}), **self.capture(nodes)}

    def observe_result(self, wait_ms=0, nodes=False):
        try:
            result = self.capture_after(wait_ms, nodes)
            return {**result, '观察': {'状态': '已取得',
                    **{k: result[k] for k in ('截图编号', '屏幕') if k in result}}}
        except Exception as error:
            return {'观察': {'状态': '失败', '错误': str(error)}, '观察错误': str(error)}

    def action(self, 操作, 参数=None):
        wait_ms = screenshot_wait(参数)
        p = 参数 or {}
        start = time.monotonic()
        status = '已执行'
        if 操作 not in OPERATIONS:
            raise ValueError('不支持的操作')
        with self.phone.lock():
            if 操作 in ('查看', '等待'):
                if 操作 == '等待' and '时间' in p:
                    wait_ms = screenshot_wait({'执行后等待毫秒': p['时间']})
                observed = self.observe_result(wait_ms, p.get('读取节点', False))
                return {'状态': '已观察' if observed['观察']['状态'] == '已取得' else '观察失败',
                        '执行': {'状态': '无需派发'}, **observed}
            aid = p.get('动作编号', uuid.uuid4().hex)
            if not re.fullmatch(r'[A-Za-z0-9_-]{1,100}', aid):
                raise ValueError('动作编号格式无效')
            claim = self.phone.run / f'unified-{aid}.json'
            if claim.exists():
                previous = json.loads(claim.read_text('utf-8'))
                return {'状态': '重复请求未执行', '动作编号': aid, '原始结果': previous,
                        '执行': previous.get('执行', {'状态': '未知'}),
                        **self.observe_result(0, p.get('读取节点', False))}
            self.phone.paused()
            if not self.latest:
                raise ValueError('请先查看手机')
            obs = self.latest
            if obs['consumed'] or time.time()-obs['captured_at'] > 180:
                raise ValueError('观察已使用或过期，请先查看')
            if p.get('截图编号', obs['observation_id']) != obs['observation_id']:
                raise ValueError('截图编号已过期')
            with self.phone.input_lock(exclusive=(操作 == '输入内容')):
                context = self.phone.context()
                if context != obs['context']:
                    raise ValueError('前台或屏幕已变化，请先查看')
                if 操作 != '打开应用':
                    require_package_allowed(self.phone.config, context['package'])
                w, h = context['width'], context['height']
                secs = duration(p)
                paths = None
                hold = 0
                if 操作 in ('点击', '双击', '长按'):
                    pos = point(p.get('位置'), w, h)
                    paths = [(pos, pos)]
                    secs = duration(p, 700) if 操作 == '长按' else .035
                elif 操作 in ('滑动', '拖拽'):
                    paths = [(point(p.get('起点'), w, h), point(p.get('终点'), w, h))]
                    if 操作 == '拖拽':
                        hold = duration({'时间': p.get('按住时间', 600)})
                elif 操作 in ('放大', '缩小'):
                    x, y = point(p.get('中心'), w, h)
                    a, b = p.get('初始指距', 200), p.get('结束指距', 400 if 操作 == '放大' else 100)
                    if not all(isinstance(v, (int, float)) and 0 < v <= min(w,h) for v in (a,b)):
                        raise ValueError('指距无效')
                    if (操作 == '放大' and b <= a) or (操作 == '缩小' and b >= a):
                        raise ValueError('指距方向与操作不一致')
                    paths = [(point([round(x+s*a/2),y],w,h),point([round(x+s*b/2),y],w,h)) for s in (-1,1)]
                if 操作 == '输入内容':
                    input_evidence = self.phone.check_input_focus(context)
                    limit = self.phone.config.get('max_input_characters', 2000)
                    if not isinstance(p.get('内容'),str) or not 0 < len(p['内容']) <= limit:
                        raise ValueError(f'内容必须为 1～{limit} 字符')
                    if input_evidence is None:
                        ime = self.phone.d.shell(['dumpsys','input_method']).output
                        match = re.search(r'(?:mCurrentEditorInfo:|curEditorInfo:)\s*\n\s*inputType=(0x[\da-fA-F]+)[\s\S]{0,1000}?\n\s*packageName=([^\s]+)',ime)
                        if not match or match[2] != context['package']:
                            raise ValueError('无法确认当前应用的输入框焦点')
                        input_evidence = {'输入框类型': int(match[1], 16)}
                if 操作 in ('删除内容', '全选'):
                    self.phone.check_input_focus(context)
                if 操作 == '按键' and p.get('键名') not in KEYS:
                    raise ValueError('未知键名')
                if 操作 == '删除内容' and (type(p.get('次数',1)) is not int or not 1 <= p.get('次数',1) <= 100):
                    raise ValueError('删除次数必须为1～100')
                if 操作 == '系统面板' and p.get('面板') not in ('通知','快捷设置','收起'):
                    raise ValueError('未知面板')
                if 操作 == '打开应用':
                    require_package_allowed(self.phone.config, p.get('包名'))
                    package = p['包名']
                    resolved = self.phone.d.shell(['cmd','package','resolve-activity','--brief',package])
                    component = launch_component(package, p.get('启动组件'), resolved.output)
                # Connect before final guard; never reconnect and replay an input on failure.
                if 操作 != '打开应用':
                    self.controller()
                self.phone._dispatch_guard(context)
                evidence = {}
                if 操作 == '输入内容':
                    evidence.update(input_evidence)
                if paths and 操作 in ('点击','双击','长按'):
                    from PIL import Image, ImageChops, ImageStat
                    x,y = paths[0][0]
                    box = (max(0,x-40),max(0,y-40),min(w,x+40),min(h,y+40))
                    with Image.open(obs['screenshot']) as saved:
                        old = saved.convert('RGB').crop(box)
                    new = self.phone.d.screenshot().convert('RGB').crop(box)
                    evidence['目标区域像素差均值'] = sum(ImageStat.Stat(ImageChops.difference(old,new)).mean)/3
                safe_params = {k: v for k, v in p.items() if k != '内容'}
                record = {'操作':操作,'参数':safe_params,'动作编号':aid,'状态':'派发中',
                          '执行': {'状态': '未知'}, '开始时间':time.time(), **evidence}
                write_json(claim, record)
                obs['consumed'] = True
                write_json(self.phone.run/'latest.json',obs)
                record['执行'] = {'状态': '未派发'}
                try:
                    self.phone._dispatch_guard(context)
                    record['执行'] = {'状态': '未知'}
                    if paths:
                        self._gesture(paths,w,h,secs,hold)
                        if 操作 == '双击':
                            record['执行'] = {'状态': '部分派发', '已完成点击次数': 1}
                            time.sleep(.1)
                            self.phone._dispatch_guard(context)
                            self._gesture(paths,w,h,.035)
                    elif 操作 == '输入内容':
                        self.controller().paste(p['内容'],lambda:self.phone._dispatch_guard(context))
                    elif 操作 == '按键':
                        self._key(KEYS[p['键名']])
                    elif 操作 == '全选':
                        self._key(29,0x1000)
                    elif 操作 == '删除内容':
                        for _ in range(p.get('次数',1)):
                            self.phone.paused()
                            self._key(67)
                            record['执行'] = {'状态': '部分派发', '已完成按键次数': _ + 1}
                    elif 操作 == '系统面板':
                        control_call(self.controller().socket.sendall, bytes([{'通知':5,'快捷设置':6,'收起':7}[p['面板']]]))
                    elif 操作 == '打开应用':
                        display_args = ['--display', str(self.phone.display_id)] if hasattr(self.phone, 'display_id') else []
                        result = control_call(self.phone.d.shell, ['am','start','-W',*display_args,'-n',component])
                        record['启动输出'] = result.output[:1500]
                        if result.exit_code or 'Error:' in result.output:
                            status = '启动命令被拒绝'
                    record['执行'] = {'状态': '已派发'}
                except Exception as e:
                    status = '未执行' if record['执行']['状态'] == '未派发' else '结果不确定'
                    record['错误'] = str(e)
                    if control_failure(self.phone, e):
                        record['通道'] = {'控制': '断开'}
                    if isinstance(e, OSError) and record['执行']['状态'] != '未派发':
                        try:
                            self.phone.quarantine_input(e)
                        except OSError as marker_error:
                            record['隔离记录错误'] = str(marker_error)
            record.update(状态=status,结束时间=time.time())
            try:
                write_json(claim,record)
                self.phone.log({'event':'unified_action',**record})
            except OSError as error:
                record['记录错误'] = str(error)
            after = self.observe_result(wait_ms, p.get('读取节点',False))
            if 操作 == '打开应用':
                after['目标应用在前台'] = after.get('屏幕', {}).get('package') == p['包名']
            return {'状态':status,'动作编号':aid,'耗时毫秒':round((time.monotonic()-start)*1000),
                    '执行': record['执行'], **evidence,
                    **{k: record[k] for k in ('启动输出', '记录错误') if k in record},
                    **({'错误':record['错误']} if '错误' in record else {}), **after,
                    **({'通道': {**record['通道'], **after.get('通道', {})}} if '通道' in record else {})}


def main():
    from .screens import ScreenRegistry
    sys.stdin.reconfigure(encoding='utf-8')
    sys.stdout.reconfigure(encoding='utf-8')
    phone = ScreenRegistry()
    print('BBUI_READY',flush=True)
    try:
        for line in sys.stdin:
            try:
                request = json.loads(line)
                if request.get('操作') == '结束会话':
                    break
                result = phone.action(request['操作'],request.get('参数',{}))
            except Exception as e:
                result = {'状态':'未执行','错误':str(e)}
            print('BBUI_RESULT '+json.dumps(result,ensure_ascii=False),flush=True)
    finally:
        phone.close()


if __name__ == '__main__':
    main()
