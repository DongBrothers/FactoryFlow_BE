# FactoryFlow 구축 문서 (AI 하네스 포함)

> 이 문서는 Claude Code가 읽고 프로젝트를 구축하기 위한 기준 문서다.
> 위치: `docs/harness-setup.md`

---

# PART 1. 구축 순서 (Claude Code는 이 순서를 반드시 지킨다)

## 공통 규칙
- 각 단계가 끝나면 결과를 짧게 보고하고, **내 확인을 받은 뒤** 다음 단계로 간다.
- PART 2의 파일 내용은 **그대로** 만든다. 문서와 다르게 고칠 부분이 보이면 고치지 말고 목록으로 알려준다.
- 버전 호환이 확실하지 않으면 추측하지 말고 멈추고 묻는다.
- 테스트를 지우거나 끄는 방식으로 빌드를 통과시키지 않는다.
- `.claude/settings.json`은 **맨 마지막(6단계)**에 만든다. 이 파일이 생기면 guard 훅이 `.claude/`, `.github/`, `common/` 수정을 막기 때문이다.

## 1단계: Gradle 멀티모듈 뼈대
1. 루트 `src/` 삭제 (루트는 모듈이 아님)
2. `settings.gradle`: rootProject.name = 'factoryflow'
   - include: `common:common-event`, `common:common-web`, `common:common-test`
   - include: `services:gateway`, `order`, `inventory`, `purchase`, `production`, `auth`, `simulator`
3. 루트 `build.gradle` (Groovy DSL)
   - Java 17 toolchain, group `com.factoryflow`, mavenCentral
   - Spring Boot 4.1.1, io.spring.dependency-management → `plugins { ... apply false }`
   - subprojects 공통: java, JUnit Platform, lombok
   - `services:*`에만 Spring Boot 플러그인, `common:*`은 java-library + BOM
4. 각 서비스: `build.gradle`, `com.factoryflow.<svc>.<Svc>Application`, `application.yml` (spring.application.name, server.port 8080, actuator health 노출)
   - gateway: Spring Cloud Gateway (Boot 4.1.1과 호환되는 Spring Cloud 버전 확인)
   - 나머지: web, actuator, validation, data-jpa, mysql, flyway, amqp, data-redis
   - 의존: `testImplementation project(':common:common-test')`, `implementation project(':common:common-web')`, 이벤트 사용 서비스는 `project(':common:common-event')`
   - **서비스끼리 `project(':services:...')` 의존 금지**
5. Gradle wrapper 버전이 Boot 4.1.1 요구사항을 만족하는지 확인
6. `./gradlew build -x test` 통과

## 2단계: common 모듈 + 서비스 내부 구조
1. common-test: PART 2의 5번 `FactoryFlowRules` (archunit-junit5 최신 1.x, api 의존으로 노출)
2. 각 서비스 `src/test`에 5번 `ArchitectureTest` (gateway는 isolation, injection만)
3. common-event (`com.factoryflow.common.event`)
   - 이벤트 베이스: eventId(UUID), traceId, version, occurredAt
   - `EventPublisher`: 같은 트랜잭션에서 outbox 테이블에 저장
   - `OutboxRelay`: 스케줄러로 미발행 outbox를 RabbitTemplate으로 발행 (exchange `factoryflow.events`, topic, 라우팅키 = 이벤트 이름)
   - 수신 멱등 처리: processed_event 저장/확인 컴포넌트
   - RabbitTemplate은 이 모듈 안에서만 사용
4. common-web: 공통 응답 형식, 전역 예외 처리, traceId 필터(MDC)
5. 각 서비스(gateway 제외) 패키지: `api/`, `event/listener/`, `event/publisher/`, `client/` (빈 패키지는 package-info.java)
   - outbox, processed_event 테이블은 서비스별 Flyway `V1__init.sql`
6. 각 서비스 `Dockerfile` (eclipse-temurin:17-jre, build/libs/*.jar, 8080)
7. 루트 `docker-compose.yml`: MySQL 8.4 (order_db, inventory_db, purchase_db, production_db, auth_db), Valkey, rabbitmq:3.13-management. 비밀번호는 `.env`에서 읽고 `.env.example`만 만든다
8. `./gradlew check` 통과

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
2. `.claude/agents/msa-reviewer.md`, `.claude/skills/add-event/SKILL.md`
3. **마지막으로** `.claude/settings.json`
4. 훅 테스트
   ```bash
   echo '{"tool_input":{"file_path":"'$(pwd)'/.github/CODEOWNERS"}}' | .claude/hooks/guard.sh; echo $?   # 2
   echo '{"tool_input":{"file_path":"'$(pwd)'/services/order/src/main/java/A.java"}}' | .claude/hooks/guard.sh; echo $?   # 0
   echo '{"session_id":"test"}' | .claude/hooks/stop-check.sh; echo $?   # 변경 없으면 0
   ```
5. 끝나면 "claude를 재시작하라"고 알려준다

---

# PART 2. 설계와 파일 내용

## 1. 영상 → 프로젝트 적용

| 영상 | 핵심 | 적용 |
|---|---|---|
| V1 Quenneville (하네스) | 지침(미리 알려줌) + 센서(결과 검사). 말보다 기계 검사 | CLAUDE.md(지침), Stop 훅·ArchUnit·guard 훅(센서) |
| V2 Voss (리뷰 하네스) | PR에서 AI가 체크리스트 기반으로 나눠서 리뷰 | `.github/review/*.md`, ci.yml review 매트릭스, autofix |
| V3 5원칙 | ② 실패에서 출발 ③ 규칙은 적게 ⑤ 계속 개선 | harness.log + 2회 규칙 harness 이슈, 대응 우선순위 |
| V4 Pocock (PR 병목) | 사람 리뷰량 축소. 딥모듈, 다이어그램, 되돌릴 수 없는 결정 집중 | 서비스별 `api/` 진입점, PR 흐름도 자동, one-way-door 라벨 + CODEOWNERS |

## 2. 프로젝트 구조

```
factoryflow/
├── CLAUDE.md
├── settings.gradle / build.gradle
├── docker-compose.yml / .env.example
├── .gitignore
├── .claude/
│   ├── settings.json
│   ├── harness.log               # 자동 실패 로그 (gitignore)
│   ├── hooks/stop-check.sh
│   ├── hooks/guard.sh
│   ├── agents/msa-reviewer.md
│   └── skills/add-event/SKILL.md
├── .github/
│   ├── CODEOWNERS
│   ├── PULL_REQUEST_TEMPLATE.md
│   ├── ISSUE_TEMPLATE/{config.yml, feature.md, bug.md, incident.md, harness.md}
│   ├── review/{standards.md, msa.md, security.md, spec.md}
│   └── workflows/{ci.yml, cd.yml, incident.yml}
├── docs/
│   ├── harness-setup.md          # 이 문서
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
        ├── api/                  # 외부 진입점 (Controller + Facade)
        ├── <업무>/               # 도메인, 서비스, Repository
        ├── event/listener/
        ├── event/publisher/
        └── client/
```

## 3. CLAUDE.md

### 루트 `CLAUDE.md`
```markdown
# FactoryFlow
현대차 공장 모델 ERP+MES. Java 17, Spring Boot 4.1.1, Gradle(Groovy) 멀티모듈.

## 명령
- 서비스 검사: ./gradlew :services:<svc>:check
- 전체: ./gradlew check
- 로컬 인프라: docker compose up -d

## 절대 규칙
- 서비스 간 코드 직접 참조 금지. 비동기는 이벤트(common-event), 동기는 client/
- 외부에서 들어오는 호출(Controller, Listener)은 api/ 를 거친다. Repository 직접 호출 금지
- RabbitTemplate 직접 사용 금지. EventPublisher 사용
- 생성자 주입만
- 테스트 삭제, @Disabled, 의미 없는 assert 금지. 테스트가 틀렸다고 판단되면 이유를 말하고 멈춘다

## 사람만 수정 (수정 필요 시 제안만)
.claude/, .github/, common/common-event/, common/common-test/, docs/specs/events.md, 루트 build.gradle, settings.gradle

## 작업 순서
1. docs/specs/ 의 해당 명세 확인 (없으면 _template.md로 초안 작성 후 확인 요청)
2. 테스트 먼저 → 구현
3. 이벤트 추가는 /add-event 사용
```

### `services/order/CLAUDE.md` (서비스별 예시)
```markdown
# order 서비스
- DB: order_db (Flyway: src/main/resources/db/migration)
- 발행: order.created, order.cancelled
- 수신: inventory.reserved, inventory.reservation-failed
- 진입점: api/OrderApi
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

# 2) 변경된 서비스만 check (common/루트 변경 시 전체)
if echo "$changed" | grep -E '^(common/|build\.gradle|settings\.gradle)' >/dev/null; then
  tasks="check"
else
  tasks=$(echo "$changed" | grep -oE '^services/[^/]+' | sort -u \
    | sed 's#services/#:services:#; s#$#:check#' | tr '\n' ' ')
fi

if [ -n "$tasks" ]; then
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
  .claude/*|.github/*|common/common-test/*|common/common-event/*|docs/specs/events.md|build.gradle|settings.gradle)
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

### `agents/msa-reviewer.md`
```markdown
---
name: msa-reviewer
description: 커밋 전 변경사항을 MSA 규칙 기준으로 검토. "리뷰해줘" 요청 시 사용
tools: Read, Grep, Glob, Bash(git diff:*)
---
git diff 기준으로 아래만 확인하고, 위반만 "파일:줄 - 문제 - 수정안" 형식으로 보고한다. 칭찬, 요약 금지.
1. 다른 서비스 패키지/DB 직접 접근
2. Controller/Listener가 api/를 거치지 않음
3. 이벤트 발행이 트랜잭션 + Outbox를 거치지 않음
4. Listener 멱등 처리(processed_event) 누락
5. 실패 시 보상 이벤트 누락
6. docs/specs/events.md 에 없는 이벤트 사용
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
3. 수신 서비스: event/listener/ 에 @RabbitListener → processed_event로 중복 확인 → api/ 호출
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
            .because("진입점은 api/를 거친다");

    public static final ArchRule NO_DIRECT_PUBLISH =
        noClasses().that().resideOutsideOfPackage("com.factoryflow.common.event..")
            .should().dependOnClassesThat()
            .haveFullyQualifiedName("org.springframework.amqp.rabbit.core.RabbitTemplate")
            .because("발행은 EventPublisher(Outbox)로만");

    public static final ArchRule NO_FIELD_INJECTION =
        GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

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
git log --oneline --grep='\[ai-fix\]' | wc -l                   # autofix 커밋 수
gh issue list -l harness --state all                            # 하네스 개선 이력
```

## 8. `.github/`

### `CODEOWNERS`
```
*                                   @DongBrothers/factoryflow
```

### `PULL_REQUEST_TEMPLATE.md`
```markdown
## 무엇을
## 왜 (명세/이슈)
closes #

## 흐름도 (AI)
<!-- ci가 자동 작성 -->

## 확인
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

### `review/standards.md`
```markdown
- 필드 주입 → 생성자 주입
- 사용 안 하는 import, 변수
- System.out → log
- 매직넘버 → 상수
- Optional.get() → orElseThrow()
```

### `review/msa.md`
```markdown
- 다른 서비스 코드/DB 직접 접근
- Controller/Listener → api/ 경유 여부
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
- PR이 연결된 명세(docs/specs)의 완료 조건을 모두 구현했는가
- 명세에 없는 동작 추가 여부
- 완료 조건마다 테스트가 있는가
```

### `workflows/ci.yml`
```yaml
name: ci
on:
  pull_request:
    branches: [main]

permissions:
  contents: write
  pull-requests: write
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

  autofix:
    needs: check
    runs-on: ubuntu-latest
    outputs:
      pushed: ${{ steps.push.outputs.pushed }}
    steps:
      - uses: actions/checkout@v4
        with:
          ref: ${{ github.head_ref }}
          token: ${{ secrets.AI_FIX_TOKEN }}
          fetch-depth: 0
      - id: skip
        run: |
          if git log -1 --pretty=%s | grep -q '\[ai-fix\]'; then echo "skip=true" >> $GITHUB_OUTPUT; fi
      - if: steps.skip.outputs.skip != 'true'
        uses: anthropics/claude-code-action@v1
        with:
          anthropic_api_key: ${{ secrets.ANTHROPIC_API_KEY }}
          prompt: |
            .github/review/standards.md 항목만, 이 PR에서 변경된 줄에 한해 고쳐라.
            동작이 바뀔 수 있는 수정은 하지 마라. 커밋하지 마라.
          claude_args: |
            --allowedTools "Read,Edit,Grep,Glob,Bash(git diff:*)"
            --max-turns 10
      - id: push
        if: steps.skip.outputs.skip != 'true'
        run: |
          if [ -n "$(git status --porcelain)" ]; then
            git config user.name "ff-ai"; git config user.email "ff-ai@users.noreply.github.com"
            git commit -am "[ai-fix] standards 자동 수정"
            git push
            echo "pushed=true" >> $GITHUB_OUTPUT
          fi

  review:
    needs: autofix
    if: needs.autofix.outputs.pushed != 'true'
    runs-on: ubuntu-latest
    strategy:
      matrix:
        kind: [msa, security, spec]
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }
      - uses: anthropics/claude-code-action@v1
        with:
          anthropic_api_key: ${{ secrets.ANTHROPIC_API_KEY }}
          prompt: |
            PR #${{ github.event.pull_request.number }} 리뷰.
            기준: .github/review/${{ matrix.kind }}.md 항목만.
            위반이 있는 줄에만 인라인 코멘트를 남겨라. 형식: [${{ matrix.kind }}] 문제 - 수정안
            위반이 없으면 아무것도 남기지 마라. 요약, 칭찬 금지.
          claude_args: |
            --allowedTools "Read,Grep,Glob,Bash(gh pr diff:*),Bash(gh pr view:*),mcp__github_inline_comment__create_inline_comment"
            --max-turns 15

  diagram:
    needs: check
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }
      - uses: anthropics/claude-code-action@v1
        with:
          anthropic_api_key: ${{ secrets.ANTHROPIC_API_KEY }}
          prompt: |
            PR #${{ github.event.pull_request.number }} 변경을 Mermaid sequenceDiagram 하나로 그려라
            (서비스, 이벤트, DB 단위). PR 본문의 "## 흐름도 (AI)" 섹션 내용만 교체해서 gh pr edit --body 로 반영하라.
          claude_args: |
            --allowedTools "Read,Grep,Glob,Bash(gh pr diff:*),Bash(gh pr view:*),Bash(gh pr edit:*)"
            --max-turns 10

  door:
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

### `workflows/cd.yml`
```yaml
name: cd
on:
  push:
    branches: [main]

permissions:
  contents: read
  id-token: write

jobs:
  detect:
    runs-on: ubuntu-latest
    outputs:
      services: ${{ steps.d.outputs.services }}
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 2 }
      - id: d
        run: |
          files=$(git diff --name-only HEAD~1 HEAD)
          if echo "$files" | grep -qE '^(common/|build\.gradle|settings\.gradle)'; then
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
      - run: ./gradlew :services:${{ matrix.svc }}:bootJar --no-daemon
      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: ${{ secrets.AWS_DEPLOY_ROLE_ARN }}
          aws-region: ap-northeast-2
      - id: ecr
        uses: aws-actions/amazon-ecr-login@v2
      - id: img
        run: |
          IMG=${{ steps.ecr.outputs.registry }}/ff-${{ matrix.svc }}:${{ github.sha }}
          docker build -t $IMG services/${{ matrix.svc }}
          docker push $IMG
          echo "image=$IMG" >> $GITHUB_OUTPUT
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
          cluster: ff-cluster
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
          anthropic_api_key: ${{ secrets.ANTHROPIC_API_KEY }}
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
- [ ] `claude` 안에서 `/install-github-app`
- [ ] GitHub Secrets: `ANTHROPIC_API_KEY`, `AI_FIX_TOKEN`, `AWS_DEPLOY_ROLE_ARN`, `AWS_READONLY_ROLE_ARN`, `SLACK_WEBHOOK_URL`
- [ ] 라벨 생성
  ```bash
  for l in feature bug incident harness one-way-door two-way-door; do gh label create $l -R DongBrothers/FactoryFlow_BE; done
  ```
- [ ] main 브랜치 보호: PR 필수, Code Owners 리뷰 필수, status check `check` 필수
- [ ] (CD 전) AWS OIDC 역할 2개: deploy용(ECR push, ECS 배포), readonly용(CloudWatch Logs 읽기)
