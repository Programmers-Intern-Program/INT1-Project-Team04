"""`python -m mcp_server` 표준 엔트리포인트.

`python -m mcp_server.server` 로 실행하면 server.py 가 `__main__` 으로 로드되어
도구 모듈의 `from mcp_server.server import mcp` 와 별개의 FastMCP 인스턴스가
생긴다 (`__main__.mcp` ≠ `mcp_server.server.mcp`). 결과적으로 SSE 클라이언트의
list_tools 가 빈 배열을 받는다.

이 파일이 있으면 `python -m mcp_server` 가 표준 명령이 되고, server 는 항상
`mcp_server.server` 로만 import 되어 인스턴스가 단일화된다.
"""

from mcp_server.server import main

if __name__ == "__main__":
    main()
