"""Install the pinned official Windows viewer (no package manager or PATH changes)."""
import hashlib
from pathlib import Path
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
URL = 'https://github.com/Genymobile/scrcpy/releases/download/v4.1/scrcpy-win64-v4.1.zip'
SHA256 = '5b12172b3264b2889f4583ee64752ce832e29bc8b1089dca81093459697165db'


def main():
    target = ROOT / 'vendor' / 'scrcpy'
    archive = target / 'scrcpy-win64-v4.1.zip'
    target.mkdir(parents=True, exist_ok=True)
    if not archive.exists() or hashlib.sha256(archive.read_bytes()).hexdigest() != SHA256:
        request = urllib.request.Request(URL, headers={'User-Agent': 'BBUI-setup'})
        with urllib.request.urlopen(request, timeout=60) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != SHA256:
            raise RuntimeError('Official scrcpy archive SHA256 mismatch')
        archive.write_bytes(data)
    with zipfile.ZipFile(archive) as bundle:
        for entry in bundle.infolist():
            if not (target / entry.filename).resolve().is_relative_to(target.resolve()):
                raise RuntimeError('Unsafe archive path')
        bundle.extractall(target)
    print('Installed verified official scrcpy 4.1 Windows viewer')


if __name__ == '__main__':
    main()
