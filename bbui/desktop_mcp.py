"""Public MCP tools plus a separate authenticated, host-only control channel."""
import atexit
import asyncio
import hmac
import json
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from mcp.types import TextContent
from .unified_mcp import server, phone
from .desktop_device import DesktopDevice
from .channel_errors import host_error

desktop = DesktopDevice(phone)
atexit.register(desktop.close)


@server.tool()
async def system_action(group: str, operation: str, params: dict, actionId: str) -> list[TextContent]:
    try:
        result = await asyncio.to_thread(desktop.execute_system, group, operation, params, actionId)
    except (ValueError, RuntimeError, OSError) as error:
        result = {'执行': {'状态': '未派发'}, '观察': {'状态': '未请求'}, '错误': str(error)}
    return [TextContent(type='text', text=json.dumps(result, ensure_ascii=False))]


def start_host_channel():
    # This capability is passed only to Python, never to Pi or the renderer.
    token = os.environ.pop('BBUI_HOST_TOKEN')

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
                value = desktop.host(body['operation'], body.get('params', {}))
                status = 200
            except Exception as error:
                value, status = host_error(error), 409
            output = json.dumps(value, ensure_ascii=False).encode()
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
