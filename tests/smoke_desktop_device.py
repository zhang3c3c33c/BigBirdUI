"""Explicit, opt-in vivo/ADB gate. Uses only disposable BBUI fixture packages."""
import base64
import json
import os
from pathlib import Path
import sys
import time
import uuid
import adbutils

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from bbui.runtime import PhoneTools
from bbui.screens import ScreenRegistry
from bbui.desktop_device import DesktopDevice


def main():
    devices = adbutils.adb.device_list()
    if len(devices) != 1:
        raise RuntimeError('Connect exactly one authorized test device')
    device = devices[0]
    run = ROOT / 'runs' / ('desktop-device-' + uuid.uuid4().hex[:8]); run.mkdir()
    config = run / 'config.json'; config.write_text(json.dumps({'serial': device.serial, 'embedded_preview': True}), encoding='utf-8')
    os.environ.update(BBUI_CONFIG_FILE=str(config), BBUI_RUNS_DIR=str(run), BBUI_ASSET_ROOT=str(ROOT / '.desktop/app/assets'))
    registry = ScreenRegistry(PhoneTools(ROOT)); desktop = DesktopDevice(registry)
    installed = []
    clipboard = None
    shared = '/storage/emulated/0/Download/bbui-desktop-' + uuid.uuid4().hex[:8]
    def check(result):
        if result.get('错误'):
            raise RuntimeError(result['错误'])
        return result
    def system(group, operation, params=None):
        return check(desktop.execute_system(group, operation, params or {}, uuid.uuid4().hex))
    def action(screen, operation, **params):
        obs = check(registry.action('查看', {'屏幕会话': screen}))
        return check(registry.action(operation, {'屏幕会话': screen, '截图编号': obs['截图编号'], '动作编号': uuid.uuid4().hex, **params}))
    def editor(screen, package, content):
        geometry = json.loads(device.shell(['run-as', package, 'cat', 'files/geometry.json']))
        action(screen, '点击', 位置=[geometry['x'], geometry['y']])
        action(screen, '输入内容', 内容=content)
        time.sleep(.2)
        assert device.shell(['run-as', package, 'cat', 'files/input.txt']).strip() == content
        action(screen, '全选'); action(screen, '删除内容')
        assert device.shell(['run-as', package, 'cat', 'files/input.txt']).strip() == ''
        action(screen, '输入内容', 内容=content)
    try:
        desktop.host('resume', {})
        print('Device:', device.prop.model, 'Android', device.prop.get('ro.build.version.release'), flush=True)
        system('apps', 'list', {'query': 'io.bbui.desktopfixture', 'limit': 10})
        assert not registry.sessions, 'System queries must not create displays'
        clipboard = system('clipboard', 'read')['data']
        assert not clipboard.get('hasClip') or clipboard.get('isPlainText'), 'Clipboard is not plain text; preserve it and defer mutation gate'
        for letter in ('a', 'b'):
            package = 'io.bbui.desktopfixture' + letter
            assert not device.shell(['pm', 'path', package]).strip(), 'Fixture already exists; do not replace user state'
            device.install(str(ROOT / '.desktop/fixtures' / (letter + '.apk')))
            installed.append(package)
        system('clipboard', 'write', {'text': 'BBUI 中文剪贴板 👋'})
        assert system('clipboard', 'read')['data']['text'] == 'BBUI 中文剪贴板 👋'
        system('files', 'mkdir', {'path': shared})
        system('files', 'write_text', {'path': shared + '/check.txt', 'text': '共享文件测试'})
        assert '共享文件测试' in json.dumps(system('files', 'read_text', {'path': shared + '/check.txt'}), ensure_ascii=False)
        system('apps', 'grant_permission', {'packageName': installed[0], 'permission': 'android.permission.CAMERA'})
        system('apps', 'revoke_permission', {'packageName': installed[0], 'permission': 'android.permission.CAMERA'})
        system('apps', 'grant_permission', {'packageName': installed[0], 'permission': 'android.permission.POST_NOTIFICATIONS'})
        device.shell(['am', 'start', '-n', installed[0] + '/io.bbui.toolfixture.FixtureActivity', '--ez', 'postNotification', 'true'])
        system('notifications', 'list', {'packageName': installed[0]})
        print('System tools: apps, notifications, clipboard, shared files PASS', flush=True)
        frame = desktop.frame('main'); (run / 'main.jpg').write_bytes(base64.b64decode(frame['image'].split(',')[1]))
        editor('main', installed[0], '主屏中文测试')
        device.shell(['am', 'force-stop', installed[0]])
        for name, package in zip(('gate_a', 'gate_b'), installed):
            result = check(registry.action('创建屏幕', {'屏幕会话': name, '包名': package, '宽度': 800, '高度': 1200}))
            time.sleep(.8)
            editor(name, package, name + ' 跨屏中文 👋')
            print(name, 'display', result['显示屏编号'], 'Chinese/select/delete PASS', flush=True)
        # Interleave both screens to exercise target-display focus, not global IME focus.
        action('gate_a', '输入内容', 内容='甲')
        action('gate_b', '输入内容', 内容='乙')
        assert device.shell(['run-as', installed[0], 'cat', 'files/input.txt']).endswith('甲')
        assert device.shell(['run-as', installed[1], 'cat', 'files/input.txt']).endswith('乙')
        obs = check(registry.action('查看', {'屏幕会话': 'gate_b'}))
        frame = desktop.frame('gate_b')
        latest = json.loads((registry.sessions['gate_b'].phone.run / 'latest.json').read_text('utf-8'))
        assert latest['observation_id'] == obs['截图编号'], 'Preview consumed model observation'
        desktop.host('hold', {}); token = desktop.host('manual', {})['token']
        geometry = json.loads(device.shell(['run-as', installed[1], 'cat', 'files/geometry.json']))
        check(desktop.host('input', {'screen': 'gate_b', 'token': token, 'frameId': frame['frameId'], 'actionId': uuid.uuid4().hex,
            'operation': '点击', 'params': {'位置': [geometry['x'], geometry['y']]}}))
        desktop.host('stop', {})
        try:
            desktop.host('input', {'screen': 'gate_b', 'token': token, 'frameId': frame['frameId'], 'actionId': uuid.uuid4().hex, 'operation': '输入内容', 'params': {'内容': '禁止派发'}})
            raise AssertionError('STOP did not veto manual input')
        except (RuntimeError, ValueError): pass
        system('apps', 'list', {'query': 'io.bbui.desktopfixture'})
        desktop.frame('gate_b')
        print('Preview, manual control and authoritative STOP PASS', flush=True)
        (run / 'result.json').write_text(json.dumps({'device': device.prop.model, 'android': device.prop.get('ro.build.version.release'), 'passed': True}, ensure_ascii=False), encoding='utf-8')
    finally:
        try:
            desktop.host('resume', {})
            if clipboard is not None:
                system('clipboard', 'write' if clipboard.get('hasClip') else 'clear', {'text': clipboard.get('text', '')})
            system('files', 'delete', {'path': shared, 'recursive': True})
        finally:
            desktop.host('stop', {}); desktop.system.close(); registry.close()
            for package in installed: device.uninstall(package)
        print('Temporary fixtures removed; result directory:', run, flush=True)


if __name__ == '__main__': main()
