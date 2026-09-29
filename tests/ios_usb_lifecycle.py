"""Opt-in physical unplug/replug acceptance; prints WAITING_UNPLUG when ready."""
import asyncio
import json
import os
import time
import uuid
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from bbui.ios_runtime import IosDevice, DeviceLease

serial = os.environ['BBUI_SMOKE_DEVICE']
root = Path('runs/ios-validation/usb-lifecycle')
device = IosDevice(config={'serial': serial}, run=root)
report = {}
try:
    device.call(device.ready())
    print('WAITING_UNPLUG', flush=True)
    deadline = time.monotonic() + 600
    while time.monotonic() < deadline:
        state = device.call(device.host('status', {}))
        if not state['connected']:
            report['unplug'] = state
            break
        time.sleep(1)
    else:
        raise TimeoutError('Physical USB unplug was not observed')
    print('UNPLUG_OBSERVED_WAITING_REPLUG', flush=True)
    device.call(device.host('stop', {}))
    while time.monotonic() < deadline:
        if device.call(device.transport.usb_present()):
            break
        time.sleep(1)
    else:
        raise TimeoutError('USB replug was not observed')
    report['afterReplugBeforeExplicitReconnect'] = device.call(device.host('status', {}))
    assert not report['afterReplugBeforeExplicitReconnect']['connected']
    device.close()
    device = IosDevice(config={'serial': serial}, run=root)
    device.call(device.ready())
    report['explicitReconnect'] = device.call(device.host('status', {}))
    assert report['explicitReconnect']['connected'] and report['explicitReconnect']['stopped']
    report['readOnlyObservationAfterReconnect'] = device.call(device.action('查看', {'屏幕会话': 'main'}))
    print('EXPLICIT_RECONNECT_READY_STOP_PRESERVED', flush=True)
finally:
    device.close()
    lease = DeviceLease(device.state)
    lease.acquire()
    lease.close()
    root.mkdir(parents=True, exist_ok=True)
    (root / 'result.json').write_text(json.dumps(report, ensure_ascii=False, default=str, indent=2), encoding='utf-8')
