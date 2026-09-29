"""Opt-in manual input timings; taps the status-bar corner, never app content."""
import json
import os
from pathlib import Path
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else ROOT))
from bbui.runtime import PhoneTools
from bbui.screens import ScreenRegistry
from bbui.desktop_device import DesktopDevice


def main():
    serial = os.environ.get('BBUI_SMOKE_DEVICE')
    if not serial:
        raise RuntimeError('Set BBUI_SMOKE_DEVICE explicitly')
    run = ROOT / 'runs' / ('desktop-input-' + uuid.uuid4().hex[:8])
    run.mkdir()
    config = run / 'config.json'
    config.write_text(json.dumps({'serial': serial, 'embedded_preview': True}), encoding='utf-8')
    os.environ.update(BBUI_CONFIG_FILE=str(config), BBUI_RUNS_DIR=str(run),
                      BBUI_ASSET_ROOT=str(ROOT / '.desktop/app/assets'))
    registry = ScreenRegistry(PhoneTools(ROOT))
    desktop = DesktopDevice(registry)
    samples = []
    current = None
    try:
        desktop.host('resume', {})
        registry.connect_phone()
        desktop.frame('main')
        tool = registry.sessions['main']
        context = tool.phone.context

        def timed_context():
            begin = time.perf_counter()
            try:
                return context()
            finally:
                if current is not None:
                    current['contextCalls'] += 1
                    current['contextMs'] += (time.perf_counter() - begin) * 1000

        tool.phone.context = timed_context
        socket = tool.control.socket

        class TimedSocket:
            def sendall(self, data):
                if current is not None and current['dispatchMs'] is None:
                    current['dispatchMs'] = (time.perf_counter() - current['started']) * 1000
                return socket.sendall(data)

            def __getattr__(self, name):
                return getattr(socket, name)

        tool.control.socket = TimedSocket()
        desktop.host('hold', {})
        token = desktop.host('manual', {'resumeStopped': True})['token']
        for _ in range(5):
            frame = desktop.frame('main')
            current = {'started': time.perf_counter(), 'dispatchMs': None, 'contextCalls': 0, 'contextMs': 0}
            result = desktop.host('input', {'screen': 'main', 'token': token, 'frameId': frame['frameId'],
                'actionId': uuid.uuid4().hex, 'operation': '点击', 'params': {'位置': [5, 5]}})
            current['totalMs'] = (time.perf_counter() - current.pop('started')) * 1000
            if result.get('错误') or result.get('执行', {}).get('状态') != '已派发':
                raise AssertionError(result.get('错误') or 'Input was not dispatched')
            samples.append(current)
            current = None
        (run / 'result.json').write_text(json.dumps(samples, indent=2), encoding='utf-8')
        print(json.dumps({'directory': str(run), 'samples': samples}), flush=True)
    finally:
        desktop.host('stop', {})
        desktop.system.close()
        registry.close()


if __name__ == '__main__':
    main()
