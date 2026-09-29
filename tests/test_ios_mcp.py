import asyncio
import json
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

from bbui import ios_mcp
from bbui.ios_discovery import discover


class McpTests(unittest.IsolatedAsyncioTestCase):
    async def test_image_encoding_error_keeps_known_dispatch(self):
        async def action(operation, params):
            return {'执行': {'状态': '已派发'}, '观察': {'状态': '已取得'}, '截图': 'missing-screenshot.png'}
        device = SimpleNamespace(action=action, call=asyncio.run)
        with patch.object(ios_mcp, 'get_device', return_value=device):
            result = await ios_mcp.phone_action('点击', {})
        receipt = json.loads(result[0].text)
        self.assertEqual(receipt['执行']['状态'], '已派发')
        self.assertEqual(receipt['观察']['状态'], '失败')
        self.assertEqual(len(result), 1)

    async def test_discovery_filters_out_network_without_pairing(self):
        with patch('pymobiledevice3.usbmux.list_devices', AsyncMock(return_value=[
                SimpleNamespace(is_usb=True, serial='usb-1234'), SimpleNamespace(is_usb=False, serial='wifi-5678')])):
            devices = await discover()
        self.assertEqual([device['serial'] for device in devices], ['usb-1234'])
        self.assertEqual(devices[0]['devicePlatform'], 'ios')


if __name__ == '__main__':
    unittest.main()
