import asyncio
import copy
import struct
import time
import unittest
from unittest.mock import AsyncMock, patch

from bbui.ios_transport import IosTransport, afc_operation, afc_path, touch_report, clipboard_version


class FakeAfc:
    def __init__(self):
        self.files = {'/a': b'hello', '/emoji': 'a😀b'.encode()}
        self.dirs = {'/', '/folder'}
        self.calls = []
        self.handles = {}

    async def exists(self, path):
        return path in self.files or path in self.dirs

    async def isdir(self, path):
        return path in self.dirs

    async def stat(self, path):
        if not await self.exists(path):
            raise FileNotFoundError(path)
        return {'st_size': len(self.files.get(path, b'')), 'st_ifmt': 'S_IFDIR' if path in self.dirs else 'S_IFREG'}

    async def listdir(self, path):
        prefix = path.rstrip('/') + '/'
        return sorted(p[len(prefix):] for p in self.files.keys() | self.dirs if p.startswith(prefix) and p != path and '/' not in p[len(prefix):])

    async def get_file_contents(self, path):
        return self.files[path]

    async def set_file_contents(self, path, data):
        self.calls.append(('write', path))
        self.files[path] = data

    async def makedirs(self, path):
        self.dirs.add(path)

    async def fopen(self, path, mode):
        handle = len(self.handles) + 1
        self.handles[handle] = [path, 0]
        if mode == 'w':
            self.files[path] = b''
        return handle

    async def fread(self, handle, size):
        path, offset = self.handles[handle]
        self.handles[handle][1] += size
        return self.files[path][offset:offset + size]

    async def fwrite(self, handle, data):
        self.files[self.handles[handle][0]] += data

    async def fclose(self, handle):
        pass

    async def rename(self, path, target):
        self.files[target] = self.files.pop(path)

    async def rm_single(self, path):
        self.calls.append(('delete', path))
        if path in self.files:
            del self.files[path]
        else:
            self.dirs.remove(path)

    async def walk(self, path):
        yield path, [], await self.listdir(path)


class TransportTests(unittest.IsolatedAsyncioTestCase):
    async def test_cleanup_bounds_unmount_and_keeps_unconfirmed_ownership(self):
        transport = IosTransport('usb', lambda *_: None)
        transport.mounted = True
        cancelled = asyncio.Event()
        async def blocked_unmount():
            try:
                await asyncio.Event().wait()
            finally:
                cancelled.set()
        transport._unmount_owned_image = blocked_unmount
        with patch('bbui.ios_transport.SERVICE_CLEANUP_TIMEOUT', .02):
            start = time.perf_counter()
            with self.assertRaisesRegex(RuntimeError, '退出清理未确认完成'):
                await transport.close()
            self.assertLess(time.perf_counter() - start, 1)
        self.assertTrue(cancelled.is_set())
        self.assertTrue(transport.mounted)

    async def test_cleanup_has_overall_deadline(self):
        transport = IosTransport('usb', lambda *_: None)
        async def blocked_cleanup():
            await asyncio.Event().wait()
        transport._cleanup = blocked_cleanup
        with patch('bbui.ios_transport.CLEANUP_TIMEOUT', .02):
            with self.assertRaisesRegex(RuntimeError, '超过总期限'):
                await transport.close()

    async def test_lockdown_close_is_bounded_after_confirmed_unmount(self):
        transport = IosTransport('usb', lambda *_: None)
        transport.mounted = True
        async def blocked_close():
            await asyncio.Event().wait()
        lockdown = AsyncMock()
        lockdown.close.side_effect = blocked_close
        mounter = AsyncMock()
        mounter.__aenter__.return_value = mounter
        with patch('pymobiledevice3.lockdown.create_using_usbmux', AsyncMock(return_value=lockdown)), \
                patch('pymobiledevice3.services.mobile_image_mounter.MobileImageMounterService', return_value=mounter), \
                patch('bbui.ios_transport.LOCKDOWN_CLEANUP_TIMEOUT', .02):
            with self.assertRaises(TimeoutError):
                await transport._unmount_owned_image()
        self.assertFalse(transport.mounted)
        mounter.unmount_image.assert_awaited_once_with('/System/Developer')

    async def test_orientation_reuses_service_but_reads_every_call_serially(self):
        class FakeSpringboard:
            created = entered = exited = active = max_active = calls = 0
            def __init__(self, rsd):
                type(self).created += 1

            async def __aenter__(self):
                type(self).entered += 1
                return self

            async def __aexit__(self, *exc):
                type(self).exited += 1

            async def get_interface_orientation(self):
                kind = type(self)
                kind.active += 1
                kind.max_active = max(kind.max_active, kind.active)
                kind.calls += 1
                value = [1, 3, 1][kind.calls - 1]
                await asyncio.sleep(.005)
                kind.active -= 1
                return value

        transport = IosTransport('usb', lambda *_: None)
        with patch('pymobiledevice3.services.springboard.SpringBoardServicesService', FakeSpringboard):
            values = await asyncio.gather(*(transport.orientation() for _ in range(3)))
        await transport.close()
        self.assertEqual(values, [1, 3, 1])
        self.assertEqual((FakeSpringboard.created, FakeSpringboard.entered, FakeSpringboard.exited), (1, 1, 1))
        self.assertEqual(FakeSpringboard.max_active, 1)
        self.assertEqual(FakeSpringboard.calls, 3)

    async def test_orientation_failure_does_not_return_previous_value(self):
        transport = IosTransport('usb', lambda *_: None)
        transport.springboard = AsyncMock()
        transport.springboard.get_interface_orientation.side_effect = [1, OSError('read failed')]
        self.assertEqual(await transport.orientation(), 1)
        with self.assertRaises(OSError):
            await transport.orientation()

    async def test_touch_packet_tracks_five_contacts_and_release(self):
        packet = touch_report([(.1, .2), (.3, .4), (.5, .6), (.7, .8), (.9, 1)])
        self.assertEqual(len(packet), 58)
        self.assertEqual(packet[:3], bytes([9, 5, 5]))
        self.assertEqual(packet[40:45], bytes([2, 3, 4, 5, 6]))
        release = touch_report([(.1, .2)], True)
        self.assertEqual(release[3], 2)
        self.assertEqual(touch_report([])[1], 0)

    async def test_touch_cancel_releases_contacts(self):
        transport = IosTransport('usb', lambda *_: None)
        transport.hid = AsyncMock()
        count = 0
        def guard():
            nonlocal count
            count += 1
            if count == 2:
                raise RuntimeError('STOP')
        with self.assertRaises(RuntimeError):
            await transport.gesture([((.1, .1), (.2, .2))], .05, guard)
        packets = [call.args[1] for call in transport.hid.send_report.await_args_list]
        self.assertTrue(packets[0][3] & 0xc0)
        self.assertEqual(packets[-2][3], 2)
        self.assertEqual(packets[-1][1], 0)

    async def test_clipboard_restore_and_intervening_user_copy(self):
        for user_copy in (False, True):
            transport = IosTransport('usb', lambda *_: None)
            original = {'pasteboard': {'items': [{'data': {'text': {'data': b'original'}}}], 'metadata': {'pasteboardName': 'general', 'nonce': 'A', 'changeCount': 1}}}
            temporary = {'pasteboard': {'items': [{'data': {'text': {'data': b'temporary'}}}], 'metadata': {'pasteboardName': 'general', 'nonce': 'B', 'changeCount': 2}}}
            current = copy.deepcopy(temporary)
            if user_copy:
                current['pasteboard']['metadata']['changeCount'] = 3
            calls = []
            snapshots = iter([original, current])
            async def clipboard(operation, value=None):
                calls.append((operation, value))
                if operation == 'snapshot':
                    return next(snapshots)
                if operation == 'write':
                    return temporary
            transport.clipboard = clipboard
            transport.key = AsyncMock()
            await transport.text('temporary', lambda: None)
            restores = [value for op, value in calls if op == 'restore']
            self.assertEqual(restores, [] if user_copy else [original])

    async def test_clipboard_restore_failure_keeps_successful_paste(self):
        transport = IosTransport('usb', lambda *_: None)
        original = {'pasteboard': {'items': []}}
        temporary = {'pasteboard': {'items': [{'data': {}}], 'metadata': {'pasteboardName': 'general', 'nonce': 'B', 'changeCount': 2}}}
        first = True
        async def clipboard(operation, value=None):
            nonlocal first
            if operation == 'write':
                return temporary
            if operation == 'restore':
                raise OSError('restore failed')
            if first:
                first = False
                return original
            return temporary
        transport.clipboard = clipboard
        transport.key = AsyncMock()
        await transport.text('hello', lambda: None)
        transport.key.assert_awaited_once()
        self.assertIn('恢复原剪贴板失败', transport.warnings[0])

    async def test_clipboard_unresolved_data_rejected_before_write(self):
        transport = IosTransport('usb', lambda *_: None)
        transport.clipboard = AsyncMock(return_value={'items': [{'data': {'uti': {'isPromised': True}}}]})
        with self.assertRaises(ValueError):
            await transport.text('hello', lambda: None)
        self.assertEqual(transport.clipboard.await_count, 1)

    async def test_text_write_reply_failure_still_checks_cleanup(self):
        transport = IosTransport('usb', lambda *_: None)
        transport.clipboard = AsyncMock(side_effect=[{'items': []}, OSError('write reply lost'), {'items': []}])
        with self.assertRaises(OSError):
            await transport.text('hello', lambda: None)
        self.assertEqual(transport.clipboard.await_count, 3)
        self.assertTrue(transport.warnings)

    async def test_real_set_reply_omits_data_but_retains_atomic_version(self):
        transport = IosTransport('usb', lambda *_: None)
        metadata = {'pasteboardName': 'general', 'nonce': 'B', 'changeCount': 27}
        written = {'command': 'SET_REPLY', 'pasteboard': {'metadata': metadata, 'sourceMetadata': metadata,
            'items': [{'types': ['public.text'], 'data': {'public.text': {}}}]}}
        pulled = {'command': 'PULL_REPLY', 'pasteboard': {'metadata': metadata, 'sourceMetadata': metadata,
            'items': [{'types': ['public.text'], 'data': {'public.text': {'data': b'hello'}}}]}}
        original = {'items': []}
        transport.clipboard = AsyncMock(side_effect=[original, written, pulled, None])
        transport.key = AsyncMock()
        await transport.text('hello', lambda: None)
        self.assertEqual(transport.clipboard.await_args_list[-1].args, ('restore', original))
        self.assertEqual(clipboard_version(written), clipboard_version(pulled))

    async def test_incomplete_set_version_does_not_adopt_later_user_copy(self):
        transport = IosTransport('usb', lambda *_: None)
        later = {'pasteboard': {'metadata': {'pasteboardName': 'general', 'nonce': 'user', 'changeCount': 28}, 'items': []}}
        transport.clipboard = AsyncMock(side_effect=[{'items': []}, {'pasteboard': {'items': []}}, later])
        transport.key = AsyncMock()
        await transport.text('hello', lambda: None)
        self.assertEqual(transport.clipboard.await_count, 3)
        self.assertTrue(transport.warnings)

    async def test_afc_rejects_host_parent_and_missing_paths(self):
        for path in (None, 'D:/data', '../private', '/folder/../a', '\\phone\\a'):
            with self.assertRaises(ValueError):
                afc_path(path)
        with self.assertRaises(ValueError):
            await afc_operation(FakeAfc(), 'copy', {'path': '/a'})

    async def test_afc_default_does_not_overwrite(self):
        afc = FakeAfc()
        with self.assertRaises(ValueError):
            await afc_operation(afc, 'write_text', {'path': '/a', 'text': 'changed'})
        self.assertEqual(afc.files['/a'], b'hello')
        await afc_operation(afc, 'write_text', {'path': '/a', 'text': 'changed', 'overwrite': True})
        self.assertEqual(afc.files['/a'], b'changed')

    async def test_afc_list_and_utf16_pagination(self):
        afc = FakeAfc()
        listing = await afc_operation(afc, 'list', {'path': '/', 'offset': 1, 'limit': 1})
        self.assertEqual(listing['nextOffset'], 2)
        page1 = await afc_operation(afc, 'read_text', {'path': '/emoji', 'limit': 2})
        self.assertEqual(page1['text'], 'a')
        page2 = await afc_operation(afc, 'read_text', {'path': '/emoji', 'offset': page1['nextOffset'], 'limit': 2})
        self.assertEqual(page2['text'], '😀')

    async def test_afc_root_mutations_rejected(self):
        for op in ('write_text', 'delete', 'mkdir', 'copy', 'move', 'rename'):
            with self.assertRaises(ValueError):
                await afc_operation(FakeAfc(), op, {'path': '/', 'destination': '/other'})

    async def test_afc_recursive_copy_and_stop_between_writes(self):
        afc = FakeAfc()
        afc.files['/folder/1'] = b'one'
        afc.files['/folder/2'] = b'two'
        count = 0
        def guard():
            nonlocal count
            count += 1
            if count == 4:
                raise RuntimeError('STOP')
        with self.assertRaises(RuntimeError):
            await afc_operation(afc, 'copy', {'path': '/folder', 'destination': '/new', 'recursive': True}, guard)
        self.assertIn('/new/1', afc.files)
        self.assertNotIn('/new/2', afc.files)


if __name__ == '__main__':
    unittest.main()
