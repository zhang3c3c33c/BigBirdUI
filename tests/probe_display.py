"""Explicit hardware probe: create a display, open a benign system app, capture, close."""
from pathlib import Path
import sys
import time
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from bbui.runtime import ROOT, PhoneTools
from bbui.scrcpy_display import ScrcpyDisplay

p = PhoneTools()
with ScrcpyDisplay(p.serial, ROOT, 720, 1280, 240) as display:
    print('display_id', display.display_id, flush=True)
    package = 'com.android.bbkcalculator'
    resolved = display.device.shell(['cmd', 'package', 'resolve-activity', '--brief', package])
    component = [x.strip() for x in resolved.splitlines() if x.strip().startswith(package + '/')][0]
    print(display.device.shell(['am', 'start', '--display', str(display.display_id), '-n', component]), flush=True)
    time.sleep(3)
    picture = display.screenshot()
    path = p.run / 'virtual-probe.png'
    picture.save(path)
    print('saved', path, picture.size, 'frame', display.frame_sequence, flush=True)
    dump = display.device.shell(['dumpsys', 'activity', 'activities'])
    (p.run / 'virtual-probe-activities.txt').write_text(dump, encoding='utf-8')
    print('\n'.join(x for x in dump.splitlines() if 'Display #' in x or 'topResumedActivity=' in x), flush=True)
