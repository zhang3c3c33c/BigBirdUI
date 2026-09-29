import asyncio
import io
import json
import tempfile
import threading
import time
import unittest
from unittest.mock import AsyncMock
from pathlib import Path

from PIL import Image
from bbui.ios_runtime import IosDevice, DeviceLease, device_id
from bbui.ios_mcp import host_request
from bbui.channel_errors import ChannelFailure, host_error


class FakeTransport:
    def __init__(self, serial, status):
        self.serial, self.status = serial, status
        self.captures = self.inputs = self.active = self.max_active = self.releases = 0
        self.fail_capture = False
        self.fail_dispatch = False
        self.orientation_value = 1
        self.present = True
        self.size = (75, 134)
        self.on_dispatch = self.before_dispatch = lambda: None
        self.warnings = []

    async def connect(self):
        pass

    async def usb_present(self):
        return self.present

    async def screenshot(self):
        self.active += 1
        self.max_active = max(self.max_active, self.active)
        self.captures += 1
        try:
            await asyncio.sleep(.015)
            if self.fail_capture:
                raise OSError('screenshot unavailable')
            out = io.BytesIO()
            Image.new('RGB', self.size, (self.captures % 255, 0, 0)).save(out, 'PNG')
            return out.getvalue()
        finally:
            self.active -= 1

    async def orientation(self):
        return self.orientation_value

    async def gesture(self, paths, seconds, guard, hold=0):
        guard()
        self.before_dispatch()
        self.inputs += 1
        self.on_dispatch()
        if self.fail_dispatch:
            raise OSError('write after receipt failed')
        await asyncio.sleep(.01)
        guard()

    async def key(self, usage, guard, command=False):
        await self.gesture([], 0, guard)

    async def apps(self, operation, params):
        if operation in {'launch', 'force_stop'}:
            self.before_dispatch()
            self.inputs += 1
            self.on_dispatch()
        return []

    async def clipboard(self, operation, value):
        return 'phone text'

    async def release(self):
        self.releases += 1

    async def close(self):
        await self.release()


class RuntimeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.device = IosDevice({'serial': 'AB-CD'}, run=self.root / 'run', state=self.root / 'state', transport_factory=FakeTransport)
        self.device.call(self.device.ready())

    def tearDown(self):
        self.device.close()
        self.temp.cleanup()

    def host(self, host_operation, **params):
        return self.device.call(self.device.host(host_operation, params))

    def observe(self):
        return self.device.call(self.device.action('查看', {}))

    def tap(self, oid, action='tap1'):
        return self.device.call(self.device.action('点击', {'截图编号': oid, '动作编号': action, '位置': [30, 50]}))

    def test_identity_and_exclusive_lease(self):
        self.assertEqual(device_id('ab-cd'), 'ios:ABCD')
        other = DeviceLease(self.root / 'state')
        with self.assertRaises(RuntimeError):
            other.acquire()
        self.device.close()
        other.acquire()
        other.close()

    def test_preview_timeout_is_observation_only(self):
        self.device.transport.screenshot = AsyncMock(side_effect=TimeoutError())
        with self.assertRaises(ChannelFailure) as caught:
            host_request(self.device, 'frame', {'screen': 'main'})
        self.assertEqual(host_error(caught.exception)['code'], 'observation_failed')
        self.assertTrue(self.host('status')['connected'])

    def test_orientation_timeout_does_not_disconnect_control(self):
        self.device.transport.orientation = AsyncMock(side_effect=TimeoutError())
        with self.assertRaises(ChannelFailure) as caught:
            host_request(self.device, 'resume', {})
        self.assertEqual(caught.exception.channel, 'observation')
        self.assertTrue(self.host('status')['connected'])

    def test_business_failure_and_post_dispatch_observation_keep_control(self):
        oid = self.observe()['截图编号']
        self.device.transport.gesture = AsyncMock(side_effect=RuntimeError('request rejected'))
        result = self.tap(oid)
        self.assertNotIn('控制', result.get('通道', {}))
        self.assertTrue(self.host('status')['connected'])
        oid = self.observe()['截图编号']
        async def dispatch(*args, **kwargs):
            self.device.transport.before_dispatch()
            self.device.transport.on_dispatch()
            self.device.transport.fail_capture = True
        self.device.transport.gesture = dispatch
        result = self.tap(oid, 'second')
        self.assertEqual(result['执行']['状态'], '已派发')
        self.assertEqual(result['通道'], {'观察': '失败'})
        self.assertTrue(self.host('status')['connected'])

    def test_optional_system_service_failure_does_not_disconnect_hid(self):
        self.device.transport.clipboard = AsyncMock(side_effect=ConnectionResetError('pasteboard service closed'))
        result = self.device.call(self.device.system('clipboard', 'write', {'text': 'temporary'}, 'clipboard-error'))
        self.assertNotIn('控制', result.get('通道', {}))
        self.assertTrue(self.host('status')['connected'])

    def test_dispatch_failure_retains_receipt_and_control_health_after_observation_failures(self):
        original_screenshot = self.device.transport.screenshot
        oid = self.observe()['截图编号']
        async def fail(*args, **kwargs):
            self.device.transport.before_dispatch()
            self.device.transport.on_dispatch()
            self.device.transport.screenshot = AsyncMock(side_effect=TimeoutError('screenshot stalled'))
            raise ConnectionResetError('HID disconnected')
        self.device.transport.gesture = fail
        result = self.tap(oid)
        self.assertEqual(result['执行']['状态'], '部分派发')
        self.assertEqual(result['通道'], {'控制': '断开', '观察': '失败'})
        status = self.host('status')
        self.assertFalse(status['connected'])
        self.assertEqual(status['connectionStatus']['code'], 'control_disconnected')
        self.device.transport.screenshot = original_screenshot
        self.device.transport.orientation = AsyncMock(side_effect=TimeoutError('orientation stalled'))
        self.assertEqual(self.observe()['观察']['状态'], '失败')
        self.assertEqual(self.host('status')['connectionStatus'], status['connectionStatus'])
        self.assertFalse(self.host('status')['connected'])
        duplicate = self.tap(oid)
        self.assertEqual(duplicate['执行'], result['执行'])

    def test_preview_single_flight_and_independent_observation(self):
        old = self.observe()['截图编号']
        async def requests():
            return await asyncio.gather(*(self.device.frame({'screen': 'main'}) for _ in range(8)))
        frames = self.device.call(requests())
        self.assertEqual(len({frame['frameId'] for frame in frames}), 1)
        self.assertEqual(self.device.transport.max_active, 1)
        self.assertEqual(self.device.latest['id'], old)
        self.assertEqual(self.device.transport.captures, 2)
        next_frame = self.host('frame', screen='main', afterFrameId=frames[0]['frameId'])
        self.assertNotEqual(next_frame['frameId'], frames[0]['frameId'])

    def test_fresh_post_action_observation_and_durable_dedup(self):
        old = self.observe()['截图编号']
        first = self.tap(old)
        self.assertEqual(first['执行']['状态'], '已派发')
        self.assertNotEqual(first['截图编号'], old)
        duplicate = self.tap(old)
        self.assertEqual(duplicate['状态'], '重复请求未执行')
        self.assertEqual(self.device.transport.inputs, 1)
        self.assertEqual(json.loads((self.root / 'state/action-tap1.json').read_text('utf-8'))['执行']['状态'], '已派发')

    def test_observation_failure_keeps_dispatch(self):
        old = self.observe()['截图编号']
        self.device.transport.fail_capture = True
        result = self.tap(old)
        self.assertEqual(result['执行']['状态'], '已派发')
        self.assertEqual(result['观察']['状态'], '失败')
        self.assertEqual(self.device.transport.inputs, 1)

    def test_uncertain_dispatch_is_not_replayed_and_stops_input(self):
        old = self.observe()['截图编号']
        self.device.transport.fail_dispatch = True
        result = self.tap(old)
        self.assertEqual(result['执行']['状态'], '部分派发')
        self.assertTrue((self.root / 'state/INPUT_UNCERTAIN').exists())
        self.tap(old)
        self.assertEqual(self.device.transport.inputs, 1)

    def test_stop_keeps_observation_available(self):
        old = self.observe()['截图编号']
        self.host('stop')
        with self.assertRaises(RuntimeError):
            self.tap(old)
        self.assertIn('截图编号', self.observe())

    def test_latest_observation_is_required(self):
        old = self.observe()['截图编号']
        self.observe()
        with self.assertRaises(ValueError):
            self.tap(old)
        self.assertEqual(self.device.transport.inputs, 0)

    def test_manual_gate_frame_and_generation(self):
        self.host('hold')
        token = self.host('manual')['token']
        frame = self.host('frame', screen='main')
        result = self.host('input', token=token, screen='main', frameId=frame['frameId'], actionId='manual1', operation='点击', params={'位置': [20, 20]})
        self.assertEqual(result['执行']['状态'], '已派发')
        self.host('hold')
        with self.assertRaises(ValueError):
            self.host('input', token=token, screen='main', frameId=frame['frameId'], actionId='manual2', operation='点击', params={'位置': [20, 20]})

    def test_hold_interrupts_inflight_before_next_dispatch(self):
        old = self.observe()['截图编号']
        async def work():
            action = asyncio.create_task(self.device.action('点击', {'截图编号': old, '动作编号': 'inflight', '位置': [20, 20]}))
            await asyncio.sleep(.005)
            await self.device.host('hold', {})
            return await action
        result = self.device.call(work())
        self.assertEqual(result['执行']['状态'], '部分派发')
        self.assertTrue(self.device.held)

    def test_resume_requires_new_observation_and_host_quarantine_blocks(self):
        self.observe()
        self.host('stop')
        (self.root / 'state/INPUT_UNCERTAIN').write_text('{}')
        with self.assertRaises(RuntimeError):
            self.host('resume')
        with self.assertRaises(RuntimeError):
            self.host('manual', resumeStopped=True)
        self.assertTrue(self.device.stopped())
        (self.root / 'state/INPUT_UNCERTAIN').unlink()
        self.host('resume')
        self.assertIsNone(self.device.latest)
        self.assertFalse(self.device.stopped())

    def test_landscape_and_unknown_screens_reject_before_dispatch(self):
        old = self.observe()['截图编号']
        self.device.transport.orientation_value = 3
        with self.assertRaises(ValueError):
            self.tap(old)
        with self.assertRaises(ValueError):
            self.host('frame', screen='virtual1')
        self.assertEqual(self.device.transport.inputs, 0)

    def test_usb_disconnect_does_not_reconnect(self):
        self.device.transport.present = False
        self.assertFalse(self.host('status')['connected'])
        self.device.transport.present = True
        self.device.last_usb_check = 0
        self.assertFalse(self.host('status')['connected'])

    def test_restart_preserves_stop_and_action_receipt(self):
        old = self.observe()['截图编号']
        self.tap(old)
        self.host('stop')
        self.device.close()
        self.device = IosDevice({'serial': 'AB-CD'}, run=self.root / 'run', state=self.root / 'state', transport_factory=FakeTransport)
        self.device.call(self.device.ready())
        self.assertTrue(self.host('status')['stopped'])
        self.assertEqual(self.tap(old)['状态'], '重复请求未执行')
        self.assertEqual(self.device.transport.inputs, 0)

    def test_capabilities_do_not_claim_android_services(self):
        caps = self.host('status')['capabilities']
        self.assertNotIn('返回', caps['phoneKeys'])
        self.assertNotIn('notifications', caps['systemOperations'])
        with self.assertRaises(ValueError):
            self.device.call(self.device.system('notifications', 'list', {}, 'n'))

    def test_force_stop_missing_pid_is_not_a_dispatch(self):
        async def no_process(operation, params):
            return {'pid': 0, 'requested': False}
        self.device.transport.apps = no_process
        result = self.device.call(self.device.system('apps', 'force_stop', {'packageName': 'test.app'}, 'absent-pid'))
        self.assertEqual(result['执行']['状态'], '无需派发')
        self.assertFalse(result['结果']['requested'])
        self.assertEqual(self.device.transport.inputs, 0)

    def test_failed_cleanup_never_acknowledges_lease_release(self):
        async def failed_close():
            raise RuntimeError('cleanup unconfirmed')
        self.device.transport.close = failed_close
        with self.assertRaisesRegex(RuntimeError, 'cleanup unconfirmed'):
            self.host('shutdown')
        other = DeviceLease(self.root / 'state')
        try:
            with self.assertRaises(RuntimeError):
                other.acquire()
        finally:
            # Test-only equivalent of process exit. Production host kills the
            # failed process before checking that this lease has been released.
            self.device.lease.close()


class PreparationTests(unittest.TestCase):
    def test_synchronous_first_download_does_not_block_constructor_or_status(self):
        entered, finish = threading.Event(), threading.Event()
        calls = []
        class SlowPreparation(FakeTransport):
            async def connect(self):
                self.status('preparing_support', '正在准备开发者支持文件')
                calls.append('prepare-start')
                entered.set()
                finish.wait(3)  # Model the upstream synchronous first download.
                calls.append('prepare-end')

            async def close(self):
                calls.append('close')

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            start = time.perf_counter()
            device = IosDevice({'serial': 'new-device'}, run=root / 'run', state=root / 'state', transport_factory=SlowPreparation)
            try:
                self.assertLess(time.perf_counter() - start, 1)
                self.assertTrue(entered.wait(1))
                start = time.perf_counter()
                for _ in range(10):
                    status = host_request(device, 'status', {})
                    self.assertFalse(status['connected'])
                    self.assertEqual(status['connectionStatus']['code'], 'preparing_support')
                self.assertLess(time.perf_counter() - start, .5)
            finally:
                finish.set()
                device.close()
            self.assertEqual(calls, ['prepare-start', 'prepare-end', 'close'])
            self.assertFalse(device.thread.is_alive())

    def test_empty_connection_failure_reports_class_and_reason(self):
        class FailedConnection(FakeTransport):
            async def connect(self):
                raise EOFError()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            device = IosDevice({'serial': 'failed-device'}, run=root / 'run', state=root / 'state', transport_factory=FailedConnection)
            try:
                with self.assertRaises(RuntimeError):
                    device.call(device.ready())
                message = device.status_snapshot()['connectionStatus']['message']
                self.assertIn('EOFError', message)
                self.assertIn('连接服务已断开', message)
            finally:
                device.close()


if __name__ == '__main__':
    unittest.main()
