import tempfile
import threading
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, Mock, patch

from PIL import Image

from bbui.desktop_device import DesktopDevice, external_displays
from bbui.scrcpy_display import ScrcpyDisplay


MAIN = 'mBaseDisplayInfo=DisplayInfo{"内置屏幕", displayId 0, type INTERNAL, uniqueId "local:0"}'
EXTERNAL = 'mBaseDisplayInfo=DisplayInfo{"scrcpy", displayId 152, FLAG_PRESENTATION, FLAG_TRUSTED, type VIRTUAL, uniqueId "virtual:com.android.shell,2000,scrcpy,150"}'
CAPTURE = 'mBaseDisplayInfo=DisplayInfo{"scrcpy", displayId 153, FLAG_PRIVATE, type VIRTUAL, uniqueId "virtual:com.android.shell,2000,scrcpy,151"}'
DUMP = '\n'.join((MAIN, EXTERNAL, CAPTURE))


class ExternalPreviewTests(unittest.TestCase):
    def fixture(self):
        folder = self.enterContext(tempfile.TemporaryDirectory())
        base = SimpleNamespace(serial='fixture', root=Path(folder), run=Path(folder), state_dir=Path(folder))
        registry = SimpleNamespace(base=base, mutex=threading.RLock(), sessions={}, _own_device=Mock())
        desktop = DesktopDevice(registry)
        desktop.system = Mock()
        device = Mock()
        device.shell.return_value = DUMP
        self.enterContext(patch('bbui.desktop_device.adbutils.adb.device', return_value=device))
        return desktop, device

    def test_discovery_excludes_main_capture_and_owned_screens_and_is_cached(self):
        desktop, device = self.fixture()
        sources = desktop.host('previewSources', {})
        self.assertEqual(len(sources), 1)
        self.assertEqual(sources[0]['显示屏编号'], 152)
        self.assertTrue(sources[0]['external'])
        self.assertTrue(sources[0]['readOnly'])
        self.assertTrue(sources[0]['屏幕会话'].startswith('external:152:'))
        self.assertEqual(desktop.host('previewSources', {}), sources)
        device.shell.assert_called_once_with(['dumpsys', 'display'], timeout=3)
        self.assertEqual(desktop.phone.sessions, {})
        desktop.phone._own_device.assert_not_called()
        desktop.phone.sessions['owned'] = SimpleNamespace(phone=SimpleNamespace(display_id=152))
        self.assertEqual(desktop.preview_sources(force=True), [])

    def test_external_preview_uses_readonly_existing_display_and_close_only_closes_its_viewer(self):
        desktop, device = self.fixture()
        key = desktop.preview_sources()[0]['屏幕会话']
        viewer = MagicMock()
        viewer.frame_snapshot.return_value = (Image.new('RGB', (80, 120)), 1, 1)
        with patch('bbui.desktop_device.ScrcpyDisplay', return_value=viewer) as create:
            frame = desktop.host('frame', {'screen': key})
            create.assert_called_once_with('fixture', desktop.base.root, existing_display=152, control=False)
        self.assertEqual(frame['screen'], key)
        self.assertEqual(device.shell.call_count, 3)  # Discover, verify before and after attachment.
        desktop.host('frame', {'screen': key})
        self.assertEqual(device.shell.call_count, 3)  # No per-frame display enumeration.
        with self.assertRaisesRegex(ValueError, '仅供查看'):
            desktop.host('input', {'screen': key})
        self.assertEqual(desktop.phone.sessions, {})
        desktop.close()
        viewer.__exit__.assert_called_once()
        desktop.phone._own_device.assert_not_called()
        self.assertTrue(all(call.args[0] == ['dumpsys', 'display'] for call in device.shell.call_args_list))

    def test_reused_display_id_rejects_stale_identity_before_attachment(self):
        desktop, device = self.fixture()
        key = desktop.preview_sources()[0]['屏幕会话']
        device.shell.return_value = DUMP.replace('scrcpy,150', 'scrcpy,999')
        with patch('bbui.desktop_device.ScrcpyDisplay') as create:
            with self.assertRaisesRegex(ValueError, '身份已变化'):
                desktop.host('frame', {'screen': key})
            create.assert_not_called()
        self.assertNotEqual(next(iter(desktop.external)), key)

    def test_identity_changed_during_attachment_closes_new_viewer_without_returning_pixels(self):
        desktop, device = self.fixture()
        key = desktop.preview_sources()[0]['屏幕会话']
        viewer = MagicMock()
        viewer.__enter__.side_effect = lambda: setattr(device.shell, 'return_value', MAIN)
        with patch('bbui.desktop_device.ScrcpyDisplay', return_value=viewer):
            with self.assertRaisesRegex(ValueError, '身份已变化'):
                desktop.host('frame', {'screen': key})
        viewer.__exit__.assert_called_once()
        viewer.frame_snapshot.assert_not_called()

    def test_failed_enumeration_retains_sources_and_removed_display_closes_only_viewer(self):
        desktop, device = self.fixture()
        key = desktop.preview_sources()[0]['屏幕会话']
        viewer = MagicMock()
        desktop.external[key]['video'] = viewer
        device.shell.side_effect = TimeoutError('display query timeout')
        with self.assertRaises(TimeoutError): desktop.preview_sources(force=True)
        self.assertIn(key, desktop.external)
        viewer.__exit__.assert_not_called()
        device.shell.side_effect = None
        device.shell.return_value = MAIN
        self.assertEqual(desktop.preview_sources(force=True), [])
        viewer.__exit__.assert_called_once()

    def test_scrcpy_readonly_existing_display_never_opens_control_socket(self):
        with patch('bbui.scrcpy_control.adbutils.adb.device') as device, \
                patch('bbui.scrcpy_display.assets') as assets:
            # Use mocks for pinned assets and transport; no adb command is executed.
            server = assets.return_value.__truediv__.return_value.__truediv__.return_value.__truediv__.return_value
            server.read_bytes.return_value = b'fixture'
            video_socket = Mock()
            device.return_value.create_connection.return_value = video_socket
            with patch('bbui.scrcpy_display.hashlib.sha256') as digest, \
                    patch('bbui.scrcpy_display.threading.Thread') as thread, \
                    patch('bbui.scrcpy_display.read_exact', return_value=b'\0'):
                from bbui.scrcpy_control import SERVER_SHA256
                digest.return_value.hexdigest.return_value = SERVER_SHA256
                preview = ScrcpyDisplay('fixture', Path('.'), existing_display=152, control=False)
                preview.__enter__()
                device.return_value.create_connection.assert_called_once()
                command = device.return_value.shell.call_args.args[0]
                self.assertIn('display_id=152', command)
                self.assertIn('control=false', command)
                self.assertNotIn('new_display=', command)
                self.assertIsNone(preview.socket)
                preview.__exit__()
                video_socket.close.assert_called_once()


if __name__ == '__main__': unittest.main()
