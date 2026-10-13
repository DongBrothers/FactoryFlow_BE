#!/usr/bin/env bash
# one-way door(머지 후 되돌리기 어려운 변경) 판정. ci.yml door, gate.yml door-gate 가 같이 쓴다.
# 해당하면 이유를 한 줄씩 출력하고 0, 아니면 1 을 돌려준다.
#   - 이벤트 계약(docs/specs/events.md) 변경: 다른 서비스가 이미 받고 있는 payload
#   - 이미 있는 마이그레이션 수정·삭제: 적용된 환경과 체크섬이 어긋난다
#   - 마이그레이션에 데이터를 잃을 수 있는 SQL 추가 (DROP, RENAME, MODIFY, CHANGE, TRUNCATE, DELETE)
# 코드만 바뀐 변경은 PR 을 revert 하면 되돌릴 수 있으므로 해당하지 않는다.
set -euo pipefail
base=$1
head=$2
reasons=()

if git diff --name-only "$base...$head" | grep -qx 'docs/specs/events.md'; then
  reasons+=("이벤트 계약(docs/specs/events.md) 변경")
fi

changed=$(git diff --name-only --diff-filter=MDR "$base...$head" -- '*/db/migration/*' || true)
if [ -n "$changed" ]; then
  reasons+=("이미 있는 마이그레이션 수정·삭제: $(echo $changed)")
fi

destructive=$(git diff "$base...$head" -- '*/db/migration/*.sql' \
  | grep -E '^\+[^+]' \
  | grep -iE '\b(DROP|RENAME|MODIFY|CHANGE|TRUNCATE|DELETE)\b' || true)
if [ -n "$destructive" ]; then
  reasons+=("마이그레이션에 데이터를 잃을 수 있는 SQL: $(echo "$destructive" | head -3 | tr '\n' ' ')")
fi

if [ ${#reasons[@]} -gt 0 ]; then
  printf '%s\n' "${reasons[@]}"
  exit 0
fi
exit 1
