# gateway 서비스
- Spring Cloud Gateway (WebFlux). Servlet 기반 common-web 은 사용하지 않는다
- DB: 없음
- 발행: 없음
- 수신: 없음
- 역할: 외부 요청 라우팅 → 각 서비스. 업무 로직을 두지 않는다
- 사용자 헤더: 클라이언트가 보낸 X-User-* 는 모두 지우고, 검증한 JWT 의 sub 로 X-User-Id 만 다시 채운다 (config/UserHeaderFilter). role 은 넘기지 않는다
- 경로: SecurityConfig 의 경로는 밖 주소(/api/...) 기준. 라우트는 StripPrefix=1 로 /api 를 떼고 넘긴다
- CORS: 허용 origin 은 app.cors.allowed-origins (env CORS_ALLOWED_ORIGINS). 기본은 비어 있음(모두 거부), 프론트 주소는 환경별 설정에 넣는다. allowCredentials=true 라 "*" 는 안 된다
