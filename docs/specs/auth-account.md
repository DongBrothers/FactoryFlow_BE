# 계정 발급과 인증

> 초안 (리뷰 대기). 로그인하는 사람은 **사내 직원**뿐이다. 고객은 로그인하지 않는다.
> 주문은 어떤 경로로든 들어오고, order 는 `customer_id` 만 가진다 (주문 유입 경로는 이 명세 범위 밖).

## 목적

직원 계정의 생애주기(발급 → 활성화 → 로그인 → 비활성)와 토큰 발급·검증을 정한다.
계정은 **관리자가 발급**하고(자가 회원가입 없음), 직원은 메일 링크로 **활성화**만 한다.
역할(role)은 관리자가 발급 시점에 정하며, 직원이 스스로 고를 수 없다.

발급만 하고 활성화하지 않는 사람이 있을 수 있으므로, **발급 단계에서는 DB 에 저장하지 않는다.**
대기 정보는 Redis 에 TTL 로 두고, 활성화(비밀번호 설정)가 끝난 계정만 `user_account` 에 저장한다.

### 이번 범위

- 관리자 계정 발급 (이메일·이름·역할 입력) → Redis 대기
- 역할 목록 (발급 화면의 역할 선택지, `role` 테이블)
- 활성화: 이메일로 받은 **일회용 링크**로 비밀번호 설정 → 이때 DB 저장
- 로그인(이메일 + 비밀번호)·토큰: JWT Access(RS256) + Refresh(Redis, 회전)
- 역할 기반 접근 제어 (admin 전용 API)
- 활성화 메일 발송 (SMTP, 아래 "활성화 메일")
- springdoc OpenAPI 문서

### 범위 밖 (이번에 안 함)

- 비밀번호 재설정(찾기)과 6자리 이메일 코드 — 다음 범위
- 계정 비활성화 API (상태 `DISABLED` 는 두되 바꾸는 API 는 다음 범위)
- 최초 관리자 seed (dev 는 DB 에 직접 넣어 두었다)
- MFA, 비번 주기 만료, SSO/외부 IdP, 복수 역할

## 흐름

### 0. 사전 (최초 1회)

- 최초 관리자 1명은 발급 흐름을 거치지 않고 DB 에 직접 심는다 (첫 발급자가 없으므로). 방법은 다음 범위.

### 1. 발급 (관리자) — `POST /api/auth/admin/users`

```json
{ "email": "kim@gmail.com", "name": "김철수", "role": "SALES" }
```

1. 요청 형식은 Bean Validation 으로 확인한다 (아래 규칙)
2. 이메일을 정규화한다 (앞뒤 공백 제거 + 소문자)
3. 검증: 허용 도메인인가 / `role` 테이블에 있는 역할인가 (없으면 400) / 이미 `user_account` 에 있는 이메일인가 (있으면 409)
4. 같은 이메일의 대기 건이 이미 있으면 **덮어쓴다** — 옛 토큰 키를 지워 옛 링크를 무효로 만든다
5. 새 활성화 토큰(랜덤 UUID) 발급 → Redis 에 두 키를 같은 TTL(24시간)로 저장 (아래 "Redis 키")
6. 그 이메일로 활성화 메일을 보낸다 (아래 "활성화 메일"). 보내지 못하면 5번에서 만든 두 키를 지우고 503
7. `201 Created`. DB 에 아직 행이 없으므로 id 는 돌려주지 않는다

```json
{ "email": "kim@gmail.com", "name": "김철수", "role": "SALES" }
```

### 1-1. 역할 목록 (관리자) — `GET /api/auth/admin/roles`

발급 화면의 역할 선택지. `role` 테이블을 `sort_order` 순서로 한 번에 준다 (몇 개뿐인 기준 정보라 페이지 없음). 아래는 V5 가 넣은 값.

```json
[
  { "code": "SALES", "name": "영업" },
  { "code": "PURCHASE", "name": "구매" },
  { "code": "PRODUCTION", "name": "생산" },
  { "code": "ADMIN", "name": "관리자" }
]
```

- 발급의 `role` 에는 `code` 를 보낸다. 화면에는 `name` 을 보인다
- 역할은 마이그레이션으로만 넣는다 (V5 가 처음 4개). 추가·삭제 API 는 없다. 역할마다 `permissions` 도 준다 (권한 편집 화면용, docs/specs/role-permission-admin.md)

### 2. 활성화 (직원) — `POST /api/auth/activation`

```json
{ "token": "3f2c...", "password": "..." }
```

1. 직원이 메일의 링크(프론트 페이지 `/activate?token=`)를 열고 비밀번호를 입력 → 프론트가 이 API 호출.
   링크를 열기만 해서는 토큰이 쓰이지 않는다 (이 API 호출 때 쓰인다). 24시간이 지나면 관리자가 다시 발급한다
2. 토큰 키를 **꺼내면서 삭제**한다 (1회용). 없거나 만료면 400
3. 대기 정보로 `user_account` 를 **처음 저장**한다 — `status = ACTIVE`, 비밀번호 해시, 새 `public_id`
   - 중복이 아닌 이유(DB 장애 등)로 저장이 실패하면 꺼낸 토큰을 되돌린다. 직원은 같은 링크로 다시 시도할 수 있다
     (그 사이 같은 이메일로 다시 발급됐으면 되돌리지 않는다). 응답은 500
4. `auth:pending:{email}` 키를 지운다
5. `204 No Content`

### 3. 로그인 (직원) — `POST /api/auth/login`

```json
{ "email": "kim@gmail.com", "password": "..." }
```

1. 이메일을 정규화하고, 잠금 상태면 429
2. 이메일로 계정 조회 → 없거나 비번이 틀리면 실패 횟수를 세고 401
3. `ACTIVE` 가 아니면 403
4. 성공 시 실패 횟수를 지우고 **Access(JWT) + Refresh** 발급
5. `200`. Access 는 본문, Refresh 는 httpOnly 쿠키

```json
{ "accessToken": "eyJ..." }
```

- Access 토큰 claim: `sub`(user_account.public_id), `role`(역할 코드). 이메일 같은 개인정보는 넣지 않는다.
  권한은 넣지 않는다 — gateway 는 Redis 로 받은 역할 → 권한 표로 판단하고, 프론트는 내 정보(`GET /api/auth/me`)로 받는다
  (docs/specs/role-permission-admin.md. #171 에서 넣었던 `permissions` 를 뺐다)

### 4. 토큰 재발급·로그아웃

- 재발급 `POST /api/auth/token/refresh`: 쿠키의 Refresh 제출 → 새 Access+Refresh 발급, **옛 Refresh 무효(회전)**. `200` + 새 쿠키
- 로그아웃 `POST /api/auth/logout`: 쿠키의 Refresh 를 Redis 에서 삭제(즉시 무효화) + 쿠키 만료. `204`

### 5. 검증 (gateway·서비스)

- gateway 가 auth 의 **내부 API JWKS 엔드포인트(`GET /internal/jwks`)에서 공개키를 받아 캐시**하고,
  Access 토큰 서명을 공개키로 검증한다 (auth 에 매 요청 묻지 않음). 키 교체 시 JWKS 만 갱신
- admin 전용 API 는 gateway 가 JWT 의 `role` 로 역할 → 권한 표를 찾아 `ACCOUNT_ADMIN` 이 있는지 확인한다. 다른 API 의 필요한 권한은
  docs/specs/role-permissions.md. auth 는 role 헤더를 받지 않는다 (2026-10-14, 리뷰)

## Redis 키

| 키 | 값 | TTL | 용도 |
|---|---|---|---|
| `auth:activation:{token}` | 대기 정보 hash (`email`, `name`, `role`) | 24시간 | 링크의 토큰으로 대기 정보 찾기 |
| `auth:pending:{email}` | token | 24시간 | 같은 이메일 재발급 시 옛 토큰 찾아 지우기 |
| `auth:refresh:{publicId}` | Refresh 토큰 | 14일 | 사용자당 Refresh 1개 (단일 세션). 재발급·로그아웃 때 값이 같을 때만 지운다(조회와 삭제를 한 번에, 동시 요청 중 하나만 통과) |
| `auth:login-fail:{email}` | 실패 횟수 | 마지막 실패부터 15분 | 로그인 실패 잠금 |

- 링크에는 토큰만 담는다. 이메일을 링크에 넣지 않는다 (URL·로그·브라우저 기록에 개인정보가 남음)

## 활성화 메일

- 발급 요청 안에서 바로(동기) 보낸다. 관리자가 응답으로 발송 성공·실패를 바로 알 수 있게
- 받는 사람: 발급한 이메일. 보내는 사람: 설정값 (SMTP 계정)
- 제목 `[FactoryFlow] 계정 활성화 안내`, 본문(텍스트): 이름, 활성화 링크, "24시간 동안 유효하며 지나면 관리자에게 다시 발급을 요청"
- 링크: `{auth.activation-mail.link-base-url}?token={토큰}`. 설정값은 프론트의 활성화 페이지 주소다.
  프론트가 아직 없어 기본값은 로컬 자리표시자(`http://localhost:5173/activate`)이고, 환경별 설정으로 바꾼다.
  프론트가 없는 동안은 메일의 토큰을 복사해 활성화 API 를 직접 부른다
- 메일 서버(`spring.mail.*`)의 기본값은 로컬 Mailpit(`localhost:1025`, docker compose)이다. 보낸 메일은
  `http://localhost:8025` 에서 본다. 계정·비밀번호는 저장소에 두지 않고, dev 는 `APP_DEV_YML_AUTH`(노션에도)로 덮어쓴다.
  dev 설정을 빠뜨리면 auth 는 정상으로 뜨고 발급만 503 이 난다
- 연결·응답 제한 시간은 5초. 넘으면 발송 실패로 본다
- **발송 실패** (연결 실패, 인증 실패, 시간 초과): 이번 발급으로 만든 두 키를 지우고 503 `MAIL_UNAVAILABLE`.
  관리자가 다시 발급하면 된다. 그 사이 같은 이메일로 다른 발급이 덮어썼으면 그 건은 지우지 않는다.
  같은 이메일의 옛 대기 건은 발급 4번에서 이미 덮어써졌으므로 남지 않는다 (옛 링크도 무효)
- 로그: 발송 성공·실패만 남긴다. 이메일·토큰·링크는 남기지 않는다

## 규칙 / 예외

### API 별 응답

이 문서의 API 경로(`/api/auth/...`)는 밖에서 gateway 로 부르는 주소다. gateway 가 앞의 `/api` 를 떼고(`StripPrefix=1`,
다른 서비스와 같은 규칙) 넘기므로 auth 컨트롤러는 `/auth/...` 를 받는다. Refresh 쿠키 경로는 브라우저 기준이라 `/api/auth` 다.

| API | 상황 | 응답 |
|---|---|---|
| 발급 | 성공 (같은 이메일 대기 건이 있으면 덮어쓰고 성공) | 201 |
| 발급 | 형식 오류 (이메일 형식·빈 값·role 없음), 허용 도메인 아님 (INVALID_EMAIL_DOMAIN), `role` 테이블에 없는 역할 (INVALID_ROLE) | 400 |
| 발급 | ADMIN 이 아님 (gateway 가 JWT role 로 막음, auth 까지 오지 않음) | 403 |
| 발급 | 이미 활성화된 이메일 (DUPLICATE_EMAIL) | 409 |
| 발급 | 활성화 메일을 보내지 못함 (MAIL_UNAVAILABLE). 대기 정보는 남지 않으므로 다시 발급하면 된다 | 503 |
| 역할 목록 | 성공 | 200 |
| 역할 목록 | ADMIN 이 아님 (gateway 가 JWT role 로 막음) | 403 |
| 활성화 | 성공 | 204 |
| 활성화 | 형식 오류, 없거나 만료·재사용된 토큰 (INVALID_ACTIVATION_TOKEN) | 400 |
| 활성화 | 저장 순간 같은 이메일이 이미 있음 (같은 이메일을 거의 동시에 두 번 발급한 경우, DUPLICATE_EMAIL). 계정은 이미 있으므로 재시도가 아니라 로그인하면 된다 | 409 |
| 로그인 | 성공 | 200 + 쿠키 |
| 로그인 | 형식 오류 | 400 |
| 로그인 | 없는 이메일 또는 틀린 비번 (LOGIN_FAILED, 둘을 구분하지 않음) | 401 |
| 로그인 | `DISABLED` 계정 (ACCOUNT_NOT_ACTIVE) | 403 |
| 로그인 | 실패 횟수 초과로 잠김 (ACCOUNT_LOCKED) | 429 |
| 재발급 | 성공 | 200 + 쿠키 |
| 재발급 | 쿠키 없음, 옛·위조·만료 토큰, 계정 없음 (INVALID_REFRESH_TOKEN) | 401 |
| 재발급 | `DISABLED` 계정 (ACCOUNT_NOT_ACTIVE) | 403 |
| 로그아웃 | 항상 (쿠키가 없거나 옛 토큰이어도) | 204 |
| JWKS `GET /internal/jwks` | 항상 | 200 (JWKS 표준 형식, 공통 ApiResponse 로 감싸지 않음). 서버끼리만 쓰는 내부 API: 다른 내부 API 처럼 `InternalJwksController`, 문서에 "인증 (내부)" 로 표시, gateway 로 노출하지 않음 |

### 계정·토큰

- **로그인 ID**: 이메일. 사번(`employee_no`)은 두지 않는다
- **이메일 정규화**: 저장·비교 전에 앞뒤 공백 제거 + 소문자. `Kim@` 과 `kim@` 은 같은 계정. 단 발급은 이메일 형식
  검사(`@Email`)를 먼저 하므로 앞뒤 공백이 있으면 400 (관리자 입력 실수를 알려 줌). 로그인은 공백을 무시한다
- **유일성**: `user_account.email` 유니크. 대기 건은 이메일당 1개 (재발급 시 덮어쓰기)
- **상태**: `ACTIVE`, `DISABLED`. 활성화 전 계정은 DB 에 없으므로 `INVITED` 상태는 없다. 비밀번호는 NOT NULL
- **식별자**: 내부 PK·조인은 `id`(BIGINT), 외부로 나가는 것(JWT `sub`, 타 서비스 참조)은
  `public_id`(UUID). 순차 id 노출로 인한 사용자 열거를 막는다. Refresh 토큰도 `publicId.랜덤` 형식이다
- **키·서명**: Access 는 비대칭키 **RS256**. auth 만 개인키로 서명, 검증 측은 공개키만 가진다
- **토큰 만료**: Access 15분, Refresh 14일, 활성화 토큰 24시간
- **토큰 전달**: Access 는 응답 바디, Refresh 는 **httpOnly 쿠키**(Secure·SameSite, Path=/api/auth,
  Max-Age=Refresh TTL). JS 가 Refresh 를 못 읽어 XSS 탈취를 막는다
- **단일 세션(Refresh)**: 사용자당 하나만 유효하다. 로그인·재발급 때 새로 발급하면 이전 Refresh 는 덮어써져 무효
- **허용 도메인**: 설정값(`auth.allowed-email-domain`). 로컬·데모는 `gmail.com`, 운영은 회사 도메인.
  gmail 제한은 데모용 placeholder 이며 실제 직원 증명이 아니다
- **로그인 실패 잠금**: N회 연속 실패 시 일시 잠금 (Redis 카운터, 이메일 기준). 무차별 대입 차단
- **역할**: 1인 1역할. `role` 테이블(`id` PK, `code` UNIQUE, `name` 화면 이름, `sort_order` 선택지 순서)에
  `SALES 영업, PURCHASE 구매, PRODUCTION 생산, ADMIN 관리자` (V5). `user_account.role_id` 가 `role(id)` 를 FK 로 가리킨다
  (`fk_user_account_role`). V5 가 옛 `user_account.role` 코드 글자를 `role_id` 로 옮기고 `role` 컬럼을 지운다.
  API(발급 요청·응답·역할 목록)·JWT `role` 클레임·발급 대기(Redis)의 `role` 은 코드 글자다. 활성화 때 코드로 역할을 찾아 `role_id` 로 저장하고,
  로그인·재발급 때 `role_id` 로 코드를 찾아 클레임에 넣는다.
  WORKER 는 #118 에서 PRODUCTION 으로 합쳤다 (V3). 발급 대기의 WORKER 를 PRODUCTION 으로 읽던 변환은 대기 TTL(24시간)이 지나 남은 건이 없어 지웠다 (2026-10-23).
  ADMIN 은 계정 발급만 한다 (docs/specs/role-permissions.md)
- **역할과 권한**: 역할이 갖는 권한의 원본은 auth DB 한 곳이다 (V6).
  `permission`(`id` PK, `code` UNIQUE, `name`) 에 `READ, ORDER_WRITE, PRODUCTION_WRITE, PURCHASE_WRITE, ACCOUNT_ADMIN`,
  `role_permission`(`id` PK, `role_id`·`permission_id` 둘 다 FK, 둘을 합쳐 UNIQUE `uk_role_permission`) 에 역할별 권한.
  auth 는 표 전체를 Redis(`auth:role-permissions` 키와 같은 이름의 `-changed` 채널)로 gateway 에 보낸다. 역할의 권한은 운영 중 관리자 API 로 바꾼다
  (docs/specs/role-permission-admin.md). 권한이 없는 역할도 발급·로그인은 되고 gateway 에서 모두 403 이다
- **역할을 새로 넣을 때**: 기존 권한을 조합하는 역할이면 마이그레이션 하나(`role` 행 + `role_permission` 행)로 끝난다.
  gateway·프론트는 권한만 보므로 고치지 않는다. 새 권한 종류(새 기능)가 필요할 때만 gateway·프론트를 함께 고친다 (docs/specs/role-permissions.md "규칙 / 예외")

### 검증·로그·문서

- 요청 형식 검증은 모두 DTO 의 Bean Validation 으로 한다 (`@Valid`). 형식 오류는 common-web 공통 핸들러가 400 `VALIDATION_ERROR`
- **로그에 남기지 않는 것**: 비밀번호(평문·해시), 활성화·Refresh 토큰, 이메일 (개인정보, 기획서 10.1)
- springdoc OpenAPI 로 문서화한다. auth 는 `/v3/api-docs` 와 Swagger UI 를 연다. 응답 코드는 API 마다 `@ApiResponse` 로
  위 표의 코드를 전부 적고, 공통 예외 핸들러 응답을 자동으로 붙이지 않는다 (`springdoc.override-with-generic-response: false`)
- **권한 확인과 헤더 신뢰 전제 (보안)**: JWT 는 gateway 만 검증한다. 권한 확인(ACCOUNT_ADMIN 포함)도 gateway 가 JWT 의
  `role` 과 역할 → 권한 표로 하고, role·permissions 는 헤더로 넘기지 않는다 (헤더와 JWT 를 따로 맞출 필요가 없게. 2026-10-14 리뷰,
  2026-10-23 권한으로 바꿈). 그래서 업무 서비스는 역할·권한을 모르고, 권한 방식을 바꿔도 auth·gateway 만 고친다. 뒤 서비스가 믿는
  `X-User-Id` 같은 헤더는 다음 두 가지가 모두 성립할 때만 유효하다.
  1. 서비스는 gateway 를 통해서만 접근 가능(네트워크 격리: ECS 보안그룹에서 인터넷 직접 접속 차단).
  2. gateway 가 클라이언트가 보낸 `X-User-*` 헤더를 **버리고(strip), 검증된 JWT 로 다시 채운다**.
  둘 중 하나라도 빠지면 클라이언트가 헤더를 위조해 우회할 수 있다.
- **공개 경로는 토큰을 검증하지 않는다**: 로그인·활성화·재발급·로그아웃은 `Authorization` 헤더가 있든 없든, 만료됐든
  아니든 auth 까지 전달한다. 프론트는 보통 모든 요청에 Access 토큰을 붙이는데, 검증하면 만료된 토큰 때문에 재발급이
  401 로 막힌다 (#63). 이 경로들은 `X-User-Id` 도 붙지 않는다

### gateway 오류 응답

서비스는 모든 오류를 공통 형식 `{"code": "...", "message": "..."}` 로 돌려준다 (common-web `ErrorResponse`).
gateway 가 직접 만드는 오류도 같은 형식으로 맞춘다 (#66). 상태 코드는 바꾸지 않는다 (아래 연결 실패만 예외).
gateway 는 WebFlux 라 common-web 을 쓰지 않고 같은 형식을 따로 만든다.

- `code` 는 상태 코드 이름 그대로 (예: 401 → `UNAUTHORIZED`, 404 → `NOT_FOUND`, 504 → `GATEWAY_TIMEOUT`).
  서비스 공통 처리기의 `NOT_FOUND`, `INTERNAL_SERVER_ERROR` 와 같은 방식이다
- `message` 는 상태별 고정 문구. `NOT_FOUND`, `INTERNAL_SERVER_ERROR` 는 서비스 공통 처리기와 같은 문구를 쓴다

  | 상태 | code | message |
  |---|---|---|
  | 401 | `UNAUTHORIZED` | 로그인이 필요합니다. |
  | 403 | `FORBIDDEN` | 권한이 없습니다. |
  | 404 | `NOT_FOUND` | 요청한 리소스를 찾을 수 없습니다. |
  | 500 | `INTERNAL_SERVER_ERROR` | 서버 내부 오류가 발생했습니다. |
  | 503 | `SERVICE_UNAVAILABLE` | 서비스에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요. |
  | 504 | `GATEWAY_TIMEOUT` | 서비스 응답 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요. |
  | 그 밖의 상태 | 상태 이름 | 요청을 처리할 수 없습니다. |
- 401 의 `WWW-Authenticate: Bearer ...` 헤더는 그대로 둔다 (RFC 6750)
- **예외: 뒤 서비스 연결 실패는 503**. gateway 기본값은 500 인데, 서비스가 꺼진 것을 "gateway 내부 오류" 로 보여 의미가 틀려서
  503 `SERVICE_UNAVAILABLE` 로 바꾼다 (client 규칙의 `{상대 서비스}_UNAVAILABLE` 503 과 같은 뜻)
- **서비스가 낸 오류는 바꾸지 않는다**: auth 의 `AUTH-006` 같은 응답은 상태·본문 그대로 전달한다
- **CORS 거부(403)는 본문 없이 둔다**: 브라우저가 CORS 거부 응답의 본문을 프론트에 넘기지 않아 형식이 의미가 없다
- 오류 본문에 요청 경로, 예외 메시지, 스택 같은 내부 정보를 넣지 않는다

## 관련 이벤트 (events.md 참조)

- 없음. auth 는 이벤트를 발행·수신하지 않는다.

## 결정 기록

1. **공개키 전달**: JWKS 엔드포인트. (키 회전 용이)
2. **로그인 ID**: 이메일. 사번은 로그인 말고 쓰는 곳이 없어 삭제했다 (2026-10-12).
3. **role 저장**: `role` 테이블 + `user_account.role_id` 숫자 FK (2026-10-23 바꿈). 처음에는 고정 역할·1인1역할이라
   enum 컬럼으로 단순화했지만, 프론트 계정 발급 화면이 역할 선택지(코드와 한국어 이름)를 DB 에서 가져오기로 해서 테이블로 뺐다.
   코드 글자를 FK 로 두는 안도 봤지만, 기획서대로 숫자 id 로 가리켜 코드를 바꿔도 `role` 한 곳만 고치게 했다.
   JWT 클레임·gateway 권한 검사·프론트는 그대로 코드 글자를 쓰므로, 로그인 때 id 로 코드를 찾는 조회가 하나 는다.
   역할별 권한도 `role_permission` 테이블로 뺐다 (2026-10-23, 결정 기록 17).
4. **토큰 만료**: Access 15분 / Refresh 14일 / 활성화 24시간.
5. **발급 시 DB 에 저장하지 않음**: 활성화하지 않는 사람이 DB 에 남지 않도록 Redis 에 대기시키고
   활성화 때 저장한다 (2026-10-12).
6. **Redis 두 키**: 링크로 찾는 토큰 키 + 재발급용 이메일 키. 이메일 키 하나만 두면 토큰으로 찾을 수 없고,
   링크에 이메일을 넣으면 개인정보가 노출된다 (2026-10-12).
7. **같은 이메일 재발급**: 덮어쓰기. 메일을 잃어버린 직원을 관리자가 다시 발급해 구제할 수 있다 (2026-10-12).
8. **V2 마이그레이션 직접 수정**: V2 는 main 에 올라간 적이 없어 V3 를 만들지 않고 고친다.
   로컬 DB 는 `docker compose down -v` 로 한 번 초기화한다.
9. **ADMIN 확인은 gateway 한 곳**: auth 가 `X-User-Role` 헤더로 다시 확인하던 것을 뺐다. role 을 헤더로 넘기면 헤더를
   따로 관리하고 JWT role 과 같은지 맞춰야 해서다 (2026-10-14, PR #30 리뷰).
10. **JWKS 는 내부 API**: `/internal/jwks`, `InternalJwksController`. 다른 내부 API 와 같은 규칙으로 두어 외부 API 와
    서버끼리 쓰는 API 를 경로·이름으로 구분한다 (2026-10-14, PR #30 리뷰).
11. **auth 컨트롤러 경로는 `/auth`**: 처음에 외부 주소 `/api/auth` 를 컨트롤러에 그대로 써서, gateway 가 `/api` 를 뗀 뒤
    dev 에서 모든 auth API 가 404 였다. 다른 서비스(order `/orders`)처럼 `/api` 없이 받는다 (2026-10-14).
12. **공개 경로는 토큰을 검증하지 않음**: Spring Security 는 Bearer 토큰이 있으면 `permitAll` 보다 먼저 검증해 401 을
    낸다. 공개 경로에서는 토큰을 읽지 않게 해서, 만료된 Access 토큰이 붙은 재발급도 auth 에 닿게 한다 (2026-10-15, #63).
13. **gateway 오류도 공통 형식**: 프론트가 오류를 `code` 하나로 구분하게 한다. 상태 코드는 그대로 두고, `code` 는 상태
    이름을 그대로 써서 새 이름을 만들지 않는다. 단 뒤 서비스 연결 실패는 기본값 500 이 "내부 오류" 로 보여 틀리므로 503 으로 바꾼다
    (2026-10-15, #66).
14. **메일 발송 실패는 발급 실패(503)**: 성공으로 두면 관리자는 메일이 안 간 줄 모르고 직원은 활성화할 수 없다.
    대기 정보를 지우고 실패로 알리면, 같은 이메일 재발급(덮어쓰기)으로 다시 시도하면 된다 (2026-10-15, #61).
15. **메일 테스트는 GreenMail**: 테스트 안에서 가짜 SMTP 서버를 띄운다. `JavaMailSender` 는 mock 할 수 있는 대상
    (`client/`, `Clock`)이 아니고, 테스트에서 실제 메일 계정이나 dev 서버를 부르지 않는다 (2026-10-15, #61).
16. **메일 서버 기본값은 localhost:1025**: 메일 서버 설정이 아예 없으면 메일 발송 객체가 만들어지지 않아 auth 전체가 뜨지
    않는다(로그인 포함). 기본값을 두면 설정을 빠뜨려도 발급만 503 으로 실패하고, 로컬은 설정 없이 Mailpit 으로 동작한다
    (2026-10-15, #61).
17. **권한은 DB, 검사는 권한 코드로**: 역할을 더하거나 권한을 바꿀 때 gateway·프론트·문서를 함께 뒤지지 않도록, 역할 → 권한은
    auth DB 한 곳에 두고 JWT `permissions` 로 내보낸다. gateway 는 "API → 필요한 권한" 만 코드에 두고(API 는 어차피 코드라서) 역할 이름을 모른다.
    경로 규칙까지 DB 에 두는 안은 하지 않았다 — 새 API 는 어차피 배포가 필요하고, DB 행 하나가 리뷰 없이 쓰기 API 를 열 수 있고,
    gateway 가 실행 중에 auth 에 기대게 된다. 대가: 권한 변경은 이미 발급된 Access 토큰이 만료(최대 15분)된 뒤 반영된다
    (→ 18 에서 바꿈)
18. **권한 표는 Redis 로 gateway 메모리에** (2026-10-23): 관리자가 화면에서 권한을 바꾸면 바로 반영되어야 해서, 권한을 토큰에서 빼고
    gateway 가 역할 → 권한 표를 메모리에 들게 했다. 표는 auth 가 Redis 키에 쓰고 채널로 발행한다 — gateway 가 여러 대여도 모두 받고,
    auth 를 부르지 않으며, 이미 쓰는 Redis 라 새 인프라가 없다. auth 가 gateway 를 직접 부르는 안은 gateway 가 여러 대(배포 중 포함)면
    한 대에만 가고, gateway 에 밖에서 막아야 할 입구가 생겨 하지 않았다. RabbitMQ 이벤트는 gateway 에 Outbox·인스턴스별 큐가 필요해
    하지 않았다. 평소 주기적 재조회는 하지 않는다 (권한은 드물게 바뀐다). 놓칠 수 있는 때(시작, Redis 재연결)에만 키를 다시 읽는다
    (docs/specs/role-permission-admin.md)

## 다른 서비스에 필요한 후속 변경 (auth 범위 밖, 추후 상의 필요)

### gateway 배선 PR 체크리스트

gateway 는 JWT 를 검증하고, 뒤 서비스는 JWT 를 다시 열지 않고 gateway 가 넣어 준 `X-User-*` 헤더를 믿는다.
이 구조는 아래 **보안 필수** 두 가지가 모두 갖춰져야 안전하다 (둘 중 하나라도 빠지면 헤더를 위조해 우회할 수 있다.
"규칙 / 예외 > 권한 확인과 헤더 신뢰 전제" 참고).

- [x] (보안 필수) gateway 가 클라이언트가 보낸 `X-User-*` 헤더를 지우고, 검증한 JWT 의 sub(public_id) 로 `X-User-Id` 를
      다시 채운다. role 은 넘기지 않는다 (권한은 gateway 가 JWT 로 확인)
- [ ] (보안 필수) 서비스는 gateway 를 통해서만 접근되는지 확인 (dev ECS 보안그룹에서 서비스 포트의 외부 접속 차단)
- [x] CORS 다시 만들기. PR #30 에서 명세 밖이라 뺐다(AI 리뷰 [spec]). 뺀 설정은 다음과 같았다
  - 허용 origin 은 설정값 `app.cors.allowed-origins` (env `CORS_ALLOWED_ORIGINS`). 기본은 비어 있어 모든 origin 을 거부한다. 프론트 주소가 아직 없고, 소유하지 않은 주소를 허용하지 않기 위해서다 (2026-10-15). 주소가 정해지면 환경별 설정에 넣는다
  - 메서드 GET, POST, PUT, PATCH, DELETE, OPTIONS / 헤더 전부 / 경로 `/**`
  - **allowCredentials = true 필수**: Refresh 토큰이 httpOnly 쿠키라, 없으면 브라우저가 재발급·로그아웃 요청에
    쿠키를 싣지 않는다. 이때 허용 origin 에 `*` 는 쓸 수 없다(정확한 origin 목록)
  - Spring Security(WebFlux) 에서는 `http.cors(...)` 에 `CorsConfigurationSource` 를 연결해야 preflight 가 인증 전에 통과한다
- [ ] `X-User-Id` 의미 맞추기 (order 담당과). gateway 는 로그인한 **직원**의 public_id(UUID) 를 넣는데, order 의
      `POST /orders` 는 이 헤더를 **고객 번호(Long)** 로 읽는다. 고객은 로그인하지 않으므로 고객 번호는 요청 본문
      (`customerId`) 으로 받고, `X-User-Id` 는 필요하면 "주문을 입력한 직원" 으로 쓰는 방향

### 그 밖의 후속 변경

- (끝남) `customer` 는 로그인하지 않으므로 auth 에서 뺐다. auth V4(#124)에서 테이블을 지웠고, order V9(#126)에 고객을 새로 만들었다 (docs/specs/customer.md)
- (끝남) purchase `requested_by`(옛 `auth_db.admin.id` 참조)는 purchase V5 에서 지웠다. 발주한 직원은 `ordered_by`(직원 public_id) 다
- 다른 서비스가 직원을 가리킬 때는 실제 FK 가 아니라 public_id 만 보관하는 논리 참조라 auth 변경으로 구조가 깨지지는 않는다.

## 완료 조건 (테스트로 확인할 것)

- [ ] 발급 시 DB 에는 아무것도 생기지 않고, Redis 에 토큰 키와 이메일 키가 24시간 TTL 로 생긴다
- [ ] 허용 도메인이 아니면 400, 이미 활성화된 이메일이면 409
- [ ] 같은 이메일로 다시 발급하면 옛 토큰으로는 활성화할 수 없고 새 토큰으로만 된다
- [ ] 유효 토큰으로 활성화하면 `user_account` 에 ACTIVE + 비번 해시로 저장되고, Redis 두 키가 모두 사라진다
- [ ] 없는·만료·이미 쓴 토큰으로 활성화하면 400
- [ ] 이메일은 대소문자와 상관없이 같은 계정으로 다룬다 (발급·로그인). 로그인은 앞뒤 공백도 무시하고, 발급은 앞뒤 공백이 있으면 400
- [ ] 올바른 이메일+비번 로그인 시 Access(RS256)+Refresh 발급, Access claim 에 `sub`(public_id)·`role` 이 있고 `permissions`·이메일은 없다 (권한은 내 정보 API, docs/specs/role-permission-admin.md)
- [ ] 틀린 비번·없는 이메일 401, `DISABLED` 403, N회 실패 시 429
- [ ] Refresh 재발급 시 새 쌍 발급 + 옛 Refresh 무효(회전), 로그아웃 시 Refresh 무효
- [ ] 공개키로 Access 서명 검증 성공
- [ ] gateway 에서 `ACCOUNT_ADMIN` 권한이 없는 JWT 로 admin API 를 부르면 403, 토큰이 없으면 401
- [ ] gateway 가 직접 만든 오류(401·403·404·5xx)의 본문이 `{"code","message"}` 이고 상태 코드는 그대로, 서비스가 낸 오류는 그대로 전달된다
- [ ] 비번은 해시로 저장되고, 비번·토큰·이메일이 로그에 남지 않는다
- [ ] `user_account` 에 `employee_no` 컬럼이 없고, `email` 은 UNIQUE, `password` 는 NOT NULL
- [ ] V5 가 4개 역할을 순서대로 넣고, 역할 목록 API 가 `sort_order` 순서로 `{code, name}` 을 준다 (역할 개수에 묶이지 않게 확인한다). 없는 역할로 발급하면 400 `AUTH-013`, 없는 `role_id` 는 `user_account` 에 저장되지 않는다(FK). V5 전에 있던 계정은 같은 역할의 `role_id` 로 옮겨지고 `role` 컬럼은 없다
- [ ] `/v3/api-docs` 에 6개 API 의 summary, 위 표의 응답 코드, 요청·응답 필드 설명이 나오고, JWKS 는 `/internal/jwks` 내부 API 로 나온다
- [ ] 발급하면 활성화 메일이 발급한 이메일로 가고, 링크에 토큰이 들어 있다
- [ ] 메일 발송이 실패하면 503 이고, Redis 에 이번 발급의 대기 정보가 남지 않는다
- [ ] 메일 발송 로그에 이메일·토큰·링크가 남지 않는다
