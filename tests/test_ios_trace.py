import asyncio
import io
import json
import unittest
import tempfile
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from contextlib import contextmanager, redirect_stderr
from unittest.mock import patch

from bbui.ios_transport import traced_async, trace_stage


class TraceTests(unittest.IsolatedAsyncioTestCase):
    def test_concurrent_file_writes_are_serialized_without_lost_records(self):
        original_open = Path.open
        active = maximum_active = 0
        counter_lock = threading.Lock()

        @contextmanager
        def slow_open(path, *args, **kwargs):
            nonlocal active, maximum_active
            with counter_lock:
                active += 1
                maximum_active = max(maximum_active, active)
            try:
                # Widen the competing-open window so this verifies serialization
                # on every platform, not just Windows append-handle behavior.
                time.sleep(0.001)
                with original_open(path, *args, **kwargs) as output:
                    yield output
            finally:
                with counter_lock:
                    active -= 1

        def write_stages(worker):
            for item in range(16):
                with trace_stage(f'worker.{worker}.{item}'):
                    time.sleep(0)

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'trace.jsonl'
            with patch.dict('os.environ', {'BBUI_IOS_TRACE': '1', 'BBUI_IOS_TRACE_FILE': str(path)}):
                with patch.object(Path, 'open', slow_open), ThreadPoolExecutor(max_workers=8) as workers:
                    list(workers.map(write_stages, range(8)))
            rows = [json.loads(line)['iosTrace'] for line in path.read_text('utf-8').splitlines()]
        self.assertEqual(maximum_active, 1)
        self.assertEqual(len(rows), 8 * 16 * 2)
        self.assertEqual({(row['method'], row['status']) for row in rows}, {
            (f'worker.{worker}.{item}', status)
            for worker in range(8) for item in range(16) for status in ('start', 'end')
        })

    async def test_off_by_default_does_not_log_arguments_or_result(self):
        @traced_async('transport.test')
        async def operation(secret):
            return secret
        output = io.StringIO()
        with patch.dict('os.environ', {'BBUI_IOS_TRACE': ''}), redirect_stderr(output):
            self.assertEqual(await operation('private'), 'private')
        self.assertEqual(output.getvalue(), '')

    async def test_success_logs_only_stage_and_elapsed(self):
        @traced_async('transport.test')
        async def operation(secret):
            return {'clipboard': secret}
        output = io.StringIO()
        with patch.dict('os.environ', {'BBUI_IOS_TRACE': '1'}), redirect_stderr(output):
            result = await operation('private')
        self.assertEqual(result, {'clipboard': 'private'})
        rows = [json.loads(line)['iosTrace'] for line in output.getvalue().splitlines()]
        self.assertEqual([row['status'] for row in rows], ['start', 'end'])
        self.assertEqual(rows[0]['method'], 'transport.test')
        self.assertGreaterEqual(rows[1]['elapsedMs'], 0)
        self.assertNotIn('private', output.getvalue())

    async def test_explicit_file_sink_is_opt_in_and_contains_only_whitelisted_fields(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'trace.jsonl'
            with patch.dict('os.environ', {'BBUI_IOS_TRACE': '0', 'BBUI_IOS_TRACE_FILE': str(path)}):
                with trace_stage('host.frame'):
                    pass
            self.assertFalse(path.exists())
            with patch.dict('os.environ', {'BBUI_IOS_TRACE': '1', 'BBUI_IOS_TRACE_FILE': str(path)}):
                with trace_stage('host.frame'):
                    pass
            rows = [json.loads(line)['iosTrace'] for line in path.read_text('utf-8').splitlines()]
            self.assertEqual(len(rows), 2)
            for row in rows:
                self.assertLessEqual(set(row), {'method', 'status', 'elapsedMs', 'errorClass'})

    async def test_error_logs_class_without_sensitive_message_and_reraises(self):
        output = io.StringIO()
        error = TimeoutError('private device or clipboard detail')
        with patch.dict('os.environ', {'BBUI_IOS_TRACE': '1'}), redirect_stderr(output):
            with self.assertRaises(TimeoutError) as caught:
                with trace_stage('host.frame'):
                    raise error
        self.assertIs(caught.exception, error)
        rows = [json.loads(line)['iosTrace'] for line in output.getvalue().splitlines()]
        self.assertEqual(rows[-1]['errorClass'], 'TimeoutError')
        self.assertNotIn('private', output.getvalue())


if __name__ == '__main__':
    unittest.main()
