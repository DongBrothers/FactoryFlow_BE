# simulator 서비스
- DB: 없음 (JPA, Flyway 의존성 없음)
- 발행: 없음
- 수신: 없음
- 역할: 생산 라인. client/ 로 production 내부 API 를 호출해 READY 건을 시작, 완료, 불량 처리 (docs/specs/saga-order-fulfillment.md)
- 진입점: controller/SimulatorController (HTTP), event/listener (이벤트) → service/SimulatorService
- 보상: 없음
- 에러 코드: exception/SimulatorErrorCode (common-web ErrorCode 구현, CustomException 으로 던진다)
