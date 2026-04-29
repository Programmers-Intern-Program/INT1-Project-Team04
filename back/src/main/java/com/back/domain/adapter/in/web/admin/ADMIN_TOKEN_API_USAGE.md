# 관리자 토큰 대시보드 API 사용 가이드

## 개요

관리자가 토큰 시스템의 통계와 사용자별 토큰 현황을 조회할 수 있는 API입니다.

**⚠️ 주의**: 현재 인증/인가 없이 누구나 접근 가능합니다. 프로덕션 배포 전 관리자 권한 체크 필수!

---

## API 엔드포인트

### 1. 전체 토큰 통계 조회

**GET** `/api/admin/tokens/statistics`

전체 시스템의 토큰 통계를 조회합니다.

**Response**
```json
{
  "totalUsers": 150,
  "totalTokensGranted": 15000,
  "totalTokensUsed": 4500,
  "totalTokensRemaining": 10500,
  "activeUsers": 120,
  "averageTokensPerUser": 100.0
}
```

**필드 설명**
- `totalUsers`: 토큰을 보유한 전체 사용자 수
- `totalTokensGranted`: 누적 지급된 총 토큰
- `totalTokensUsed`: 누적 사용된 총 토큰
- `totalTokensRemaining`: 현재 남아있는 총 토큰
- `activeUsers`: 잔액이 0보다 큰 활성 사용자 수
- `averageTokensPerUser`: 사용자당 평균 지급 토큰

---

### 2. 전체 사용자 토큰 현황 조회

**GET** `/api/admin/tokens/users?page=0&size=20`

모든 사용자의 토큰 보유 현황을 페이징하여 조회합니다.

**Query Parameters**
- `page` (optional, default=0): 페이지 번호 (0부터 시작)
- `size` (optional, default=20): 페이지당 항목 수

**Response**
```json
{
  "users": [
    {
      "userId": 1,
      "email": "user1@example.com",
      "nickname": "사용자1",
      "balance": 90,
      "totalGranted": 100,
      "totalUsed": 10,
      "lastUpdatedAt": "2026-04-28T15:30:00",
      "createdAt": "2026-04-01T10:00:00"
    },
    {
      "userId": 2,
      "email": "user2@example.com",
      "nickname": "사용자2",
      "balance": 75,
      "totalGranted": 100,
      "totalUsed": 25,
      "lastUpdatedAt": "2026-04-28T14:20:00",
      "createdAt": "2026-04-02T11:00:00"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 2
}
```

**필드 설명**
- `userId`: 사용자 ID
- `email`: 이메일
- `nickname`: 닉네임
- `balance`: 현재 토큰 잔액
- `totalGranted`: 누적 지급 토큰
- `totalUsed`: 누적 사용 토큰
- `lastUpdatedAt`: 마지막 토큰 변동 시간
- `createdAt`: 토큰 계정 생성 시간

---

### 3. 최근 토큰 사용 내역 조회

**GET** `/api/admin/tokens/history/recent?limit=100`

모든 사용자의 최근 토큰 사용 내역을 시간 역순으로 조회합니다.

**Query Parameters**
- `limit` (optional, default=100): 조회할 최대 항목 수

**Response**
```json
{
  "history": [
    {
      "id": "hist-uuid-1",
      "userId": 1,
      "userEmail": "user1@example.com",
      "userNickname": "사용자1",
      "usageType": "USE",
      "amount": 10,
      "balanceBefore": 100,
      "balanceAfter": 90,
      "description": "AI 자연어 파싱: 강남 집값...",
      "sessionId": "session-uuid-1",
      "createdAt": "2026-04-28T15:30:00"
    },
    {
      "id": "hist-uuid-2",
      "userId": 2,
      "userEmail": "user2@example.com",
      "userNickname": "사용자2",
      "usageType": "GRANT",
      "amount": 100,
      "balanceBefore": 0,
      "balanceAfter": 100,
      "description": "신규 가입 웰컴 토큰",
      "sessionId": null,
      "createdAt": "2026-04-28T14:00:00"
    }
  ],
  "totalCount": 2
}
```

**필드 설명**
- `id`: 히스토리 ID
- `userId`: 사용자 ID
- `userEmail`: 사용자 이메일
- `userNickname`: 사용자 닉네임
- `usageType`: 토큰 사용 유형 (`USE`, `GRANT`)
- `amount`: 토큰 증감량
- `balanceBefore`: 변경 전 잔액
- `balanceAfter`: 변경 후 잔액
- `description`: 사용 내역 설명
- `sessionId`: 파싱 세션 ID (있는 경우)
- `createdAt`: 기록 생성 시간

---

## 사용 예시 (curl)

### 1. 전체 통계 조회
```bash
curl -X GET http://localhost:8080/api/admin/tokens/statistics
```

### 2. 사용자 토큰 현황 조회 (2페이지, 50개씩)
```bash
curl -X GET "http://localhost:8080/api/admin/tokens/users?page=1&size=50"
```

### 3. 최근 200개 사용 내역 조회
```bash
curl -X GET "http://localhost:8080/api/admin/tokens/history/recent?limit=200"
```

---

## TODO

### 보안
- [ ] 관리자 인증/인가 추가 (`@PreAuthorize("hasRole('ADMIN')")` 등)
- [ ] IP 화이트리스트 설정
- [ ] Rate Limiting 적용

### 기능 개선
- [ ] 날짜 범위 필터링 (특정 기간 통계)
- [ ] 사용자 검색 (이메일, 닉네임)
- [ ] 토큰 사용 유형별 필터링
- [ ] CSV/Excel 내보내기 기능
- [ ] 실시간 대시보드 (WebSocket)

### 모니터링
- [ ] 비정상 토큰 사용 패턴 감지 알림
- [ ] 일일/주간 통계 리포트 자동 생성
