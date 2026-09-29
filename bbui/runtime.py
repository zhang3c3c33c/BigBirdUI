from __future__ import annotations

from http.client import RemoteDisconnected
import json
import os
import re
import time
import uuid
import xml.etree.ElementTree as ET
from contextlib import contextmanager, nullcontext
from pathlib import Path
from .app_policy import blocked_packages, require_package_allowed
from .paths import device_state

ROOT = Path(__file__).resolve().parents[1]


def write_json(path, value):
    tmp = path.with_suffix('.tmp')
    with tmp.open('w', encoding='utf-8') as f:
        f.write(json.dumps(value, ensure_ascii=False, indent=2))
        f.flush()
        os.fsync(f.fileno())
    tmp.replace(path)


def parse_nodes(xml):
    nodes = []
    for index, element in enumerate(ET.fromstring(xml).iter('node')):
        a = element.attrib
        bounds = [int(n) for n in re.findall(r'-?\d+', a.get('bounds', ''))]
        if len(bounds) != 4:
            continue
        nodes.append(dict(id=f'n{index}', text=a.get('text', ''),
                          description=a.get('content-desc', ''),
                          resource_id=a.get('resource-id', ''),
                          class_name=a.get('class', ''), package=a.get('package', ''),
                          bounds=bounds, clickable=a.get('clickable') == 'true',
                          enabled=a.get('enabled') == 'true',
                          focused=a.get('focused') == 'true',
                          scrollable=a.get('scrollable') == 'true',
                          password=a.get('password') == 'true'))
    return nodes


def fingerprint(node):
    return tuple(str(node[k]) for k in ('text', 'description', 'resource_id', 'class_name',
                                      'package', 'bounds', 'enabled'))


class PhoneTools:
    def input_lock(self, exclusive=False):
        return nullcontext()

    def check_input_focus(self, context):
        pass

    def __init__(self, root=ROOT, device=None):
        self.root = Path(root)
        self.config = json.loads(Path(os.environ.get('BBUI_CONFIG_FILE', self.root / 'config.local.json')).read_text('utf-8'))
        blocked_packages(self.config)
        self.serial = self.config['serial']
        self.run = Path(os.environ.get('BBUI_RUNS_DIR', self.root / 'runs')) / self.serial
        self.run.mkdir(parents=True, exist_ok=True)
        self.state_dir = device_state(self.root, self.serial)
        self.state_dir.mkdir(parents=True, exist_ok=True)
        self._device = device
        self._input_failure = None
        self._bridge_owner = os.environ.get('BBUI_BRIDGE_OWNER') or f'standalone-{uuid.uuid4().hex}'

    @property
    def d(self):
        if self._device is None:
            import uiautomator2 as u2
            self._device = u2.connect(self.serial)
        return self._device

    @contextmanager
    def lock(self):
        # OS lock releases on process exit; no stale lock-file recovery needed.
        with (getattr(self, 'state_dir', self.run) / 'device.lock').open('a+b') as f:
            f.seek(0, 2)
            if not f.tell():
                f.write(b'0')
                f.flush()
            f.seek(0)
            if os.name == 'nt':
                import msvcrt
                msvcrt.locking(f.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(f, fcntl.LOCK_EX | fcntl.LOCK_NB)
            try:
                yield
            finally:
                f.seek(0)
                if os.name == 'nt':
                    msvcrt.locking(f.fileno(), msvcrt.LK_UNLCK, 1)
                else:
                    fcntl.flock(f, fcntl.LOCK_UN)

    def log(self, value):
        with (self.run / 'events.jsonl').open('a', encoding='utf-8') as f:
            f.write(json.dumps({'time': time.time(), **value}, ensure_ascii=False) + '\n')
            f.flush()
            os.fsync(f.fileno())

    def context(self):
        app = self.d.app_current()
        info = self.d.info
        return {'package': app.get('package'), 'activity': app.get('activity'),
                'rotation': info.get('displayRotation'),
                'width': info.get('displayWidth'), 'height': info.get('displayHeight')}

    def observe(self):
        import requests
        with self.lock():
            for attempt in range(3):
                try:
                    return self._observe()
                except (ConnectionError, TimeoutError, RemoteDisconnected, requests.exceptions.RequestException):
                    if attempt == 2:
                        raise
                    self._device = None
                    time.sleep(0.5)

    def _observe(self):
        stable = False
        context_error = None
        node_error = None
        after = {}
        for attempt in range(3):
            started = time.time()
            try:
                before = self.context()
            except Exception as error:
                before = None
                context_error = str(error)
            try:
                xml = self.d.dump_hierarchy()
                parsed = parse_nodes(xml)
                node_error = None
            except Exception as error:
                xml, parsed = '', []
                node_error = str(error)
            tree_at = time.time()
            picture = self.d.screenshot()
            screen_at = time.time()
            try:
                after = self.context()
            except Exception as error:
                after = {}
                context_error = str(error)
            if before is not None and before == after:
                stable = True
                break
        if not stable:
            context_error = context_error or 'Screen context changed during capture; image is not an input credential'
        observation_id = uuid.uuid4().hex
        folder = self.run / observation_id
        folder.mkdir()
        (folder / 'hierarchy.xml').write_text(xml, encoding='utf-8')
        picture.save(folder / 'screen.png')
        value = {'observation_id': observation_id, 'serial': self.serial,
                 'started_at': started, 'tree_at': tree_at, 'screen_at': screen_at,
                 'captured_at': time.time(), 'context': after,
                 'capture_is_atomic': False, 'screenshot': str(folder / 'screen.png'),
                 'hierarchy': str(folder / 'hierarchy.xml'),
                 'nodes': parsed, 'consumed': not stable, 'input_eligible': stable,
                 '执行': {'状态': '无需派发'}, '观察': {'状态': '已取得'},
                 'nodes_status': 'failed' if node_error else 'available',
                 **({'nodes_error': node_error} if node_error else {}),
                 **({'context_error': context_error} if context_error else {})}
        write_json(folder / 'observation.json', value)
        write_json(self.run / 'latest.json', value)
        self.log({'event': 'observation', 'observation_id': observation_id, 'context': after})
        return value

    def paused(self):
        if (self.run / 'STOP').exists() or (getattr(self, 'state_dir', self.run) / 'STOP').exists():
            raise RuntimeError('Device is paused. Explicitly resume before acting.')
        if getattr(self, '_input_failure', None) or (self.run / 'INPUT_UNCERTAIN').exists() or (getattr(self, 'state_dir', self.run) / 'INPUT_UNCERTAIN').exists():
            raise RuntimeError('A previous input may still be in flight; observation remains available. Host must confirm completion before new input.')
        if getattr(self, 'control_error', None):
            raise RuntimeError('Control channel disconnected; reconnect before input.')
        gate = getattr(self, 'control_gate', None)
        if gate is not None:
            gate.check()

    def quarantine_input(self, reason):
        self._input_failure = str(reason)
        try:
            with (getattr(self, 'state_dir', self.run) / 'INPUT_UNCERTAIN').open('x', encoding='utf-8') as marker:
                json.dump({'owner': self._bridge_owner, 'reason': str(reason),
                           'createdAtMs': round(time.time() * 1000)}, marker, ensure_ascii=False)
        except FileExistsError:
            pass

    def stop(self):
        (getattr(self, 'state_dir', self.run) / 'STOP').write_text('Stop requested', encoding='utf-8')
        return {'paused': True, 'note': 'Blocks new dispatch; cannot undo in-flight input.'}

    def resume(self):
        with self.lock():
            (self.run / 'STOP').unlink(missing_ok=True)
            (getattr(self, 'state_dir', self.run) / 'STOP').unlink(missing_ok=True)
            return {'paused': False}

    def act(self, action: dict):
        """One action only. Always inspect a new observation before another action."""
        with self.lock():
            action_id = action['action_id']
            if not re.fullmatch(r'[A-Za-z0-9_-]{1,100}', action_id):
                raise ValueError('Invalid action_id')
            claim = self.run / f'action-{action_id}.json'
            if claim.exists():
                previous = json.loads(claim.read_text('utf-8'))
                return {'status': 'already_attempted', 'record': previous,
                        '执行': previous.get('执行', {'状态': '未知'}), '观察': {'状态': '未请求'}}
            self.paused()
            obs = json.loads((self.run / 'latest.json').read_text('utf-8'))
            if action['observation_id'] != obs['observation_id']:
                raise ValueError('Stale observation: use latest observation_id')
            if time.time() - obs['captured_at'] > self.config.get('observation_max_age_seconds', 180):
                raise ValueError('Observation expired')
            if obs.get('consumed'):
                raise ValueError('Observation already used; observe again')
            context = self.context()
            if context != obs['context']:
                raise ValueError('App/rotation/geometry changed; observe again')
            kind = action['type']
            if kind not in ('launch', 'tap', 'tap_point', 'input_focused', 'type_text', 'back', 'swipe'):
                raise ValueError('Unknown action type')
            if kind == 'launch':
                require_package_allowed(self.config, action.get('package'))
            else:
                require_package_allowed(self.config, context['package'])
            fresh = parse_nodes(self.d.dump_hierarchy()) if kind in ('tap', 'type_text') or action.get('required_texts') else []
            for text in action.get('required_texts', []):
                if not any(n['text'] == text or n['description'] == text for n in fresh):
                    raise ValueError(f'Required screen text missing: {text}')
            target = None
            if kind in ('tap_point', 'input_focused'):
                from PIL import Image, ImageChops, ImageStat
                x, y = action['point']
                if not (isinstance(x, int) and isinstance(y, int) and
                        0 <= x < context['width'] and 0 <= y < context['height']):
                    raise ValueError('Point outside screenshot')
                box = (max(0, x-60), max(0, y-60), min(context['width'], x+60), min(context['height'], y+60))
                with Image.open(obs['screenshot']) as saved:
                    old_roi = saved.convert('RGB').crop(box)
                current_roi = self.d.screenshot().convert('RGB').crop(box)
                difference = ImageStat.Stat(ImageChops.difference(old_roi, current_roi))
                visual_difference = sum(difference.mean) / 3
                if kind == 'input_focused':
                    ime = self.d.shell(['dumpsys', 'input_method']).output
                    match = re.search(r'(?:mCurrentEditorInfo:|curEditorInfo:)\s*\n\s*inputType=(0x[0-9a-fA-F]+)[\s\S]{0,1000}?\n\s*packageName=([^\s]+)', ime)
                    if not match or match[2] != context['package']:
                        raise ValueError('Cannot confirm focused editor belongs to foreground app')
                    input_type = int(match[1], 16)
                    self.check_input_focus(context)
                    if not isinstance(action.get('text'), str) or len(action['text']) > self.config.get('max_input_characters', 2000):
                        raise ValueError('Invalid text')
            if kind in ('tap', 'type_text'):
                original = next((n for n in obs['nodes'] if n['id'] == action['node_id']), None)
                if not original:
                    raise ValueError('Unknown node_id')
                matches = [n for n in fresh if fingerprint(n) == fingerprint(original)]
                if len(matches) != 1 or not matches[0]['enabled']:
                    raise ValueError('Target changed, ambiguous or disabled; observe again')
                target = matches[0]
                x1, y1, x2, y2 = target['bounds']
                if not (0 <= x1 < x2 <= context['width'] and 0 <= y1 < y2 <= context['height']):
                    raise ValueError('Target outside screen')
                if kind == 'type_text':
                    if target['class_name'] != 'android.widget.EditText':
                        raise ValueError('Target must be an EditText')
                    if not isinstance(action.get('text'), str) or len(action['text']) > self.config.get('max_input_characters', 2000):
                        raise ValueError('Text exceeds configured max_input_characters')
            if kind == 'swipe':
                points = action['points']
                if len(points) != 4 or not all(isinstance(v, int) for v in points):
                    raise ValueError('points must contain four integer pixel coordinates')
                for x, y in (points[:2], points[2:]):
                    if not (0 <= x < context['width'] and 0 <= y < context['height']):
                        raise ValueError('Swipe outside screen')
            if self.context() != context:
                raise ValueError('Context changed before dispatch')
            self.paused()
            if kind == 'launch':
                package = action['package']
                resolved = self.d.shell(['cmd', 'package', 'resolve-activity', '--brief', package])
                components = [line.strip() for line in resolved.output.splitlines()
                              if line.strip().startswith(package + '/')]
                if len(components) != 1:
                    raise ValueError('Could not resolve a unique launcher Activity; launch not dispatched')
            # Durable intent before dispatch: crash/timeout is uncertain, never auto-retry.
            record = {'status': 'dispatching', 'action': {k: v for k, v in action.items() if k != 'text'},
                      '执行': {'状态': '未知'}, 'time': time.time()}
            if kind in ('tap_point', 'input_focused'):
                record['visual_difference'] = visual_difference
            if kind == 'input_focused':
                record['input_type'] = input_type
            write_json(claim, record)
            obs['consumed'] = True
            write_json(self.run / 'latest.json', obs)
            self.log({'event': 'action_intent', **record})
            record['执行'] = {'状态': '未派发'}
            try:
                self._dispatch_guard(context)
                record['执行'] = {'状态': '未知'}
                if kind == 'launch':
                    launched = self.d.shell(['am', 'start', '-W', '-n', components[0]])
                    record['执行'] = {'状态': '已派发'}
                    if launched.exit_code or 'Error:' in launched.output:
                        raise RuntimeError(launched.output)
                    record['目标应用在前台'] = self.context().get('package') == package
                elif kind == 'tap':
                    self._tap((x1 + x2) // 2, (y1 + y2) // 2, context)
                elif kind == 'tap_point':
                    self._tap(x, y, context)
                elif kind == 'input_focused':
                    from .scrcpy_control import ScrcpyControl
                    with ScrcpyControl(self.serial, self.root) as controller:
                        controller.paste(action['text'], lambda: self._dispatch_guard(context))
                elif kind == 'type_text':
                    selector = {'className': target['class_name']}
                    if target['resource_id']:
                        selector['resourceId'] = target['resource_id']
                    field = self.d(**selector)
                    if field.count != 1:
                        raise ValueError('Editable selector is ambiguous')
                    self._dispatch_guard(context)
                    field.set_text(action['text'])
                elif kind == 'back':
                    self.d.press('back')
                else:
                    self.d.swipe(*points, duration=0.4)
                record['status'] = 'dispatched'
                record['执行'] = {'状态': '已派发'}
            except Exception as e:
                record.update(status='not_dispatched' if record['执行']['状态'] == '未派发' else 'uncertain', error=str(e))
                if isinstance(e, OSError) and record['执行']['状态'] != '未派发':
                    try:
                        self.quarantine_input(e)
                    except OSError as marker_error:
                        record['隔离记录错误'] = str(marker_error)
            try:
                write_json(claim, record)
                self.log({'event': 'action_result', **record})
            except OSError as error:
                record['记录错误'] = str(error)
            result = {'status': record['status'], 'action_id': action_id,
                      '状态': {'已派发': '已派发', '未派发': '未执行'}.get(record['执行']['状态'], '结果不确定'),
                      '执行': record['执行'], 'record': record}
            try:
                observed = self._observe()
                result.update(观察={'状态': '已取得'}, observation=compact(observed), 截图=observed['screenshot'])
            except Exception as error:
                result['观察'] = {'状态': '失败', '错误': str(error)}
            return result

    def _dispatch_guard(self, context):
        self.paused()
        if self.context() != context:
            raise ValueError('Context changed while connecting control channel')
        self.paused()

    def _tap(self, x, y, context):
        if self.config.get('input_backend') == 'scrcpy':
            from .scrcpy_control import ScrcpyControl
            with ScrcpyControl(self.serial, self.root) as controller:
                controller.tap(x, y, context['width'], context['height'],
                               lambda: self._dispatch_guard(context))
        else:
            self._dispatch_guard(context)
            self.d.click(x, y)


def compact(observation):
    nodes = []
    for n in observation['nodes']:
        if n['package'] == 'com.android.systemui' and observation['context'].get('package') != n['package']:
            continue
        if not (n['text'] or n['description'] or n['clickable'] or n['scrollable'] or
                n['class_name'] == 'android.widget.EditText'):
            continue
        nodes.append({k: v for k, v in n.items() if v not in ('', False) and k != 'package'})
    return {**observation, 'nodes': nodes}
