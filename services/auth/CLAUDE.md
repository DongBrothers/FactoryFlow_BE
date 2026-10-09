# auth 서비스
- DB: auth_db (Flyway: src/main/resources/db/migration)
- 발행: 없음
- 수신: 없음
- 진입점: controller/AuthController (HTTP), event/listener (이벤트) → service/AuthService
- 보상: 없음
- 에러 코드: exception/AuthErrorCode (common-web ErrorCode 구현, CustomException 으로 던진다)
