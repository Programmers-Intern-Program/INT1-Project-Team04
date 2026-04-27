"""도메인별 MCP 도구 모듈.

각 담당자는 이 디렉터리 안에 `<domain>.py` 파일을 만들고,
`from mcp_server.server import mcp` 로 인스턴스를 가져와 `@mcp.tool()` 로 도구를 등록한다.

이 패키지가 import 되는 시점에 leaf 모듈을 함께 import 해 도구가 mcp 인스턴스에
등록되게 한다 (server.py 가 직접 import 하면 순환). 도구 추가 시 아래 import 목록에
한 줄씩 추가한다.
"""

from mcp_server.tools import real_estate  # noqa: F401
