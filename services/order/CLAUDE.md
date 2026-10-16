# order 서비스
- DB: order_db (Flyway: src/main/resources/db/migration). 테이블: customer_order (order_no UUID UNIQUE, 밖으로는 order_no 만 노출), order_item, 차량(생산 건)별 상태 (해당 하위 이슈에서 추가)
- 상태: 차량(생산 건)마다 상태를 갖고, 품목 상태는 그 차량들의 요약, customer_order.status 는 품목들의 요약이다. 차량 상태를 바꿀 때 같은 트랜잭션에서 함께 갱신
- 스냅샷: 주문 생성 때 client/ 로 production 에 1회 조회해 제품 존재 확인 + product_code, product_name 저장
- 발행: order.created, order.cancel-requested, order.cancelled
- 수신: production.status-changed, production.cancelled, production.cancel-rejected, production.completed, shipment.completed
- API: 주문 생성 POST /orders, 주문 상세 GET /orders/{orderNo}, 주문 취소 요청 POST /orders/{orderNo}/cancel (docs/specs/order-cancel.md)
- 보상: 주문 취소 시 차량 CANCEL_REQUESTED + order.cancel-requested. production 이 차량마다 cancelled / cancel-rejected 로 답하고, 응답이 끝나면 취소된 차량이 있을 때 order.cancelled (docs/specs/saga-order-fulfillment.md)
