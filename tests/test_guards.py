import json
import tempfile
import time
import unittest
from pathlib import Path
from bbui.runtime import PhoneTools, parse_nodes, write_json

XML = '<hierarchy><node text="Send" content-desc="" resource-id="send" class="android.widget.Button" package="com.tencent.mm" bounds="[10,10][90,50]" enabled="true" clickable="true"/></hierarchy>'
CTX = dict(package='com.tencent.mm', activity='Chat', rotation=0, width=100, height=200)


class FakeDevice:
    def __init__(self):
        self.calls = []
        self.xml = XML

    def dump_hierarchy(self):
        return self.xml

    def click(self, x, y):
        self.calls.append((x, y))


class GuardTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        write_json(root / 'config.local.json', dict(serial='test', blocked_packages=[]))
        self.device = FakeDevice()
        self.phone = PhoneTools(root, self.device)
        self.phone.context = lambda: CTX.copy()
        self.obs = dict(observation_id='obs1', captured_at=time.time(), context=CTX, nodes=parse_nodes(XML))
        write_json(self.phone.run / 'latest.json', self.obs)
        self.action = dict(type='tap', node_id='n0', observation_id='obs1', action_id='send-1')

    def test_duplicate_action_does_not_send_twice(self):
        self.phone.act(self.action)
        self.assertEqual(self.phone.act(self.action)['status'], 'already_attempted')
        self.assertEqual(len(self.device.calls), 1)

    def test_blocked_foreground_is_not_dispatched(self):
        self.phone.config['blocked_packages'] = ['com.tencent.mm']
        with self.assertRaisesRegex(ValueError, 'blocked_packages'):
            self.phone.act(self.action)
        self.assertEqual(self.device.calls, [])

    def test_blocked_launch_target_is_not_dispatched(self):
        self.phone.config['blocked_packages'] = ['app.blocked']
        with self.assertRaisesRegex(ValueError, 'blocked_packages'):
            self.phone.act({**self.action, 'type': 'launch', 'package': 'app.blocked'})
        self.assertEqual(self.device.calls, [])

    def test_old_observation_blocked(self):
        self.action['observation_id'] = 'old'
        with self.assertRaises(ValueError):
            self.phone.act(self.action)
        self.assertEqual(self.device.calls, [])

    def test_moved_target_blocked(self):
        self.device.xml = XML.replace('[10,10]', '[11,10]')
        with self.assertRaises(ValueError):
            self.phone.act(self.action)
        self.assertEqual(self.device.calls, [])

    def test_recipient_guard_blocks_wrong_chat(self):
        self.action['required_texts'] = ['文件传输助手']
        with self.assertRaises(ValueError):
            self.phone.act(self.action)
        self.assertEqual(self.device.calls, [])

    def test_stop_blocks_dispatch(self):
        self.phone.stop()
        with self.assertRaises(RuntimeError):
            self.phone.act(self.action)
        self.assertEqual(self.device.calls, [])

    def test_failed_dispatch_is_not_retried(self):
        def fail(x, y):
            self.device.calls.append((x, y))
            raise TimeoutError('response lost after possible tap')
        self.device.click = fail
        result = self.phone.act(self.action)
        self.assertEqual(result['执行']['状态'], '未知')
        self.assertEqual(self.phone.act(self.action)['status'], 'already_attempted')
        self.assertEqual(len(self.device.calls), 1)

    def test_consumed_observation_cannot_dispatch_new_action(self):
        self.phone.act(self.action)
        self.action['action_id'] = 'send-2'
        with self.assertRaises(ValueError):
            self.phone.act(self.action)
        self.assertEqual(len(self.device.calls), 1)


if __name__ == '__main__':
    unittest.main()
