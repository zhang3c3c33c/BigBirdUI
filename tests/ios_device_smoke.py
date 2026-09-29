"""Opt-in USB iPhone acceptance using the existing MultiTouch Visualizer.

BBUI_SMOKE_DEVICE selects one phone. Temporary AFC content and clipboard are
restored in finally. No application is installed or removed.
"""
import asyncio
import json
import os
import sys
import time
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from bbui.ios_runtime import IosDevice, DeviceLease
from bbui.ios_transport import clipboard_identity

serial = os.environ['BBUI_SMOKE_DEVICE']
root = Path('runs') / ('ios-device-' + uuid.uuid4().hex[:8])
device = IosDevice(config={'serial': serial}, run=root)
report = []
directory = '/BBUI-validation-' + uuid.uuid4().hex


async def run():
    await device.ready()
    await device.host('resume', {})
    original = await device.transport.clipboard('snapshot')
    async def action(operation, **params):
        observed = await device.action('查看', {'屏幕会话': 'main'})
        values = {'屏幕会话': 'main', '截图编号': observed['截图编号'], '动作编号': uuid.uuid4().hex, **params}
        result = await device.action(operation, values)
        report.append({'operation': operation, 'result': result})
        if result.get('错误') or result.get('执行', {}).get('状态') not in {'已派发', '无需派发'}:
            raise AssertionError(result)
        return result, values
    async def system(group, operation, **params):
        result = await device.system(group, operation, params, uuid.uuid4().hex)
        report.append({'operation': group + '.' + operation, 'result': result})
        if result.get('错误'):
            raise AssertionError(result)
        return result.get('结果')
    created = False
    try:
        try:
            duplicate = DeviceLease(device.state)
            duplicate.acquire()
        except RuntimeError:
            report.append({'exclusiveLease': True})
        else:
            duplicate.close()
            raise AssertionError('Second executor obtained lease')
        apps = await system('apps', 'list', query='MultiTouch')
        assert apps['items'] and apps['items'][0]['packageName'] == 'com.Goodix.MultiTouch-Visualizer', apps
        details = await system('apps', 'details', packageName='com.Goodix.MultiTouch-Visualizer')
        assert details['packageName'] == 'com.Goodix.MultiTouch-Visualizer', details
        await action('打开应用', 包名='com.Goodix.MultiTouch-Visualizer', 执行后等待毫秒=400)
        await action('点击', 位置=[80, 44])
        await action('点击', 位置=[290, 44])
        for operation, params in [
            ('点击', {'位置': [150, 450]}), ('双击', {'位置': [250, 450]}),
            ('长按', {'位置': [350, 450], '时间': 700}),
            ('滑动', {'起点': [180, 600], '终点': [500, 850], '时间': 700}),
            ('拖拽', {'起点': [200, 850], '终点': [550, 650], '时间': 800}),
            ('放大', {'中心': [375, 1000], '初始指距': 100, '结束指距': 400, '时间': 700}),
            ('缩小', {'中心': [375, 1100], '初始指距': 400, '结束指距': 100, '时间': 700}),
        ]:
            result, values = await action(operation, **params)
            repeated = await device.action(operation, values)
            assert repeated['状态'] == '重复请求未执行', repeated
        report.append({'gestureScreenshot': result.get('截图')})
        await device.action('等待', {'屏幕会话': 'main', '时间': 50})
        await device.action('列出屏幕', {'屏幕会话': 'main'})
        await device.action('列出应用', {'屏幕会话': 'main', '关键词': 'MultiTouch'})
        await system('clipboard', 'write', text='BBUI 临时中文验收 😀')
        assert (await system('clipboard', 'read'))['text'] == 'BBUI 临时中文验收 😀'
        await system('clipboard', 'clear')
        assert (await system('clipboard', 'read'))['text'] in ('', None)
        await system('files', 'mkdir', path=directory)
        created = True
        await system('files', 'write_text', path=directory + '/中文.txt', text='中文 AFC 😀\nsecond line')
        assert (await system('files', 'read_text', path=directory + '/中文.txt'))['data']['text'] == '中文 AFC 😀\nsecond line'
        await system('files', 'stat', path=directory + '/中文.txt')
        await system('files', 'list', path=directory)
        await system('files', 'search', path=directory, query='中文')
        await system('files', 'copy', path=directory + '/中文.txt', destination=directory + '/copy.txt')
        await system('files', 'rename', path=directory + '/copy.txt', destination=directory + '/renamed.txt')
        await system('files', 'move', path=directory + '/renamed.txt', destination=directory + '/moved.txt')
        await system('files', 'delete', path=directory + '/moved.txt')
        await action('按键', 键名='最近任务')
        await action('按键', 键名='主页')
        await system('apps', 'force_stop', packageName='com.Goodix.MultiTouch-Visualizer')
        await device.host('stop', {})
        observation = await device.action('查看', {'屏幕会话': 'main'})
        try:
            await device.action('点击', {'屏幕会话': 'main', '截图编号': observation['截图编号'], '位置': [300, 500], '动作编号': uuid.uuid4().hex})
        except RuntimeError as error:
            assert '停止' in str(error)
        else:
            raise AssertionError('STOP accepted input')
        report.append({'stopBlocksInputObservationAvailable': True})
        await device.host('resume', {})
    finally:
        if created:
            await device.transport.files('delete', {'path': directory, 'recursive': True})
        await device.transport.clipboard('restore', original)
        restored = await device.transport.clipboard('snapshot')
        # Source metadata changes on a new set; the original items are exact.
        assert clipboard_identity(restored).get('items') == clipboard_identity(original).get('items')
        report.append({'temporaryFilesRemovedAndClipboardRestored': True})
        await device.transport.home(False, lambda: None)


try:
    device.call(run(), timeout=240)
finally:
    root.mkdir(parents=True, exist_ok=True)
    (root / 'result.json').write_text(json.dumps(report, ensure_ascii=False, default=str, indent=2), encoding='utf-8')
    device.close()
    lease = DeviceLease(device.state)
    lease.acquire()
    lease.close()
    print(json.dumps({'report': str(root / 'result.json'), 'records': len(report), 'leaseReleased': True}))
