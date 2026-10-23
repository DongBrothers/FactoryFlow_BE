# 발주 제안

## 목적
부족 부품 조회(`GET /inventory/parts/shortages`)는 이미 발주한(ORDERED, 아직 입고 안 됨) 수량을 빼지 않는다 (inventory 는 구매를 모른다). 그래서 구매 담당이 발주 중 목록을 따로 보고 빼야 하고, 놓치면 같은 부품을 두 번 산다 (#170 2번).
현업 MRP 의 기본 기능처럼 **"더 사야 할 수량 = 부족 수량 − 발주 중 수량"** 을 부품마다 계산해 보여 주고, 지난번에 산 공급업체를 함께 알려 준다.

- 제안만 한다. 구매 건은 지금처럼 담당자가 `POST /purchases` 로 직접 만든다 (자동 발주 없음, #114)
- 안전 재고, 발주 단위(MOQ), 리드타임, 수요 예측은 하지 않는다 (기준 정보가 없다)

## 흐름

### 발주 제안 조회 (purchase) `GET /purchases/suggestions`
gateway `/api/purchases/suggestions`. 조회라 READ 권한 (SALES·PURCHASE·PRODUCTION, docs/specs/role-permissions.md). gateway·권한 표 변경 없음

1. purchase 가 inventory 내부 API `GET /internal/parts/shortages` 로 부족 부품을 받는다 (client, 트랜잭션 밖). 응답은 공개 API 와 같은 값
2. purchase DB 에서 그 부품들의 **ORDERED 구매 건 qty 합**(발주 중 수량)을 부품별로 한 번에 읽는다
3. 그 부품들의 **가장 최근에 만든 구매 건의 공급업체**(상태 무관, 구매 건 id 가 가장 큰 것. 구매 건은 만들 때 바로 발주라 id 순 = 발주 순)를 한 번에 읽는다. 삭제된 공급업체도 이름은 보여 준다
4. 부품마다 `suggestedQty = max(0, shortQty − orderedQty)`

```json
[ { "partId": 1012, "partCode": "TIRE-18", "partName": "타이어 18인치", "unit": "EA",
    "available": 3, "waitingQty": 20, "shortQty": 17, "orderedQty": 10, "suggestedQty": 7,
    "lastSupplierId": 2, "lastSupplierName": "한국타이어" } ]
```
- 부족 부품은 모두 나온다. 발주 중 수량으로 이미 채워지는 부품은 `suggestedQty` 0 (담당자가 "이미 오고 있음" 을 본다)
- 순서: suggestedQty 큰 순, 같으면 부품 코드 순. 페이지 없음 (부족한 부품만이라 적다)
- 산 적 없는 부품은 lastSupplierId·lastSupplierName 이 null

### inventory 내부 API `GET /internal/parts/shortages`
- 공개 `GET /inventory/parts/shortages` 와 같은 계산 (`PartService.findShortages`), 응답 이름만 내부용(`InternalPartShortageResponse`)
- 서비스끼리만 부르고 gateway·api-docs 에 노출하지 않는다

## 규칙 / 예외
| 상황 | 처리 |
|---|---|
| 부족 부품 없음 | 빈 목록 (purchase DB 를 읽지 않음) |
| 발주 중 수량이 부족 수량보다 많음 | suggestedQty 0 (음수 아님) |
| RECEIVED·CANCELLED 구매 건 | 발주 중 수량에 넣지 않는다 (입고된 것은 이미 available 에 들어 있다) |
| inventory 응답 없음·3초 초과·비정상 응답 | 503 `INVENTORY_UNAVAILABLE` (구매 건 만들기와 같은 client) |

- 발주 중 수량은 그때그때 계산한다 (테이블 변경 없음). 부품별 합계·최근 구매 건은 QueryDSL 로 DB 에서 묶어 읽는다 (purchase 에 querydsl 의존성 추가)
- 구매는 inventory 를 부르기만 한다 (purchase → inventory, 이미 있는 방향). inventory 는 purchase 를 부르지 않는다

## 관련 이벤트 (events.md 참조)
없음

## 완료 조건 (테스트로 확인할 것)
- [ ] 부족 부품마다 orderedQty(ORDERED 합), suggestedQty = max(0, shortQty − orderedQty)
- [ ] RECEIVED·CANCELLED 구매 건은 orderedQty 에 안 들어감, 다른 부품의 ORDERED 도 안 들어감
- [ ] 발주 중 수량이 더 많으면 suggestedQty 0, 목록에는 남음
- [ ] lastSupplier: 가장 최근 구매 건의 공급업체, 산 적 없으면 null
- [ ] 순서: suggestedQty 큰 순, 같으면 부품 코드 순
- [ ] 부족 부품이 없으면 빈 목록
- [ ] inventory 실패·시간 초과는 503 INVENTORY_UNAVAILABLE
- [ ] inventory `GET /internal/parts/shortages` 가 공개 부족 부품 조회와 같은 값
- [ ] Swagger: `GET /purchases/suggestions` 문서화
