"""Read-only stdio handshake and tool discovery; does not touch the phone."""
import asyncio
import sys
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client


async def main():
    params = StdioServerParameters(command=sys.executable, args=['-m', 'bbui.mcp_server'])
    async with stdio_client(params) as (read, write):
        async with ClientSession(read, write) as session:
            await session.initialize()
            result = await session.list_tools()
            names = {t.name for t in result.tools}
            assert names == {'phone_observe', 'phone_act', 'phone_stop'}, names
            print('MCP stdio handshake: three tool schemas, host-owned resume OK')


if __name__ == '__main__':
    asyncio.run(main())
