import errno
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch
from adbutils.errors import AdbConnectionError, AdbError, AdbTimeout

from bbui.channel_errors import ChannelFailure, control_call, control_failure, host_error, transport_failed
from bbui.desktop_device import DesktopDevice


class ChannelTests(unittest.TestCase):
    def test_only_transport_failures_are_classified(self):
        for error in [TimeoutError(), ConnectionResetError(), EOFError(), OSError(errno.EPIPE, 'broken')]:
            self.assertTrue(transport_failed(error))
        for error in [ValueError('invalid'), RuntimeError('app rejected launch'), PermissionError('file denied'),
                      FileNotFoundError('missing file'), OSError('video unavailable'),
                      ChannelFailure('observation', TimeoutError())]:
            self.assertFalse(transport_failed(error))

    def test_host_error_does_not_classify_business_errors_or_raw_timeouts(self):
        self.assertEqual(host_error(ValueError('invalid')), {'error': 'invalid'})
        self.assertNotIn('channel', host_error(TimeoutError('unknown stage')))
        error = host_error(ChannelFailure('observation', TimeoutError('screenshot')))
        self.assertEqual((error['channel'], error['code']), ('observation', 'observation_failed'))

    def test_failed_control_socket_marks_error_with_no_replay(self):
        send = Mock(side_effect=ConnectionResetError('socket closed'))
        with self.assertRaises(ChannelFailure) as caught:
            control_call(send, b'input')
        send.assert_called_once_with(b'input')
        self.assertEqual(host_error(caught.exception)['code'], 'control_disconnected')

    def test_adb_control_transport_errors_exclude_command_rejection(self):
        for error in [AdbConnectionError('closed'), AdbTimeout('timed out')]:
            shell = Mock(side_effect=error)
            with self.assertRaises(ChannelFailure):
                control_call(shell, ['am', 'start'])
            shell.assert_called_once_with(['am', 'start'])
        with self.assertRaises(AdbError) as caught:
            control_call(Mock(side_effect=AdbError('device rejected command')))
        self.assertNotIsInstance(caught.exception, ChannelFailure)

    def test_android_known_control_failure_overrides_usb_presence(self):
        with tempfile.TemporaryDirectory() as folder:
            base = SimpleNamespace(serial='fixture', run=Path(folder), state_dir=Path(folder), stop=Mock())
            desktop = DesktopDevice(SimpleNamespace(base=base))
            self.assertTrue(control_failure(base, ChannelFailure('control', 'scrcpy closed')))
            base.stop.assert_called_once()
            with patch('bbui.desktop_device.adbutils.adb.device') as adb:
                adb.return_value.get_state.return_value = 'device'
                status = desktop.host('status', {})
            self.assertFalse(status['connected'])
            self.assertEqual(status['connectionStatus']['code'], 'control_disconnected')

    def test_android_preview_failure_does_not_poison_control(self):
        base = SimpleNamespace()
        desktop = DesktopDevice(SimpleNamespace(base=base))
        desktop.frame = Mock(side_effect=TimeoutError('video stalled'))
        with self.assertRaises(ChannelFailure) as caught:
            desktop.host('frame', {'screen': 'main'})
        self.assertEqual(caught.exception.channel, 'observation')
        self.assertFalse(hasattr(base, 'control_error'))

    def test_android_guard_failure_is_not_a_control_socket_failure(self):
        base = SimpleNamespace(stop=Mock())
        self.assertFalse(control_failure(base, ConnectionError('video context lost')))
        base.stop.assert_not_called()
