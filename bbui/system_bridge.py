"""Structured app_process transport to the shared Android system executor."""
import hashlib
import json
import threading
import uuid
import adbutils
from .paths import assets


class SystemBridge:
    def __init__(self, base):
        self.base = base
        self.process = None
        self.pending = {}
        self.mutex = threading.RLock()
        self.ready = threading.Event()
        self.failure = None
        self.epoch = 0

    def start(self):
        with self.mutex:
            if self.failure:
                raise ConnectionError(self.failure)
            if not self.process:
                device = adbutils.adb.device(self.base.serial)
                root = assets(self.base.root)
                jar = root / 'vendor' / 'desktop-agent.jar'
                digest = hashlib.sha256(jar.read_bytes()).hexdigest()[:16]
                remote = f'/data/local/tmp/bbui-desktop-{digest}.jar'
                device.sync.push(str(jar), remote)
                support = root / 'vendor' / 'scrcpy' / 'scrcpy-server-v4.1'
                target = '/data/local/tmp/bbui-scrcpy-server-v4.1.jar'
                device.sync.push(str(support), target)
                self.process = device.shell(f'CLASSPATH={remote} app_process / io.bbui.desktopagent.Main {target}', stream=True)
                threading.Thread(target=self._read, daemon=True).start()
        if not self.ready.wait(30):
            raise TimeoutError('Android 系统执行器启动超时')
        if self.failure:
            raise ConnectionError(self.failure)

    def _read(self):
        try:
            buffer = b''
            while True:
                chunk = self.process.conn.recv(65536)
                if not chunk:
                    raise ConnectionError('Android 系统执行器连接已结束')
                buffer += chunk
                while b'\n' in buffer:
                    line, buffer = buffer.split(b'\n', 1)
                    if not line.startswith(b'BBUI '):
                        continue
                    item = json.loads(line[5:])
                    if item.get('id') == 'ready':
                        if item.get('error'):
                            raise RuntimeError(item['error'])
                        self.ready.set()
                    else:
                        with self.mutex:
                            pending = self.pending.get(item.get('id'))
                            if pending:
                                pending['result'] = item
                                pending['event'].set()
        except Exception as error:
            with self.mutex:
                self.failure = str(error)
                self.ready.set()
                for pending in self.pending.values():
                    pending['event'].set()

    def request(self, kind, **body):
        self.start()
        ident = uuid.uuid4().hex
        pending = {'event': threading.Event()}
        with self.mutex:
            self.pending[ident] = pending
            self.process.conn.sendall((json.dumps({'id': ident, 'type': kind, **body}, ensure_ascii=False) + '\n').encode())
        try:
            if not pending['event'].wait(110):
                raise TimeoutError('系统操作结果未知；禁止自动重放')
            result = pending.get('result')
            if result is None:
                raise ConnectionError(self.failure)
            if result.get('error'):
                raise RuntimeError(result['error'])
            return result.get('data')
        finally:
            with self.mutex:
                self.pending.pop(ident, None)

    def stopped(self, value):
        # Calling STOP never starts an otherwise unused helper.
        if not self.process:
            return
        self.epoch += 1
        return self.request('stop' if value else 'begin', epoch=self.epoch)

    def close(self):
        if self.process:
            self.process.close()
