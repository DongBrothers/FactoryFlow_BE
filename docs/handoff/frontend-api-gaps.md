# 프론트 화면설계에서 나온 백엔드 작업 목록 (인계)

> 2026-10-19, main `cf52256` 기준으로 정리 (처음 정리는 2026-10-18 `38ba1e7`). 프론트 화면설계(Figma "화면설계 v1") 중에 찾은 빈틈이다.
> 다른 세션이 이 파일만 보고 작업을 시작할 수 있게 적었다. 작업 전에 main 을 최신으로 받고, 아래 "확인한 코드" 가 그대로인지 먼저 본다.

## 공통 전제

- 작업 순서는 루트 CLAUDE.md 그대로: `docs/specs/` 명세 초안(`_template.md`) → 사용자 확인 → 테스트 먼저 → 구현 → `./gradlew :services:<svc>:check`
- 항목마다 브랜치 하나, PR 하나. 새 작업은 worktree 말고 이 폴더에서 브랜치만 바꾼다. PR 의 "## 왜" 는 비워 둔다
- 역할 규칙 (gateway `SecurityConfig.authorizeByRole`, 쓰기는 `authorizeWrites`, 표는 `docs/specs/role-permissions.md`): GET 은 SALES·PURCHASE·PRODUCTION, 쓰기는 표에 넣은 것만, ADMIN 은 `/api/auth/admin/**` 만. **새 GET 은 업무 역할에게 저절로 열리고, 새 쓰기 API 와 ADMIN 이 볼 GET 은 표에 줄을 넣어야 열린다**
- 화면 갱신은 폴링이라 서버 푸시는 필요 없다
- 테스트를 지우거나 단언을 바꿔야 하면 이유와 매핑을 먼저 말하고 승인을 받는다

## 남은 작업 (추천 순서)

| # | 작업 | 서비스 | 쓰는 화면 | 크기 |
|---|---|---|---|---|
| 1 | 내 정보 `GET /auth/me` + ADMIN 에게도 열기 | auth, gateway | 공통 레이아웃 상단 이름 | 작음 |
| 2 | 직원 이름 조회 (public_id → 이름) | auth | 작업지시 상세 실적의 작업자, 구매 목록의 발주자 | 작음 |
| 3 | 설비 상태 이력 조회 | production | 설비 현황의 이력 패널 | 작음 |

백엔드가 필요 없는 것 (프론트에서 처리)
- 작업지시 목록·완성차 목록에는 `productId` 만 있다 → 프론트가 `GET /api/production/products` 로 id → 이름을 바꾼다
- 주문 목록·상세에는 `customerId` 만 있다 → 프론트가 `GET /api/orders/customers` (전체를 한 번에 줌)로 id → 고객 이름을 바꾼다. 주문 등록의 고객 검색도 이 목록을 화면에서 거른다 (2026-10-19)
- 설비 목록 `GET /api/production/equipments?process=` 는 process 가 필수다 → 설비 현황 화면은 공정 5개를 각각 불러 합친다 (2026-10-18 결정. process 를 선택으로 바꾸는 안은 하지 않는다)

## 끝난 것

### 고객을 order 에 만들기 → #126 (#125) 로 구현됨
- order V9 `customer` (name, contact), 고객 등록 `POST /orders/customers` (SALES), 고객 목록 `GET /orders/customers` (페이지 없이 전체, 등록 순), 등록되지 않은 고객으로 주문하면 404 `CUSTOMER_NOT_FOUND` (production 을 부르기 전에 확인). 명세 `docs/specs/customer.md`
- 처음 이 파일에 적은 안과 다른 점
  - 주문에 고객 이름 스냅샷(`customer_order.customer_name`)과 응답의 `customerName` 은 넣지 않았다 → 위 "프론트에서 처리" 로 대신한다
  - 이름 검색(`?q=`)은 없다. 수정·삭제도 없다
  - 고객은 시드가 아니라 등록 API 로 넣는다 (order 에 `db/seed-dev` 없음). 화면에 고객 등록이 필요하다 (Figma v1 에는 아직 없음)
- 남은 문서 정리 (작은 일, 다른 PR 에 붙여도 됨): `docs/specs/auth-account.md:275` 의 "`customer` 는 auth_db 에 … 남아 있다" 는 #124 에서 테이블을 지웠고 #126 에서 order 에 만들었으므로 낡은 문장이다

---

## 1. 내 정보 `GET /auth/me`

- 화면 상단에 "이름 · 역할" 을 띄우려고. JWT 에는 `sub`(public_id)·`role` 만 있고 이름은 없다 (개인정보를 토큰에 넣지 않는 규칙)
- auth: `X-User-Id`(gateway 가 JWT sub 로 채움)로 `UserAccount` 를 찾아 `{ publicId, name, role }` (이메일은 넣을지 명세에서 정한다. 로그에는 남기지 않는다)
- 확인한 코드: `services/auth/src/main/java/com/factoryflow/auth/domain/UserAccount.java` 에 `publicId`, `name`, `role` 이 있다. 지금 auth 에 `X-User-Id` 를 읽는 곳은 없다
- gateway: 지금 GET 은 SALES·PURCHASE·PRODUCTION 만이라 **ADMIN 은 403**. `authorizeByRole` 에 `GET /api/auth/me` → 네 역할 모두, 를 일반 GET 줄보다 앞에 넣는다. `/api/auth/**` 의 공개 경로(로그인 등)와 겹치지 않는지 확인
- 명세: `docs/specs/auth-account.md` 에 API 와 응답 표 추가, `docs/specs/role-permissions.md` 표에 줄 추가

## 2. 직원 이름 조회

- 실적의 작업자(`production_result.worker_id`, 응답 `workerId`)와 구매의 발주자(`PurchaseResponse.orderedBy`)가 public_id 만 있다. 화면에 이름으로 보이기로 했다 (2026-10-16 결정). 재고 조정의 담당자(`stock_adjustment.adjusted_by`, #130)도 public_id 다 (조정 목록 API 는 아직 없음)
- auth: `GET /auth/users?publicIds=a,b,c` → `[ { publicId, name } ]`. 이메일·역할은 넣지 않는다. 없는 id 는 빠진다. ids 가 비면 400
- 프론트가 상세 응답의 id 들을 모아 한 번에 부른다 (production·purchase 는 바꾸지 않는다)
- GET 이라 업무 역할 3개에게 저절로 열린다. 경로가 `/api/auth/admin/**` 아래가 아니어야 ADMIN 전용이 되지 않는다

## 3. 설비 상태 이력 조회

- 데이터는 이미 쌓인다: `equipment_status_history` (production V6), `EquipmentStatusHistory`, `EquipmentStatusHistoryRepository` 가 있다. 조회 API 만 없다
- `GET /production/equipments/{equipmentCode}/history?lastId=&size=` → `CursorPage { items: [ { fromStatus, toStatus, changedAt } ] }` 최신 먼저 (목록은 커서로 나눠 준다는 SAGA "목록 조회" 규칙)
- 없는 설비 404 `EQUIPMENT_NOT_FOUND`, 코드 형식 400
