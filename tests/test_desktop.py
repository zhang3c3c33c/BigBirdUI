import tempfile
import threading
import unittest
import os
import struct
from contextlib import nullcontext
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, Mock, patch
from PIL import Image
from bbui.control_gate import ControlGate
from bbui.desktop_device import DesktopDevice
from bbui.paths import device_state
from bbui.runtime import PhoneTools, write_json
from bbui.screens import ScreenTools, display_context, display_focus
from bbui.scrcpy_display import ScrcpyDisplay
from bbui.unified import UnifiedPhone


class DesktopTests(unittest.TestCase):
    def test_device_status_checks_adb_without_observing_or_creating_a_screen(self):
        with tempfile.TemporaryDirectory() as folder:
            base = SimpleNamespace(serial='fixture', run=Path(folder), state_dir=Path(folder))
            registry = SimpleNamespace(base=base)
            desktop = DesktopDevice(registry)
            with patch('bbui.desktop_device.adbutils.adb.device') as device:
                device.return_value.get_state.return_value = 'device'
                self.assertEqual(desktop.host('status', {}), {'connected': True, 'stopped': False})
                device.return_value.get_state.return_value = 'offline'
                self.assertFalse(desktop.host('status', {})['connected'])
                device.return_value.get_state.side_effect = OSError('USB disconnected')
                self.assertFalse(desktop.host('status', {})['connected'])
            self.assertFalse(hasattr(registry, 'sessions'))

    def test_stop_and_uncertainty_override_valid_manual_authority(self):
        with tempfile.TemporaryDirectory() as folder:
            phone = PhoneTools.__new__(PhoneTools)
            phone.run = phone.state_dir = Path(folder)
            phone.control_gate = gate = ControlGate()
            gate.hold()
            with self.assertRaises(RuntimeError):
                phone.paused()
            token = gate.grant()
            with gate.manual(token):
                phone.paused()
                phone.stop()
                with self.assertRaisesRegex(RuntimeError, 'paused'):
                    phone.paused()
                (phone.run / 'STOP').unlink()
                (phone.run / 'INPUT_UNCERTAIN').touch()
                with self.assertRaisesRegex(RuntimeError, 'in flight'):
                    phone.paused()

    def test_manual_token_is_thread_local_and_revoked_by_hold(self):
        gate = ControlGate(); gate.hold(); token = gate.grant()
        failures = []
        def model():
            try: gate.check()
            except RuntimeError: failures.append(True)
        with gate.manual(token):
            gate.check()
            thread = threading.Thread(target=model); thread.start(); thread.join()
            gate.hold()
            with self.assertRaises(RuntimeError): gate.check()
        self.assertEqual(failures, [True])
        with self.assertRaises(ValueError):
            with gate.manual(token): pass

    def test_explicit_manual_resume_clears_stop_with_model_gate_held(self):
        with tempfile.TemporaryDirectory() as folder:
            phone = PhoneTools.__new__(PhoneTools)
            phone.run = phone.state_dir = Path(folder)
            registry = SimpleNamespace(base=phone, _own_device=Mock())
            desktop = DesktopDevice(registry)
            desktop.system = Mock()
            desktop.host('stop', {})
            with self.assertRaisesRegex(RuntimeError, 'paused'):
                desktop.host('manual', {})
            token = desktop.host('manual', {'resumeStopped': True})['token']
            self.assertFalse((phone.run / 'STOP').exists())
            self.assertTrue(desktop.gate.held)
            with self.assertRaisesRegex(RuntimeError, '模型不能派发'):
                phone.paused()
            with desktop.gate.manual(token):
                phone.paused()
            desktop.host('stop', {})
            with self.assertRaises(ValueError):
                with desktop.gate.manual(token): pass

    def test_explicit_manual_resume_never_clears_uncertainty_and_restores_stop_on_failure(self):
        with tempfile.TemporaryDirectory() as folder:
            phone = PhoneTools.__new__(PhoneTools)
            phone.run = phone.state_dir = Path(folder)
            desktop = DesktopDevice(SimpleNamespace(base=phone, _own_device=Mock()))
            desktop.system = Mock()
            desktop.host('stop', {})
            (phone.run / 'INPUT_UNCERTAIN').touch()
            with self.assertRaisesRegex(RuntimeError, 'in flight'):
                desktop.host('manual', {'resumeStopped': True})
            self.assertTrue((phone.run / 'STOP').exists())
            self.assertTrue((phone.run / 'INPUT_UNCERTAIN').exists())
            self.assertTrue(desktop.gate.held)
            self.assertIsNone(desktop.gate.token)

    def test_focus_is_resolved_on_target_display_and_user(self):
        windows = '  Display: mDisplayId=0\n  mCurrentFocus=Window{aaa u0 com.main/.Main}\n  Display: mDisplayId=7\n  mCurrentFocus=Window{bbb u10 com.work/.Editor}\n'
        self.assertEqual(display_focus(windows, 7)['focusedWindow'], 'bbb')
        self.assertEqual(display_focus(windows, 7)['focusedWindowUser'], 10)
        self.assertEqual(display_focus(windows, 8), {})
        context = display_context('Display #7 activities:\n  topResumedActivity=ActivityRecord{x u10 com.work/.Editor t2}\n', 7, 800, 1200)
        self.assertEqual(context['userId'], 10)

    def test_device_lease_is_shared_across_install_and_data_directories(self):
        with tempfile.TemporaryDirectory() as folder, patch.dict(os.environ, {'BBUI_DEVICE_STATE_DIR': folder}):
            first, second = PhoneTools.__new__(PhoneTools), PhoneTools.__new__(PhoneTools)
            first.run, second.run = Path(folder) / 'data-a', Path(folder) / 'data-b'
            first.state_dir = device_state(Path(folder) / 'install-a', 'same-device')
            second.state_dir = device_state(Path(folder) / 'install-b', 'same-device')
            self.assertEqual(first.state_dir, second.state_dir)
            first.state_dir.mkdir()
            with first.lock():
                with self.assertRaises(OSError):
                    with second.lock(): pass
            with second.lock(): pass

    def test_system_uncertain_dispatch_is_retained_and_never_replayed(self):
        with tempfile.TemporaryDirectory() as folder:
            base = SimpleNamespace(state_dir=Path(folder), config={}, paused=Mock(), quarantine_input=Mock())
            registry = SimpleNamespace(base=base, mutex=threading.RLock(), _own_device=Mock(),
                gate=SimpleNamespace(acquire=lambda **kwargs: nullcontext()))
            desktop = DesktopDevice(registry)
            desktop.system = Mock()
            desktop.system.request.side_effect = ConnectionError('lost after dispatch')
            first = desktop.execute_system('clipboard', 'write', {'text': 'fixture'}, 'same-action')
            second = desktop.execute_system('clipboard', 'write', {'text': 'fixture'}, 'same-action')
            self.assertEqual(first['执行']['状态'], '未知')
            self.assertEqual(first, second)
            desktop.system.request.assert_called_once()
            base.quarantine_input.assert_called()

    def preview_fixture(self):
        folder = self.enterContext(tempfile.TemporaryDirectory())
        base = PhoneTools.__new__(PhoneTools)
        base.run = base.state_dir = Path(folder)
        base._bridge_owner = 'fixture'
        base._input_failure = None
        video = ScrcpyDisplay.__new__(ScrcpyDisplay)
        video.condition = threading.Condition()
        video.frame = Image.new('RGB', (80, 120), 'green')
        video.frame_sequence, video.stream_generation = 1, 1
        video.video_error, video.closed = None, False
        video.socket = Mock()
        context = {'display_id': 3, 'focusedWindow': 'editor', 'focusedWindowUser': 0,
                   'userId': 0, 'package': 'test.app', 'width': 80, 'height': 120}
        phone = SimpleNamespace(lock=Mock(side_effect=nullcontext), context=Mock(return_value=context), display=video,
            paused=base.paused, quarantine_input=base.quarantine_input, config={}, display_id=3,
            input_lock=Mock(side_effect=lambda **kwargs: nullcontext()))
        phone.check_input_focus = Mock(side_effect=lambda current: ScreenTools.check_input_focus(phone, current))
        tool = UnifiedPhone(phone)
        tool.action = Mock()
        tool.control = video
        tool.latest = {'observation_id': 'model-observation'}
        registry = SimpleNamespace(base=base, sessions={'screen': tool},
            action=Mock(return_value={'执行': {'状态': '已派发'}}), mutex=threading.RLock(), _own_device=Mock())
        desktop = DesktopDevice(registry)
        desktop.gate.hold()
        params = {'token': desktop.gate.grant(), 'screen': 'screen', 'operation': '点击',
                  'actionId': 'manual-action', 'params': {'位置': [40, 60]}}
        params['frameId'] = desktop.frame('screen')['frameId']
        return desktop, tool, video, params

    def test_preview_reads_latest_video_without_context_input_lock_or_observations(self):
        desktop, tool, video, params = self.preview_fixture()
        tool.phone.context.assert_not_called()
        tool.phone.lock.assert_not_called()
        tool.action.assert_not_called()
        self.assertIsNone(desktop.host('frame', {'screen': 'screen', 'afterFrameId': params['frameId']}))
        video.frame_sequence += 10  # The preview skips intermediate decoded frames.
        result = desktop.frame('screen', params['frameId'])
        self.assertEqual(result['sequence'], 11)
        self.assertEqual((result['width'], result['height']), (80, 120))
        self.assertEqual(video.frame.size, (80, 120))
        self.assertNotEqual(result['frameId'], params['frameId'])
        self.assertEqual(len(desktop.frames), 2)

    def test_first_main_preview_initializes_once_without_existing_preview_attribute(self):
        desktop, tool, video, params = self.preview_fixture()
        desktop.base.serial, desktop.base.root = 'fixture', Path('.')
        desktop.phone._main = Mock(return_value=tool)
        tool.phone.display = None
        tool.control = MagicMock()
        old_control = tool.control
        with patch('bbui.desktop_device.ScrcpyDisplay', return_value=video) as create, patch.object(ScrcpyDisplay, '__enter__', return_value=video):
            first = desktop.frame('main')
            self.assertIsNone(desktop.frame('main', first['frameId']))
        create.assert_called_once_with('fixture', Path('.'), existing_display=0)
        old_control.__exit__.assert_called_once()
        self.assertIs(tool.phone.preview, video)

    def test_preview_manual_input_checks_target_without_creating_model_observations(self):
        desktop, tool, video, params = self.preview_fixture()
        result = desktop.host('input', params)
        self.assertEqual(result['执行']['状态'], '已派发')
        self.assertEqual(tool.phone.context.call_count, 2)
        tool.action.assert_not_called()
        desktop.phone.action.assert_not_called()
        self.assertIsNone(tool.latest)
        self.assertEqual([call.args[0][1] for call in video.socket.sendall.call_args_list], [0, 1])
        self.assertEqual([file.name for file in desktop.base.run.iterdir()], ['manual-manual-action.json'])

    def test_stale_preview_identity_rejects_manual_input_before_observation(self):
        for change in ('generation', 'dimensions', 'video', 'screen', 'closed', 'error'):
            with self.subTest(change=change):
                desktop, tool, video, params = self.preview_fixture()
                if change == 'generation': video.stream_generation += 1
                elif change == 'dimensions': video.frame = Image.new('RGB', (120, 80))
                elif change == 'video': tool.phone.display = Mock()
                elif change == 'screen': desktop.frames[0]['screen'] = 'other'
                elif change == 'closed': video.closed = True
                else: video.video_error = 'lost'
                with self.assertRaisesRegex(ValueError, '画面或目标窗口'):
                    desktop.host('input', params)
                tool.action.assert_not_called()
                desktop.phone.action.assert_not_called()

    def test_changed_window_or_stream_before_dispatch_rejects_manual_input(self):
        for change in ('window', 'generation', 'video'):
            with self.subTest(change=change):
                desktop, tool, video, params = self.preview_fixture()
                calls = 0
                def context():
                    nonlocal calls
                    calls += 1
                    context = dict(tool.phone.context.return_value)
                    if calls > 1:
                        if change == 'window': context['focusedWindow'] = 'new'
                        elif change == 'generation': video.stream_generation += 1
                        else: tool.phone.display = Mock()
                    return context
                tool.phone.context.side_effect = context
                result = desktop.host('input', params)
                self.assertEqual(result['执行']['状态'], '未派发')
                video.socket.sendall.assert_not_called()
                desktop.phone.action.assert_not_called()

    def test_manual_input_rejects_blocked_app_wrong_focus_display_and_invalid_coordinates(self):
        for change in ('blocked', 'focus', 'user', 'display', 'coordinates', 'global-key'):
            with self.subTest(change=change):
                desktop, tool, video, params = self.preview_fixture()
                context = tool.phone.context.return_value
                if change == 'blocked': tool.phone.config['blocked_packages'] = ['test.app']
                elif change == 'focus': context.pop('focusedWindow')
                elif change == 'user': context['focusedWindowUser'] = 10
                elif change == 'display': context['display_id'] = 0
                elif change == 'coordinates': params['params']['位置'] = [80, 20]
                else: params.update(operation='按键', params={'键名': '最近任务'})
                with self.assertRaises(ValueError): desktop.host('input', params)
                video.socket.sendall.assert_not_called()
                self.assertEqual(tool.latest['observation_id'], 'model-observation')

    def test_stop_during_manual_claim_write_prevents_dispatch(self):
        desktop, tool, video, params = self.preview_fixture()
        def persist(path, value):
            write_json(path, value)
            if value.get('状态') == '派发中': desktop.base.stop()
        with patch('bbui.desktop_device.write_json', side_effect=persist):
            result = desktop.host('input', params)
        self.assertEqual(result['执行']['状态'], '未派发')
        video.socket.sendall.assert_not_called()

    def test_manual_token_release_or_replacement_during_validation_prevents_dispatch(self):
        for change in ('release', 'replace'):
            with self.subTest(change=change):
                desktop, tool, video, params = self.preview_fixture()
                def context():
                    if change == 'release': desktop.gate.release()
                    else:
                        desktop.gate.hold()
                        desktop.gate.grant()
                    return tool.phone.context.return_value
                tool.phone.context.side_effect = context
                result = desktop.host('input', params)
                self.assertEqual(result['执行']['状态'], '未派发')
                video.socket.sendall.assert_not_called()

    def test_manual_swipe_stops_on_rotation_stream_replacement_token_release_or_stop_and_releases_finger(self):
        for change in ('generation', 'video', 'release', 'stop'):
            with self.subTest(change=change):
                desktop, tool, video, params = self.preview_fixture()
                params.update(operation='滑动', params={'起点': [20, 20], '终点': [30, 40], '时间': 100})
                def send(packet):
                    if packet[1] == 0:
                        if change == 'generation': video.stream_generation += 1
                        elif change == 'video': tool.phone.display = Mock()
                        elif change == 'release': desktop.gate.release()
                        else: desktop.base.stop()
                video.socket.sendall.side_effect = send
                result = desktop.host('input', params)
                self.assertEqual(result['执行']['状态'], '未知')
                self.assertEqual([call.args[0][1] for call in video.socket.sendall.call_args_list], [0, 1])
                self.assertIsNone(tool.latest)

    def test_manual_delete_partial_receipt_and_dedup_survive_uncertainty_marker_write_failure(self):
        desktop, tool, video, params = self.preview_fixture()
        params.update(operation='删除内容', params={'次数': 3})
        video.socket.sendall.side_effect = [None, OSError('control lost after first key')]
        def quarantine(reason):
            desktop.base._input_failure = reason
            raise OSError('quarantine disk unavailable')
        desktop.base.quarantine_input = quarantine
        result = desktop.host('input', params)
        self.assertEqual(result['执行'], {'状态': '部分派发', '已完成按键次数': 1})
        self.assertIn('隔离记录错误', result)
        duplicate = desktop.host('input', params)
        self.assertEqual(duplicate['执行'], result['执行'])
        self.assertEqual(duplicate['状态'], '重复请求未执行')
        self.assertEqual(video.socket.sendall.call_count, 2)
        with self.assertRaisesRegex(RuntimeError, 'in flight'):
            desktop.host('input', {**params, 'actionId': 'new-attempt'})
        self.assertEqual(video.socket.sendall.call_count, 2)

    def test_manual_successful_dispatch_survives_final_receipt_write_failure_without_replay(self):
        desktop, tool, video, params = self.preview_fixture()
        def persist(path, value):
            if value.get('状态') == '已执行': raise OSError('receipt disk unavailable')
            write_json(path, value)
        with patch('bbui.desktop_device.write_json', side_effect=persist):
            result = desktop.host('input', params)
        self.assertEqual(result['执行']['状态'], '已派发')
        self.assertIn('记录错误', result)
        duplicate = desktop.host('input', params)
        self.assertEqual(duplicate['执行']['状态'], '未知')
        self.assertEqual(video.socket.sendall.call_count, 2)

    def test_manual_chinese_paste_rechecks_target_after_clipboard_ack_and_never_logs_text(self):
        for changed in (False, True):
            with self.subTest(changed=changed):
                desktop, tool, video, params = self.preview_fixture()
                text = '中文输入内容'
                params.update(operation='输入内容', params={'内容': text})
                video._message = Mock(side_effect=[(1, 1), (0, text), (1, 2)])
                initial = tool.phone.context.return_value
                tool.phone.context.side_effect = [initial, initial,
                    {**initial, 'focusedWindow': 'other'} if changed else initial]
                with patch('bbui.scrcpy_control.time.sleep'):
                    result = desktop.host('input', params)
                packets = [call.args[0] for call in video.socket.sendall.call_args_list]
                self.assertEqual(any(packet[0] == 0 for packet in packets), not changed)
                self.assertEqual(result['执行']['状态'], '未知' if changed else '已派发')
                self.assertEqual(tool.phone.context.call_count, 3)
                self.assertNotIn(text, (desktop.base.state_dir / 'manual-manual-action.json').read_text('utf-8'))

    def test_snapshot_keeps_pixels_and_sequence_atomic_and_returns_owned_copy(self):
        desktop, tool, video, params = self.preview_fixture()
        picture, sequence, generation = video.frame_snapshot()
        picture.putpixel((0, 0), (255, 0, 0))
        self.assertEqual(video.frame.getpixel((0, 0)), (0, 128, 0))
        self.assertEqual((sequence, generation), (1, 1))
        self.assertIsNone(video.frame_snapshot(after=(sequence, generation)))
        video.stream_generation += 1
        self.assertIsNotNone(video.frame_snapshot(after=(sequence, generation)))

    def test_decoder_session_header_invalidates_same_size_frame(self):
        desktop, tool, video, params = self.preview_fixture()
        video.video_socket = Mock()
        decoder = Mock()
        decoded = Mock()
        decoded.to_image.return_value = Image.new('RGB', (80, 120))
        decoder.decode.return_value = [decoded]
        session = struct.pack('>QI', (1 << 63) | 80, 120)
        packet = struct.pack('>QI', 0, 1)
        with patch('bbui.scrcpy_display.av.CodecContext.create', return_value=decoder), patch(
                'bbui.scrcpy_display.read_exact', side_effect=[b'h264', session, packet, b'x',
                    session, packet, b'x', ConnectionError('end')]):
            video._decode()
        self.assertEqual(video.stream_generation, 3)
        self.assertEqual(video.frame_sequence, 3)
        self.assertEqual(video.frame.size, (80, 120))
        self.assertEqual(video.video_error, 'end')
        video.video_error = None  # Ignore the injected EOF; isolate generation validation.
        self.assertTrue(video.matches_frame(3, (80, 120)))
        self.assertFalse(video.matches_frame(1, (80, 120)))


if __name__ == '__main__': unittest.main()
