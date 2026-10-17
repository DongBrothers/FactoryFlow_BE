# 역할별 API 권한

## 목적
지금 gateway 는 `/api/auth/admin/**` 만 ADMIN 으로 막고, 나머지는 로그인만 하면 누구나 부른다 (#107).
그래서 영업이 공정을 끝내거나, 작업자가 출고·발주·입고를 할 수 있다. 바꾸는 요청(쓰기)을 역할로 막는다.

- **조회(GET)는 로그인한 직원 모두.** 사내 ERP 라 현황을 함께 본다
- **쓰기는 역할 표에 있는 역할만.** 표에 없는 쓰기 요청은 ADMIN 만 (새 쓰기 API 를 만들면 표에 넣어야 열린다)
- **ADMIN 은 모두 할 수 있다**
- 확인은 지금처럼 gateway 한 곳에서 JWT 의 `role` 로 한다. 뒤 서비스는 다시 확인하지 않는다 (docs/specs/auth-account.md "권한 확인과 헤더 신뢰 전제")
- 역할은 지금 값 그대로: `SALES, PURCHASE, PRODUCTION, WORKER, ADMIN` (역할 추가 없음)

## 흐름

### 역할 표 (밖 주소 기준, gateway 가 StripPrefix 전에 본다)
| 요청 | 하는 일 | 허용 역할 |
|---|---|---|
| `POST /api/orders` | 주문 생성 | SALES |
| `POST /api/orders/{orderNo}/cancel` | 주문 취소 요청 | SALES |
| `POST /api/production/work-orders/{id}/steps/{process}/start` | 공정 시작 | WORKER, PRODUCTION |
| `POST /api/production/work-orders/{id}/steps/{process}/complete` | 공정 종료·실적 | WORKER, PRODUCTION |
| `POST /api/purchases/{id}/order` | 발주 | PURCHASE |
| `POST /api/purchases/{id}/receive` | 입고 | PURCHASE |
| `POST /api/inventory/finished-vehicles/{vin}/ship` | 완성차 출고 | PRODUCTION |
| `/api/auth/admin/**` | 계정 발급 (지금 규칙) | ADMIN |
| 그 밖의 GET | 조회 | 로그인한 직원 모두 |
| 그 밖의 쓰기 (POST, PUT, PATCH, DELETE) | 표에 없는 쓰기 | ADMIN |
| 공개 경로 (로그인·활성화·재발급·로그아웃, health) | 지금 규칙 | 누구나 |
ADMIN 은 표의 모든 요청을 할 수 있다.

### 응답
- 토큰 없음·만료·서명 틀림: 401 (지금 규칙)
- 역할이 맞지 않음: 403 (지금 공통 오류 형식)

## 규칙 / 예외
| 상황 | 처리 |
|---|---|
| role 클레임 없는 토큰 | 권한 없음 → 쓰기는 403, GET 은 통과 |
| 표에 없는 새 쓰기 API | ADMIN 만. 다른 역할에 열려면 이 표와 SecurityConfig 에 더한다 |
| 내부 API (`/internal/**`) | gateway 에 노출하지 않는다 (지금 규칙, 라우트 없음) |
| CORS preflight (OPTIONS) | 지금처럼 인증 전에 응답 |

### 테이블 변경
없음

## 관련 이벤트 (events.md 참조)
없음

## 완료 조건 (테스트로 확인할 것)
gateway (SecurityConfigTest, 토큰은 RoleTokenDecoder)
- [ ] 표의 쓰기 요청마다 허용 역할은 통과(라우트가 없으면 404), 다른 역할은 403
- [ ] ADMIN 은 표의 모든 쓰기 요청 통과
- [ ] 표에 없는 쓰기 요청은 ADMIN 만 통과, 다른 역할 403
- [ ] GET 은 어느 역할이든 통과, 토큰 없으면 401
- [ ] 공개 경로는 지금처럼 토큰 없이 통과
