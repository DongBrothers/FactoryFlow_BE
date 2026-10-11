# purchase 서비스
- DB: purchase_db (Flyway: src/main/resources/db/migration). 테이블: supplier, purchase
- 스냅샷: part_code, part_name 은 AUTO 면 inventory.shortage 이벤트에서, MANUAL 이면 client/ 로 inventory 1회 조회해서 저장
- 발행: purchase.received
- 수신: inventory.shortage (같은 부품의 AUTO + REQUESTED 건이 있으면 qty 에 합침)
- 진입점: controller/PurchaseController (HTTP), event/listener (이벤트) → service/PurchaseService
- 보상: 없음 (입고 취소는 이후 기능)
- 에러 코드: exception/PurchaseErrorCode (common-web ErrorCode 구현, CustomException 으로 던진다)
