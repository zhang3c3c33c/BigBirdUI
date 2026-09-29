import asyncio
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

from bbui.ios_discovery import discover, device_name, discovery_error


class DiscoveryTests(unittest.IsolatedAsyncioTestCase):
    def test_errors_distinguish_missing_service_from_runtime_and_protocol_failure(self):
        from pymobiledevice3.exceptions import ConnectionFailedToUsbmuxdError
        self.assertEqual(discovery_error(ConnectionFailedToUsbmuxdError())['code'], 'driver_missing')
        self.assertEqual(discovery_error(ImportError('dependency'))['code'], 'runtime_missing')
        self.assertEqual(discovery_error(RuntimeError('bad reply'))['code'], 'discovery_failed')

    async def test_usb_name_is_read_without_pairing_and_client_is_closed(self):
        client = SimpleNamespace(get_value=AsyncMock(return_value='我的 iPhone'), close=AsyncMock())
        with patch('pymobiledevice3.lockdown.create_using_usbmux', AsyncMock(return_value=client)) as create:
            self.assertEqual(await device_name('USB-ID'), '我的 iPhone')
        create.assert_awaited_once_with(serial='USB-ID', connection_type='USB', autopair=False)
        client.get_value.assert_awaited_once_with(key='DeviceName')
        client.close.assert_awaited_once()

    async def test_name_failure_falls_back_and_closes_existing_client(self):
        client = SimpleNamespace(get_value=AsyncMock(side_effect=RuntimeError('locked')), close=AsyncMock())
        with patch('pymobiledevice3.lockdown.create_using_usbmux', AsyncMock(return_value=client)):
            self.assertEqual(await device_name('123456789'), 'iPhone · 23456789')
        client.close.assert_awaited_once()

    async def test_untrusted_connection_keeps_device_selectable(self):
        with patch('pymobiledevice3.lockdown.create_using_usbmux', AsyncMock(side_effect=RuntimeError('not trusted'))):
            self.assertEqual(await device_name('untrusted'), 'iPhone · ntrusted')

    async def test_only_usb_devices_are_named_and_reported(self):
        rows = [SimpleNamespace(serial='usb', is_usb=True), SimpleNamespace(serial='network', is_usb=False)]
        with patch('pymobiledevice3.usbmux.list_devices', AsyncMock(return_value=rows)), patch('bbui.ios_discovery.device_name', AsyncMock(return_value='Phone')) as name:
            self.assertEqual(await discover(), [{'serial': 'usb', 'label': 'Phone', 'devicePlatform': 'ios'}])
        name.assert_awaited_once_with('usb')


if __name__ == '__main__':
    unittest.main()
