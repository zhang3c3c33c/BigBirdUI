"""Read-only MCP discovery of the unified two-argument tool."""
import asyncio
import sys
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

async def main():
    params=StdioServerParameters(command=sys.executable,args=['-m','bbui.unified_mcp'])
    async with stdio_client(params) as (read,write):
        async with ClientSession(read,write) as session:
            await session.initialize()
            result=await session.list_tools()
            assert len(result.tools)==1
            tool=result.tools[0]
            assert tool.name=='phone_action'
            schema=tool.model_dump(by_alias=True)
            inputs=schema.get('inputSchema',schema.get('input_schema'))
            props=inputs['properties']
            assert set(props)=={'操作','参数'},props
            parameters=props['参数']
            if '$ref' in parameters:
                parameters=inputs['$defs'][parameters['$ref'].split('/')[-1]]
            assert '执行后等待毫秒' not in parameters.get('required', []),parameters
            delay=parameters['properties']['执行后等待毫秒']
            assert delay['type']=='integer' and delay['minimum']==0 and delay['maximum']==30000,delay
            assert delay['default']==0,delay
            for value in ({'执行后等待毫秒': True}, {'执行后等待毫秒': '3000'}):
                rejected=await session.call_tool('phone_action',{'操作':'查看','参数':value})
                assert rejected.is_error,rejected
            print('Unified MCP: optional zero wait and invalid-call rejection OK')

if __name__=='__main__':
    asyncio.run(main())
