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
git diff --name-only --diff-filter=D HEAD | grep -q 'src/test/' \
  && fail TEST_DELETED "테스트 파일 삭제 금지. 테스트가 틀렸다면 이유를 말하고 멈춰라."
git diff -U0 HEAD -- '*.java' | grep -qE '^\+.*@Disabled' \
  && fail TEST_DISABLED "@Disabled 추가 금지."
git diff -U0 HEAD -- '*.java' | grep -qE '^\+.*assertTrue\(true\)' \
  && fail FAKE_ASSERT "의미 없는 assert 금지."

# 2) 변경된 서비스만 check (common/루트 변경 시 전체)
if echo "$changed" | grep -qE '^(common/|build\.gradle|settings\.gradle)'; then
  tasks="check"
else
  tasks=$(echo "$changed" | grep -oE '^services/[^/]+' | sort -u \
    | sed 's#services/#:services:#; s#$#:check#' | tr '\n' ' ')
fi

if [ -n "$tasks" ]; then
  out=$(./gradlew $tasks -q --console=plain 2>&1) || {
    type=BUILD_FAIL
    echo "$out" | grep -qE 'Architecture Violation' && type=ARCH_VIOLATION
    echo "$out" | grep -qE 'tests completed, [0-9]+ failed' && type=TEST_FAIL
    fail "$type" "검사 실패. 고친 뒤 다시 끝내라.\n$(echo "$out" | tail -40)"
  }
fi

rm -f "$CNT"; exit 0
