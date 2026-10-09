# production 서비스
- DB: production_db (Flyway: src/main/resources/db/migration)
- 발행: production.completed
- 수신: inventory.reserved
- 진입점: api/ProductionApi
- 보상: 미정 (정의 시 docs/specs 에 먼저 작성)
- 에러 코드: exception/ProductionErrorCode (common-web ErrorCode 구현, CustomException 으로 던진다)
