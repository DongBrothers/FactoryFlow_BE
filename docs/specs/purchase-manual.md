# 구매 담당이 직접 구매 (자동 구매 제거)

## 목적
지금은 재고가 부족하면 inventory 가 `inventory.shortage` 를 보내고, purchase 가 구매 건(AUTO, 요청됨 REQUESTED)을 자동으로 만든다 (docs/specs/purchase-receiving.md).
이것을 바꿔 **구매 담당 직원이 부족한 부품을 보고 직접 구매**한다 (#114).

- 담당자는 inventory 의 **부족 부품 조회**로 무엇이 얼마나 모자란지 본다 (지금 상태를 그때그때 계산)
- 담당자가 **부품·수량·공급업체**를 넣어 구매 건을 만들면 바로 **발주(ORDERED)** 상태가 된다. 수량은 부족분보다 많이 살 수 있다
- 입고 → 재고 증가 → LOT → 기다리던 차에 먼저 온 순서로 재예약 흐름은 **그대로**다
- 없애는 것: `inventory.shortage` 이벤트(발행·수신), 자동 구매 건(AUTO), 요청(REQUESTED) 단계와 발주 API
- 넣지 않는 것: 부족 알림(메일·화면 알림), 구매 중인 수량을 뺀 "더 사야 할 수량", 구매 취소·부분 입고(#105)

## 흐름

### 1. 부족 부품 조회 (inventory) `GET /inventory/parts/shortages`
gateway `/api/inventory/parts/shortages`. 조회라 로그인한 직원 모두 (docs/specs/role-permissions.md)
```json
[ { "partId": 1012, "code": "TIRE-18", "name": "타이어 18인치", "unit": "EA",
    "available": 3, "waitingQty": 6, "shortQty": 3, "waitingProductions": 1 } ]
```
- 대상: 삭제되지 않은 부품 중 **기다리는 수량(WAITING 예약 줄 qty 합) > available** 인 부품
- shortQty = waitingQty − available (이만큼 들어와야 기다리는 줄이 모두 찬다)
- waitingProductions = 그 부품을 기다리는 생산 건 수
- shortQty 큰 순, 같으면 부품 코드 순. 페이지 없음 (부족한 부품만이라 적다)
- 이미 발주한 수량은 빼지 않는다 (inventory 는 구매를 모른다). 담당자는 발주 중인 구매(`GET /purchases?status=ORDERED`)와 함께 본다

### 2. 구매 건 만들기 = 발주 (purchase) `POST /purchases`
gateway `/api/purchases`. **PURCHASE, ADMIN** 만 (역할 표에 추가)
```json
{ "partId": 1012, "qty": 10, "supplierId": 2 }
```
| 확인 (이 순서로) | 실패 |
|---|---|
| 형식: partId·qty·supplierId 필수, 1 이상. 헤더 `X-User-Id` 필수 (gateway 가 넣음) | 400 |
| 부품 있음 (inventory 내부 API 로 확인, 삭제된 부품은 없음) | 404 `PART_NOT_FOUND` |
| inventory 응답 없음·3초 초과·비정상 응답 | 503 `INVENTORY_UNAVAILABLE` |
| 공급업체 있음 | 404 `SUPPLIER_NOT_FOUND` |

통과하면 (주문 생성과 같은 구조: `PurchaseService` 가 트랜잭션 밖에서 inventory 를 부르고, `PurchaseWriter` 가 저장)
- 구매 건 `{ partId, partCode·partName(inventory 에서 받은 스냅샷), qty, supplierId, source MANUAL, status ORDERED, ordered_at = 지금, ordered_by = X-User-Id(직원 public_id) }`
- 이벤트는 보내지 않는다
- 응답 201 구매 건 (`PurchaseResponse` 에 orderedBy 추가)

### 3. inventory 부품 확인 (내부) `GET /internal/parts?ids=1012`
- purchase 만 부른다. gateway 에 노출 안 됨, api-docs 에 없음
- 응답 `[ { "id": 1012, "code": "TIRE-18", "name": "타이어 18인치" } ]`. 삭제된 부품은 빠진다. ids 비었으면 400
- 이름은 내부 API 규칙대로 `InternalPartResponse`

### 4. 입고 (그대로) `POST /purchases/{purchaseId}/receive`
- ORDERED → RECEIVED + `purchase.received` → inventory 재고·LOT·재예약 (docs/specs/purchase-receiving.md "3. 입고")

### 5. 자동 구매 제거
| 서비스 | 없애는 것 |
|---|---|
| inventory | 예약 때 부족 증가분(shortQty) 계산과 `inventory.shortage` 발행 (`ShortageDetected`). `inventory.reservation-pending` 의 shortages 는 그대로 |
| purchase | `inventory.shortage` 수신 (리스너, 큐 `purchase.inventory-shortage`, `requestForShortage`), 발주 API `POST /purchases/{id}/order`, 상태 REQUESTED·출처 AUTO 를 새로 만드는 코드 |
| 이벤트 | `inventory.shortage` (events.md, 사람 적용) |
| gateway | 역할 표의 `POST /api/purchases/{id}/order` → `POST /api/purchases` 로 바꿈 |

- 이미 있는 AUTO·REQUESTED 구매 건(발주 안 된 자동 요청)은 V4 마이그레이션에서 CANCELLED 로 바꾼다 (발주할 API 가 없어지므로). ORDERED·RECEIVED 는 그대로
- 구매 목록의 status 는 ORDERED, RECEIVED (REQUESTED 제거)
- 남은 큐 `purchase.inventory-shortage` 와 그 DLQ 는 앱이 더 만들지 않는다. 이미 만들어진 큐는 RabbitMQ 에서 지워도 된다 (메시지는 더 오지 않음)

## 규칙 / 예외
| 상황 | 처리 |
|---|---|
| 같은 부품을 두 번 구매 | 받는다 (두 건). 담당자가 발주 중 목록을 보고 판단 |
| 부족분보다 많이 구매 | 받는다. 입고되면 기다리는 줄을 채우고 남는 만큼 available |
| 기다리는 차가 없는 부품을 구매 | 받는다 (재고 보충) |
| 부족 부품이 없음 | 빈 목록 |
| 취소로 기다리는 줄이 풀림 | 부족 부품 조회에서 바로 줄어든다 (그때그때 계산) |
| inventory 가 잠시 죽음 | 구매 건 만들기만 503. 조회·입고는 상관없음 |

### 테이블 변경
| 서비스 | 변경 |
|---|---|
| purchase (V4) | `purchase.ordered_by` (VARCHAR(36) NULL, 발주한 직원 public_id). REQUESTED 구매 건 → CANCELLED |
| inventory | 없음 |

## 관련 이벤트 (events.md 참조)
| 이벤트 | 변경 |
|---|---|
| ~~inventory.shortage~~ | 없앤다 (사람 적용) |
| purchase.received | 그대로 (purchaseId, partId, qty) |
| inventory.reservation-pending | 그대로 |

## 완료 조건 (테스트로 확인할 것)
inventory
- [ ] 부족 부품 조회: 기다리는 수량 > 재고인 부품만, shortQty·waitingProductions, shortQty 큰 순, 삭제 제외, 빈 목록, api-docs
- [ ] 내부 부품 확인: 있는 부품만(삭제 제외), ids 없으면 400, api-docs 에 없음
- [ ] 예약이 부족해도 inventory.shortage 를 보내지 않는다 (reservation-pending 은 그대로)
purchase
- [ ] 구매 건 만들기: ORDERED, MANUAL, 스냅샷, ordered_by, ordered_at, 이벤트 없음, 201
- [ ] 400 형식, 404 부품·공급업체, 503 inventory 실패·시간 초과·비정상 응답 (client 테스트)
- [ ] 발주 API 없음, 목록 status 는 ORDERED·RECEIVED
- [ ] 입고는 그대로 purchase.received
- [ ] V4: ordered_by, REQUESTED → CANCELLED
gateway
- [ ] POST /api/purchases 는 PURCHASE·ADMIN, 다른 역할 403
