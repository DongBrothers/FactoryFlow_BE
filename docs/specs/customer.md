# 고객 등록

## 목적
주문은 요청 본문의 `customerId` 로 어느 고객의 주문인지 받는데, 지금은 고객 데이터가 없어 그 번호를 확인하지 않고 그대로 저장한다 (auth 의 쓰지 않던 customer 테이블은 #123 에서 지웠다).
영업 담당이 고객을 등록하고, 주문할 때 등록된 고객인지 확인한다. 고객은 주문을 받는 order 서비스에 둔다.

## 흐름

### 1. 고객 등록 `POST /orders/customers` (SALES)
gateway `/api/orders/customers` (기존 `/api/orders/**` 라우트)
```json
{ "name": "현대캐피탈", "contact": "02-0000-0000" }
```
- order 의 `customer` 에 1줄 저장. 이벤트 없음
- `201` 과 고객 `{ customerId, name, contact }`. customerId 는 주문 생성 요청에 넣는 값이라 내보낸다 (제품의 productId 와 같음)

### 2. 고객 목록 `GET /orders/customers` (SALES, PURCHASE, PRODUCTION)
```json
[ { "customerId": 7, "name": "현대캐피탈", "contact": "02-0000-0000" } ]
```
- 기준 정보라 페이지 없이 한 번에, 등록 순(id)
- `/orders/{orderNo}` 와 겹치지 않는다 (리터럴 경로가 먼저 맞는다)

### 3. 주문 생성에서 고객 확인 (docs/specs/order-create.md)
- `POST /orders` 의 customerId 가 등록된 고객이 아니면 404 `CUSTOMER_NOT_FOUND`
- production 을 부르기 전에 확인한다 (없는 고객이면 production 을 부르지 않음)

## 규칙 / 예외
| 상황 | 응답 |
|---|---|
| name 없음·공백, 50자 초과 | 400 |
| contact 100자 초과 (없어도 됨) | 400 |
| SALES 가 아님 (등록) | 403 (gateway) |
| 주문의 customerId 가 등록되지 않음 | 404 `CUSTOMER_NOT_FOUND` |

- 같은 이름의 고객도 등록할 수 있다 (이름은 고유하지 않음)
- 수정·삭제·검색은 하지 않는다
- 이미 있는 주문의 customer_id 는 그대로 둔다 (고객 테이블과 FK 를 걸지 않음, 논리 참조)

### 테이블 (order V9)
| 컬럼 | 타입 | 뜻 |
|---|---|---|
| id | BIGINT AUTO_INCREMENT | customerId |
| name | VARCHAR(50) NOT NULL | 고객 이름 |
| contact | VARCHAR(100) NULL | 연락처 |
| created_at, updated_at | DATETIME(6) | `BaseTimeEntity`. 업무 데이터라 소프트 삭제 없음 |

## 관련 이벤트 (events.md 참조)
없음

## 완료 조건 (테스트로 확인할 것)
- [ ] 고객 등록: 201 + customerId, customer 1줄. name 없음·51자 400, contact 101자 400
- [ ] 고객 목록: 등록 순으로 전부
- [ ] 없는 customerId 로 주문: 404 `CUSTOMER_NOT_FOUND`, production 을 부르지 않고 주문·outbox 가 생기지 않음
- [ ] 등록된 고객으로 주문: 지금처럼 201
- [ ] gateway: 고객 등록은 SALES 만, 목록은 SALES·PURCHASE·PRODUCTION
- [ ] MigrationTest: V9 customer 테이블
