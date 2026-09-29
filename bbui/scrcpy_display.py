"""Pinned scrcpy 4.1 virtual display with a continuously decoded H.264 stream."""
import hashlib
import re
import secrets
import socket
import struct
import threading
import time

import adbutils
import av

from .scrcpy_control import ScrcpyControl, VERSION, SERVER_SHA256
from .paths import assets


def read_exact(sock, count):
    data = bytearray()
    while len(data) < count:
        part = sock.recv(count - len(data))
        if not part:
            raise ConnectionError('scrcpy 视频连接已关闭')
        data.extend(part)
    return bytes(data)


class ScrcpyDisplay(ScrcpyControl):
    def __init__(self, serial, root, width=1080, height=1920, dpi=320, existing_display=None, control=True):
        super().__init__(serial, root)
        self.width, self.height, self.dpi = width, height, dpi
        self.display_id = None
        self.existing_display = existing_display
        self.control_enabled = control
        self.video_socket = None
        self.condition = threading.Condition()
        self.frame = None
        self.frame_time = None
        self.frame_sequence = 0
        self.stream_generation = 0
        self.video_error = None
        self.closed = False

    def __enter__(self):
        try:
            server = assets(self.root) / 'vendor' / 'scrcpy' / f'scrcpy-server-v{VERSION}'
            if hashlib.sha256(server.read_bytes()).hexdigest() != SERVER_SHA256:
                raise RuntimeError('scrcpy server hash mismatch')
            remote = f'/data/local/tmp/bbui-scrcpy-server-v{VERSION}.jar'
            self.device.sync.push(str(server), remote)
            scid = secrets.randbelow(0x7fffffff)
            display_option = f'new_display={self.width}x{self.height}/{self.dpi}' if self.existing_display is None else f'display_id={self.existing_display}'
            self.process = self.device.shell(
                f'CLASSPATH={remote} app_process / com.genymobile.scrcpy.Server {VERSION} '
                f'scid={scid:08x} video=true audio=false control={str(self.control_enabled).lower()} tunnel_forward=true '
                f'{display_option} '
                'video_codec=h264 max_fps=30 video_bit_rate=4000000 '
                'send_dummy_byte=true send_device_meta=false send_frame_meta=true '
                'send_stream_meta=true clipboard_autosync=false power_on=false cleanup=false '
                'vd_system_decorations=false vd_destroy_content=true display_ime_policy=local', stream=True)
            self.reader = threading.Thread(target=self._drain, daemon=True)
            self.reader.start()
            deadline = time.monotonic() + 12
            while time.monotonic() < deadline:
                try:
                    self.video_socket = self.device.create_connection(
                        adbutils.Network.LOCAL_ABSTRACT, f'scrcpy_{scid:08x}')
                    break
                except (adbutils.AdbError, OSError):
                    time.sleep(.1)
            if self.video_socket is None:
                raise RuntimeError('scrcpy 虚拟屏幕启动超时: ' + self.logs.decode(errors='replace'))
            self.video_socket.settimeout(5)
            if read_exact(self.video_socket, 1) != b'\0':
                raise RuntimeError('Invalid video handshake')
            self.video_socket.settimeout(None)
            if self.control_enabled:
                self.socket = self.device.create_connection(adbutils.Network.LOCAL_ABSTRACT, f'scrcpy_{scid:08x}')
                self.socket.settimeout(5)
            self.decoder = threading.Thread(target=self._decode, daemon=True)
            self.decoder.start()
            if self.existing_display is not None:
                self.display_id = self.existing_display
                return self
            while time.monotonic() < deadline:
                match = re.search(rb'New display: .*?\(id=(\d+)\)', bytes(self.logs))
                if match:
                    self.display_id = int(match[1])
                    return self
                if self.video_error:
                    raise RuntimeError(self.video_error)
                time.sleep(.05)
            raise RuntimeError('没有收到虚拟屏幕编号: ' + self.logs.decode(errors='replace'))
        except BaseException:
            self.__exit__()
            raise

    def _decode(self):
        try:
            codec = struct.unpack('>I', read_exact(self.video_socket, 4))[0]
            if codec != int.from_bytes(b'h264', 'big'):
                raise RuntimeError('Unexpected video codec')
            decoder = av.CodecContext.create('h264', 'r')
            config = b''
            while not self.closed:
                flags, length = struct.unpack('>QI', read_exact(self.video_socket, 12))
                if flags & (1 << 63):
                    # scrcpy 4.x session header: flags(u32), width(u32), height(u32).
                    with self.condition:
                        self.stream_generation += 1
                        self.width, self.height = flags & 0xffffffff, length
                        self.frame = None
                        self.frame_time = None
                    decoder = av.CodecContext.create('h264', 'r')
                    config = b''
                    continue
                if not 0 < length <= 32 * 1024 * 1024:
                    raise RuntimeError('Invalid video packet size')
                packet = read_exact(self.video_socket, length)
                if flags & (1 << 62):
                    config += packet
                    continue
                frames = decoder.decode(av.Packet(config + packet))
                config = b''
                for frame in frames:
                    picture = frame.to_image()
                    with self.condition:
                        self.frame = picture
                        self.frame_time = time.time()
                        self.frame_sequence += 1
                        self.width, self.height = picture.size
                        self.condition.notify_all()
        except Exception as error:
            with self.condition:
                self.video_error = str(error)
                self.condition.notify_all()

    def screenshot(self, timeout=10):
        return self.frame_snapshot(timeout=timeout)[0]

    def frame_snapshot(self, after=None, timeout=10):
        """Copy the latest frame and its identity atomically; never queue old frames."""
        with self.condition:
            self.condition.wait_for(lambda: self.frame is not None or self.video_error or self.closed, timeout)
            if self.closed or self.video_error:
                raise ConnectionError(self.video_error or '屏幕会话已关闭')
            if self.frame is None:
                raise TimeoutError('虚拟屏幕尚未产生画面，请确认应用已在该屏幕启动')
            if after == (self.frame_sequence, self.stream_generation):
                return None
            return self.frame.copy(), self.frame_sequence, self.stream_generation

    def matches_frame(self, generation, size):
        with self.condition:
            return (not self.closed and not self.video_error and self.frame is not None
                    and self.stream_generation == generation and self.frame.size == size)

    def __exit__(self, *exc):
        self.closed = True
        for sock in (self.video_socket, self.socket):
            if sock:
                try:
                    sock.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                sock.close()
        if self.process:
            self.process.close()
        with self.condition:
            self.condition.notify_all()
