"""Execution facts must survive observation errors and ordinary intermediate pages."""
import tempfile
import threading
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

from PIL import Image

from bbui.runtime import PhoneTools, write_json
from bbui.screens import ScreenRegistry
from bbui.unified import UnifiedPhone, launch_component


class ToolResultTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        write_json(self.root / 'config.local.json', {'serial': 'unit-test', 'blocked_packages': []})
        self.device = Mock()
        self.device.screenshot.side_effect = lambda: Image.new('RGB', (100, 200))
        self.device.dump_hierarchy.return_value = '<hierarchy/>'
        self.phone = PhoneTools(self.root, self.device)
        self.context = {'package': 'app.first', 'activity': '.Main', 'rotation': 0,
                        'width': 100, 'height': 200}
        self.phone.context = lambda: self.context.copy()
        self.tool = UnifiedPhone(self.phone)
        self.tool.controller = Mock()
        self.tool._key = Mock()
        self.tool.capture()

    def test_dispatched_key_survives_failed_screenshot_and_duplicate(self):
        self.device.screenshot.side_effect = OSError('video unavailable')
        result = self.tool.action('按键', {'键名': '回车', '动作编号': 'once'})
        self.assertEqual(result['执行']['状态'], '已派发')
        self.assertEqual(result['观察']['状态'], '失败')
        duplicate = self.tool.action('按键', {'键名': '回车', '动作编号': 'once'})
        self.assertEqual(duplicate['执行']['状态'], '已派发')
        self.assertEqual(duplicate['状态'], '重复请求未执行')
        self.tool._key.assert_called_once()

    def test_control_socket_disconnect_retains_uncertain_dispatch_and_observation(self):
        control = SimpleNamespace(socket=SimpleNamespace(sendall=Mock(side_effect=ConnectionResetError('closed'))))
        self.tool.controller.return_value = control
        self.tool._key = lambda code, meta=0: UnifiedPhone._key(self.tool, code, meta)
        result = self.tool.action('按键', {'键名': '回车', '动作编号': 'lost-control'})
        self.assertEqual(result['执行']['状态'], '未知')
        self.assertEqual(result['观察']['状态'], '已取得')
        self.assertEqual(result['通道']['控制'], '断开')
        self.assertTrue(self.phone.control_error)
        self.tool.action('按键', {'键名': '回车', '动作编号': 'lost-control'})
        control.socket.sendall.assert_called_once()

    def test_delete_reports_completed_prefix_without_replaying(self):
        self.tool._key.side_effect = [None, OSError('second key uncertain')]
        result = self.tool.action('删除内容', {'次数': 3, '动作编号': 'delete-once'})
        self.assertEqual(result['执行']['状态'], '部分派发')
        self.assertEqual(result['执行']['已完成按键次数'], 1)
        self.assertEqual(self.tool._key.call_count, 2)
        duplicate = self.tool.action('删除内容', {'次数': 3, '动作编号': 'delete-once'})
        self.assertEqual(duplicate['执行']['状态'], '部分派发')
        self.assertEqual(self.tool._key.call_count, 2)

    def test_release_failure_quarantines_input_and_attempts_every_finger(self):
        packets = []
        def send(packet):
            packets.append(packet)
            if packet[1] == 1:
                raise OSError('release transport failed')
        control = SimpleNamespace(socket=SimpleNamespace(sendall=send))
        self.tool.controller.return_value = control
        self.tool.close = Mock()
        with patch('bbui.unified.time.sleep'):
            result = self.tool.action('放大', {'中心': [50, 100], '初始指距': 20, '结束指距': 40, '时间': 0})
        self.assertEqual(result['执行']['状态'], '未知')
        self.assertEqual(sum(packet[1] == 1 for packet in packets), 2)
        self.assertIn('抬手', result['错误'])
        self.assertTrue((self.phone.run / 'INPUT_UNCERTAIN').exists())
        self.assertEqual(result['观察']['状态'], '已取得')
        calls_before = self.tool.controller.call_count
        with self.assertRaisesRegex(RuntimeError, 'paused|in flight'):
            self.tool.action('按键', {'键名': '回车'})
        self.assertEqual(self.tool.controller.call_count, calls_before)
        self.tool.close.assert_not_called()

    def test_stop_arriving_during_pixel_capture_prevents_touch(self):
        def capture_and_stop():
            self.phone.stop()
            return Image.new('RGB', (100, 200))
        self.device.screenshot.side_effect = capture_and_stop
        self.tool._gesture = Mock()
        result = self.tool.action('点击', {'位置': [20, 20]})
        self.assertEqual(result['执行']['状态'], '未派发')
        self.tool._gesture.assert_not_called()

    def test_stop_during_claim_persistence_prevents_key(self):
        def persist(path, value):
            write_json(path, value)
            if path.name == 'latest.json':
                self.phone.stop()
        with patch('bbui.unified.write_json', side_effect=persist):
            result = self.tool.action('按键', {'键名': '回车'})
        self.assertEqual(result['执行']['状态'], '未派发')
        self.tool._key.assert_not_called()

    def test_stop_between_double_taps_retains_first_tap_fact(self):
        self.tool._gesture = Mock()
        with patch('bbui.unified.time.sleep', side_effect=lambda _: self.phone.stop()):
            result = self.tool.action('双击', {'位置': [20, 20]})
        self.assertEqual(result['执行'], {'状态': '部分派发', '已完成点击次数': 1})
        self.tool._gesture.assert_called_once()

    def test_legacy_stop_during_resolve_prevents_launch(self):
        def resolve(command):
            self.phone.stop()
            return SimpleNamespace(output='app.second/.Main', exit_code=0)
        self.device.shell.side_effect = resolve
        result = self.phone.act({'type': 'launch', 'package': 'app.second',
                                'observation_id': self.tool.latest['observation_id'], 'action_id': 'stopped-launch'})
        self.assertEqual(result['执行']['状态'], '未派发')
        self.device.shell.assert_called_once()

    def test_legacy_stop_during_intent_log_prevents_back(self):
        self.phone.log = Mock(side_effect=lambda _: self.phone.stop())
        result = self.phone.act({'type': 'back', 'observation_id': self.tool.latest['observation_id'],
                                'action_id': 'stopped-back'})
        self.assertEqual(result['执行']['状态'], '未派发')
        self.device.press.assert_not_called()

    def test_legacy_coordinate_action_does_not_require_accessibility(self):
        self.device.dump_hierarchy.side_effect = RuntimeError('accessibility unavailable')
        result = self.phone.act({'type': 'tap_point', 'point': [20, 20],
                                'observation_id': self.tool.latest['observation_id'], 'action_id': 'coordinate'})
        self.assertEqual(result['执行']['状态'], '已派发')
        self.device.click.assert_called_once_with(20, 20)
        self.assertEqual(result['observation']['nodes_status'], 'failed')
        self.assertEqual(result['观察']['状态'], '已取得')

    def test_legacy_launch_does_not_require_accessibility(self):
        self.device.dump_hierarchy.side_effect = RuntimeError('accessibility unavailable')
        self.device.shell.side_effect = [SimpleNamespace(output='app.second/.Main', exit_code=0),
                                        SimpleNamespace(output='Status: ok', exit_code=0)]
        result = self.phone.act({'type': 'launch', 'package': 'app.second',
                                'observation_id': self.tool.latest['observation_id'], 'action_id': 'launch-no-tree'})
        self.assertEqual(result['执行']['状态'], '已派发')
        self.assertEqual(self.device.shell.call_count, 2)
        self.assertEqual(result['observation']['nodes_status'], 'failed')
        self.assertEqual(result['观察']['状态'], '已取得')

    def test_launch_chooser_is_observed_without_closing_control(self):
        def shell(command):
            if command[0] == 'cmd':
                return SimpleNamespace(output='app.second/.Main', exit_code=0)
            self.context.update(package='com.vivo.doubleinstance', activity='.DoubleAppResolverActivity')
            return SimpleNamespace(output='Status: ok', exit_code=0)
        self.device.shell.side_effect = shell
        self.tool.close = Mock()
        result = self.tool.action('打开应用', {'包名': 'app.second'})
        self.assertEqual(result['执行']['状态'], '已派发')
        self.assertEqual(result['观察']['状态'], '已取得')
        self.assertFalse(result['目标应用在前台'])
        self.assertEqual(result['屏幕']['package'], 'com.vivo.doubleinstance')
        self.tool.close.assert_not_called()
        self.assertFalse((self.phone.run / 'STOP').exists())

    def test_node_failure_does_not_hide_screenshot(self):
        self.device.dump_hierarchy.side_effect = RuntimeError('no accessibility on display')
        result = self.tool.action('查看', {'读取节点': True})
        self.assertEqual(result['观察']['状态'], '已取得')
        self.assertEqual(result['节点状态'], '失败')
        self.assertTrue(Path(result['截图']).is_file())
        legacy = self.phone.observe()
        self.assertEqual(legacy['nodes_status'], 'failed')
        self.assertTrue(Path(legacy['screenshot']).is_file())

    def test_context_failure_returns_image_without_input_credential(self):
        self.phone.context = Mock(side_effect=RuntimeError('foreground not available'))
        result = self.tool.action('查看')
        self.assertEqual(result['观察']['状态'], '已取得')
        self.assertFalse(result['可用于输入'])
        self.assertTrue(self.tool.latest['consumed'])

    def test_stop_and_uncertain_marker_do_not_prevent_read(self):
        self.phone.stop()
        self.assertEqual(self.tool.action('查看')['观察']['状态'], '已取得')
        cancelled_wait = self.tool.action('等待', {'时间': 30000})
        self.assertEqual(cancelled_wait['观察']['状态'], '已取得')
        self.assertIn('等待错误', cancelled_wait)
        with self.assertRaisesRegex(RuntimeError, 'paused'):
            self.tool.action('按键', {'键名': '回车'})
        marker = self.phone.run / 'INPUT_UNCERTAIN'
        marker.write_text('pending')
        self.phone.resume()
        self.assertTrue(marker.exists())
        self.assertEqual(self.tool.action('查看')['观察']['状态'], '已取得')
        with self.assertRaisesRegex(RuntimeError, 'in flight'):
            self.tool.action('按键', {'键名': '回车'})
        self.tool._key.assert_not_called()

    def test_explicit_component_validates_package_identity(self):
        self.assertEqual(launch_component('app.second', 'app.second/.Other', ''), 'app.second/.Other')
        for invalid in ('other.app/.Main', 'app.second/.Main;reboot'):
            with self.assertRaises(ValueError):
                launch_component('app.second', invalid, '')

    def test_pixel_change_is_evidence_and_visual_reason_is_optional(self):
        self.device.screenshot.side_effect = lambda: Image.new('RGB', (100, 200), 'white')
        self.tool._gesture = Mock()
        result = self.tool.action('点击', {'位置': [20, 20]})
        self.assertEqual(result['执行']['状态'], '已派发')
        self.assertEqual(result['目标区域像素差均值'], 255)
        self.tool._gesture.assert_called_once()
        self.tool.capture()
        self.device.screenshot.side_effect = lambda: Image.new('RGB', (100, 200), 'black')
        legacy = self.phone.act({'type': 'tap_point', 'point': [20, 20],
                                'observation_id': self.tool.latest['observation_id'], 'action_id': 'visual-point'})
        self.assertEqual(legacy['执行']['状态'], '已派发')
        self.assertEqual(legacy['record']['visual_difference'], 255)

    def test_password_editor_type_is_fact_and_text_not_logged(self):
        self.device.shell.return_value = SimpleNamespace(
            output='mCurrentEditorInfo:\n inputType=0x81\n packageName=app.first')
        result = self.tool.action('输入内容', {'内容': 'synthetic-test-value'})
        self.assertEqual(result['执行']['状态'], '已派发')
        self.assertEqual(result['输入框类型'], 0x81)
        self.tool.controller.return_value.paste.assert_called_once()
        for record in self.phone.run.glob('unified-*.json'):
            self.assertNotIn('synthetic-test-value', record.read_text('utf-8'))

    def test_wrong_editor_package_still_rejects_input(self):
        self.device.shell.return_value = SimpleNamespace(
            output='mCurrentEditorInfo:\n inputType=0x81\n packageName=app.other')
        with self.assertRaisesRegex(ValueError, '输入框焦点'):
            self.tool.action('输入内容', {'内容': 'synthetic-test-value'})
        self.tool.controller.return_value.paste.assert_not_called()

    def test_virtual_screen_can_switch_app_but_not_steal_other_screen(self):
        registry = ScreenRegistry(self.phone)
        registry.lease = Mock()
        for name, package in (('one', 'app.first'), ('two', 'app.second')):
            tool = Mock()
            tool.phone.lock.return_value = threading.RLock()
            tool.phone.closed = False
            tool.phone.display_id = 1 if name == 'one' else 2
            tool.phone.display = SimpleNamespace(frame_sequence=1, frame_time=1)
            tool.phone.context.return_value = {'package': package}
            tool.action.return_value = {'执行': {'状态': '已派发'}}
            tool.observe_result.return_value = {'观察': {'状态': '已取得'}}
            registry.sessions[name] = tool
            registry.packages[package] = name
        result = registry.action('打开应用', {'屏幕会话': 'one', '包名': 'app.third', '截图编号': 'one-current'})
        self.assertEqual(result['执行']['状态'], '已派发')
        conflict = registry.action('打开应用', {'屏幕会话': 'one', '包名': 'app.second', '截图编号': 'one-current'})
        self.assertEqual(conflict['执行']['状态'], '未派发')
        self.assertEqual(conflict['观察']['状态'], '已取得')
        registry.sessions['one'].action.assert_called_once()

    def test_legacy_launch_retains_dispatch_when_foreground_check_is_false(self):
        write_json(self.phone.run / 'latest.json', {**self.tool.latest, 'nodes': []})
        self.device.shell.side_effect = [SimpleNamespace(output='app.second/.Main', exit_code=0),
                                        SimpleNamespace(output='Status: ok', exit_code=0)]
        self.device.app_wait.return_value = False
        result = self.phone.act({'type': 'launch', 'package': 'app.second',
                                'observation_id': self.tool.latest['observation_id'], 'action_id': 'legacy-launch'})
        self.assertEqual(result['执行']['状态'], '已派发')
        self.assertFalse(result['record']['目标应用在前台'])
        self.assertEqual(result['观察']['状态'], '已取得')


if __name__ == '__main__':
    unittest.main()
