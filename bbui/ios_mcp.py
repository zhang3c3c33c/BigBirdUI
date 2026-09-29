"""iPhone MCP tools and authenticated host-only control channel."""
import atexit
import asyncio
import base64
import hmac
import io
import json
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from mcp.server.mcpserver import MCPServer
from mcp.types import TextContent, ImageContent
from PIL import Image
from .ios_runtime import IosDevice, verify_lease_released
from .ios_transport import describe_error, trace_stage
from .channel_errors import ChannelFailure, host_error

server = MCPServer('BBUI iPhone')
device = None


def get_device():
    global device
    if device is None:
        device = IosDevice()
        atexit.register(device.close)
    return device


def error_result(error):
    result = {'状态': '未执行', '执行': {'状态': '未派发'}, '观察': {'状态': '未请求'}, '错误': describe_error(error)}
    if isinstance(error, ChannelFailure):
        result['通道'] = {'控制': '断开'} if error.channel == 'control' else {'观察': '失败'}
    return result


def host_request(current, operation, params):
    label = operation if operation in {'status', 'stop', 'hold', 'manual', 'resume', 'frame', 'input', 'previewSources', 'shutdown'} else 'unknown'
    with trace_stage('host.' + label):
        if operation == 'status' and not current.connected:
            return current.status_snapshot()
        try:
            return current.call(current.host(operation, params))
        except Exception as error:
            if operation in {'input', 'hold', 'stop', 'manual'} and current.control_failed(error):
                raise ChannelFailure('control', describe_error(error)) from error
            raise


@server.tool()
async def phone_action(操作: str, 参数: dict) -> list[TextContent | ImageContent]:
    """iPhone 原子操作；坐标使用本屏原始截图整数像素，动作需要最新截图编号和唯一动作编号。
    操作：查看、等待、点击、双击、长按、滑动、拖拽、放大、缩小、输入内容、删除内容、全选、按键、打开应用、列出应用、列出屏幕。
    只有 main 主屏和正向竖屏输入。没有返回键、虚拟屏幕、节点或系统面板。执行、观察分别报告，派发不等于业务成功。
    """
    current = get_device()
    try:
        result = await asyncio.to_thread(current.call, current.action(操作, 参数))
    except Exception as error:
        result = error_result(error)
    images = []
    if result.get('截图'):
        try:
            output = io.BytesIO()
            with Image.open(result['截图']) as picture:
                picture.convert('RGB').save(output, 'JPEG', quality=85)
            images.append(ImageContent(type='image', mime_type='image/jpeg', data=base64.b64encode(output.getvalue()).decode()))
        except Exception as error:
            result = {**result, '观察': {'状态': '失败'}, '观察错误': '截图传输编码失败：' + str(error)}
    return [TextContent(type='text', text=json.dumps(result, ensure_ascii=False, default=str)), *images]


@server.tool()
async def system_action(group: str, operation: str, params: dict, actionId: str) -> list[TextContent]:
    current = get_device()
    try:
        result = await asyncio.to_thread(current.call, current.system(group, operation, params, actionId))
    except Exception as error:
        result = error_result(error)
    return [TextContent(type='text', text=json.dumps(result, ensure_ascii=False, default=str))]


def start_host_channel():
    token = os.environ.pop('BBUI_HOST_TOKEN')
    current = get_device()

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_POST(self):
            if self.path != '/control' or not hmac.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + token):
                self.send_error(403)
                return
            try:
                size = int(self.headers.get('Content-Length', '0'))
                if not 0 < size <= 131072:
                    raise ValueError('Invalid request size')
                body = json.loads(self.rfile.read(size))
                value = host_request(current, body['operation'], body.get('params', {}))
                status = 200
            except Exception as error:
                value, status = host_error(error, describe_error), 409
            output = json.dumps(value, ensure_ascii=False, default=str).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(output)))
            self.end_headers()
            self.wfile.write(output)

    channel = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    Path(os.environ['BBUI_HOST_ENDPOINT']).write_text(str(channel.server_port), encoding='ascii')
    threading.Thread(target=channel.serve_forever, daemon=True).start()
    atexit.register(channel.server_close)


if __name__ == '__main__':
    start_host_channel()
    server.run(transport='stdio')
