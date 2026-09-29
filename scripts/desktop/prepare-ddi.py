"""Fetch pinned DDI at build time; the installed app never downloads images."""
import hashlib
import sys
from pathlib import Path
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from bbui.ios_ddi import DDI_HASHES, DDI_SOURCE, validate_ddi


def prepare(directory):
    directory.mkdir(parents=True, exist_ok=True)
    for name, expected in DDI_HASHES.items():
        target = directory / name
        if target.is_file() and hashlib.sha256(target.read_bytes()).hexdigest() == expected:
            continue
        remote_name = 'Image.dmg.trustcache' if name == 'Image.trustcache' else name
        with urlopen(f'{DDI_SOURCE}/{remote_name}', timeout=120) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != expected:
            raise ValueError(f'DDI checksum mismatch: {name}')
        temporary = target.with_suffix(target.suffix + '.tmp')
        temporary.write_bytes(data)
        temporary.replace(target)
    validate_ddi(directory)


if __name__ == '__main__':
    prepare(ROOT / 'vendor' / 'ios-ddi')
