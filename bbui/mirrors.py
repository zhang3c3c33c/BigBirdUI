"""View-only desktop scrcpy clients, independent from model control sockets."""
import os
from pathlib import Path
import subprocess
import threading
import time
import uuid
import adbutils


class MirrorManager:
    def __init__(self, root, serial):
        self.root, self.serial = Path(root), serial
        self.entries = {}
        self.mutex = threading.RLock()
        self.job = None

    def _command(self, name, display_id):
        executable = self.root / 'vendor' / 'scrcpy' / 'scrcpy-win64-v4.1' / 'scrcpy.exe'
        if not executable.is_file():
            raise RuntimeError('缺少 scrcpy 客户端，请运行 .venv\\Scripts\\python.exe scripts/install_scrcpy.py')
        return [str(executable), '--serial', self.serial, f'--display-id={display_id}',
                '--no-control', '--no-audio', '--no-clipboard-autosync', '--no-power-on',
                '--no-cleanup', '--force-adb-forward', '--max-fps=15', '--max-size=1280',
                '--video-bit-rate=2M', '--window-height=800',
                f'--window-title=BBUI | {name} | display {display_id} | VIEW ONLY']

    def status(self, name):
        with self.mutex:
            entry = self.entries.get(name)
            if not entry:
                return {'状态': '未打开', '只读': True}
            process, log, display_id = entry
            return {'状态': '观看中' if process.poll() is None else '已关闭', '只读': True,
                    '进程号': process.pid, '显示屏编号': display_id, '日志': str(log)}

    def open(self, name, display_id):
        with self.mutex:
            previous = self.entries.get(name)
            if previous and previous[2] == display_id and previous[0].poll() is None:
                return self.status(name)
            self.close(name)
            command = self._command(name, display_id)
            if os.name == 'nt' and self.job is None:
                from .windows_job import ViewerJob
                self.job = ViewerJob()
            folder = self.root / 'runs' / self.serial / 'mirrors'
            folder.mkdir(parents=True, exist_ok=True)
            log = folder / f'{name}-{uuid.uuid4().hex}.log'
            # Hide the console, not the explicitly requested SDL viewing window.
            with log.open('wb') as output:
                process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=output,
                                           stderr=subprocess.STDOUT, cwd=str(Path(command[0]).parent),
                                           env={**os.environ, 'ADB': adbutils.adb_path(),
                                                'SCRCPY_SERVER_PATH': str(Path(command[0]).parent / 'scrcpy-server')},
                                           creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
            self.entries[name] = process, log, display_id
            try:
                if self.job:
                    self.job.add(process)
                deadline = time.monotonic() + 15
                while time.monotonic() < deadline:
                    contents = log.read_text('utf-8', errors='replace')
                    if process.poll() is not None:
                        raise RuntimeError(f'scrcpy 镜像提前退出：{contents[-1500:]}')
                    # Texture is initialized only after video dimensions arrive.
                    if 'Texture:' in contents:
                        return self.status(name)
                    time.sleep(.1)
                raise RuntimeError(f'scrcpy 镜像等待画面超时，请检查 {log}')
            except BaseException:
                self.close(name)
                raise

    def close(self, name):
        with self.mutex:
            entry = self.entries.pop(name, None)
            if entry:
                process = entry[0]
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=3)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=3)

    def close_all(self):
        with self.mutex:
            for name in list(self.entries):
                self.close(name)
            if self.job:
                self.job.close()
                self.job = None
