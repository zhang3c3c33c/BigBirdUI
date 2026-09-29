"""Install BBUI debug artifacts; acknowledge only their explicit vivo source dialog."""
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ADB = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe"
SERIAL = os.environ.get("ANDROID_SERIAL")
if not SERIAL:
    inventory = subprocess.run([str(ADB), "devices"], check=True, capture_output=True, text=True).stdout
    devices = [line.split()[0] for line in inventory.splitlines()[1:]
               if len(line.split()) == 2 and line.split()[1] == "device"]
    if len(devices) != 1:
        raise RuntimeError("Set ANDROID_SERIAL to select the installation target")
    SERIAL = devices[0]


def adb(*args, **kwargs):
    return subprocess.run([str(ADB), "-s", SERIAL, *args], **kwargs)


def tap(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.attrib["bounds"]))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2), check=True)


def install(relative, expected):
    artifact = ROOT / relative
    if not artifact.is_file():
        raise FileNotFoundError(artifact)
    process = subprocess.Popen([str(ADB), "-s", SERIAL, "install", "-r", "-t", str(artifact)],
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    deadline = time.monotonic() + 120
    acknowledged = False
    while process.poll() is None and time.monotonic() < deadline:
        if not acknowledged:
            adb("shell", "uiautomator", "dump", "/sdcard/bbui-install.xml", capture_output=True, timeout=15)
            xml = adb("shell", "cat", "/sdcard/bbui-install.xml", capture_output=True, text=True, encoding="utf-8").stdout
            try:
                nodes = list(ET.fromstring(xml).iter("node"))
                title = next((n for n in nodes if n.get("resource-id") == "com.android.packageinstaller:id/tv_app_name"), None)
                if title is not None and title.get("text") == expected:
                    checkbox = next((n for n in nodes if n.get("resource-id") == "com.android.packageinstaller:id/deleted_file_state_cb"), None)
                    button = next((n for n in nodes if n.get("content-desc") == "继续安装" and n.get("enabled") == "true"), None)
                    if checkbox is not None and button is not None:
                        if checkbox.get("checked") == "false":
                            tap(checkbox)
                        tap(button)
                        acknowledged = True
            except ET.ParseError:
                pass
        time.sleep(0.5)
    if process.poll() is None:
        process.terminate()
        raise RuntimeError("Installation outcome uncertain; inspect phone, do not retry automatically")
    output = process.communicate()[0]
    print(output.strip(), flush=True)
    if process.returncode or "Success" not in output:
        raise RuntimeError("BBUI installation failed")


if __name__ == "__main__":
    if "--tests-only" not in sys.argv:
        install("android/app/build/outputs/apk/debug/app-debug.apk", "BBUI")
    if "--app-only" not in sys.argv:
        install("android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk", "io.bbui.assistant.test")
