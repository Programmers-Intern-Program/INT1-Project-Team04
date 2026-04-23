"""도메인별 MCP 도구 모듈.

각 담당자는 이 디렉터리 안에 `<domain>.py` 파일을 만들고,
`from mcp_server.server import mcp` 로 인스턴스를 가져와 `@mcp.tool()` 로 도구를 등록한다.
자세한 규약은 mcp-server/README.md 참조.
"""
