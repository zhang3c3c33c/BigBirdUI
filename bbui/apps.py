"""Read-only installed package inventory for the current Android user."""
import re
import time

import adbutils
from .app_policy import blocked_packages, package_allowed


def _shell(device, args):
    try:
        result = device.shell2(args, timeout=20)
    except (adbutils.AdbError, OSError) as error:
        raise RuntimeError(f'应用查询失败，请检查手机连接：{error}') from error
    if result.returncode:
        raise RuntimeError(f'应用查询失败：{result.output[:1000]}')
    return result.output.strip()


def _packages(output):
    packages = set()
    for line in output.splitlines():
        match = re.fullmatch(r'package:([A-Za-z0-9_.]+)', line.strip())
        if not match:
            raise RuntimeError(f'无法解析安装包列表：{line[:300]}')
        packages.add(match[1])
    return packages


def list_apps(serial, config, params):
    blocked = blocked_packages(config)
    keyword = params.get('关键词', '')
    include_system = params.get('包含系统应用', True)
    if not isinstance(keyword, str) or len(keyword) > 200:
        raise ValueError('关键词必须为不超过200字符的包名子串')
    if type(include_system) is not bool:
        raise ValueError('包含系统应用必须为布尔值')
    start = time.monotonic()
    device = adbutils.adb.device(serial)
    user = _shell(device, ['am', 'get-current-user'])
    if not re.fullmatch(r'\d+', user):
        raise RuntimeError(f'无法确认 Android 当前用户：{user[:300]}')
    installed = _packages(_shell(device, ['pm', 'list', 'packages', '--user', user]))
    system = _packages(_shell(device, ['pm', 'list', 'packages', '-s', '--user', user]))
    output = _shell(device, ['cmd', 'package', 'query-activities', '--brief', '--components',
                             '--user', user, '-a', 'android.intent.action.MAIN',
                             '-c', 'android.intent.category.LAUNCHER'])
    launchers = {}
    for line in output.splitlines():
        if line.strip() == 'No activities found':
            continue
        match = re.fullmatch(r'([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)', line.strip())
        if not match:
            raise RuntimeError(f'无法解析应用启动入口：{line[:300]}')
        launchers.setdefault(match[1], set()).add(line.strip())
    apps = [dict(包名=package, 系统应用=package in system, 允许操作=package_allowed(config, package),
                 可启动=bool(launchers.get(package)), 启动入口=sorted(launchers.get(package, [])))
            for package in sorted(installed)
            if (include_system or package not in system) and keyword.lower() in package.lower()]
    return {'状态': '应用已列出', '设备序列号': serial, '用户编号': int(user),
            '名称覆盖': '包名；未读取本地化名称', '实例覆盖': '当前 Android 用户；未枚举厂商分身',
            '应用策略': {'默认允许': True, '禁止包名': sorted(blocked)},
            '已安装总数': len(installed), '返回数量': len(apps), '应用列表': apps,
            '筛选': {'关键词': keyword, '包含系统应用': include_system},
            '查询耗时毫秒': round((time.monotonic() - start) * 1000),
            '说明': '当前 Android 用户的已安装包（含无桌面入口的服务）。可启动表示有 MAIN/LAUNCHER 入口，不保证启动成功；默认允许操作，仅 blocked_packages 中的完整包名被禁止。关键词仅匹配包名，不含应用中文名称。'}
