# 역할별 API 권한

## 목적
지금 gateway 는 `/api/auth/admin/**` 만 ADMIN 으로 막고, 나머지는 로그인만 하면 누구나 부른다 (#107).
그래서 영업이 공정을 끝내거나, 작업자가 출고·발주·입고를 할 수 있다. 바꾸는 요청(쓰기)을 역할로 막는다.

> **#118 에서 바뀜:** ADMIN 은 계정 발급만 한다(업무 조회·쓰기 불가). 라인 작업자 WORKER 는 권한 차이가 출고뿐이라 PRODUCTION 으로 합쳤다. 표에 없는 요청은 아무도 못 한다.

- **조회(GET)는 업무 역할(SALES, PURCHASE, PRODUCTION) 모두.** 사내 ERP 라 현황을 함께 본다
- **쓰기는 역할 표에 있는 역할만.** 표에 없는 요청은 아무도 못 한다 (새 API 를 만들면 표에 넣어야 열린다)
- **ADMIN 은 계정 발급만** (`/api/auth/admin/**`)
- 확인은 지금처럼 gateway 한 곳에서 JWT 의 `role` 로 한다. 뒤 서비스는 다시 확인하지 않는다 (docs/specs/auth-account.md "권한 확인과 헤더 신뢰 전제")
- 역할: `SALES, PURCHASE, PRODUCTION, ADMIN` (WORKER 는 #118 에서 PRODUCTION 으로 합침. 이미 있던 WORKER 계정은 auth V3 마이그레이션으로 PRODUCTION, 발급 대기의 WORKER 는 활성화 때 PRODUCTION 으로 읽는다. 합치기 전에 받은 WORKER 토큰은 권한이 없어 다시 로그인해야 한다)

## 흐름

### 역할 표 (밖 주소 기준, gateway 가 StripPrefix 전에 본다)
| 요청 | 하는 일 | 허용 역할 |
|---|---|---|
| `POST /api/orders` | 주문 생성 | SALES |
| `POST /api/orders/{orderNo}/cancel` | 주문 취소 요청 | SALES |
| `POST /api/orders/customers` | 고객 등록 (#125) | SALES |
| `POST /api/production/work-orders/{id}/steps/{process}/start` | 공정 시작 | PRODUCTION |
| `POST /api/production/work-orders/{id}/steps/{process}/complete` | 공정 종료·실적 | PRODUCTION |
| `POST /api/purchases` | 구매 건 만들기 = 발주 (#114) | PURCHASE |
| `POST /api/purchases/{id}/receive` | 입고 | PURCHASE |
| `POST /api/inventory/finished-vehicles/{vin}/ship` | 완성차 출고 | PRODUCTION |
| `POST /api/production/products`, `PUT /api/production/products/{id}` | 제품 + BOM 등록·수정 (#127) | PRODUCTION |
| `POST /api/production/productions/{id}/work-order` | 작업지시 발행 (#131) | PRODUCTION |
| `POST /api/inventory/parts`, `PUT /api/inventory/parts/{id}` | 부품 등록·수정 (#127) | PURCHASE |
| `POST /api/inventory/parts/{id}/adjustments` | 재고 조정 (#129) | PURCHASE |
| `POST /api/purchases/suppliers`, `PUT /api/purchases/suppliers/{id}` | 공급업체 등록·수정 (#127) | PURCHASE |
| `/api/auth/admin/**` | 계정 발급 (지금 규칙) | ADMIN |
| 그 밖의 GET | 조회 | SALES, PURCHASE, PRODUCTION |
| 그 밖의 요청 (표에 없는 POST, PUT, PATCH, DELETE …) | — | 아무도 못 함 (403) |
| 공개 경로 (로그인·활성화·재발급·로그아웃, health) | 지금 규칙 | 누구나 |

### 응답
- 토큰 없음·만료·서명 틀림: 401 (지금 규칙)
- 역할이 맞지 않음: 403 (지금 공통 오류 형식)

## 규칙 / 예외
| 상황 | 처리 |
|---|---|
| role 클레임 없는 토큰, 없어진 WORKER 토큰 | 권한 없음 → 모두 403 |
| 표에 없는 새 API | 아무도 못 한다. 열려면 이 표와 SecurityConfig 에 더한다 |
| 내부 API (`/internal/**`) | gateway 에 노출하지 않는다 (지금 규칙, 라우트 없음) |
| CORS preflight (OPTIONS) | 지금처럼 인증 전에 응답 |

### 테이블 변경
없음

## 관련 이벤트 (events.md 참조)
없음

## 완료 조건 (테스트로 확인할 것)
gateway (SecurityConfigTest, 토큰은 RoleTokenDecoder)
- [ ] 표의 쓰기 요청마다 허용 역할은 통과(라우트가 없으면 404), 다른 역할·ADMIN 은 403
- [ ] ADMIN 은 계정 발급만 통과, 업무 조회도 403 (#118)
- [ ] 표에 없는 요청은 모든 역할 403
- [ ] GET 은 SALES·PURCHASE·PRODUCTION 통과, 토큰 없으면 401
- [ ] WORKER 토큰은 모두 403 (#118)
auth (#118)
- [ ] V3: WORKER 계정 → PRODUCTION, WORKER 로 발급하면 400, 발급 대기의 WORKER 는 PRODUCTION 으로 활성화
- [ ] 공개 경로는 지금처럼 토큰 없이 통과
