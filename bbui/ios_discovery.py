"""Read-only USB discovery; never pair, mount an image, or open a tunnel."""
import asyncio
import json
import sys
from contextlib import suppress


async def device_name(serial):
    from pymobiledevice3.lockdown import create_using_usbmux
    client = None
    try:
        async with asyncio.timeout(3):
            client = await create_using_usbmux(serial=serial, connection_type='USB', autopair=False)
            value = await client.get_value(key='DeviceName')
            if isinstance(value, str) and value.strip() and len(value) <= 200:
                return value.strip()
    except Exception:
        pass  # Locked/untrusted devices remain selectable without initiating pairing.
    finally:
        if client:
            with suppress(Exception):
                await asyncio.wait_for(client.close(), 1)
    return 'iPhone · ' + serial[-8:]


async def discover():
    from pymobiledevice3 import usbmux
    devices = [device for device in await usbmux.list_devices() if device.is_usb]
    names = await asyncio.gather(*(device_name(device.serial) for device in devices))
    return [{'serial': device.serial, 'label': name, 'devicePlatform': 'ios'}
            for device, name in zip(devices, names)]


def discovery_error(error):
    if isinstance(error, ImportError):
        return {'code': 'runtime_missing', 'error': 'iPhone 运行依赖不完整，请重新解压完整的大鸟手机助手发行包'}
    from pymobiledevice3.exceptions import ConnectionFailedToUsbmuxdError
    if isinstance(error, (ConnectionFailedToUsbmuxdError, ConnectionRefusedError, FileNotFoundError)):
        return {'code': 'driver_missing', 'error': '无法访问 Apple 设备服务，请确认已安装 Apple Devices 或 Apple 移动设备支持'}
    return {'code': 'discovery_failed', 'error': str(error) or 'iPhone 设备发现失败，请重新连接后重试'}


if __name__ == '__main__':
    try:
        print(json.dumps(asyncio.run(discover()), ensure_ascii=True))
    except Exception as error:
        print(json.dumps(discovery_error(error)), file=sys.stderr)
        sys.exit(1)
