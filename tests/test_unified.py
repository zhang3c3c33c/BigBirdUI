import unittest
import time
import tempfile
from contextlib import nullcontext
from pathlib import Path
from unittest.mock import Mock, patch
from bbui.unified import UnifiedPhone, point, duration, screenshot_wait


class Socket:
    def __init__(self):
        self.sent = []
    def sendall(self, p):
        self.sent.append(p)


class UnifiedTests(unittest.TestCase):
    def test_wait_defaults_to_zero_and_validates_before_device_access(self):
        self.assertEqual(screenshot_wait(None), 0)
        self.assertEqual(screenshot_wait({}), 0)
        tool = object.__new__(UnifiedPhone)
        tool.phone = Mock()
        for params in ([], {'执行后等待毫秒': True},
                       {'执行后等待毫秒': '3000'}, {'执行后等待毫秒': 1.5},
                       {'执行后等待毫秒': -1}, {'执行后等待毫秒': 30001}):
            with self.subTest(params=params), self.assertRaises(ValueError):
                tool.action('查看', params)
        self.assertEqual(tool.phone.mock_calls, [])

    def test_observation_waits_before_capture(self):
        for operation, params, expected in (
            ('查看', {'执行后等待毫秒': 0}, 0),
            ('查看', {'执行后等待毫秒': 30000}, 30),
            ('等待', {'时间': 500, '执行后等待毫秒': 2000},
             .5),
        ):
            with self.subTest(operation=operation, params=params):
                events = []
                tool = object.__new__(UnifiedPhone)
                tool.phone = Mock()
                tool.phone.lock.return_value = nullcontext()
                tool.capture = lambda nodes: events.append('capture') or {'截图': 'test.png'}
                with patch('bbui.unified.time.sleep', side_effect=lambda s: events.append(('sleep', s))):
                    result = tool.action(operation, params)
                self.assertEqual(events[-1], 'capture')
                self.assertAlmostEqual(sum(e[1] for e in events[:-1]), expected)
                self.assertEqual(result['执行后等待毫秒'], expected * 1000)
                self.assertEqual(result['执行']['状态'], '无需派发')

    def test_action_and_uncertain_result_wait_before_capture(self):
        for failure in (False, True):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as folder:
                events = []
                tool = object.__new__(UnifiedPhone)
                tool.phone = Mock()
                tool.phone.run = Path(folder)
                tool.phone.lock.return_value = nullcontext()
                tool.phone.input_lock.return_value = nullcontext()
                context = {'package': 'test.app', 'width': 1080, 'height': 2400}
                tool.phone.context.return_value = context
                tool.phone.config = {'blocked_packages': []}
                tool.latest = {'consumed': False, 'captured_at': time.time(),
                               'observation_id': 'test', 'context': context}
                tool.controller = Mock()
                tool.close = Mock()
                def dispatch(code):
                    events.append('dispatch')
                    if failure:
                        raise OSError('disconnected')
                tool._key = dispatch
                tool.capture = lambda nodes: events.append('capture') or {'截图': 'test.png'}
                with patch('bbui.unified.time.sleep', side_effect=lambda s: events.append(('sleep', s))):
                    result = tool.action('按键', {'键名': '回车', '执行后等待毫秒': 3000})
                self.assertEqual(events[0], 'dispatch')
                self.assertEqual(events[-1], 'capture')
                self.assertAlmostEqual(sum(e[1] for e in events[1:-1]), 3)
                self.assertEqual(result['状态'], '结果不确定' if failure else '已执行')
                self.assertEqual(result['执行']['状态'], '未知' if failure else '已派发')
                tool.close.assert_not_called()

    def test_invalid_coordinate_rejected(self):
        for p in ([1080,100],[-1,50],[1.5,8],None):
            with self.assertRaises(ValueError):
                point(p,1080,2400)

    def test_unbounded_hold_rejected(self):
        with self.assertRaises(ValueError):
            duration({'时间':6000})

    def test_stop_during_swipe_releases_finger(self):
        tool = object.__new__(UnifiedPhone)
        sock = Socket()
        class Control:
            socket = sock
        class Phone:
            calls = 0
            def paused(self):
                self.calls += 1
                if self.calls > 1:
                    raise RuntimeError('stopped')
        tool.phone = Phone()
        tool.controller = lambda: Control()
        with self.assertRaises(RuntimeError):
            tool._gesture([([10,10],[20,20])],1080,2400,.2)
        self.assertEqual([p[1] for p in sock.sent],[0,1])

    def test_two_finger_gesture_releases_both(self):
        tool = object.__new__(UnifiedPhone)
        sock = Socket()
        class Control:
            socket = sock
        class Phone:
            def paused(self):
                pass
        tool.phone = Phone()
        tool.controller = lambda: Control()
        with patch('bbui.unified.time.sleep'):
            tool._gesture([([400,1000],[300,1000]),([600,1000],[700,1000])],1080,2400,.01)
        self.assertEqual([p[1] for p in sock.sent],[0,0,2,2,1,1])
