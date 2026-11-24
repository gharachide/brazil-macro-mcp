"""Calls the Java brazil-macro-mcp server from Python, with the official MCP SDK.

MCP is a protocol, so the client does not care what language the server is written in.
This script starts the server over STDIO, lists its tools and calls two of them.

    pip install -r requirements.txt
    python client.py ../../target/brazil-macro-mcp.jar
"""

import asyncio
import json
import os
import sys

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client


def payload(result):
    """The tool's JSON result: structured content when present, else the text block."""
    if result.structured_content is not None:
        return result.structured_content
    return json.loads(result.content[0].text)


async def call(session, tool, arguments):
    result = await session.call_tool(tool, arguments)
    data = payload(result)
    if result.is_error:
        error = data["error"]
        raise RuntimeError(f"{tool}: {error['code']}: {error['message']}")
    return data


async def main(jar):
    server = StdioServerParameters(
        command="java",
        args=["-jar", jar, "--spring.profiles.active=stdio", "--logging.level.root=WARN"],
        env=dict(os.environ),  # keep JAVA_HOME and any proxy settings
    )
    async with stdio_client(server) as (read, write):
        async with ClientSession(read, write) as session:
            await session.initialize()

            tools = await session.list_tools()
            print("Tools:", ", ".join(tool.name for tool in tools.tools))

            for series in ("SELIC_TARGET", "IPCA_12M"):
                data = await call(session, "get_latest", {"series": series})
                latest = data["latest"]
                print(f"\n{data['series']['name']}: {latest['value']} ({latest['date']})")
                print(f"Source: {data['citation']['citeAs']}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("usage: python client.py path/to/brazil-macro-mcp.jar")
    asyncio.run(main(sys.argv[1]))
