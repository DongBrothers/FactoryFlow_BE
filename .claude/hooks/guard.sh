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
