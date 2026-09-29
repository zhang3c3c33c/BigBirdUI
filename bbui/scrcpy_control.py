"""Minimal control-only client for the pinned official scrcpy 4.1 server.

Protocol reference and upstream Apache-2.0 license are under vendor/scrcpy.
No local TCP listener, video stream, desktop clipboard sync, APK or custom IME.
"""
import hashlib
import secrets
import struct
import threading
import time
from pathlib import Path
import adbutils
from .paths import assets
from .channel_errors import ChannelFailure, control_call

VERSION = '4.1'
SERVER_SHA256 = 'deacb991ed2509715160ffdc7907e47b4160eb30d1566217e9047fd5b8850cae'


def clipboard_packet(text, sequence, paste=False):
    data = text.encode('utf-8')
    if len(data) > 200000:
        raise ValueError('Clipboard text too long')
    return struct.pack('>BQB I', 9, sequence, int(paste), len(data)) + data


def key_packet(action, keycode, meta=0):
    return struct.pack('>BBIII', 0, action, keycode, 0, meta)


def touch_packet(action, x, y, width, height, pointer=-2):
    return struct.pack('>BBqiiHHHII', 2, action, pointer, x, y, width, height,
                       0 if action == 1 else 65535, 0, 0)


class ScrcpyControl:
    def __init__(self, serial, root):
        self.device = adbutils.adb.device(serial)
        self.root = Path(root)
        self.socket = None
        self.process = None
        self.logs = bytearray()

    def __enter__(self):
        try:
            server = assets(self.root) / 'vendor' / 'scrcpy' / f'scrcpy-server-v{VERSION}'
            if hashlib.sha256(server.read_bytes()).hexdigest() != SERVER_SHA256:
                raise RuntimeError('scrcpy server hash mismatch; run setup_scrcpy.py')
            remote = f'/data/local/tmp/bbui-scrcpy-server-v{VERSION}.jar'
            self.device.sync.push(str(server), remote)
            scid = secrets.randbelow(0x7fffffff)
            self.process = self.device.shell(
                f'CLASSPATH={remote} app_process / com.genymobile.scrcpy.Server {VERSION} '
                f'scid={scid:08x} video=false audio=false control=true tunnel_forward=true '
                'send_dummy_byte=true send_device_meta=false clipboard_autosync=false '
                'power_on=false cleanup=false', stream=True)
            self.reader = threading.Thread(target=self._drain, daemon=True)
            self.reader.start()
            deadline = time.monotonic() + 8
            while time.monotonic() < deadline:
                try:
                    self.socket = self.device.create_connection(adbutils.Network.LOCAL_ABSTRACT, f'scrcpy_{scid:08x}')
                    self.socket.settimeout(5)
                    if self._read(1) != b'\0':
                        raise RuntimeError('Invalid scrcpy handshake')
                    return self
                except (adbutils.AdbError, OSError):
                    if self.socket:
                        self.socket.close()
                        self.socket = None
                    time.sleep(0.1)
            raise RuntimeError('scrcpy startup failed: ' + self.logs.decode('utf-8', errors='replace'))
        except BaseException:
            self.__exit__(None, None, None)
            raise

    def _drain(self):
        try:
            while True:
                data = self.process.conn.recv(4096)
                if not data:
                    return
                self.logs.extend(data)
        except OSError:
            pass

    def _read(self, count):
        data = bytearray()
        while len(data) < count:
            part = control_call(self.socket.recv, count - len(data))
            if not part:
                raise ChannelFailure('control', 'scrcpy closed the control channel')
            data.extend(part)
        return bytes(data)

    def _message(self):
        kind = self._read(1)[0]
        if kind == 0:
            length = struct.unpack('>I', self._read(4))[0]
            if length > 262144:
                raise ValueError('Invalid clipboard packet length')
            return kind, self._read(length).decode('utf-8')
        if kind == 1:
            return kind, struct.unpack('>Q', self._read(8))[0]
        raise RuntimeError(f'Unexpected scrcpy message {kind}')

    def set_clipboard(self, text, sequence):
        control_call(self.socket.sendall, clipboard_packet(text, sequence))
        if self._message() != (1, sequence):
            raise RuntimeError('Clipboard ACK mismatch')

    def paste(self, text, before_dispatch=lambda: None):
        # ACK only acknowledges processing, not set success. Read back before pasting.
        self.set_clipboard(text, 1)
        control_call(self.socket.sendall, bytes([8, 0]))
        if self._message() != (0, text):
            raise RuntimeError('scrcpy clipboard readback mismatch; nothing pasted')
        before_dispatch()
        control_call(self.socket.sendall, key_packet(0, 279) + key_packet(1, 279))
        self.set_clipboard(text, 2)  # protocol processing barrier, NOT proof of input success
        time.sleep(0.15)

    def tap(self, x, y, width, height, before_dispatch=lambda: None):
        before_dispatch()
        control_call(self.socket.sendall, touch_packet(0, x, y, width, height))
        control_call(self.socket.sendall, touch_packet(1, x, y, width, height))
        time.sleep(0.15)

    def __exit__(self, *exc):
        if self.socket:
            self.socket.close()
        if self.process:
            self.process.close()
