# 프론트 화면설계에서 나온 백엔드 작업 목록 (인계)

> 2026-10-24, main `77d55b7` 기준으로 정리 (처음 정리는 2026-10-18 `38ba1e7`, 다음 2026-10-19 `cf52256`). 프론트 화면설계(Figma "화면설계 v1") 중에 찾은 빈틈이다.
> 다른 세션이 이 파일만 보고 작업을 시작할 수 있게 적었다. 작업 전에 main 을 최신으로 받고, 아래 "확인한 코드" 가 그대로인지 먼저 본다.
> 프론트는 아직 이 저장소에 없다 (Figma 화면설계만 있음). 프론트가 할 일은 아래 "프론트 할 일" 에 목록으로 둔다.

## 공통 전제

- 작업 순서는 루트 CLAUDE.md 그대로: `docs/specs/` 명세 초안(`_template.md`) → 사용자 확인 → 테스트 먼저 → 구현 → `./gradlew :services:<svc>:check`
- 항목마다 브랜치 하나, PR 하나. 새 작업은 worktree 말고 이 폴더에서 브랜치만 바꾼다. PR 의 "## 왜" 는 비워 둔다
- 권한 규칙 (gateway `SecurityConfig`, 표는 `docs/specs/role-permissions.md`): gateway 는 역할 이름을 모른다. 토큰의 `role` 로, auth 가 Redis(`auth:role-permissions`)로 보낸 역할 → 권한 표에서 권한을 찾아 판단한다 (`docs/specs/role-permission-admin.md`). GET 은 `READ`, 쓰기는 표에 넣은 권한만, `/api/auth/admin/**` 은 `ACCOUNT_ADMIN`, `GET /api/auth/me` 는 로그인한 직원 모두. 역할별 권한의 원본은 auth DB(`role_permission`)이고 관리자가 화면에서 바꾼다. **새 GET 은 `READ` 로 저절로 열리고, 새 쓰기 API 는 표에 줄을 넣어야 열린다**
- 화면 갱신은 폴링이라 서버 푸시는 필요 없다
- 테스트를 지우거나 단언을 바꿔야 하면 이유와 매핑을 먼저 말하고 승인을 받는다

## 남은 작업 (추천 순서)

| # | 작업 | 서비스 | 쓰는 화면 | 크기 |
|---|---|---|---|---|
| 3 | 설비 상태 이력 조회 | mes | 설비 현황의 이력 패널 | 작음 |

(1번 "내 정보", 2번 "직원 이름 조회" 는 끝났다 → 아래 "끝난 것". 번호는 프론트 할 일에서 가리키므로 그대로 둔다)

백엔드가 필요 없는 것 (프론트에서 처리)
- 작업지시 목록·완성차 목록에는 `productId` 만 있다 → 프론트가 `GET /api/production/products` 로 id → 이름을 바꾼다
- 주문 목록·상세에는 `customerId` 만 있다 → 프론트가 `GET /api/orders/customers` (전체를 한 번에 줌)로 id → 고객 이름을 바꾼다. 주문 등록의 고객 검색도 이 목록을 화면에서 거른다 (2026-10-19)
- 설비 목록 `GET /api/mes/equipments?process=` 는 process 가 필수다 → 설비 현황 화면은 공정 5개를 각각 불러 합친다 (2026-10-18 결정. process 를 선택으로 바꾸는 안은 하지 않는다. MES 분리로 production → mes 로 옮겨졌다, `docs/specs/mes-split.md`)

## 프론트 할 일

프론트 저장소가 생기면 이 목록대로 만든다. API 근거는 `docs/specs/role-permission-admin.md`, `docs/specs/auth-account.md`. 오류 응답의 `code` 는 `AUTH-0xx` 형식이다.

1. **권한 편집 화면** (ADMIN, Figma 에 아직 없음)
   - 역할마다 권한: `GET /api/auth/admin/roles` → `[{ code, name, permissions }]`
   - 체크박스 선택지: `GET /api/auth/admin/permissions` → `[{ code, name }]`
   - 저장: `PUT /api/auth/admin/roles/{code}/permissions` 본문 `{ "permissions": [...] }`. **통째로 교체**한다 (보낸 목록이 그 역할의 권한 전부, 빈 목록 = 권한 없음). 응답은 바뀐 역할 `{ code, name, permissions }`
   - 오류: 404 `AUTH-014` 역할 없음 / 400 `AUTH-015` 없는 권한 코드 / 409 `AUTH-016` "ACCOUNT_ADMIN 을 가진 역할이 하나는 남아야 함" (DB 는 안 바뀜) / 503 `AUTH-017` "저장됐지만 반영 실패" → 같은 내용으로 다시 저장하라고 안내
   - 반영은 1초 안팎 (gateway 가 Redis 로 받음). 이미 로그인한 사용자도 다음 요청부터 바뀐다
2. **메뉴·첫 화면은 역할 이름이 아니라 권한으로 고른다**: 로그인 뒤 `GET /api/auth/me` 의 `permissions` 를 본다 (예: 주문 등록 버튼은 `ORDER_WRITE`, 계정 발급·권한 편집 메뉴는 `ACCOUNT_ADMIN`). 토큰에는 `sub`·`role` 만 있고 `permissions` 는 없다. 그러면 관리자가 권한을 바꿔도 프론트는 고치지 않는다
3. **화면 상단 이름·역할**: `GET /api/auth/me` 의 `name`·`role` (이메일은 주지 않는다)
4. **계정 발급의 역할 선택지**: `GET /api/auth/admin/roles` 의 `code`·`name`. 발급 `role` 에는 `code` 를 보낸다 (하드코딩하지 않는다)
5. **실적 작업자·구매 발주자 이름**: 상세 응답의 public_id 들(mes 실적의 `workerId`, purchase 의 `orderedBy`)을 모아 `GET /api/auth/users?publicIds=a,b,c` 로 한 번에 이름을 받는다 → `[{ publicId, name }]`. 1~100개, 없는 id 는 빠지고 순서는 정해지지 않으므로 `publicId` 로 찾는다. ADMIN 은 `READ` 가 없어 403 (ADMIN 화면에는 쓸 곳이 없음)
6. **설비 이력 패널**: 남은 작업 3번 API (3번이 끝나면 경로를 여기 적는다)

## 끝난 것

### 직원 이름 조회 `GET /api/auth/users?publicIds=` (2026-10-24, 브랜치 `feat/user-names`)
- 응답 `[{ publicId, name }]`. 이메일·역할 없음. 없는 id 는 빠지고, 같은 id 는 한 번, `DISABLED` 계정도 준다. 순서는 정하지 않는다
- `publicIds` 가 없거나 비었거나 100개 초과, UUID 형식 아니면 400
- gateway 규칙 추가 없음 (GET → `READ`). ADMIN 은 403 (ADMIN 화면에 쓸 곳이 없어 그대로. 필요하면 권한 편집으로 ADMIN 에 `READ` 를 더한다)
- mes·purchase·inventory 는 바꾸지 않았다. 프론트가 id 를 모아 부른다 (위 "프론트 할 일" 5번)
- 명세 `docs/specs/auth-account.md` "6. 직원 이름 조회"

### 내 정보 `GET /api/auth/me` (2026-10-23, #175)
- 응답 `{ publicId, name, role, permissions }`. 이메일은 없다 (화면에 필요 없음, 개인정보)
- gateway: 로그인한 직원 모두 (ADMIN 포함, 권한 없이). 토큰 없으면 401
- 계정이 없으면 404 `AUTH-018`, `X-User-Id` 가 없으면(gateway 를 거치지 않은 요청) 400
- 명세 `docs/specs/role-permission-admin.md` "6. 토큰과 내 정보"

### 역할 권한 편집 (2026-10-23, #175)
- 관리자 API 3개(위 "프론트 할 일" 1번), auth → Redis 표 동기화, gateway 의 메모리 표 판단. 토큰에서 `permissions` 를 뺐다
- 명세 `docs/specs/role-permission-admin.md`

### 역할 목록 `GET /api/auth/admin/roles` (2026-10-23, #171)
- 계정 발급 화면의 역할 선택지를 DB 에서 가져온다. 응답 `[{ code, name, permissions }]` (`SALES 영업, PURCHASE 구매, PRODUCTION 생산, ADMIN 관리자` 순, `permissions` 는 #175 에서 더함), 페이지 없음. ADMIN 전용 (gateway `/api/auth/admin/**` 규칙 그대로)
- 발급 `role` 에는 `code` 를 보낸다. 없는 역할이면 400 `AUTH-013`
- 역할별 권한은 auth DB(`role_permission`) 한 곳이다 (명세 `docs/specs/role-permissions.md`). Access 토큰에는 `sub`·`role` 만 있고, 권한은 `GET /api/auth/me` 로 받는다 (#171 때는 토큰에 `permissions` 가 있었으나 #175 에서 뺐다)

### 고객을 order 에 만들기 → #126 (#125) 로 구현됨
- order V9 `customer` (name, contact), 고객 등록 `POST /orders/customers` (SALES), 고객 목록 `GET /orders/customers` (페이지 없이 전체, 등록 순), 등록되지 않은 고객으로 주문하면 404 `CUSTOMER_NOT_FOUND` (production 을 부르기 전에 확인). 명세 `docs/specs/customer.md`
- 처음 이 파일에 적은 안과 다른 점
  - 주문에 고객 이름 스냅샷(`customer_order.customer_name`)과 응답의 `customerName` 은 넣지 않았다 → 위 "프론트에서 처리" 로 대신한다
  - 이름 검색(`?q=`)은 없다. 수정·삭제도 없다
  - 고객은 시드가 아니라 등록 API 로 넣는다 (order 에 `db/seed-dev` 없음). 화면에 고객 등록이 필요하다 (Figma v1 에는 아직 없음)
- `docs/specs/auth-account.md` 의 낡은 "`customer` 는 auth_db 에 남아 있다" 문장은 2026-10-24 에 고쳤다

---

## 3. 설비 상태 이력 조회

- 데이터는 이미 쌓인다: `equipment_status_history` (mes V1), `services/mes/.../domain/EquipmentStatusHistory.java`, `repository/EquipmentStatusHistoryRepository.java` 가 있다. 조회 API 만 없다
- `GET /mes/equipments/{equipmentCode}/history?lastId=&size=` (gateway `/api/mes/...`) → `CursorPage { items: [ { fromStatus, toStatus, changedAt } ] }` 최신 먼저 (목록은 커서로 나눠 준다, `docs/specs/list-apis.md`)
- 없는 설비 404 `EQUIPMENT_NOT_FOUND`, 코드 형식 400 (`EquipmentController` 의 `EQUIPMENT_CODE` 패턴 그대로)
- 확인한 코드: `services/mes/.../controller/EquipmentController.java` (`/mes/equipments`, 목록·센서값), `MesErrorCode.EQUIPMENT_NOT_FOUND`
