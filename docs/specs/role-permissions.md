# 역할별 API 권한

## 목적
지금 gateway 는 `/api/auth/admin/**` 만 ADMIN 으로 막고, 나머지는 로그인만 하면 누구나 부른다 (#107).
그래서 영업이 공정을 끝내거나, 작업자가 출고·발주·입고를 할 수 있다. 바꾸는 요청(쓰기)을 역할로 막는다.

> **#118 에서 바뀜:** ADMIN 은 계정 발급만 한다(업무 조회·쓰기 불가). 라인 작업자 WORKER 는 권한 차이가 출고뿐이라 PRODUCTION 으로 합쳤다. 표에 없는 요청은 아무도 못 한다.

> **2026-10-23 바뀜 (권한 테이블):** gateway 는 역할 이름을 보지 않고 **권한**만 본다. 역할이 어떤 권한을 갖는지는 auth DB(`role_permission`)
> 한 곳에 두고, 로그인 때 JWT `permissions` 클레임으로 내보낸다. 기존 권한을 조합한 역할을 더하거나 역할의 권한을 바꾸는 일은
> auth 마이그레이션 하나로 끝나고 gateway·프론트 코드는 고치지 않는다.

> **2026-10-23 다시 바뀜 (docs/specs/role-permission-admin.md):** 권한을 토큰에 싣지 않는다. gateway 는 auth 가 Redis 로 보낸
> 역할 → 권한 표를 메모리에 들고, 토큰의 `role` 로 권한을 찾는다. 관리자가 화면에서 역할의 권한을 바꾸면 1초 안팎에 반영된다.
> 아래 API → 권한 표와 `hasAuthority` 검사는 그대로다.

- **조회(GET)는 `READ` 권한.** 업무 역할(SALES, PURCHASE, PRODUCTION) 이 갖는다. 사내 ERP 라 현황을 함께 본다
- **쓰기는 아래 표의 권한만.** 표에 없는 요청은 아무도 못 한다 (새 API 를 만들면 표에 넣어야 열린다)
- **ADMIN 은 계정 발급만** (`ACCOUNT_ADMIN` 권한 하나, `/api/auth/admin/**`)
- 확인은 지금처럼 gateway 한 곳에서 JWT 로 한다. 뒤 서비스는 다시 확인하지 않는다 (docs/specs/auth-account.md "권한 확인과 헤더 신뢰 전제")
- 역할 목록과 역할별 권한의 원본은 auth DB 다 (docs/specs/auth-account.md "역할과 권한"). **이 문서에 역할 → 권한 표를 두지 않는다** (두 곳을 고치게 되므로).
  아래 "테이블 변경" 의 표는 V6 이 처음 넣는 값의 기록이다
- WORKER 는 #118 에서 PRODUCTION 으로 합쳤다. 이미 있던 WORKER 계정은 auth V3 마이그레이션으로 PRODUCTION

## 흐름

### 권한 (gateway 코드와 auth `permission` 테이블이 같은 코드를 쓴다)
| 권한 | 이름 | 하는 일 |
|---|---|---|
| `READ` | 조회 | 그 밖의 GET 전부 |
| `ORDER_WRITE` | 주문 쓰기 | 주문·고객 |
| `PRODUCTION_WRITE` | 생산 쓰기 | 제품·작업지시·공정·출고 |
| `PURCHASE_WRITE` | 구매 쓰기 | 발주·입고·공급업체·부품·재고 조정 |
| `ACCOUNT_ADMIN` | 계정 관리 | 계정 발급, 역할 목록 |

### API → 필요한 권한 (밖 주소 기준, gateway 가 StripPrefix 전에 본다)
| 요청 | 하는 일 | 필요한 권한 |
|---|---|---|
| `POST /api/orders` | 주문 생성 | ORDER_WRITE |
| `POST /api/orders/{orderNo}/cancel` | 주문 취소 요청 | ORDER_WRITE |
| `POST /api/orders/customers` | 고객 등록 (#125) | ORDER_WRITE |
| `POST /api/mes/work-orders/{id}/steps/{process}/start` | 공정 시작 | PRODUCTION_WRITE |
| `POST /api/mes/work-orders/{id}/steps/{process}/complete` | 공정 종료·실적 | PRODUCTION_WRITE |
| `POST /api/inventory/finished-vehicles/{vin}/ship` | 완성차 출고 | PRODUCTION_WRITE |
| `POST /api/production/products`, `PUT /api/production/products/{id}` | 제품 + BOM 등록·수정 (#127) | PRODUCTION_WRITE |
| `POST /api/production/productions/{id}/work-order` | 작업지시 발행 (#131) | PRODUCTION_WRITE |
| `POST /api/purchases` | 구매 건 만들기 = 발주 (#114) | PURCHASE_WRITE |
| `POST /api/purchases/{id}/receive` | 입고 | PURCHASE_WRITE |
| `POST /api/inventory/parts`, `PUT /api/inventory/parts/{id}` | 부품 등록·수정 (#127) | PURCHASE_WRITE |
| `POST /api/inventory/parts/{id}/adjustments` | 재고 조정 (#129) | PURCHASE_WRITE |
| `POST /api/purchases/suppliers`, `PUT /api/purchases/suppliers/{id}` | 공급업체 등록·수정 (#127) | PURCHASE_WRITE |
| `/api/auth/admin/**` | 계정 발급, 역할 목록 `GET /api/auth/admin/roles` | ACCOUNT_ADMIN |
| 그 밖의 GET | 조회 | READ |
| 그 밖의 요청 (표에 없는 POST, PUT, PATCH, DELETE …) | — | 아무도 못 함 (403) |
| 공개 경로 (로그인·활성화·재발급·로그아웃, health) | 지금 규칙 | 누구나 |

- gateway 는 JWT 의 `role` 로 메모리의 역할 → 권한 표(Redis 로 받음)에서 권한을 찾아 권한(authority)으로 쓴다
  (`ROLE_` 접두사 없음, `hasAuthority`). gateway 코드에는 역할 이름을 두지 않는다 (역할 코드는 표의 키일 뿐이다)
- 내 정보 `GET /api/auth/me` 는 로그인한 직원 모두(권한 없이)다 (docs/specs/role-permission-admin.md)

### 응답
- 토큰 없음·만료·서명 틀림: 401 (지금 규칙)
- 권한이 맞지 않음: 403 (지금 공통 오류 형식)

## 규칙 / 예외
| 상황 | 처리 |
|---|---|
| 토큰의 `role` 이 표에 없음 (WORKER 등 없어진 역할) | 권한 없음 → 공개 경로와 내 정보 말고 모두 403 |
| 표에 없는 새 API | 아무도 못 한다. 열려면 이 표와 SecurityConfig 에 더한다 (기존 권한이면 gateway 한 줄) |
| 기존 권한을 조합한 새 역할 | auth 마이그레이션 하나 (`role` 행 + `role_permission` 행). auth 가 뜰 때 표를 Redis 로 다시 보낸다. gateway·프론트·이 문서는 고치지 않는다 |
| 역할의 권한 변경 | 관리자 화면 (`PUT /api/auth/admin/roles/{code}/permissions`). 1초 안팎에 gateway 에 반영 (docs/specs/role-permission-admin.md) |
| 새 권한 종류 (새 기능) | auth 마이그레이션(`permission` 행 + 부여) + 이 문서의 권한 표 + gateway + 프론트 메뉴 |
| 프론트 메뉴·첫 화면 | 역할이 아니라 `permissions` 로 고른다 (역할 이름으로 분기하지 않는다) |
| 내부 API (`/internal/**`) | gateway 에 노출하지 않는다 (지금 규칙, 라우트 없음) |
| CORS preflight (OPTIONS) | 지금처럼 인증 전에 응답 |

### 배포 순서
#171 은 auth 를 먼저, Access TTL(15분) 뒤 gateway 를 배포하는 순서였다 (토큰의 `permissions` 로 판단했으므로).
권한 표를 Redis 로 받는 지금은 auth·gateway 를 함께 배포한다 (docs/specs/role-permission-admin.md "배포").

### 테이블 변경 (auth V6)
- `permission` (`id` PK, `code` UNIQUE, `name`) 에 위 "권한" 표의 5개
- `role_permission` (`id` PK, `role_id` FK → `role.id`, `permission_id` FK → `permission.id`, 둘을 합쳐 UNIQUE `uk_role_permission`. 다른 엔티티처럼 숫자 id 를 둔다)
- V6 이 처음 넣는 역할별 권한 (지금 gateway 표와 같은 동작. **이후 원본은 마이그레이션이고 이 표는 고치지 않는다**)

  | 역할 | 권한 |
  |---|---|
  | SALES | READ, ORDER_WRITE |
  | PURCHASE | READ, PURCHASE_WRITE |
  | PRODUCTION | READ, PRODUCTION_WRITE |
  | ADMIN | ACCOUNT_ADMIN |

## 관련 이벤트 (events.md 참조)
없음

## 완료 조건 (테스트로 확인할 것)
gateway (SecurityConfigTest·RolePermissionTest, 토큰은 `role` 로 만들고 역할 → 권한 표는 테스트용으로 넣는다)
- [ ] 표의 쓰기 요청마다 필요한 권한이 있으면 통과(라우트가 없으면 404), 없으면 403
- [ ] `ACCOUNT_ADMIN` 만 있으면 계정 발급만 통과, 업무 조회도 403 (#118)
- [ ] 표에 없는 요청은 어떤 권한이든 403
- [ ] GET 은 `READ` 가 있으면 통과, 없으면 403, 토큰 없으면 401
- [ ] 표에 없는 `role` 의 토큰은 모두 403
- [ ] SecurityConfig 에 역할 이름(SALES 등)이 없다

auth
- [ ] V3: WORKER 계정 → PRODUCTION, WORKER 로 발급하면 400
- [ ] V6: 위 "V6 이 처음 넣는 역할별 권한" 그대로 들어간다
- [ ] 로그인·재발급 Access 토큰의 `permissions` 가 그 역할의 권한이다 (예: SALES → READ, ORDER_WRITE)
- [ ] 마이그레이션으로 역할과 권한을 더하면 코드 수정 없이 그 역할로 발급·로그인되고 토큰에 그 권한이 들어간다
- [ ] 공개 경로는 지금처럼 토큰 없이 통과
