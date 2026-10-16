# purchase 서비스
- DB: purchase_db (Flyway: src/main/resources/db/migration). 테이블: supplier, purchase
- 스냅샷: part_code, part_name 은 AUTO 면 inventory.shortage 이벤트에서, MANUAL 이면 client/ 로 inventory 1회 조회해서 저장
- 발행: purchase.received (purchaseId, partId, qty)
- 수신: inventory.shortage (같은 부품의 AUTO + REQUESTED 건이 있으면 qty 에 합침. 발주한 건에는 합치지 않고 새 건)
- 구매 담당 API: 목록(상태별 커서), 공급업체 목록, 발주(REQUESTED → ORDERED), 입고(ORDERED → RECEIVED + purchase.received). 공급업체는 기본 데이터(Flyway) (docs/specs/purchase-receiving.md)
- 동시 변경: 구매 건은 @Version 으로 막는다 (부족 합치기 vs 발주·입고)
- 보상: 없음 (입고 취소는 이후 기능)
