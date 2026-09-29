import struct
import unittest
from bbui.scrcpy_control import ScrcpyControl, clipboard_packet, touch_packet


class FakeSocket:
    def __init__(self, data):
        self.data = data
        self.sent = []

    def recv(self, count):
        part, self.data = self.data[:min(count, 2)], self.data[min(count, 2):]
        return part

    def sendall(self, data):
        self.sent.append(data)


class ScrcpyTests(unittest.TestCase):
    def controller(self, wire):
        c = object.__new__(ScrcpyControl)
        c.socket = FakeSocket(wire)
        return c

    def test_unicode_length_is_bytes(self):
        packet = clipboard_packet('测试', 7)
        self.assertEqual(struct.unpack('>BQBI', packet[:14]), (9, 7, 0, 6))
        self.assertEqual(packet[14:], '测试'.encode())

    def test_fragmented_clipboard_read(self):
        data = '测试'.encode()
        c = self.controller(b'\0' + struct.pack('>I', len(data)) + data)
        self.assertEqual(c._message(), (0, '测试'))

    def test_readback_mismatch_never_pastes(self):
        data = b'wrong'
        c = self.controller(b'\1' + struct.pack('>Q', 1) + b'\0' + struct.pack('>I', len(data)) + data)
        with self.assertRaises(RuntimeError):
            c.paste('desired')
        self.assertEqual([p[0] for p in c.socket.sent], [9, 8])

    def test_touch_packet_uses_original_screen_pixels(self):
        values = struct.unpack('>BBqiiHHHII', touch_packet(0, 900, 180, 1080, 2400))
        self.assertEqual(values, (2, 0, -2, 900, 180, 1080, 2400, 65535, 0, 0))

    def test_disconnect_is_failure_not_success(self):
        with self.assertRaises(ConnectionError):
            self.controller(b'\1\0')._message()
