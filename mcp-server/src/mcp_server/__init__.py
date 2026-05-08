"""지켜봐줄게 MCP 서버 패키지."""

from importlib.metadata import PackageNotFoundError, version

try:
    __version__ = version("mcp-server")
except PackageNotFoundError:
    # 설치되지 않은 상태 (editable 도 아닌 로컬 실행) 에선 unknown 표시.
    __version__ = "0.0.0+unknown"
