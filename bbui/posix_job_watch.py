"""POSIX lifetime adapter: end host children once the desktop host is gone.

The Windows build puts the host into a Job object. Linux has no equivalent, so a
watcher in its own session tracks the process groups the host reports on stdin
and terminates them when the host exits or asks for a clean release.
"""
import os
import select
import signal
import sys
import time

GRACE_SECONDS = 3.0


def _pidfd(pid):
    try:
        return os.pidfd_open(pid, 0)
    except (AttributeError, OSError):
        return None


def _alive(pid):
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True


def _signal_group(pid, number):
    try:
        # Children are session leaders, so their group id is their process id.
        os.killpg(pid, number)
    except (ProcessLookupError, PermissionError):
        pass


def _reap(watched):
    for pid, handle in list(watched.items()):
        if handle is not None:
            if select.select([handle], [], [], 0)[0]:
                os.close(handle)
                watched.pop(pid)
        elif not _alive(pid):
            watched.pop(pid)


def main(parent_pid):
    parent = _pidfd(parent_pid)
    watched, released = {}, False
    stdin = sys.stdin.buffer.fileno()
    print('BBUI_JOB_READY', flush=True)
    while True:
        poll = [stdin] + ([parent] if parent is not None else [])
        ready = select.select(poll, [], [], .2)[0]
        if parent is not None and parent in ready:
            break
        if stdin in ready:
            chunk = os.read(stdin, 4096)
            if not chunk:
                break
            for token in chunk.decode('ascii', 'replace').split():
                if token == 'R':
                    released = True
                else:
                    try:
                        pid = int(token)
                    except ValueError:
                        continue
                    watched[pid] = _pidfd(pid)
        if released or (parent is None and not _alive(parent_pid)):
            break
        # Drop children that already exited so a reused pid is never signalled.
        _reap(watched)
    for pid in watched:
        _signal_group(pid, signal.SIGTERM)
    deadline = time.monotonic() + GRACE_SECONDS
    while watched and time.monotonic() < deadline:
        _reap(watched)
        time.sleep(.05)
    for pid, handle in watched.items():
        _signal_group(pid, signal.SIGKILL)
        if handle is not None:
            os.close(handle)


if __name__ == '__main__':
    main(int(sys.argv[1]))
