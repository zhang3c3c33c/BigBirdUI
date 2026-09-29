"""Pinned iPhone developer support files shared by runtime and desktop build."""
import hashlib
from pathlib import Path

from .paths import assets

DDI_BUILD = '27A5194q'
DDI_SOURCE = 'https://raw.githubusercontent.com/doronz88/DeveloperDiskImage/6af851ace2375489705c0c8025933b805094ad42/PersonalizedImages/Xcode_iOS_DDI_Personalized'
DDI_HASHES = {
    'Image.dmg': '686d4b4999260edcaa44b71e206c417642c89ae46e3d5ce99b2be5a35912ed6f',
    'BuildManifest.plist': 'f5e92cf450193c7ddd0ed82212ce2a7a65293a3ae10af3172d0b2172a2226b62',
    'Image.trustcache': 'a7be363b4b36c0f3ea1e43618b6c7f1a2ee626067a68083c4801d833b30ac6ea',
}


def bundled_ddi():
    return assets(Path(__file__).resolve().parents[1]) / 'vendor' / 'ios-ddi'


def validate_ddi(directory):
    paths = tuple(Path(directory) / name for name in DDI_HASHES)
    for file in paths:
        if hashlib.sha256(file.read_bytes()).hexdigest() != DDI_HASHES[file.name]:
            raise ValueError(f'DDI checksum mismatch: {file.name}')
    return paths
