import struct
import threading
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import Mock, patch
from PIL import Image

from bbui.screens import InputGate, ScreenRegistry, display_context
from bbui.scrcpy_display import ScrcpyDisplay, read_exact


class ScreenTests(unittest.TestCase):
    def test_display_lookup_never_falls_back_to_another_screen(self):
        dump = '''Display #2 (activities from top to bottom):
    topResumedActivity=ActivityRecord{abc u0 app.two/.Main t2 d2}
Display #0 (activities from top to bottom):
    topResumedActivity=ActivityRecord{def u0 app.main/.Main t1 d0}
'''
        self.assertEqual(display_context(dump, 2, 720, 1280)['package'], 'app.two')
        with self.assertRaises(RuntimeError):
            display_context(dump, 3, 720, 1280)

    def test_reader_gestures_overlap_and_clipboard_waits(self):
        gate = InputGate()
        barrier = threading.Barrier(3)
        release = threading.Event()
        pasted = threading.Event()
        def reader():
            with gate.acquire():
                barrier.wait(timeout=2)
                release.wait(timeout=2)
        def writer():
            with gate.acquire(exclusive=True):
                pasted.set()
        with ThreadPoolExecutor(3) as pool:
            readers = [pool.submit(reader) for _ in range(2)]
            barrier.wait(timeout=2)
            w = pool.submit(writer)
            self.assertFalse(pasted.wait(.05))
            release.set()
            for future in readers: future.result()
            w.result()
        self.assertTrue(pasted.is_set())

    def test_registry_waits_on_different_screens_overlap(self):
        base = Mock()
        registry = ScreenRegistry(base)
        registry.lease = Mock()
        barrier = threading.Barrier(2)
        for name in ('a', 'b'):
            tool = Mock()
            tool.phone.lock.return_value = threading.RLock()
            tool.phone.closed = False
            tool.phone.display = None
            tool.phone.display_id = 2 if name == 'a' else 3
            def action(op, params):
                barrier.wait(timeout=2)
                return {'状态': '已观察'}
            tool.action.side_effect = action
            registry.sessions[name] = tool
        with ThreadPoolExecutor(2) as pool:
            calls = [pool.submit(registry.action, '查看', {'屏幕会话': n, '执行后等待毫秒': 3000}) for n in ('a', 'b')]
            self.assertEqual({f.result()['屏幕会话'] for f in calls}, {'a', 'b'})

    def test_same_screen_calls_are_serialized(self):
        registry = ScreenRegistry(Mock())
        registry.lease = Mock()
        tool = Mock()
        tool.phone.lock.return_value = threading.RLock()
        tool.phone.closed = False
        tool.phone.display = None
        tool.phone.display_id = 2
        registry.sessions['a'] = tool
        active, maximum = 0, 0
        def action(op, params):
            nonlocal active, maximum
            active += 1
            maximum = max(active, maximum)
            time.sleep(.03)
            active -= 1
            return {'状态': '已观察'}
        tool.action.side_effect = action
        with ThreadPoolExecutor(2) as pool:
            futures = [pool.submit(registry.action, '查看', {'屏幕会话': 'a', '执行后等待毫秒': 0}) for _ in range(2)]
            for f in futures: f.result()
        self.assertEqual(maximum, 1)

    def test_missing_screen_or_observation_does_not_dispatch(self):
        registry = ScreenRegistry(Mock())
        registry.lease = Mock()
        result = registry.action('点击', {'屏幕会话': 'missing', '执行后等待毫秒': 0})
        self.assertEqual(result['执行']['状态'], '未派发')
        tool = Mock()
        tool.phone.lock.return_value = threading.RLock()
        tool.phone.closed = False
        tool.phone.display = None
        tool.observe_result.return_value = {'观察': {'状态': '已取得'}}
        registry.sessions['a'] = tool
        result = registry.action('点击', {'屏幕会话': 'a', '执行后等待毫秒': 0})
        self.assertEqual(result['执行']['状态'], '未派发')
        self.assertEqual(result['观察']['状态'], '已取得')
        tool.action.assert_not_called()

    def test_fragmented_video_read_and_disconnect(self):
        sock = Mock()
        sock.recv.side_effect = [b'ab', b'c', b'd']
        self.assertEqual(read_exact(sock, 4), b'abcd')
        sock.recv.side_effect = [b'a', b'']
        with self.assertRaises(ConnectionError):
            read_exact(sock, 4)

    def test_scrcpy_41_session_and_config_headers_and_rotation(self):
        display = object.__new__(ScrcpyDisplay)
        display.condition = threading.Condition()
        display.closed = False
        display.frame = None
        display.frame_sequence = 0
        display.stream_generation = 0
        display.video_error = None
        def session(w, h): return struct.pack('>III', 0x80000000, w, h)
        def packet(flags, data): return struct.pack('>QI', flags, len(data)) + data
        wire = bytearray(b'h264' + session(2, 2) + packet(1 << 62, b'config1') + packet(1 << 61, b'frame1')
                         + session(4, 6) + packet(1 << 62, b'config2') + packet(1 << 61, b'frame2'))
        def recv(size):
            chunk = bytes(wire[:min(3, size)])
            del wire[:len(chunk)]
            return chunk
        display.video_socket = Mock(recv=recv)
        decoded = []
        def decode(data):
            decoded.append(data)
            # A new session must discard the previous resolution's frame.
            self.assertIsNone(display.frame)
            frame = Mock()
            def picture():
                if len(decoded) == 2: display.closed = True
                return Image.new('RGB', (display.width, display.height))
            frame.to_image.side_effect = picture
            return [frame]
        decoder = Mock(decode=decode)
        with patch('bbui.scrcpy_display.av.CodecContext.create', return_value=decoder), patch('bbui.scrcpy_display.av.Packet', side_effect=lambda data: data):
            display._decode()
        self.assertIsNone(display.video_error)
        self.assertEqual(decoded, [b'config1frame1', b'config2frame2'])
        self.assertEqual(display.frame.size, (4, 6))
