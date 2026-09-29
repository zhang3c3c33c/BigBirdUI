"""Platform-specific event-loop clock for the desktop iOS executor."""
import asyncio
import os
import time


def new_event_loop():
    if os.name != 'nt':
        return asyncio.new_event_loop()

    class QpcProactorEventLoop(asyncio.ProactorEventLoop):
        def __init__(self):
            super().__init__()
            # CPython 3.12 uses GetTickCount64 (15.625 ms) for monotonic.
            # Its timer look-ahead must match our QPC clock, otherwise an I/O
            # completion can run a timer up to 15 ms early. Keep this one
            # CPython adaptation local to the packaged Windows runtime.
            self._clock_resolution = time.get_clock_info('perf_counter').resolution

        def time(self):
            return time.perf_counter()

    return QpcProactorEventLoop()
