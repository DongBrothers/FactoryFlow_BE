# purchase 서비스
- DB: purchase_db (Flyway: src/main/resources/db/migration). 테이블: supplier, purchase
- 구매: 구매 담당이 부족 부품 조회(inventory GET /inventory/parts/shortages)를 보고 부품·수량·공급업체를 넣어 구매 건을 만든다. 만들면 바로 발주(ORDERED), 발주한 직원(ordered_by = X-User-Id) (docs/specs/purchase-manual.md). 자동 구매(inventory.shortage, AUTO·REQUESTED)는 #114 에서 없앴다
- 스냅샷: part_code, part_name 은 client/ 로 inventory(GET /internal/parts)에 1회 조회해 저장한다. 호출은 PurchaseService 에서 트랜잭션 밖에, 저장은 PurchaseWriter. 실패·시간 초과는 INVENTORY_UNAVAILABLE(503)
- 발행: purchase.received (purchaseId, partId, qty)
- 수신: 없음
- 구매 담당 API: 목록(ORDERED·RECEIVED, 커서), 공급업체 목록, 구매 건 만들기(= 발주), 입고(ORDERED → RECEIVED + purchase.received). 공급업체는 기본 데이터(Flyway)에 더해 등록·수정 POST /purchases/suppliers, PUT /purchases/suppliers/{id} (docs/specs/master-data.md) (docs/specs/purchase-receiving.md "3. 입고")
- 발주 제안: GET /purchases/suggestions. inventory GET /internal/parts/shortages(PartClient) 로 부족 부품을 받아 부품별 ORDERED 합을 빼 suggestedQty, 가장 최근 구매 건의 공급업체를 붙임 (QueryDSL: PurchaseRepositoryCustom/Impl, docs/specs/purchase-suggestion.md)
- 동시 변경: 구매 건은 @Version 으로 막는다 (같은 구매 건을 동시에 입고)
- 보상: 없음 (구매 취소·부분 입고는 #105)
