"""Linux lifetime adapter: children end when the desktop host disappears."""
import os
import subprocess
import sys
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SLEEP = [sys.executable, '-c', 'import time; time.sleep(60)']


@unittest.skipIf(os.name == 'nt', 'POSIX process groups only')
class PosixJobWatchTests(unittest.TestCase):
    def setUp(self):
        self.host = subprocess.Popen(SLEEP, start_new_session=True)
        self.addCleanup(self._terminate, self.host)
        self.child = subprocess.Popen(SLEEP, start_new_session=True)
        self.addCleanup(self._terminate, self.child)
        self.watcher = subprocess.Popen([sys.executable, '-m', 'bbui.posix_job_watch', str(self.host.pid)],
            cwd=ROOT, env={**os.environ, 'PYTHONPATH': str(ROOT), 'PYTHONUTF8': '1'},
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            start_new_session=True, text=True)
        self.addCleanup(self._terminate, self.watcher)
        self.assertIn('BBUI_JOB_READY', self.watcher.stdout.readline())

    def _terminate(self, process):
        if process.poll() is None:
            process.kill()
        process.wait(timeout=10)
        for stream in (process.stdin, process.stdout, process.stderr):
            if stream:
                stream.close()

    def track(self):
        self.watcher.stdin.write(f'{self.child.pid}\n')
        self.watcher.stdin.flush()
        time.sleep(.5)

    def test_host_exit_terminates_tracked_children(self):
        self.track()
        self.host.kill()
        self.host.wait(timeout=10)
        self.assertEqual(self.watcher.wait(timeout=15), 0)
        self.assertIsNotNone(self.child.wait(timeout=15), 'tracked child outlived the host')

    def test_release_terminates_tracked_children_without_killing_the_watcher_host(self):
        self.track()
        self.watcher.stdin.write('R')
        self.watcher.stdin.close()
        self.assertEqual(self.watcher.wait(timeout=15), 0)
        self.assertIsNotNone(self.child.wait(timeout=15), 'tracked child outlived the release')
        self.assertIsNone(self.host.poll(), 'release must not signal the host itself')


if __name__ == '__main__':
    unittest.main()
