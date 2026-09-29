import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch

from bbui.mirrors import MirrorManager
from bbui.screens import ScreenRegistry


class MirrorTests(unittest.TestCase):
    def test_viewer_cannot_inject_or_create_another_display(self):
        with tempfile.TemporaryDirectory() as directory:
            manager = MirrorManager(directory, 'device123')
            with patch.object(Path, 'is_file', return_value=True):
                command = manager._command('calc', 7)
            self.assertIn('--no-control', command)
            self.assertIn('--no-clipboard-autosync', command)
            self.assertIn('--no-power-on', command)
            self.assertIn('--display-id=7', command)
            self.assertFalse(any('--new-display' in arg for arg in command))

    def test_connect_reuses_main_and_preview_failure_is_distinct(self):
        base, mirrors = Mock(), Mock()
        base.serial = 'configured-device'
        registry = ScreenRegistry(base, mirrors)
        registry.lease = Mock()
        main = Mock()
        main.phone.lock.return_value = threading.RLock()
        registry.sessions['main'] = main
        mirrors.open.side_effect = RuntimeError('viewer missing')
        with patch('bbui.screens.adbutils.adb') as adb:
            adb.device.return_value.get_state.return_value = 'device'
            result = registry.action('连接手机', {'执行后等待毫秒': 0})
        self.assertEqual(result['状态'], '手机已连接')
        self.assertEqual(result['镜像']['状态'], '启动失败')
        self.assertEqual(len(registry.sessions), 1)
        main.action.assert_not_called()

    def test_unauthorized_device_does_not_open_viewer(self):
        registry = ScreenRegistry(Mock(), Mock())
        registry.lease = Mock()
        with patch('bbui.screens.adbutils.adb') as adb:
            adb.device.return_value.get_state.return_value = 'unauthorized'
            with self.assertRaisesRegex(RuntimeError, 'unauthorized'):
                registry.connect_phone()
        registry.mirrors.open.assert_not_called()

    def test_closed_window_does_not_close_model_screen(self):
        with tempfile.TemporaryDirectory() as directory:
            manager = MirrorManager(directory, 'serial')
            process = Mock(pid=123)
            process.poll.return_value = 0
            manager.entries['calc'] = process, Path(directory)/'log', 7
            self.assertEqual(manager.status('calc')['状态'], '已关闭')
            manager.close('calc')
            process.terminate.assert_not_called()

    @unittest.skipUnless(os.name == 'nt', 'Windows process ownership')
    def test_job_close_terminates_owned_viewer(self):
        from bbui.windows_job import ViewerJob
        job = ViewerJob()
        child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)'],
                                 creationflags=subprocess.CREATE_NO_WINDOW)
        try:
            job.add(child)
            job.close()
            child.wait(timeout=3)
            self.assertIsNotNone(child.returncode)
        finally:
            job.close()
            if child.poll() is None:
                child.kill()
                child.wait(timeout=3)
