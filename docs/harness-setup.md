# FactoryFlow 구축 문서 (AI 하네스 포함)

> 이 문서는 Claude Code가 읽고 프로젝트를 구축하기 위한 기준 문서다.
> 위치: `docs/harness-setup.md`
> 구축이 끝나면 이 문서는 삭제한다 (PART 3 마지막 항목).

---

# PART 1. 구축 순서 (Claude Code는 이 순서를 반드시 지킨다)

## 공통 규칙
- 각 단계가 끝나면 결과를 짧게 보고하고, **내 확인을 받은 뒤** 다음 단계로 간다.
- PART 2의 파일 내용은 **그대로** 만든다. 문서와 다르게 고칠 부분이 보이면 고치지 말고 목록으로 알려준다.
- 버전 호환이 확실하지 않으면 추측하지 말고 멈추고 묻는다.
- 테스트를 지우거나 끄는 방식으로 빌드를 통과시키지 않는다.
- `.claude/settings.json`은 **맨 마지막(6단계)**에 만든다. 이 파일이 생기면 guard 훅이 `.claude/`, `.github/`, `common/`, 루트 `CLAUDE.md` 수정을 막기 때문이다.

## 1단계: Gradle 멀티모듈 뼈대
1. 루트 `src/` 삭제 (루트는 모듈이 아님)
2. `settings.gradle`: rootProject.name = 'factoryflow'
   - include: `common:common-event`, `common:common-web`, `common:common-test`
   - include: `services:gateway`, `order`, `inventory`, `purchase`, `production`, `auth`, `simulator`
3. 루트 `build.gradle` (Groovy DSL)
   - Java 17 toolchain, group `com.factoryflow`, mavenCentral
   - Spring Boot 4.0.8, io.spring.dependency-management, com.diffplug.spotless → `plugins { ... apply false }`
   - subprojects 공통: java, JUnit Platform, lombok, spotless
     ```groovy
     spotless {
         java {
             googleJavaFormat().aosp()
             removeUnusedImports()
             trimTrailingWhitespace()
             endWithNewline()
         }
     }
     ```
     (`check`가 `spotlessCheck`에 의존하므로 포맷이 틀리면 빌드 실패)
   - `services:*`에만 Spring Boot 플러그인, `common:*`은 java-library + BOM
4. 각 서비스: `build.gradle`, `com.factoryflow.<svc>.<Svc>Application`, `application.yml` (spring.application.name, server.port 8080, actuator health 노출)
   - gateway: Spring Cloud Gateway (Spring Cloud 2025.1.3, Boot 4.0.8 기준)
   - 나머지: web, actuator, validation, data-jpa, mysql, flyway, amqp, data-redis
   - 의존: `testImplementation project(':common:common-test')`, `implementation project(':common:common-web')`, 이벤트 사용 서비스는 `project(':common:common-event')`
   - **서비스끼리 `project(':services:...')` 의존 금지**
5. Gradle wrapper 버전이 Boot 4.0.8 요구사항을 만족하는지 확인
6. `./gradlew spotlessApply build -x test` 통과

## 2단계: common 모듈 + 서비스 내부 구조
1. common-test: PART 2의 5번 `FactoryFlowRules` (archunit-junit5 최신 1.x, api 의존으로 노출)
2. 각 서비스 `src/test`에 5번 `ArchitectureTest` (gateway는 isolation, injection, streams만)
3. common-event (`com.factoryflow.common.event`)
   - 이벤트 베이스: eventId(UUID), traceId, version, occurredAt
   - `EventPublisher`: 같은 트랜잭션에서 outbox 테이블에 저장
   - `OutboxRelay`: 스케줄러로 미발행 outbox를 RabbitTemplate으로 발행 (exchange `factoryflow.events`, topic, 라우팅키 = 이벤트 이름)
   - 수신 멱등 처리: processed_event 저장/확인 컴포넌트
   - RabbitTemplate은 이 모듈 안에서만 사용
4. common-web: 공통 응답 형식, 전역 예외 처리, traceId 필터(MDC)
5. 각 서비스(gateway 제외) 패키지: `controller/`, `domain/`, `dto/`, `repository/`, `service/`, `event/listener/`, `event/publisher/`, `client/`, `exception/` (빈 패키지는 package-info.java)
   - outbox, processed_event 테이블은 서비스별 Flyway `V1__init.sql`
6. 각 서비스 `Dockerfile` (eclipse-temurin:17-jre, build/libs/*.jar, 8080)
7. 루트 `docker-compose.yml`: MySQL 8.4 (order_db, inventory_db, purchase_db, production_db, auth_db), Redis, rabbitmq:3.13-management. 비밀번호는 `.env`(커밋 제외)에서 읽는다
8. `./gradlew spotlessApply check` 통과

## 3단계: CLAUDE.md + docs
1. PART 2의 3번 루트 `CLAUDE.md` (그대로)
2. 각 서비스 `CLAUDE.md` (order 예시 형식, 발행/수신은 6번 events.md 표 기준)
3. PART 2의 6번 `docs/specs/_template.md`, `docs/specs/events.md`, `docs/runbooks/rabbitmq-down.md`
4. 같은 형식으로 `docs/runbooks/service-5xx.md`, `docs/runbooks/db-connection.md` 추가 (클러스터 ff-cluster, 서비스 ff-<svc>, 로그 그룹 /ecs/ff-<svc>)

## 4단계: .github
PART 2의 8번 파일 전부. 만든 뒤 YAML 문법 검사 (actionlint 없으면 python yaml 파싱).

## 5단계: Lambda + .gitignore
1. `lambda/ff-alarm-to-github/lambda_function.py`
2. 루트 `.gitignore`를 9번 내용으로 (기존 필요 항목 유지)
3. `git status`로 `.env`, `*.pem`, `application-local.yml`, `.claude/harness.log`가 추적 안 되는지 확인

## 6단계: .claude 하네스 (마지막)
1. `.claude/hooks/stop-check.sh`, `.claude/hooks/guard.sh` 생성 + `chmod +x`
2. `.claude/skills/add-event/SKILL.md`
3. **마지막으로** `.claude/settings.json`
4. 훅 테스트
   ```bash
   echo '{"tool_input":{"file_path":"'$(pwd)'/.github/workflows/ci.yml"}}' | .claude/hooks/guard.sh; echo $?   # 2
   echo '{"tool_input":{"file_path":"'$(pwd)'/CLAUDE.md"}}' | .claude/hooks/guard.sh; echo $?   # 2
   echo '{"tool_input":{"file_path":"'$(pwd)'/services/order/CLAUDE.md"}}' | .claude/hooks/guard.sh; echo $?   # 0
   echo '{"tool_input":{"file_path":"'$(pwd)'/services/order/src/main/java/A.java"}}' | .claude/hooks/guard.sh; echo $?   # 0
   echo '{"session_id":"test"}' | .claude/hooks/stop-check.sh; echo $?   # 변경 없으면 0
   ```
5. 끝나면 "claude를 재시작하라"고 알려준다

---

# PART 2. 설계와 파일 내용

## 1. 영상 → 프로젝트 적용

| 영상 | 핵심 | 적용 |
|---|---|---|
| V1 Quenneville (하네스) | 지침(미리 알려줌) + 센서(결과 검사). 말보다 기계 검사 | CLAUDE.md(지침), Stop 훅·ArchUnit·Spotless·guard 훅(센서) |
| V2 Voss (리뷰 하네스) | PR에서 AI가 체크리스트 기반으로 나눠서 리뷰 | `.github/review/{msa,security,spec,quality}.md`, ci.yml review 매트릭스, gate.yml ai-review-gate (msa/security/spec 위반 시 머지 차단) |
| V3 5원칙 | ② 실패에서 출발 ③ 규칙은 적게 ⑤ 계속 개선 | harness.log + 2회 규칙 harness 이슈, 대응 우선순위 |
| V4 Pocock (PR 병목) | 사람 리뷰량 축소. 딥모듈, 다이어그램, 되돌릴 수 없는 결정 집중 | 서비스별 진입점(controller/listener → service), PR 흐름도 자동, one-way-door 라벨 |
| V5 (사람의 개입) | 증거 기반 검증, 바깥 고리 소유, 설명 가능한 것만 배포, 주의력 집중 | PR "증거" 칸 + 테스트 결과 리포트, "왜"는 작성자가 직접 + pr-body 검사, one-way-door 는 사람 승인 필수(door-gate) |

## 2. 프로젝트 구조

```
factoryflow/
├── CLAUDE.md                     # 사람만 수정
├── settings.gradle / build.gradle
├── docker-compose.yml / .env (커밋 제외)
├── .gitignore
├── .claude/
│   ├── settings.json
│   ├── harness.log               # 자동 실패 로그 (gitignore)
│   ├── hooks/stop-check.sh
│   ├── hooks/guard.sh
│   └── skills/add-event/SKILL.md
├── .github/
│   ├── PULL_REQUEST_TEMPLATE.md
│   ├── ISSUE_TEMPLATE/{config.yml, feature.md, bug.md, incident.md, harness.md}
│   ├── review/{msa.md, security.md, spec.md, quality.md}
│   └── workflows/{ci.yml, pr-body.yml, gate.yml, cd.yml, incident.yml}
├── docs/
│   ├── harness-setup.md          # 이 문서 (구축 후 삭제)
│   ├── specs/_template.md
│   ├── specs/events.md           # 사람만 수정
│   └── runbooks/*.md
├── common/
│   ├── common-event/
│   ├── common-web/
│   └── common-test/
├── lambda/ff-alarm-to-github/
└── services/<svc>/               # gateway, order, inventory, purchase, production, auth, simulator
    ├── CLAUDE.md
    ├── Dockerfile
    ├── build.gradle
    └── src/main/java/com/factoryflow/<svc>/
        ├── controller/           # HTTP 진입점
        ├── domain/               # Entity, enum
        ├── dto/                  # 요청/응답 DTO
        ├── repository/           # JPA Repository (service 에서만 사용)
        ├── service/              # 업무 로직, 트랜잭션, 이벤트 발행
        ├── event/listener/
        ├── event/publisher/
        └── client/
```

## 3. CLAUDE.md

### 루트 `CLAUDE.md`
```markdown
# FactoryFlow
현대차 공장 모델 ERP+MES. Java 17, Spring Boot 4.0.8, Gradle(Groovy) 멀티모듈.

## 명령
- 서비스 검사: ./gradlew :services:<svc>:check
- 전체: ./gradlew check
- 포맷 정리: ./gradlew spotlessApply
- 로컬 인프라: docker compose up -d

## 절대 규칙
- 서비스 간 코드 직접 참조 금지. 비동기는 이벤트(common-event), 동기는 client/
- 외부에서 들어오는 호출(Controller, Listener)은 service 를 거친다. Repository 직접 호출 금지
- RabbitTemplate 직접 사용 금지. EventPublisher 사용
- 생성자 주입만. System.out 금지, 로그는 Slf4j
- 테스트 삭제, @Disabled, 의미 없는 assert 금지. 테스트가 틀렸다고 판단되면 이유를 말하고 멈춘다

## 패키지 구조 (서비스별)
com.factoryflow.<svc>.{controller, domain, dto, repository, service, event/listener, event/publisher, client, exception}

## 사람만 수정 (수정 필요 시 제안만)
CLAUDE.md(루트), .claude/, .github/, common/common-event/, common/common-test/, docs/specs/events.md, 루트 build.gradle, settings.gradle

## 작업 순서
1. docs/specs/ 의 해당 명세 확인 (없으면 _template.md로 초안 작성 후 확인 요청)
2. 테스트 먼저 → 구현
3. 이벤트 추가는 /add-event 사용
4. PR 생성 시 "## 왜" 섹션은 비워둔다 (작성자가 직접 씀)
```

### `services/order/CLAUDE.md` (서비스별 예시)
```markdown
# order 서비스
- DB: order_db (Flyway: src/main/resources/db/migration)
- 발행: order.created, order.cancelled
- 수신: inventory.reserved, inventory.reservation-failed
- 진입점: controller/OrderController (HTTP), event/listener (이벤트) → service/OrderService
- 보상: inventory.reservation-failed 수신 시 주문 상태 FAILED
```

## 4. `.claude/`

### `settings.json`
```json
{
  "permissions": {
    "deny": [
      "Read(./.env)",
      "Read(./**/application-local.yml)",
      "Read(./**/*.pem)",
      "Bash(git push:*)",
      "Bash(aws:*)"
    ]
  },
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Edit|Write|MultiEdit",
        "hooks": [{ "type": "command", "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/guard.sh" }]
      }
    ],
    "Stop": [
      {
        "hooks": [{ "type": "command", "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/stop-check.sh", "timeout": 600 }]
      }
    ]
  }
}
```

### `hooks/stop-check.sh`
```bash
#!/usr/bin/env bash
# Claude가 "끝"이라고 할 때 실행. exit 2 = 멈추지 말고 고쳐라 (최대 3회)
set -uo pipefail
input=$(cat)
sid=$(echo "$input" | jq -r '.session_id')
cd "$(git rev-parse --show-toplevel)"
LOG=.claude/harness.log
CNT=/tmp/ff-stop-$sid
n=$(cat "$CNT" 2>/dev/null || echo 0)

log() { echo "$(date '+%F %T')|$sid|$1|$2" >> "$LOG"; }

fail() { # $1=실패유형 $2=Claude에게 줄 메시지
  n=$((n+1)); echo "$n" > "$CNT"
  log "$1" "try$n"
  if [ "$n" -ge 3 ]; then
    log "$1" "GIVEUP"
    echo "3회 실패. 사람 확인 필요: $1" >&2
    rm -f "$CNT"; exit 0
  fi
  echo -e "$2" >&2; exit 2
}

changed=$( { git diff --name-only HEAD; git ls-files --others --exclude-standard; } | sort -u )
[ -z "$changed" ] && { rm -f "$CNT"; exit 0; }

# 1) 테스트 무력화 차단
git diff --name-only --diff-filter=D HEAD | grep 'src/test/' >/dev/null \
  && fail TEST_DELETED "테스트 파일 삭제 금지. 테스트가 틀렸다면 이유를 말하고 멈춰라."
git diff -U0 HEAD -- '*.java' | grep -E '^\+.*@Disabled' >/dev/null \
  && fail TEST_DISABLED "@Disabled 추가 금지."
git diff -U0 HEAD -- '*.java' | grep -E '^\+.*assertTrue\(true\)' >/dev/null \
  && fail FAKE_ASSERT "의미 없는 assert 금지."

# 2) 변경된 서비스만 포맷 정리 + check (common/루트 변경 시 전체)
if echo "$changed" | grep -E '^(common/|build\.gradle|settings\.gradle)' >/dev/null; then
  apply="spotlessApply"
  tasks="check"
else
  mods=$(echo "$changed" | grep -oE '^services/[^/]+' | sort -u | sed 's#services/#:services:#')
  apply=$(echo "$mods" | sed '/^$/d; s#$#:spotlessApply#' | tr '\n' ' ')
  tasks=$(echo "$mods" | sed '/^$/d; s#$#:check#' | tr '\n' ' ')
fi

if [ -n "$tasks" ]; then
  ./gradlew $apply -q --console=plain >/dev/null 2>&1
  out=$(./gradlew $tasks -q --console=plain 2>&1) || {
    type=BUILD_FAIL
    echo "$out" | grep -E 'tests completed, [0-9]+ failed' >/dev/null && type=TEST_FAIL
    echo "$out" | grep -E 'Architecture Violation' >/dev/null && type=ARCH_VIOLATION
    fail "$type" "검사 실패. 고친 뒤 다시 끝내라.\n$(echo "$out" | tail -40)"
  }
fi

rm -f "$CNT"; exit 0
```

### `hooks/guard.sh`
```bash
#!/usr/bin/env bash
# 수정 금지 파일 차단. exit 2 = 거부
input=$(cat)
root=$(git rev-parse --show-toplevel)
f=$(echo "$input" | jq -r '.tool_input.file_path // empty')
rel=${f#$root/}

deny() {
  echo "$(date '+%F %T')|guard|GUARD_BLOCK|$rel" >> "$root/.claude/harness.log"
  echo "차단: $1" >&2; exit 2
}

case "$rel" in
  CLAUDE.md|.claude/*|.github/*|common/common-test/*|common/common-event/*|docs/specs/events.md|build.gradle|settings.gradle)
    deny "$rel 은 사람만 수정한다. 변경 제안만 하고 멈춰라." ;;
esac

if [[ "$rel" =~ ^services/[^/]+/build\.gradle$ ]]; then
  new=$(echo "$input" | jq -r '.tool_input.new_string // .tool_input.content // empty')
  echo "$new" | grep -q "project(':services:" \
    && deny "서비스 간 모듈 의존 금지. 이벤트나 client/로 통신하라."
fi
exit 0
```

**harness.log 예시**
```
2026-10-12 14:03:11|a1b2|ARCH_VIOLATION|try1
2026-10-12 14:05:40|a1b2|ARCH_VIOLATION|try2
2026-10-13 10:20:02|guard|GUARD_BLOCK|common/common-event/EventPublisher.java
```

### `skills/add-event/SKILL.md`
```markdown
---
name: add-event
description: 새 RabbitMQ 이벤트를 추가할 때 사용
---
1. docs/specs/events.md 에 이벤트가 있는지 확인. 없으면 아래 형식으로 추가안을 제시하고 멈춘다 (사람이 추가)
   - 이름: <도메인>.<과거형> (예: order.created)
   - 발행: 서비스 / 수신: 서비스 목록
   - payload 필드 + 공통 필드(eventId, traceId, version, occurredAt)
2. 발행 서비스: event/publisher/ 에 레코드 + EventPublisher.publish() (같은 트랜잭션, Outbox)
3. 수신 서비스: event/listener/ 에 @RabbitListener → processed_event로 중복 확인 → service 호출
4. 실패 시 보상 이벤트 정의 여부 확인
5. 테스트: 발행 1개(Outbox 저장 확인), 수신 2개(정상 / 중복 무시)
```

## 5. ArchUnit

### `common/common-test/src/main/java/com/factoryflow/test/arch/FactoryFlowRules.java`
```java
package com.factoryflow.test.arch;

import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.GeneralCodingRules;
import java.util.List;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

public final class FactoryFlowRules {
    private static final List<String> SERVICES =
        List.of("gateway", "order", "inventory", "purchase", "production", "auth", "simulator");

    public static ArchRule noOtherServiceAccess(String me) {
        String[] others = SERVICES.stream().filter(s -> !s.equals(me))
            .map(s -> "com.factoryflow." + s + "..").toArray(String[]::new);
        return noClasses().that().resideInAPackage("com.factoryflow." + me + "..")
            .should().dependOnClassesThat().resideInAnyPackage(others)
            .because("서비스 간 직접 참조 금지. 이벤트나 client/로 통신");
    }

    public static final ArchRule ENTRY_THROUGH_API =
        noClasses().that().haveSimpleNameEndingWith("Controller")
            .or().haveSimpleNameEndingWith("Listener")
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
            .because("진입점(Controller/Listener)은 service를 거친다");

    public static final ArchRule NO_DIRECT_PUBLISH =
        noClasses().that().resideOutsideOfPackage("com.factoryflow.common.event..")
            .should().dependOnClassesThat()
            .haveFullyQualifiedName("org.springframework.amqp.rabbit.core.RabbitTemplate")
            .because("발행은 EventPublisher(Outbox)로만");

    public static final ArchRule NO_FIELD_INJECTION =
        GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

    public static final ArchRule NO_STANDARD_STREAMS =
        GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    private FactoryFlowRules() {}
}
```

### 서비스별 `ArchitectureTest.java` (예: order)
```java
@AnalyzeClasses(packages = "com.factoryflow.order", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
    @ArchTest ArchRule isolation = FactoryFlowRules.noOtherServiceAccess("order");
    @ArchTest ArchRule entry     = FactoryFlowRules.ENTRY_THROUGH_API;
    @ArchTest ArchRule publish   = FactoryFlowRules.NO_DIRECT_PUBLISH;
    @ArchTest ArchRule injection = FactoryFlowRules.NO_FIELD_INJECTION;
    @ArchTest ArchRule streams   = FactoryFlowRules.NO_STANDARD_STREAMS;
}
```

## 6. docs

### `docs/specs/_template.md`
```markdown
# <기능명>
## 목적
## 흐름
1.
## 규칙 / 예외
## 관련 이벤트 (events.md 참조)
## 완료 조건 (테스트로 확인할 것)
- [ ]
```

### `docs/specs/events.md`
```markdown
# 이벤트 계약 (사람만 수정)
공통 필드: eventId(UUID), traceId, version, occurredAt
Exchange: factoryflow.events (topic), 라우팅 키 = 이벤트 이름

| 이벤트 | 발행 | 수신 | payload |
|---|---|---|---|
| order.created | order | inventory | orderId, items[{partId, qty}] |
| order.cancelled | order | inventory | orderId |
| inventory.reserved | inventory | order, production | orderId |
| inventory.reservation-failed | inventory | order | orderId, reason |
| inventory.shortage | inventory | purchase | partId, shortQty |
| purchase.received | purchase | inventory | partId, qty |
| production.completed | production | order, inventory | orderId, qty |

## 변경 규칙
- 필드 추가만 허용 (version 유지). 삭제/이름 변경은 새 version + 기존 버전 병행
```

### `docs/runbooks/rabbitmq-down.md`
```markdown
# RabbitMQ 장애
증상: 이벤트 적체, Outbox 미발행 증가
확인: ssh ec2-user@<RABBITMQ_HOST> → docker ps / docker logs rabbitmq --tail 100
     (<RABBITMQ_HOST> 는 GitHub Secret APP_DEV_YML_<SVC> 의 spring.rabbitmq.host 값. 저장소에 IP를 적지 않는다)
조치: docker restart rabbitmq → Outbox 재발행 자동 확인
```

## 7. 실패 기록 운영 규칙

| 상황 | 할 일 | 누가 |
|---|---|---|
| 훅이 막음 / 스스로 고침 | 없음 (harness.log 자동 기록) | 훅 |
| 처음 보는 AI 실수 | 없음 | - |
| 같은 실수 두 번째 | `harness` 이슈 생성 (harness.log 줄 첨부) | 본 사람 |
| 이슈가 있는데 또 발생 | 이슈에 댓글 1줄 (날짜/PR) | 본 사람 |
| 대응 | 우선순위(테스트 > ArchUnit > 훅 > 체크리스트 > CLAUDE.md)대로 하나 → PR | 담당 |
| 닫기 | 대응 PR 머지 후 2주 재발 없음 | 담당 |

```bash
cut -d'|' -f3 .claude/harness.log | sort | uniq -c | sort -rn   # 유형별 횟수
grep GIVEUP .claude/harness.log                                 # 사람에게 넘어온 건
gh issue list -l harness --state all                            # 하네스 개선 이력
```

## 8. `.github/`

### `PULL_REQUEST_TEMPLATE.md`
```markdown
## 무엇을
<!-- 한두 줄 -->

## 왜 (작성자가 직접 작성, AI 생성 금지)
<!-- 왜 이 방식인가. 다른 방법 대신 이걸 고른 이유 -->

- 명세: docs/specs/
- closes #

## 흐름도 (AI)
<!-- ci가 자동 작성 -->

## 증거
- 테스트: CI 테스트 리포트 참고
- API 확인 (Swagger/curl 요청과 응답):
- 로그/스크린샷 (해당 시):

## AI 리뷰 무시 사유 (ai-review-override 라벨을 붙일 때만)
<!-- 어떤 AI 지적이 왜 틀렸거나 지금 고치지 않는지 -->

## 확인
- [ ] 이 변경을 AI 없이 팀원에게 설명할 수 있다
- [ ] 로컬 check 통과
- [ ] 이벤트 변경 시 events.md 반영
- [ ] one-way-door 라벨이면 롤백 방법 작성:
```

### `ISSUE_TEMPLATE/config.yml`
```yaml
blank_issues_enabled: false
```

### `ISSUE_TEMPLATE/feature.md`
```markdown
---
name: 기능
about: 새 기능
title: "[feat] "
labels: feature
---
## 목적
## 명세
docs/specs/
## 완료 조건
- [ ]
```

### `ISSUE_TEMPLATE/bug.md`
```markdown
---
name: 버그
about: 버그 제보
title: "[bug] "
labels: bug
---
## 현상
## 재현
## 기대 결과
## 원인
## 재발 방지 (테스트/규칙 추가 여부)
```

### `ISSUE_TEMPLATE/incident.md`
```markdown
---
name: 장애 (AI 자동 작성)
about: CloudWatch 알람 → AI 진단
title: "[incident] "
labels: incident
---
## 알람
## 영향 서비스
## 로그 요약
## 추정 원인 (코드 위치)
## 권장 조치 (runbook)
## 조치 결과 (사람 작성)
```

### `ISSUE_TEMPLATE/harness.md`
```markdown
---
name: 하네스 개선
about: 같은 AI 실수를 두 번째 봤을 때만 작성
title: "[harness] "
labels: harness
---
## 실수

## 발생
- 1회:
- 2회:
<!-- 이후 재발은 댓글로 한 줄 -->

## harness.log

## 대응 (가능한 가장 위 단계)
- [ ] 테스트
- [ ] ArchUnit
- [ ] 훅
- [ ] 리뷰 체크리스트
- [ ] CLAUDE.md

## 대응 PR

## 닫는 조건
대응 PR 머지 후 2주 재발 없음
```

### `review/msa.md`
```markdown
> ArchUnit 이 이미 잡는 것(타 서비스 import, Controller/Listener 의 Repository 직접 호출,
> RabbitTemplate 직접 사용, 필드 주입, System.out)은 보지 않는다.

- 다른 서비스 DB 직접 접근 (네이티브 SQL, 다른 스키마 이름)
- Controller/Listener 안의 비즈니스 로직 → service 로 이동
- 이벤트 발행이 트랜잭션 + Outbox
- Listener 멱등 처리
- 실패 시 보상 이벤트
- events.md 계약과 payload 일치
```

### `review/security.md`
```markdown
- 비밀번호, 키, 토큰 하드코딩
- 인증 없는 엔드포인트 추가
- SQL 문자열 조합
- 로그에 개인정보/토큰 출력
- 입력값 검증 누락 (@Valid)
```

### `review/spec.md`
```markdown
## 명세 찾기
1. PR 본문 "## 왜" 아래 docs/specs/ 경로
2. 없으면 closes #N → gh issue view N → 이슈의 명세 경로
3. 명세가 없으면 "[spec] 연결된 명세 없음" 코멘트 1개만 남기고 종료

## 검사
- PR이 연결된 명세(docs/specs)의 완료 조건을 모두 구현했는가
- 명세에 없는 동작 추가 여부
- 완료 조건마다 테스트가 있는가
```

### `review/quality.md`
```markdown
> 코멘트만 남긴다 (머지를 막지 않음). 팀원이 배울 수 있게 "왜 문제인지"를 한 줄 덧붙인다.
> Spotless(포맷, 안 쓰는 import), ArchUnit(구조 규칙)이 잡는 것은 보지 않는다.

- 로직 오류 (조건 반대, 경계값, 누락된 분기)
- null / Optional 처리 (Optional.get(), NPE 가능성)
- 예외 처리 (삼키는 catch, 너무 넓은 catch, CustomException/ErrorCode 미사용)
- 트랜잭션 범위 (@Transactional 누락, readOnly 누락, 트랜잭션 안의 외부 호출)
- JPA 성능 (N+1, 불필요한 전체 조회)
- 동시성 (재고 차감 등 경쟁 조건, 락 누락)
- 테스트 품질 (동작을 실제로 검증하는가, 실패 케이스가 있는가)
- 이름과 가독성 (의도가 드러나지 않는 이름, 매직넘버, 너무 긴 메서드)
```

### `workflows/ci.yml`
```yaml
name: ci
on:
  pull_request:
    branches: [main]

permissions:
  contents: read
  pull-requests: write
  checks: write
  issues: write
  id-token: write

concurrency:
  group: ci-${{ github.event.pull_request.number }}
  cancel-in-progress: true

jobs:
  check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17', cache: gradle }
      - id: m
        run: |
          files=$(git diff --name-only ${{ github.event.pull_request.base.sha }}...HEAD)
          if echo "$files" | grep -qE '^(common/|build\.gradle|settings\.gradle)'; then
            echo "tasks=check" >> $GITHUB_OUTPUT
          else
            t=$(echo "$files" | grep -oE '^services/[^/]+' | sort -u | sed 's#services/#:services:#; s#$#:check#' | tr '\n' ' ')
            echo "tasks=$t" >> $GITHUB_OUTPUT
          fi
      - if: steps.m.outputs.tasks != ''
        run: ./gradlew ${{ steps.m.outputs.tasks }} --no-daemon
      - name: Publish test results
        uses: dorny/test-reporter@v1
        if: always() && steps.m.outputs.tasks != ''
        with:
          name: JUnit Test Results
          path: '**/build/test-results/test/*.xml'
          reporter: java-junit
          fail-on-error: true

  # AI job 과 door 는 Repository variable AI_ENABLED=true 일 때만 실행 (시크릿, 라벨 준비 후 켠다)
  # review 는 코멘트만 남기고 실패하지 않는다. 머지 판단은 gate.yml 의 ai-review-gate 가 한다
  review:
    needs: check
    if: vars.AI_ENABLED == 'true'
    runs-on: ubuntu-latest
    strategy:
      matrix:
        kind: [msa, security, spec, quality]
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }
      - uses: anthropics/claude-code-action@v1
        with:
          claude_code_oauth_token: ${{ secrets.CLAUDE_CODE_OAUTH_TOKEN }}
          prompt: |
            PR #${{ github.event.pull_request.number }} 리뷰.
            기준: .github/review/${{ matrix.kind }}.md 항목만.
            위반이 있는 줄에만 인라인 코멘트를 남겨라. 형식: [${{ matrix.kind }}] 문제 - 수정안
            위반이 없으면 아무것도 남기지 마라. 요약, 칭찬 금지.
          claude_args: |
            --allowedTools "Read,Grep,Glob,Bash(gh pr diff:*),Bash(gh pr view:*),Bash(gh issue view:*),mcp__github_inline_comment__create_inline_comment"
            --max-turns 15

  diagram:
    needs: check
    if: vars.AI_ENABLED == 'true'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }
      - uses: anthropics/claude-code-action@v1
        with:
          claude_code_oauth_token: ${{ secrets.CLAUDE_CODE_OAUTH_TOKEN }}
          prompt: |
            PR #${{ github.event.pull_request.number }} 변경을 Mermaid sequenceDiagram 하나로 그려라
            (서비스, 이벤트, DB 단위). PR 본문의 "## 흐름도 (AI)" 섹션 내용만 교체해서 gh pr edit --body 로 반영하라.
            다른 섹션은 한 글자도 바꾸지 마라.
          claude_args: |
            --allowedTools "Read,Grep,Glob,Bash(gh pr diff:*),Bash(gh pr view:*),Bash(gh pr edit:*)"
            --max-turns 10

  door:
    if: vars.AI_ENABLED == 'true'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }
      - env:
          GH_TOKEN: ${{ github.token }}
        run: |
          files=$(git diff --name-only ${{ github.event.pull_request.base.sha }}...HEAD)
          if echo "$files" | grep -qE 'db/migration/|^common/common-event/|^docs/specs/events.md|^services/auth/|^services/gateway/.*/security/|/reservation/'; then
            gh pr edit ${{ github.event.pull_request.number }} --add-label one-way-door
          else
            gh pr edit ${{ github.event.pull_request.number }} --add-label two-way-door
          fi
```

### `workflows/pr-body.yml`
```yaml
name: pr-body
on:
  pull_request:
    types: [opened, edited, reopened, synchronize]

permissions:
  contents: read

jobs:
  explain:
    runs-on: ubuntu-latest
    steps:
      - env:
          BODY: ${{ github.event.pull_request.body }}
        run: |
          why=$(echo "$BODY" | awk '/^## 왜/{f=1;next} /^## /{f=0} f' \
            | grep -vE '^\s*$|^\s*<!--|-->\s*$|^- 명세: docs/specs/\s*$|^- closes #\s*$')
          [ -n "$why" ] || { echo "'## 왜' 섹션을 직접 작성하세요"; exit 1; }
          echo "$BODY" | grep -q '\- \[x\] 이 변경을 AI 없이' \
            || { echo "'설명할 수 있다' 체크박스를 확인하세요"; exit 1; }
```

### `workflows/gate.yml`
```yaml
name: gate
on:
  pull_request:
    types: [opened, synchronize, reopened, edited, labeled, unlabeled]
  pull_request_review:
    types: [submitted, dismissed]

permissions:
  contents: read
  pull-requests: read
  checks: read

concurrency:
  group: gate-${{ github.event.pull_request.number }}
  cancel-in-progress: true

jobs:
  # one-way-door 변경은 사람 승인 1명 필수 (라벨별 승인 수는 브랜치 보호로 못 걸어서 체크로 강제)
  door-gate:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }
      - env:
          GH_TOKEN: ${{ github.token }}
          PR: ${{ github.event.pull_request.number }}
          BASE: ${{ github.event.pull_request.base.sha }}
          HEAD: ${{ github.event.pull_request.head.sha }}
        run: |
          # ci.yml door job 과 같은 경로 패턴. 라벨을 기다리지 않고 파일로 직접 판정한다
          files=$(git diff --name-only "$BASE...$HEAD")
          labels=$(gh pr view "$PR" --json labels --jq '.labels[].name')
          if ! echo "$labels" | grep -x one-way-door >/dev/null \
             && ! echo "$files" | grep -E 'db/migration/|^common/common-event/|^docs/specs/events.md|^services/auth/|^services/gateway/.*/security/|/reservation/' >/dev/null; then
            echo "two-way-door: 승인 불필요"; exit 0
          fi
          approved=$(gh pr view "$PR" --json reviews --jq '
            [.reviews[] | select(.state == "APPROVED" or .state == "CHANGES_REQUESTED" or .state == "DISMISSED")]
            | group_by(.author.login) | map(last) | map(select(.state == "APPROVED")) | length')
          if [ "$approved" -lt 1 ]; then
            echo "::error::one-way-door 변경이다. 사람 승인 1명이 필요하다"; exit 1
          fi
          echo "one-way-door: 승인 $approved 명"

  # AI 리뷰(msa, security, spec) 위반 코멘트가 이 커밋에 있으면 실패. quality 는 보지 않는다
  ai-review-gate:
    runs-on: ubuntu-latest
    timeout-minutes: 40
    steps:
      - env:
          GH_TOKEN: ${{ github.token }}
          REPO: ${{ github.repository }}
          PR: ${{ github.event.pull_request.number }}
          SHA: ${{ github.event.pull_request.head.sha }}
          AI_ENABLED: ${{ vars.AI_ENABLED }}
        run: |
          if [ "$AI_ENABLED" != "true" ]; then
            echo "AI 리뷰 꺼짐 (vars.AI_ENABLED != true): 통과"; exit 0
          fi

          if gh pr view "$PR" -R "$REPO" --json labels --jq '.labels[].name' | grep -x ai-review-override >/dev/null; then
            reason=$(gh pr view "$PR" -R "$REPO" --json body --jq .body \
              | awk '/^## AI 리뷰 무시 사유/{f=1;next} /^## /{f=0} f' | grep -vE '^\s*$|^\s*<!--|-->\s*$' || true)
            [ -n "$reason" ] || { echo "::error::ai-review-override 라벨이 있으면 '## AI 리뷰 무시 사유'를 작성하라"; exit 1; }
            echo "ai-review-override: AI 리뷰 결과를 무시하고 통과"; exit 0
          fi

          # ci.yml 의 check 와 review 4개가 이 커밋에서 끝날 때까지 대기 (최대 30분)
          for i in $(seq 1 120); do
            runs=$(gh api "repos/$REPO/commits/$SHA/check-runs?per_page=100" --jq '.check_runs')
            latest() { echo "$runs" | jq -r --arg n "$1" '[.[] | select(.name == $n)] | sort_by(.started_at) | last // {} | "\(.status // "none"):\(.conclusion // "")"'; }
            check=$(latest check)
            case "$check" in
              completed:success) ;;
              completed:*) echo "check 실패: AI 리뷰 없음 (check 가 머지를 막는다)"; exit 0 ;;
              *) echo "check 대기 ($check)"; sleep 15; continue ;;
            esac
            pending=0; failed=0
            for k in msa security spec quality; do
              r=$(latest "review ($k)")
              case "$r" in
                completed:success) ;;
                completed:*) failed=1 ;;
                *) pending=1 ;;
              esac
            done
            [ "$pending" = 1 ] && { echo "review 대기"; sleep 15; continue; }
            [ "$failed" = 1 ] && { echo "::error::AI 리뷰 실행이 실패했다. 다시 실행하거나 ai-review-override 를 사용하라"; exit 1; }

            violations=$(gh api "repos/$REPO/pulls/$PR/comments" --paginate \
              --jq '.[] | select(.body | test("^\\[(msa|security|spec)\\]")) | "\(.original_commit_id) \(.path):\(.line // .original_line) \(.body | split("\n")[0])"' \
              | awk -v sha="$SHA" '$1 == sha { $1 = ""; print }')
            if [ -n "$violations" ]; then
              echo "::error::AI 리뷰 위반 $(echo "$violations" | wc -l | tr -d ' ')건. 고치거나 ai-review-override 라벨 + 사유를 남겨라"
              echo "$violations"; exit 1
            fi
            echo "AI 리뷰 위반 없음"; exit 0
          done
          echo "::error::AI 리뷰가 30분 안에 끝나지 않았다"; exit 1
```

### `workflows/cd.yml`
```yaml
name: cd
on:
  push:
    branches: [main]
  workflow_dispatch:   # 수동 실행 시 서비스 전체 배포

permissions:
  contents: read
  id-token: write

jobs:
  # Repository variable CD_ENABLED=true 일 때만 실행 (AWS 시크릿, ECR/ECS 준비 후 켠다)
  detect:
    if: vars.CD_ENABLED == 'true'
    runs-on: ubuntu-latest
    outputs:
      services: ${{ steps.d.outputs.services }}
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 2 }
      - id: d
        run: |
          files=$(git diff --name-only HEAD~1 HEAD)
          if [ "${{ github.event_name }}" = "workflow_dispatch" ] || echo "$files" | grep -qE '^(common/|build\.gradle|settings\.gradle)'; then
            list="gateway order inventory purchase production auth simulator"
          else
            list=$(echo "$files" | grep -oE '^services/[^/]+' | sort -u | sed 's#services/##' | tr '\n' ' ')
          fi
          echo "services=$(echo $list | jq -Rc 'split(" ") | map(select(length>0))')" >> $GITHUB_OUTPUT

  deploy:
    needs: detect
    if: needs.detect.outputs.services != '[]'
    runs-on: ubuntu-latest
    strategy:
      matrix:
        svc: ${{ fromJson(needs.detect.outputs.services) }}
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17', cache: gradle }
      - name: inject application-dev.yml
        env:
          APP_DEV_YML: ${{ secrets[format('APP_DEV_YML_{0}', matrix.svc)] }}
        run: |
          if [ -z "$APP_DEV_YML" ]; then
            echo "::error::GitHub Secret APP_DEV_YML_${{ matrix.svc }} 이 없다"; exit 1
          fi
          printf '%s\n' "$APP_DEV_YML" > services/${{ matrix.svc }}/src/main/resources/application-dev.yml
      - run: ./gradlew :services:${{ matrix.svc }}:bootJar --no-daemon
      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: ${{ secrets.AWS_DEPLOY_ROLE_ARN }}
          aws-region: ${{ vars.AWS_REGION }}
      - id: ecr
        uses: aws-actions/amazon-ecr-login@v2
      - id: img
        run: |
          REPO=${{ steps.ecr.outputs.registry }}/ff-${{ matrix.svc }}
          docker build -t $REPO:${{ github.sha }} -t $REPO:latest services/${{ matrix.svc }}
          docker push $REPO:${{ github.sha }}
          docker push $REPO:latest
          echo "image=$REPO:${{ github.sha }}" >> $GITHUB_OUTPUT
      - run: |
          aws ecs describe-task-definition --task-definition ff-${{ matrix.svc }} \
            --query taskDefinition > td.json
      - id: td
        uses: aws-actions/amazon-ecs-render-task-definition@v1
        with:
          task-definition: td.json
          container-name: ${{ matrix.svc }}
          image: ${{ steps.img.outputs.image }}
      - uses: aws-actions/amazon-ecs-deploy-task-definition@v2
        with:
          task-definition: ${{ steps.td.outputs.task-definition }}
          service: ff-${{ matrix.svc }}
          cluster: ${{ vars.ECS_CLUSTER }}
          wait-for-service-stability: true
```

### `workflows/incident.yml`
```yaml
name: incident
on:
  repository_dispatch:
    types: [cloudwatch-alarm]

permissions:
  contents: read
  issues: write
  id-token: write

jobs:
  diagnose:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: ${{ secrets.AWS_READONLY_ROLE_ARN }}
          aws-region: ap-northeast-2
      - uses: anthropics/claude-code-action@v1
        with:
          claude_code_oauth_token: ${{ secrets.CLAUDE_CODE_OAUTH_TOKEN }}
          prompt: |
            CloudWatch 알람: ${{ toJson(github.event.client_payload) }}
            1. aws logs 로 관련 /ecs/ff-* 로그 최근 30분 확인
            2. 에러와 관련된 코드, docs/runbooks/ 확인
            3. .github/ISSUE_TEMPLATE/incident.md 형식으로 gh issue create --label incident
            "조치 결과" 섹션은 비워둔다.
          claude_args: |
            --allowedTools "Read,Grep,Glob,Bash(aws logs:*),Bash(gh issue create:*)"
            --max-turns 20
      - if: always()
        run: |
          curl -s -X POST -H 'Content-type: application/json' \
            -d "{\"text\":\"[FactoryFlow 장애] ${{ github.event.client_payload.alarm }} - GitHub incident 이슈 확인\"}" \
            ${{ secrets.SLACK_WEBHOOK_URL }}
```

### `lambda/ff-alarm-to-github/lambda_function.py`
```python
import json, os, urllib.request

def lambda_handler(event, context):
    msg = json.loads(event["Records"][0]["Sns"]["Message"])
    body = json.dumps({
        "event_type": "cloudwatch-alarm",
        "client_payload": {
            "alarm": msg.get("AlarmName"),
            "reason": msg.get("NewStateReason"),
            "time": msg.get("StateChangeTime"),
        },
    }).encode()
    req = urllib.request.Request(
        "https://api.github.com/repos/DongBrothers/FactoryFlow_BE/dispatches",
        data=body,
        headers={
            "Authorization": f"Bearer {os.environ['GITHUB_TOKEN']}",
            "Accept": "application/vnd.github+json",
        },
        method="POST",
    )
    urllib.request.urlopen(req)
    return {"ok": True}
```

## 9. `.gitignore`
```
.gradle/
build/
.idea/
*.iml
out/
.env
*.pem
**/application-local.yml
**/application-dev.yml
.claude/harness.log
.claude/settings.local.json
```

---

# PART 3. 사람이 직접 할 일 (Claude Code 밖)

## 시작 전
- [ ] 현재 상태 커밋: `git add -A && git commit -m "init"`
- [ ] `brew install jq`

## 구축 후
- [ ] claude 재시작 후 하네스 테스트: "services/order 에 com.factoryflow.inventory 패키지 클래스를 import 하는 코드를 추가하고 작업을 끝내 봐" → Stop 훅이 잡고 스스로 되돌리는지, harness.log에 ARCH_VIOLATION 남는지 확인 → `git checkout .`
- [ ] main 에 push (PR/이슈 템플릿은 main 에 있어야 GitHub 화면에 나타남)
- [ ] `claude` 안에서 `/install-github-app`
- [ ] GitHub Secrets: `CLAUDE_CODE_OAUTH_TOKEN` (`/install-github-app` 에서 Claude 구독 인증 시 자동 생성), `AWS_DEPLOY_ROLE_ARN`, `AWS_READONLY_ROLE_ARN`, `SLACK_WEBHOOK_URL`, `APP_DEV_YML_<SVC>` (서비스 7개, 각 서비스 application-dev.yml 내용)
- [ ] Repository variables: `AWS_REGION`(ap-northeast-2), `ECS_CLUSTER`(ff-cluster), `AI_ENABLED`(AI 시크릿+라벨 준비 후 true), `CD_ENABLED`(AWS 준비 후 true)
- [ ] 라벨 생성
  ```bash
  for l in feature bug incident harness one-way-door two-way-door ai-review-override; do gh label create $l -R DongBrothers/FactoryFlow_BE; done
  ```
- [ ] main 브랜치 보호: PR 필수, Require approvals 0, status check `check`, `explain`, `door-gate`, `ai-review-gate` 필수
  - 사람 승인은 one-way-door 변경에만 필요 (door-gate 가 강제). 나머지는 AI 리뷰(msa/security/spec)가 머지 관문
  - AI 지적이 틀렸으면 `ai-review-override` 라벨 + PR 의 "AI 리뷰 무시 사유" 작성
- [ ] (CD 전) AWS OIDC 역할 2개: deploy용(ECR push, ECS 배포), readonly용(CloudWatch Logs 읽기)
- [ ] **이 문서(`docs/harness-setup.md`) 삭제**
