> ArchUnit 이 이미 잡는 것(타 서비스 import, Controller/Listener 의 Repository 직접 호출,
> RabbitTemplate 직접 사용, 필드 주입, System.out)은 보지 않는다.

- 다른 서비스 DB 직접 접근 (네이티브 SQL, 다른 스키마 이름)
- Controller/Listener 안의 비즈니스 로직 → service 로 이동
- 이벤트 발행이 트랜잭션 + Outbox
- Listener 멱등 처리
- 실패 시 보상 이벤트
- events.md 계약과 payload 일치
