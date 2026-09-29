import atexit
import asyncio
import base64
import json
import io
from pathlib import Path
from mcp.server.mcpserver import MCPServer
from mcp.server.mcpserver.exceptions import ToolError
from mcp.types import ImageContent, TextContent
from pydantic import BaseModel, ConfigDict, Field, StrictInt
from .screens import ScreenRegistry

server = MCPServer('BBUI Unified Phone')
phone = ScreenRegistry()
atexit.register(phone.close)


class ActionParameters(BaseModel):
    model_config = ConfigDict(extra='allow')
    屏幕会话: str = Field(default='main', description='目标屏幕会话。main为主屏；创建屏幕时给定唯一英文名称。不同屏幕可并行。')
    截图编号: str | None = Field(default=None, description='动作必填本屏幕最新截图编号；查看/等待/屏幕管理无需提供。')
    关键词: str = Field(default='', max_length=200, description='列出应用：可选包名子串筛选，不匹配中文应用名。')
    包含系统应用: bool = Field(default=True, strict=True, description='列出应用：默认包含系统包，false仅返回非系统应用。')
    执行后等待毫秒: StrictInt = Field(
        default=0, ge=0, le=30000,
        description='操作后等待再截图，默认0。等待操作如指定时间，则仅等待时间，不叠加此参数。')


@server.tool()
async def phone_action(操作: str, 参数: ActionParameters) -> list[TextContent | ImageContent]:
    """手机原子操作，自动返回执行后截图。操作：查看/等待/点击/双击/长按/滑动/拖拽/放大/缩小/输入内容/删除内容/全选/按键/打开应用/系统面板。
    参数使用中文字段：位置或起点/终点=[x,y]，时间=毫秒；放大缩小=中心/初始指距/结束指距；输入内容=内容；按键=键名；打开应用=包名；系统面板=面板(通知/快捷设置/收起)。可选截图编号、动作编号、读取节点。
    执行后等待毫秒默认0；等待操作指定时间时不额外叠加等待。执行和观察分别报告，派发不表示业务成功。
    另支持创建屏幕(屏幕会话/可选包名宽度高度密度)、列出屏幕、关闭屏幕；屏幕管理不保证返回截图。main为主屏，不同屏幕可并行，同屏幕每步基于最新截图编号。
    列出应用：只读查询当前Android用户已安装包名、系统应用标记、允许操作标记、可启动标记及启动入口。可选关键词(包名子串)、包含系统应用(默认true)。无需截图编号，不返回截图，执行后等待毫秒填0。不会更改禁止列表。
    虚拟屏幕可切换应用，但不迁移其他会话的前台任务；暂不支持读取节点和设备全局操作。坐标是本屏截图原始像素。首次先查看。输入在当前光标处插入，不清空。已执行不等于业务成功，需看返回图片。结果不确定不能盲目重试。"""
    try:
        result = await asyncio.to_thread(phone.action,操作,参数.model_dump(exclude_unset=True))
    except (ValueError, RuntimeError, ConnectionError, TimeoutError) as error:
        result = {'状态': '未执行', '执行': {'状态': '未派发'}, '观察': {'状态': '未请求'}, '错误': str(error)}
    content = [TextContent(type='text',text=json.dumps(result,ensure_ascii=False))]
    if result.get('截图'):
        from PIL import Image
        output = io.BytesIO()
        with Image.open(result['截图']) as picture:
            picture.convert('RGB').save(output, 'JPEG', quality=85)
        content.append(ImageContent(type='image',mime_type='image/jpeg',data=base64.b64encode(output.getvalue()).decode()))
    return content


if __name__ == '__main__':
    server.run(transport='stdio')
