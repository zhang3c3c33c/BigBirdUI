import unittest
from unittest.mock import Mock, patch

from bbui.apps import list_apps
from bbui.screens import ScreenRegistry


class AppTests(unittest.TestCase):
    def query(self, outputs, params=None):
        device = Mock()
        device.shell2.side_effect = [Mock(returncode=0, output=x) for x in outputs]
        with patch('bbui.apps.adbutils.adb') as adb:
            adb.device.return_value = device
            result = list_apps('serial', {'blocked_packages': ['app.system']}, params or {})
        return result, device

    def test_all_installed_includes_services_and_unallowed_apps(self):
        result, device = self.query(['10', 'package:app.allowed\npackage:app.system\npackage:app.other\npackage:app.allowed',
                                    'package:app.system', 'app.allowed/.Main\napp.allowed/.Other'])
        self.assertEqual(result['用户编号'], 10)
        self.assertEqual(result['已安装总数'], 3)
        apps = {a['包名']: a for a in result['应用列表']}
        self.assertTrue(apps['app.system']['系统应用'])
        self.assertFalse(apps['app.system']['允许操作'])
        self.assertFalse(apps['app.system']['可启动'])
        self.assertTrue(apps['app.allowed']['允许操作'])
        self.assertEqual(len(apps['app.allowed']['启动入口']), 2)
        for call in device.shell2.call_args_list[1:]:
            args = call.args[0]
            self.assertEqual(args[args.index('--user') + 1], '10')

    def test_filter_is_host_side_and_does_not_change_total(self):
        outputs = ['0', 'package:app.allowed\npackage:app.system', 'package:app.system', 'No activities found']
        result, _ = self.query(outputs, {'包含系统应用': False, '关键词': 'ALLOWED'})
        self.assertEqual(result['返回数量'], 1)
        self.assertEqual(result['已安装总数'], 2)
        result, device = self.query(outputs, {'关键词': '; touch /sdcard/x'})
        self.assertEqual(result['返回数量'], 0)
        self.assertFalse(any('touch' in str(c.args) for c in device.shell2.call_args_list))

    def test_failed_query_is_not_an_empty_success(self):
        with self.assertRaisesRegex(RuntimeError, '无法解析安装包列表'):
            self.query(['0', 'Error: user unavailable'])
        with patch('bbui.apps.adbutils.adb') as adb:
            adb.device.return_value.shell2.return_value = Mock(returncode=1, output='device offline')
            with self.assertRaisesRegex(RuntimeError, 'device offline'):
                list_apps('serial', {}, {})

    def test_bad_params_are_rejected_before_device_access(self):
        with patch('bbui.apps.adbutils.adb') as adb:
            for params in ({'包含系统应用': 'false'}, {'关键词': 2}):
                with self.assertRaises(ValueError):
                    list_apps('serial', {}, params)
            adb.device.assert_not_called()

    def test_inventory_does_not_create_screen_or_acquire_input_lease(self):
        base = Mock()
        base.config = {'blocked_packages': []}
        registry = ScreenRegistry(base)
        with patch('bbui.screens.list_apps', return_value={'状态': '应用已列出'}) as query:
            result = registry.action('列出应用', {'执行后等待毫秒': 0})
        self.assertEqual(result['状态'], '应用已列出')
        query.assert_called_once()
        self.assertEqual(registry.sessions, {})
        base.lock.assert_not_called()
