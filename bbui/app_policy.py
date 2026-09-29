"""Allow every valid package unless explicitly blocked by local configuration."""
import re


def valid_package(package):
    return isinstance(package, str) and re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*', package) is not None


def blocked_packages(config):
    packages = config.get('blocked_packages', [])
    if not isinstance(packages, list) or any(not valid_package(p) for p in packages):
        raise ValueError('blocked_packages 必须为完整包名字符串列表，不支持通配符')
    return set(packages)


def package_allowed(config, package):
    blocked = blocked_packages(config)
    return valid_package(package) and package not in blocked


def require_package_allowed(config, package):
    if not valid_package(package):
        raise ValueError('必须提供有效的完整应用包名')
    if not package_allowed(config, package):
        raise ValueError(f'应用已被 blocked_packages 禁止操作：{package}')
