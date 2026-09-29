"""Download the pinned official scrcpy server and its license/protocol references."""
import concurrent.futures
import hashlib
from pathlib import Path
import requests

ROOT = Path(__file__).resolve().parent / 'vendor' / 'scrcpy'
VERSION = '4.1'
SHA = 'deacb991ed2509715160ffdc7907e47b4160eb30d1566217e9047fd5b8850cae'


def fetch(item):
    name, url = item
    r = requests.get(url, timeout=45)
    r.raise_for_status()
    if name == 'scrcpy-server-v4.1' and hashlib.sha256(r.content).hexdigest() != SHA:
        raise RuntimeError('Server digest mismatch')
    (ROOT / name).write_bytes(r.content)
    return name


if __name__ == '__main__':
    ROOT.mkdir(parents=True, exist_ok=True)
    base = 'https://raw.githubusercontent.com/Genymobile/scrcpy/v4.1/'
    files = [('scrcpy-server-v4.1', 'https://github.com/Genymobile/scrcpy/releases/download/v4.1/scrcpy-server-v4.1'),
             ('LICENSE', base + 'LICENSE'), ('develop.md', base + 'doc/develop.md')]
    for name, folder in [('Controller', 'control'), ('ControlMessageReader', 'control'),
                         ('DeviceMessageWriter', 'control'), ('DesktopConnection', 'device'),
                         ('FakeContext', ''), ('Options', '')]:
        files.append((name + '.java', base + 'server/src/main/java/com/genymobile/scrcpy/' +
                      (folder + '/' if folder else '') + name + '.java'))
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        print(list(pool.map(fetch, files)))
