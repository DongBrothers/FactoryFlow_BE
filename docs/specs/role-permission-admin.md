# 역할 권한 관리 (관리자) + gateway 권한 표 동기화

## 목적
관리자가 화면에서 역할의 권한을 바꾸고, 바꾼 권한이 **1초 안팎**에 gateway 에 반영되게 한다.
지금(#171)은 권한이 Access 토큰의 `permissions` 클레임에 실려 있어, 바꾼 권한은 토큰이 갱신될 때(최대 15분) 반영된다.

- gateway 는 역할 → 권한 표를 **메모리(Map)** 에 들고, 토큰의 `role` 로 권한을 찾는다. 권한 검사(`hasAuthority`)와 API → 권한 표는 그대로다
- 표는 **Redis** 로 받는다. gateway 는 auth 를 부르지 않는다
- 역할·권한의 원본은 그대로 auth DB(`role`, `permission`, `role_permission`, docs/specs/role-permissions.md) 하나다. Redis 키와 gateway Map 은 복사본이다
- 토큰에서 `permissions` 를 뺀다. 프론트는 내 정보 API(`GET /api/auth/me`)로 권한을 받아 메뉴를 고른다

### 이번 범위
- 관리자: 역할별 권한 조회, 권한 목록, 역할의 권한 바꾸기
- auth → Redis 표 동기화, gateway 의 표 수신과 권한 판단
- 내 정보 `GET /api/auth/me` (docs/handoff/frontend-api-gaps.md 1번을 이 문서로 옮긴다)

### 범위 밖
- 역할 추가·삭제를 화면에서 하기 (역할은 지금처럼 마이그레이션으로만. 삭제는 그 역할을 쓰는 계정 처리까지 정해야 한다)
- 권한 종류(코드)를 화면에서 만들기. 권한 코드는 gateway 가 검사해야 의미가 있으므로 마이그레이션 + gateway 코드로만 더한다
- 직원 한 사람의 역할 바꾸기 (API 없음)

## 흐름

### 1. 역할 목록 + 권한 (관리자) — `GET /api/auth/admin/roles`
지금 응답(#171, 발급 화면의 역할 선택지)에 `permissions` 를 더한다 (필드 추가만, 발급 화면은 그대로 쓴다).
```json
[
  { "code": "SALES", "name": "영업", "permissions": ["READ", "ORDER_WRITE"] },
  { "code": "ADMIN", "name": "관리자", "permissions": ["ACCOUNT_ADMIN"] }
]
```
- 역할은 `sort_order` 순, `permissions` 는 권한 코드 순

### 2. 권한 목록 (관리자) — `GET /api/auth/admin/permissions`
권한 편집 화면의 체크박스 선택지. 몇 개뿐인 기준 정보라 페이지 없음, 코드 순.
```json
[ { "code": "ACCOUNT_ADMIN", "name": "계정 관리" }, { "code": "ORDER_WRITE", "name": "주문 쓰기" } ]
```

### 3. 역할의 권한 바꾸기 (관리자) — `PUT /api/auth/admin/roles/{code}/permissions`
```json
{ "permissions": ["READ", "ORDER_WRITE", "PURCHASE_WRITE"] }
```
1. 요청 형식은 Bean Validation (`permissions` 는 null 이 아닌 목록, 원소는 빈 값 아님. 빈 목록은 허용 = 권한 없는 역할)
2. 역할이 없으면 404 `ROLE_NOT_FOUND`
3. 없는 권한 코드가 있으면 400 `INVALID_PERMISSION` (같은 코드가 여러 번 오면 한 번으로 본다)
4. 바꾼 뒤 `ACCOUNT_ADMIN` 을 가진 역할이 하나도 없게 되면 409 `ACCOUNT_ADMIN_REQUIRED` (아무도 권한을 되돌릴 수 없게 되므로)
5. 그 역할의 `role_permission` 을 요청한 목록으로 **통째로 바꾼다** (트랜잭션 하나, 저장은 별도 Writer)
6. 트랜잭션이 끝난 뒤 4번 "표 동기화" 로 Redis 에 쓴다
7. `200` + 바뀐 역할 `{ code, name, permissions }`

- 같은 목록으로 다시 보내도 같은 결과다 (멱등). Redis 쓰기가 실패하면 같은 내용으로 다시 저장하면 된다 (아래 규칙)

### 4. 표 동기화 (auth → Redis)
| 이름 | 종류 | 값 |
|---|---|---|
| `auth:role-permissions` | 키 (TTL 없음) | 표 전체 JSON `{"SALES": ["ORDER_WRITE", "READ"], "ADMIN": ["ACCOUNT_ADMIN"], ...}` (권한이 없는 역할은 빈 배열) |
| `auth:role-permissions-changed` | pub/sub 채널 | 키와 같은 JSON (표 전체). 신호이면서 새 표다 |

auth 는 DB 에서 표 전체를 읽어 **키에 쓰고 채널에 발행**한다. 하는 때:
- auth 가 뜰 때 (Flyway 다음, 요청을 받기 전)
- 역할의 권한을 바꾼 뒤 (3번)
- auth 가 Redis 에 다시 연결됐을 때 (Redis 가 재시작해 키가 사라졌을 수 있으므로)

### 5. gateway 의 표 받기와 권한 판단
1. 뜰 때 `auth:role-permissions` 키를 읽어 Map 을 채우고, `auth:role-permissions-changed` 를 구독한다
2. 채널에 표가 오면 새 Map 을 만들어 **통째로 바꿔 끼운다** (요청 처리 중 반쯤 바뀐 표를 보지 않게)
3. Redis 구독이 끊겼다가 다시 붙으면 키를 다시 읽는다 (끊긴 동안 온 신호를 놓쳤을 수 있으므로)
4. 요청마다: 토큰의 `role` → Map 에서 권한 → 지금 API → 권한 표로 `hasAuthority` 판단. DB·auth·Redis 를 부르지 않는다
- 평소에는 Redis 를 주기적으로 읽지 않는다 (권한은 드물게 바뀐다). 다시 읽는 것은 위 1·3번뿐이다

### 6. 토큰과 내 정보
- Access 토큰 claim: `sub`, `role` 만. `permissions` 를 뺀다 (gateway 가 쓰지 않는 두 번째 원본이 되어 최대 15분 어긋나므로)
- 내 정보 `GET /api/auth/me` → `200`
  ```json
  { "publicId": "…", "name": "김철수", "role": "SALES", "permissions": ["ORDER_WRITE", "READ"] }
  ```
  - `X-User-Id`(gateway 가 JWT sub 로 채움)로 계정을 찾는다. 권한은 DB 에서 읽는다 (gateway Map 과 같은 원본)
  - 이메일은 넣지 않는다 (화면에 필요 없음, 개인정보)
  - gateway: 로그인한 직원 모두 (ADMIN 포함, 권한 없이). 일반 GET(READ) 규칙보다 앞에 둔다

## 규칙 / 예외
| 상황 | 처리 |
|---|---|
| gateway 가 뜰 때 Redis 키가 없거나 읽지 못함 | Map 이 비어 공개 경로 말고는 모두 403 (권한을 모를 때 열지 않는다). 키를 읽을 때까지 몇 초마다 다시 읽고, 채널로 표가 오면 바로 채운다 |
| 떠 있는 gateway 와 Redis 연결이 끊김 | 들고 있는 Map 으로 계속 판단한다. 다시 붙으면 키를 다시 읽는다 |
| 3번 저장 뒤 Redis 쓰기 실패 | DB 는 바뀌었다. 503 `ROLE_PERMISSION_SYNC_UNAVAILABLE` ("저장했지만 반영하지 못했다, 같은 내용으로 다시 저장하라"). 다시 저장하면 다시 쓴다 |
| 토큰의 `role` 이 표에 없음 (없어진 역할, WORKER 등) | 권한 없음 → 공개 경로와 내 정보 말고 모두 403 |
| 운영 중 권한을 DB 에 직접(SQL) 고침 | 하지 않는다. 관리자 API 로만 바꾼다. 고쳤다면 auth 를 재시작하면 표를 다시 쓴다 |
| 마이그레이션으로 역할·권한을 더함 | auth 배포(재시작) 때 표를 다시 쓴다 |
| 직원 한 사람의 역할이 바뀜 | 토큰의 `role` 이 바뀌어야 하므로 다음 재발급 때(최대 15분) |
| 내 정보에 `X-User-Id` 가 없음 (gateway 를 거치지 않은 요청) | 400 (공통 핸들러) |
| 내 정보의 계정이 없음 | 404 `USER_NOT_FOUND` |

### 배포
- auth 와 gateway 를 같은 배포에서 함께 바꾼다 (merge 하면 CD 가 둘을 함께 배포한다. 운영 데이터 없음, dev 만)
- gateway 가 auth 보다 먼저 뜨면 키가 없어 잠깐 모두 403 이다. auth 가 뜨면서 표를 발행하면 gateway 가 받아 바로 풀린다
- 배포 전 #171 토큰(`permissions` 있음)도 `role` 이 있으므로 새 gateway 에서 그대로 통한다

### 사람이 할 일
- GitHub 시크릿 `APP_DEV_YML_GATEWAY` 에 `spring.data.redis.host`·`port` (auth 의 `APP_DEV_YML_AUTH` 와 같은 Redis)
- dev 보안그룹에서 gateway → Redis(6379) 허용
- 프론트: 권한 편집 화면 (Figma 에 없음), `/api/auth/me` 의 `permissions` 로 메뉴·첫 화면 고르기

### 테이블 변경
없음 (#171 의 V5·V6 그대로). 바뀌는 것은 규칙뿐이다: `role_permission` 행은 운영 중 관리자 API 로 바꾼다 (지금까지는 마이그레이션으로만)

## 관련 이벤트 (events.md 참조)
없음. Redis 채널은 업무 이벤트가 아니라 gateway 복사본을 바꾸라는 신호라 events.md·Outbox·ProcessedEventGuard 를 쓰지 않는다

## 완료 조건 (테스트로 확인할 것)
auth
- [ ] 역할 목록이 역할마다 `permissions` 를 준다
- [ ] 권한 목록이 `{code, name}` 을 코드 순으로 준다
- [ ] 역할의 권한을 바꾸면 `role_permission` 이 요청한 목록과 같아지고 200 + 바뀐 역할
- [ ] 없는 역할 404, 없는 권한 코드 400, 형식 오류 400, `ACCOUNT_ADMIN` 을 가진 역할이 없어지는 변경 409 (409 면 DB 가 바뀌지 않는다)
- [ ] 바꾼 뒤 Redis 키가 새 표이고, 채널에 같은 표가 발행된다
- [ ] Redis 쓰기가 실패하면 503, 같은 내용으로 다시 저장하면 키가 새 표가 된다
- [ ] auth 가 뜨면 Redis 키에 DB 의 표가 있다
- [ ] Access 토큰에 `permissions` 가 없고 `sub`·`role` 이 있다
- [ ] 내 정보가 `{publicId, name, role, permissions}` 를 주고 이메일은 없다. 계정이 없으면 404

gateway
- [ ] 뜰 때 Redis 키의 표로 판단한다 (SALES 토큰 → ORDER_WRITE API 통과, PURCHASE_WRITE API 403)
- [ ] 채널로 새 표가 오면 다음 요청부터 새 표로 판단한다 (SALES 에 PURCHASE_WRITE 를 더하면 구매 쓰기 통과)
- [ ] 키가 없으면 공개 경로 말고 모두 403, 키가 생기거나 채널로 표가 오면 풀린다
- [ ] 표에 없는 역할의 토큰은 403
- [ ] 내 정보는 로그인한 모든 역할(ADMIN 포함)이 통과, 토큰 없으면 401
- [ ] #171 의 API → 권한 테스트(RolePermissionTest)는 토큰의 permissions 대신 role + 표로 같은 결과
- [ ] SecurityConfig 에 역할 이름이 없다 (지금 테스트 그대로)
