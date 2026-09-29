import hashlib
import importlib.util
import io
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, patch

from bbui.ios_ddi import DDI_HASHES, bundled_ddi, validate_ddi
from bbui.ios_transport import ConnectionFailure, IosTransport


class BundledDdiTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.directory = self.root / 'vendor' / 'ios-ddi'
        self.directory.mkdir(parents=True)
        self.contents = {name: ('fixture-' + name).encode() for name in DDI_HASHES}
        self.hashes = {name: hashlib.sha256(data).hexdigest() for name, data in self.contents.items()}
        for name, data in self.contents.items():
            (self.directory / name).write_bytes(data)
        self.enterContext(patch.dict(os.environ, {'BBUI_ASSET_ROOT': str(self.root)}))
        self.enterContext(patch.dict(DDI_HASHES, self.hashes, clear=True))
        self.downloader = self.enterContext(patch(
            'pymobiledevice3.services.mobile_image_mounter.auto_mount_personalized',
            side_effect=AssertionError('Runtime must not download DDI')))
        self.mounter = AsyncMock()
        self.mounter.__aenter__.return_value = self.mounter
        self.factory = self.enterContext(patch(
            'pymobiledevice3.services.mobile_image_mounter.PersonalizedImageMounter', return_value=self.mounter))
        self.transport = IosTransport('fixture', lambda *_: None)

    async def test_mounts_verified_packaged_files_without_download_or_global_cache(self):
        self.assertEqual(bundled_ddi(), self.directory)
        paths = validate_ddi(self.directory)
        await self.transport._mount_support('lockdown')
        self.mounter.mount.assert_awaited_once_with(*paths)
        self.mounter.__aexit__.assert_awaited_once()
        self.assertTrue(self.transport.mounted)
        self.downloader.assert_not_called()

    async def test_missing_or_corrupt_file_fails_before_mount_without_download(self):
        for name, data in self.contents.items():
            for corrupt in (False, True):
                with self.subTest(name=name, corrupt=corrupt):
                    file = self.directory / name
                    if corrupt:
                        file.write_bytes(b'corrupt')
                    else:
                        file.unlink()
                    with self.assertRaises(ConnectionFailure) as error:
                        await self.transport._mount_support('lockdown')
                    self.assertEqual(error.exception.code, 'support_files_missing')
                    self.factory.assert_not_called()
                    self.downloader.assert_not_called()
                    self.assertFalse(self.transport.mounted)
                    file.write_bytes(data)

    async def test_mount_race_and_failure_do_not_claim_ownership(self):
        from pymobiledevice3.exceptions import AlreadyMountedError
        self.mounter.mount.side_effect = AlreadyMountedError()
        await self.transport._mount_support('lockdown')
        self.assertFalse(self.transport.mounted)
        self.mounter.mount.side_effect = OSError('ticket unavailable')
        with self.assertRaises(OSError):
            await self.transport._mount_support('lockdown')
        self.assertFalse(self.transport.mounted)
        self.downloader.assert_not_called()

    async def test_confirmed_mount_retains_ownership_when_service_close_fails(self):
        self.mounter.__aexit__.side_effect = OSError('close failed')
        with self.assertRaises(OSError):
            await self.transport._mount_support('lockdown')
        self.assertTrue(self.transport.mounted)

    def test_build_reuses_valid_files_and_rejects_bad_download(self):
        spec = importlib.util.spec_from_file_location('prepare_ddi', Path(__file__).resolve().parents[1] / 'scripts/desktop/prepare-ddi.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with patch.object(module, 'urlopen') as fetch:
            module.prepare(self.directory)
            fetch.assert_not_called()
            target = self.directory / 'Image.trustcache'
            target.unlink()
            fetch.return_value = io.BytesIO(b'incorrect')
            with self.assertRaises(ValueError):
                module.prepare(self.directory)
            self.assertFalse(target.exists())
            fetch.return_value = io.BytesIO(self.contents[target.name])
            module.prepare(self.directory)
            self.assertTrue(fetch.call_args.args[0].endswith('/Image.dmg.trustcache'))
            validate_ddi(self.directory)
