# auth 서비스
- DB: auth_db (Flyway: src/main/resources/db/migration). 테이블: customer, admin
- 계정: 고객(customer)과 관리자(admin)는 테이블이 따로다. 관리자는 회원가입 없이 별도 절차로만 만든다
- 비밀번호는 해시만 저장한다. 개인정보 컬럼(전화, 주소 등)을 추가하면 암호화해서 저장한다
- 발행: 없음
- 수신: 없음
- 진입점: controller/AuthController (HTTP), event/listener (이벤트) → service/AuthService
- 보상: 없음
- 에러 코드: exception/AuthErrorCode (common-web ErrorCode 구현, CustomException 으로 던진다)
