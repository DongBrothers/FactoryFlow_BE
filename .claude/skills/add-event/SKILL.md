---
name: add-event
description: 새 RabbitMQ 이벤트를 추가할 때 사용
---
1. docs/specs/events.md 에 이벤트가 있는지 확인. 없으면 아래 형식으로 추가안을 제시하고 멈춘다 (사람이 추가)
   - 이름: <도메인>.<과거형> (예: order.created)
   - 발행: 서비스 / 수신: 서비스 목록
   - payload 필드 + 공통 필드(eventId, traceId, version, occurredAt)
2. 발행 서비스: event/publisher/ 에 레코드 + EventPublisher.publish() (같은 트랜잭션, Outbox)
3. 수신 서비스: event/listener/ 에 @RabbitListener → processed_event로 중복 확인 → api/ 호출
4. 실패 시 보상 이벤트 정의 여부 확인
5. 테스트: 발행 1개(Outbox 저장 확인), 수신 2개(정상 / 중복 무시)
