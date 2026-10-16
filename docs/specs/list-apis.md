# 목록·조회 API (제품, 주문, 부품 재고, 완성차, LOT 별 재고)

## 목적
화면에서 쓸 목록·조회 API 를 만든다 (#101).
지금은 서비스 간 내부 API 나, 주문 번호·VIN 을 이미 알아야 하는 조회만 있어서 주문할 제품을 고르거나 주문·재고·완성차를 훑어볼 수 없다.
상태를 바꾸지 않는 조회만 있고, 이벤트·테이블 변경은 없다.

- 주문·완성차 목록은 최신 먼저(id 내림차순)이고, 다음 페이지는 **마지막으로 받은 orderNo / VIN** 으로 이어 받는다.
  서버가 그 값을 내부 id 로 바꿔 `id < 그 id` 로 읽으므로 내부 id 는 밖으로 나가지 않는다 (order 의 "밖으로는 order_no 만" 규칙)
- 제품·부품은 기준 정보라 수가 적어 페이지 없이 한 번에 준다
- 검색(이름·기간), 정렬 바꾸기, 전체 개수는 넣지 않는다

## 흐름

### 1. 제품 목록 (production) `GET /production/products`
gateway `/api/production/products`. 주문할 때 고를 수 있는 제품만 준다 (삭제되지 않았고 BOM 이 있는 제품. BOM 이 없으면 주문이 404 로 거절된다)
```json
[ { "productId": 1, "code": "AVANTE", "name": "아반떼" } ]
```
- 제품 코드 순
- productId 는 주문 생성 요청(`POST /orders`)에 넣는 값이라 내보낸다

### 2. 주문 목록 (order) `GET /orders?status=&lastOrderNo=&size=20`
gateway `/api/orders`
```json
{ "items": [ { "orderNo": "…", "customerId": 7, "status": "IN_PRODUCTION", "createdAt": "2026-10-17T10:00:00" } ],
  "count": 1, "last": false, "nextOrderNo": "…" }
```
- 최신 주문 먼저. status 가 있으면 그 상태만 (주문 상태 값: RECEIVED, WAITING_MATERIAL, IN_PRODUCTION, COMPLETED, SHIPPED, CANCEL_REQUESTED, CANCELLED)
- 처음엔 lastOrderNo 없이, 다음 페이지는 응답의 nextOrderNo 를 lastOrderNo 로 넘긴다. last = true 면 nextOrderNo 는 null
- 품목·차량은 주문 상세(`GET /orders/{orderNo}`)에서 본다

| 확인 | 실패 |
|---|---|
| status 가 주문 상태 값, lastOrderNo 가 UUID 형식, size 1~100 | 400 |
| lastOrderNo 주문 있음 | 404 `ORDER_NOT_FOUND` |

### 3. 부품 재고 목록 (inventory) `GET /inventory/parts`
gateway `/api/inventory/parts`. 삭제되지 않은 부품, 부품 코드 순
```json
[ { "partId": 1001, "code": "ENG-G16", "name": "가솔린 엔진 1.6", "unit": "EA", "available": 299, "reserved": 1 } ]
```
- available = 바로 쓸 수 있는 수량, reserved = 생산에 잡힌 수량, 창고 실물 = 둘의 합
- partId 는 구매 API 가 이미 내보내는 값이고 LOT 별 재고(5) 경로에 쓴다

### 4. 완성차 목록 (inventory) `GET /inventory/finished-vehicles?status=&lastVin=&size=20`
gateway `/api/inventory/finished-vehicles`
```json
{ "items": [ { "vin": "KMH…", "productId": 1, "status": "IN_STOCK", "completedAt": "2026-10-17T10:00:00", "shippedAt": null } ],
  "count": 1, "last": true, "nextVin": null }
```
- 최신 완성차 먼저 (completedAt = 완성차 재고에 들어온 시각). status 가 있으면 그 상태만 (IN_STOCK, SHIPPED)
- 다음 페이지는 nextVin 을 lastVin 으로 넘긴다
- 삭제된 완성차는 뺀다

| 확인 | 실패 |
|---|---|
| status 가 IN_STOCK·SHIPPED, lastVin 이 VIN 형식, size 1~100 | 400 |
| lastVin 완성차 있음 | 404 `FINISHED_VEHICLE_NOT_FOUND` |

### 5. LOT 별 재고 (inventory) `GET /inventory/parts/{partId}/lots`
gateway `/api/inventory/parts/{partId}/lots`. 그 부품의 남은 LOT 을 다음에 쓸 순서(입고 순)로
```json
{ "partCode": "ENG-G16", "partName": "가솔린 엔진 1.6", "onHand": 300, "withoutLotQty": 290,
  "lots": [ { "lotNo": "LOT-261017-000012", "receivedQty": 10, "remainingQty": 10, "receivedAt": "2026-10-17T10:00:00" } ] }
```
- onHand = 창고 실물(available + reserved), withoutLotQty = LOT 없는 재고(onHand − LOT 남은 수량 합, docs/specs/part-lot.md 와 같은 계산)
- 다 쓴 LOT(remainingQty 0)은 뺀다

| 확인 | 실패 |
|---|---|
| partId 양수 | 400 |
| 부품 있음 (삭제되지 않음) | 404 `PART_NOT_FOUND` |

## 규칙 / 예외
| 상황 | 처리 |
|---|---|
| 목록이 비었음 | 200, 빈 목록 (페이지면 last = true) |
| 페이지를 넘기는 사이 새 주문·완성차가 생김 | 이미 받은 것보다 최신이라 다음 페이지에 섞이지 않는다 (id < 마지막 id) |
| lastOrderNo / lastVin 이 다른 상태의 것 | 그 id 보다 작은 것부터 status 로 거른다 (오류 아님) |
| BOM 이 없거나 삭제된 제품 | 제품 목록에 없다 |

### 테이블 변경
없음

## 관련 이벤트 (events.md 참조)
없음

## 완료 조건 (테스트로 확인할 것)
production
- [ ] 제품 목록: 삭제되지 않고 BOM 이 있는 제품만, 코드 순
order
- [ ] 주문 목록: 최신 먼저, status 거르기, lastOrderNo 로 다음 페이지(nextOrderNo, last), 내부 id 없음
- [ ] 400 (status 값, lastOrderNo 형식, size 범위), 404 없는 lastOrderNo, 빈 목록
inventory
- [ ] 부품 재고 목록: 삭제된 부품 빼고 코드 순, available·reserved
- [ ] 완성차 목록: 최신 먼저, status 거르기, lastVin 으로 다음 페이지, 삭제된 것 빼기, 내부 id 없음, 400·404
- [ ] LOT 별 재고: 남은 LOT 입고 순, onHand·withoutLotQty, 다 쓴 LOT 빼기, 400·404
공통
- [ ] api-docs 에 summary 와 응답 코드
