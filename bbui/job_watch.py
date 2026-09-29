"""Windows lifetime adapter: parent and subsequently spawned children share a Job."""
import ctypes
from ctypes import wintypes
import sys
from .windows_job import ViewerJob, ExtendedLimits


def main(parent_pid):
    job = ViewerJob()
    api = job.api
    api.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    api.OpenProcess.restype = wintypes.HANDLE
    api.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
    parent = api.OpenProcess(0x100000 | 0x100 | 0x1, False, parent_pid)
    if not parent:
        raise ctypes.WinError(ctypes.get_last_error())
    try:
        if not api.AssignProcessToJobObject(job.handle, parent):
            raise ctypes.WinError(ctypes.get_last_error())
        print('BBUI_JOB_READY', flush=True)
        # EOF means the host died: closing the job terminates all descendants.
        # A clean host releases only after its own workers have confirmed exit.
        if sys.stdin.buffer.read(1) == b'R':
            limits = ExtendedLimits()
            if not api.SetInformationJobObject(job.handle, 9, ctypes.byref(limits), ctypes.sizeof(limits)):
                raise ctypes.WinError(ctypes.get_last_error())
    finally:
        api.CloseHandle(parent)
        job.close()


if __name__ == '__main__': main(int(sys.argv[1]))
