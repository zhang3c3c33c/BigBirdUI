"""Storage and resource paths shared by GUI and CLI."""
import hashlib
import os
from pathlib import Path


def device_state(root, serial):
    override = os.environ.get('BBUI_DEVICE_STATE_DIR')
    if not override and Path(root).resolve() != Path(__file__).resolve().parents[1]:
        return Path(root) / 'runs' / serial
    base = Path(override) if override else Path(os.environ.get('LOCALAPPDATA', Path.home() / '.local' / 'share')) / 'BBUI' / 'devices'
    return base / hashlib.sha256(serial.encode()).hexdigest()[:24]


def assets(root):
    return Path(os.environ.get('BBUI_ASSET_ROOT', root))
