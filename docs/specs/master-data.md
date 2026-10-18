# 기준 데이터 등록·수정 (제품·BOM, 부품, 공급업체)

## 목적
지금은 제품·BOM·부품·공급업체를 dev 시드와 SQL 로만 넣을 수 있다. 담당자가 화면에서 등록하고 고칠 수 있게 한다.
등록과 수정만 한다. 삭제는 하지 않는다 (필요하면 SQL 로 is_deleted, 흐름은 docs/specs/stalled-flows.md 그대로).

| 기준 데이터 | 서비스 | 담당 (역할) |
|---|---|---|
| 제품 + BOM | production | 생산 (PRODUCTION) |
| 부품 | inventory | 구매·자재 (PURCHASE) |
| 공급업체 | purchase | 구매 (PURCHASE) |

## 흐름

### 1. 부품 (inventory)
**등록** `POST /inventory/parts` (gateway `/api/inventory/parts`)
```json
{ "code": "TIRE-19", "name": "타이어 19인치", "unit": "EA" }
```
- part 1줄: available 0, reserved 0. 재고가 없으므로 LOT 도 없다 (첫 입고나 재고 조정 때 생긴다)
- `201` 부품 `{ partId, code, name, unit, available, reserved }` (부품 목록 `GET /inventory/parts` 의 한 줄과 같은 모양)

**수정** `PUT /inventory/parts/{partId}`
```json
{ "name": "타이어 19인치 (사계절)", "unit": "EA" }
```
- 이름과 단위만 바꾼다. code 는 바꾸지 않는다 (구매 건·화면이 코드로 부품을 알아본다)
- 수량(available, reserved)은 이 API 로 바꾸지 않는다 (입고, 예약, 재고 조정만 바꾼다)
- `200` 부품

### 2. 공급업체 (purchase)
**등록** `POST /purchases/suppliers` (gateway `/api/purchases/suppliers`)
```json
{ "name": "넥센타이어", "contact": "02-0000-0004" }
```
- `201` 공급업체 `{ supplierId, name, contact }` (공급업체 목록 `GET /purchases/suppliers` 의 한 줄과 같은 모양)

**수정** `PUT /purchases/suppliers/{supplierId}` — 같은 본문, `200`
- 이미 발주한 구매 건의 supplier_id 는 그대로다 (공급업체 이름이 바뀌어도 같은 업체)

### 3. 제품 + BOM (production)
**등록** `POST /production/products` (gateway `/api/production/products`)
```json
{ "code": "IONIQ5", "name": "아이오닉 5", "bom": [ { "partId": 1001, "qty": 1 }, { "partId": 1012, "qty": 4 } ] }
```
1. 요청 형식 확인 (Bean Validation)
2. **트랜잭션 밖에서** inventory 내부 API 로 BOM 부품이 있는지 한 번에 확인: `GET /internal/parts?ids=1001,1012` (purchase 가 쓰는 것과 같은 API, 삭제된 부품은 응답에서 빠진다)
3. `ProductWriter` 가 한 트랜잭션에서 product 1줄 + bom 부품 수만큼 저장
4. `201` 제품 상세 `{ productId, code, name, bom: [ { partId, qty } ] }`
- BOM 이 있어야 주문할 수 있으므로 등록 때 BOM 을 1개 이상 받는다. 등록하면 바로 제품 목록(`GET /production/products`)에 나오고 주문할 수 있다

**수정** `PUT /production/products/{productId}`
```json
{ "name": "아이오닉 5 (2027)", "bom": [ { "partId": 1001, "qty": 1 }, { "partId": 1013, "qty": 4 } ] }
```
- 이름과 BOM 을 바꾼다. code 는 바꾸지 않는다
- BOM 은 통째로 바꾼다 (요청의 BOM 이 새 BOM)
  - 요청에 있고 지금도 있는 부품: 수량만 바꾼다
  - 요청에 있고 전에 지운 부품: 그 줄을 되살리고 수량을 바꾼다 (UNIQUE(product_id, part_id) 라 새 줄을 만들 수 없다)
  - 요청에 있고 처음 보는 부품: 새 줄
  - 지금 있는데 요청에 없는 부품: 소프트 삭제
- 부품 확인은 등록과 같다 (트랜잭션 밖, 그다음 `ProductWriter`)
- `200` 제품 상세

**상세** `GET /production/products/{productId}` (BOM 수정 화면용, 읽기 역할)
- `200` 제품 상세. BOM 은 삭제되지 않은 줄만, partId 순

**BOM 을 바꿔도 이미 들어간 주문은 그대로다**: 차량마다 필요한 부품은 생산 건을 만들 때(`order.created` 수신) 그때의 BOM 으로 계산해 `production.material-requested` 로 보냈다. 그 뒤 BOM 을 바꿔도 이미 보낸 예약은 바뀌지 않고, 새 주문부터 새 BOM 을 쓴다.

### 4. production → inventory 호출 (새 client)
- `client/inventory/PartClient` + `PartClientConfig` (HttpServiceProxyFactory). purchase 의 같은 이름 client 와 같은 구조
- 주소 `clients.inventory.url` (application.yml 기본 `${INVENTORY_URL:http://localhost:8082}`), 시간 제한 `clients.inventory.timeout` 3초
- 호출 실패, 시간 초과, 본문이나 data 가 없는 응답은 client 의 default 메서드에서 503 `INVENTORY_UNAVAILABLE`
- dev 는 `APP_DEV_YML_PRODUCTION` 시크릿에 `clients.inventory.url: http://inventory.factoryflow.local:8080` 를 더한다 (배포 전에)

## 규칙 / 예외
| 상황 | 응답 |
|---|---|
| 부품: code 없음·공백·50자 초과, name 없음·공백·100자 초과, unit 이 EA·SET·KG 가 아님 | 400 |
| 제품: code 없음·공백·50자 초과, name 없음·공백·100자 초과, bom 비어 있음, partId 없음, qty 1 미만, 같은 partId 두 번 (`@UniquePartIds`) | 400 |
| 공급업체: name 없음·공백·100자 초과, contact 100자 초과 | 400 |
| id 가 숫자가 아님·1 미만 | 400 |
| 같은 code 의 부품·제품이 이미 있음 (지운 것 포함, UNIQUE 제약) | 409 `PART_CODE_DUPLICATED` / `PRODUCT_CODE_DUPLICATED` |
| 수정할 부품·제품·공급업체가 없음 (지운 것 포함) | 404 `PART_NOT_FOUND` / `PRODUCT_NOT_FOUND` / `SUPPLIER_NOT_FOUND` |
| BOM 의 부품이 inventory 에 없거나 지워짐 | 404 `PART_NOT_FOUND` (production) |
| inventory 호출 실패·3초 초과·비정상 응답 | 503 `INVENTORY_UNAVAILABLE` (production) |
| 역할이 아님 (부품·공급업체는 PURCHASE, 제품은 PRODUCTION) | 403 (gateway) |

- 같은 code 를 동시에 두 번 등록하면 DB 의 UNIQUE 제약이 늦은 쪽을 막는다. 그 실패도 409 로 돌려준다
- 두 사람이 같은 제품을 동시에 고치면 나중에 저장한 쪽이 남는다. 수정은 제품 행을 잠그고(`PESSIMISTIC_WRITE`) 한 번에 하나씩 처리하므로, 둘 다 같은 새 부품을 넣어도 BOM 줄을 두 번 만들지 않는다 (잠그지 않으면 UNIQUE(product_id, part_id) 위반으로 500 이 났다, PR #128 리뷰)
- 이벤트는 보내지 않는다. 테이블 변경도 없다 (기존 part, product, bom, supplier 그대로)

## 관련 이벤트 (events.md 참조)
없음

## 완료 조건 (테스트로 확인할 것)
- [ ] 부품 등록: 201, available·reserved 0, LOT 없음. 중복 code 409, 형식 오류 400
- [ ] 부품 수정: 이름·단위만 바뀌고 code·수량은 그대로. 없는 부품 404
- [ ] 공급업체 등록·수정: 201·200, 없는 공급업체 404, 형식 오류 400
- [ ] 제품 등록: inventory 에 부품 확인 후 product + bom 저장, 제품 목록에 나옴. 중복 code 409, 없는 부품 404, inventory 장애 503 (둘 다 아무것도 저장하지 않음), 같은 partId 두 번 400
- [ ] 제품 수정: BOM 통째로 바꾸기 (수량 변경, 지운 줄 되살리기, 새 줄, 빠진 줄 소프트 삭제). 없는 제품 404
- [ ] 같은 제품을 동시에 고치며 같은 새 부품을 넣어도 둘 다 200, BOM 줄은 하나
- [ ] 제품 상세: 삭제되지 않은 BOM 만
- [ ] BOM 을 바꾼 뒤 들어온 주문만 새 BOM 으로 부품을 요청함 (기존 생산 건의 예약은 그대로)
- [ ] production `PartClient`: 실패·시간 초과·data 없음 503
- [ ] gateway: 부품·공급업체 POST/PUT 은 PURCHASE, 제품 POST/PUT 은 PRODUCTION, 나머지 역할 403
- [ ] Swagger: 새 API 의 summary·응답 코드
