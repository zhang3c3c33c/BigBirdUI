"""Additional ownership restriction. This never clears or bypasses STOP."""
from contextlib import contextmanager
import threading
import uuid


class ControlGate:
    def __init__(self):
        self.mutex = threading.RLock()
        self.local = threading.local()
        self.held = False
        self.token = None

    def check(self):
        with self.mutex:
            if self.held and (not self.token or getattr(self.local, 'token', None) != self.token):
                raise RuntimeError('设备输入已由宿主暂停，模型不能派发新输入')

    def hold(self):
        with self.mutex:
            self.held, self.token = True, None

    def grant(self):
        with self.mutex:
            if not self.held:
                raise RuntimeError('必须先暂停并等待在途调用')
            self.token = uuid.uuid4().hex
            return self.token

    def release(self):
        with self.mutex:
            self.held, self.token = False, None

    def check_manual(self, token):
        with self.mutex:
            if not self.held or not token or token != self.token:
                raise ValueError('人工控制权已过期')

    @contextmanager
    def manual(self, token):
        self.check_manual(token)
        self.local.token = token
        try:
            yield
        finally:
            self.local.token = None
