import asyncio
import os
import time
import unittest
from unittest.mock import patch

from bbui.ios_platform import new_event_loop


class PlatformClockTests(unittest.TestCase):
    def test_non_windows_keeps_standard_loop(self):
        expected = object()
        with patch('bbui.ios_platform.os.name', 'posix'), patch('bbui.ios_platform.asyncio.new_event_loop', return_value=expected) as factory:
            self.assertIs(new_event_loop(), expected)
            factory.assert_called_once_with()

    @unittest.skipUnless(os.name == 'nt', 'Windows runtime adapter')
    def test_windows_clock_and_timer_resolution_match_qpc(self):
        loop = new_event_loop()
        try:
            self.assertIsInstance(loop, asyncio.ProactorEventLoop)
            with patch('bbui.ios_platform.time.perf_counter', return_value=1234.56789):
                self.assertEqual(loop.time(), 1234.56789)
            self.assertEqual(loop._clock_resolution, time.get_clock_info('perf_counter').resolution)
            self.assertLess(loop._clock_resolution, .001)
        finally:
            loop.close()

    @unittest.skipUnless(os.name == 'nt', 'Windows runtime adapter')
    def test_timer_does_not_fire_early_when_io_wakes_loop(self):
        loop = new_event_loop()
        async def check():
            fired = loop.create_future()
            deadline = loop.time() + .030
            loop.call_at(deadline, lambda: fired.set_result(loop.time()))
            # Simulate frequent socket completions while waiting. CPython's
            # default 15.625 ms look-ahead fires the deadline early here.
            while not fired.done():
                await asyncio.sleep(.001)
            return fired.result() - deadline
        try:
            self.assertGreaterEqual(loop.run_until_complete(check()), -0.0001)
        finally:
            loop.close()


if __name__ == '__main__':
    unittest.main()
