# order 서비스
- DB: order_db (Flyway: src/main/resources/db/migration). 테이블: customer_order (order_no UUID UNIQUE, 밖으로는 order_no 만 노출), order_item
- 상태: 품목(order_item)마다 상태를 갖고, customer_order.status 는 품목 상태의 요약이다. 품목 상태를 바꿀 때 같은 트랜잭션에서 함께 갱신
- 스냅샷: 주문 생성 때 client/ 로 production 에 1회 조회해 제품 존재 확인 + product_code, product_name 저장
- 발행: order.created, order.cancelled
- 수신: production.status-changed, production.completed, production.cancel-rejected
- 진입점: controller/OrderController (HTTP), event/listener (이벤트) → service/OrderService
- 보상: 주문 취소 시 품목 CANCEL_REQUESTED + order.cancelled. production 이 생산 시작 전이면 취소, 아니면 cancel-rejected 로 품목 복귀 (docs/specs/saga-order-fulfillment.md)
- 에러 코드: exception/OrderErrorCode (common-web ErrorCode 구현, CustomException 으로 던진다)
