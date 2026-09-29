"""Observe an existing external display; never stop or control its owner."""
import base64
import json
import os
from pathlib import Path
import sys
import uuid
import adbutils

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from bbui.runtime import PhoneTools
from bbui.screens import ScreenRegistry
from bbui.desktop_device import DesktopDevice, external_displays


def main():
    serial = os.environ.get('BBUI_SMOKE_DEVICE')
    if not serial:
        raise RuntimeError('Set BBUI_SMOKE_DEVICE explicitly; an external virtual display must already exist')
    run = ROOT / 'runs' / ('desktop-external-' + uuid.uuid4().hex[:8]); run.mkdir()
    config = run / 'config.json'
    config.write_text(json.dumps({'serial': serial, 'embedded_preview': True}), encoding='utf-8')
    os.environ.update(BBUI_CONFIG_FILE=str(config), BBUI_RUNS_DIR=str(run), BBUI_ASSET_ROOT=str(ROOT / '.desktop/app/assets'))
    registry = ScreenRegistry(PhoneTools(ROOT)); desktop = DesktopDevice(registry)
    device = adbutils.adb.device(serial)
    target = None
    try:
        sources = desktop.host('previewSources', {})
        assert sources, 'No external display to validate'
        expected = os.environ.get('BBUI_EXTERNAL_DISPLAY')
        target = next(item for item in sources if not expected or item['显示屏编号'] == int(expected))
        assert target['readOnly'] and target['external']
        frame = desktop.host('frame', {'screen': target['屏幕会话']})
        assert frame['width'] > 0 and frame['height'] > 0
        (run / 'external.jpg').write_bytes(base64.b64decode(frame['image'].split(',')[1]))
        try:
            desktop.host('input', {'screen': target['屏幕会话'], 'operation': '点击', 'params': {'位置': [5, 5]},
                'frameId': frame['frameId'], 'actionId': uuid.uuid4().hex, 'token': 'not-granted'})
            raise AssertionError('External input was accepted')
        except ValueError as error:
            assert '外部屏幕' in str(error)
        assert not registry.sessions, 'External viewer must not become a model-owned screen'
        refreshed = desktop.host('previewSources', {})
        assert {item['屏幕会话'] for item in refreshed} == {item['屏幕会话'] for item in sources}, 'Capture-only displays leaked into source list'
    finally:
        desktop.close()
        registry.close()
    remaining = external_displays(device.shell(['dumpsys', 'display']))
    assert target and any(item['屏幕会话'] == target['屏幕会话'] for item in remaining), 'Closing viewer removed owner display'
    result = {'source': target, 'size': [frame['width'], frame['height']], 'ownerDisplayPreserved': True}
    (run / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'directory': str(run), **result}, ensure_ascii=False))


if __name__ == '__main__':
    main()
