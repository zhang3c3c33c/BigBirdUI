from contextlib import nullcontext
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import Mock, patch

from bbui.app_policy import blocked_packages, package_allowed, require_package_allowed
from bbui.screens import ScreenRegistry
from bbui.unified import UnifiedPhone


class PolicyTests(unittest.TestCase):
    def test_default_allows_unlisted_apps_and_legacy_allowlist_is_retired(self):
        for config in ({}, {'blocked_packages': []}, {'allowed_packages': ['app.old']}):
            self.assertTrue(package_allowed(config, 'com.sankuai.meituan'))

    def test_deny_is_exact_and_prevents_invalid_package_arguments(self):
        config = {'blocked_packages': ['app.blocked']}
        self.assertFalse(package_allowed(config, 'app.blocked'))
        self.assertTrue(package_allowed(config, 'app.blocked.other'))
        for package in (None, '', '--user', 'app.name;reboot'):
            with self.assertRaises(ValueError):
                require_package_allowed({}, package)
        for value in ('app.blocked', None, [1], ['app.*']):
            with self.assertRaises(ValueError):
                blocked_packages({'blocked_packages': value})

    def test_virtual_screen_deny_happens_before_device_access(self):
        base = Mock()
        base.config = {'blocked_packages': ['app.blocked']}
        registry = ScreenRegistry(base)
        with patch('bbui.screens.ScrcpyDisplay') as display:
            result = registry.action('创建屏幕', {'屏幕会话': 'blocked-test', '包名': 'app.blocked', '执行后等待毫秒': 0})
            self.assertEqual(result['执行']['状态'], '未派发')
            self.assertIn('blocked_packages', result['错误'])
            display.assert_not_called()
        base.lock.assert_not_called()

    def test_unified_denies_foreground_input_and_launch_target_before_dispatch(self):
        for operation, params in [('点击', {'位置': [10, 10]}), ('按键', {'键名': '回车'}),
                                  ('打开应用', {'包名': 'app.blocked'})]:
            with self.subTest(operation=operation), tempfile.TemporaryDirectory() as directory:
                tool = object.__new__(UnifiedPhone)
                tool.phone = Mock()
                tool.phone.run = Path(directory)
                tool.phone.lock.return_value = nullcontext()
                tool.phone.input_lock.return_value = nullcontext()
                context = {'package': 'app.other' if operation == '打开应用' else 'app.blocked', 'width': 100, 'height': 200}
                tool.phone.context.return_value = context
                tool.phone.config = {'blocked_packages': ['app.blocked']}
                tool.latest = {'consumed': False, 'captured_at': time.time(), 'observation_id': 'latest', 'context': context}
                tool.controller = Mock()
                with self.assertRaisesRegex(ValueError, 'blocked_packages'):
                    tool.action(operation, {**params, '执行后等待毫秒': 0})
                tool.controller.assert_not_called()
                tool.phone.d.shell.assert_not_called()
                self.assertFalse(tool.latest['consumed'])
