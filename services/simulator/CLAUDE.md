# simulator 서비스
- DB: 없음 (JPA, Flyway 의존성 없음)
- 발행: 없음
- 수신: 없음
- 진입점: controller/SimulatorController (HTTP), event/listener (이벤트) → service/SimulatorService
- 보상: 없음
- 에러 코드: exception/SimulatorErrorCode (common-web ErrorCode 구현, CustomException 으로 던진다)
