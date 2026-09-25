#!/bin/sh
# Codex supplies agent_id to PreToolUse for child calls. Its task MCP token does not.
# Every child tool except the two bounded review reads is refused here.

payload=$(/bin/cat)
name=$(printf '%s' "$payload" | /usr/bin/plutil -extract tool_name raw -o - - 2>/dev/null) || {
  echo "ByteQuay could not read this tool call, so it was refused." >&2
  exit 2
}
printf '%s' "$payload" | /usr/bin/plutil -extract agent_id raw -o /dev/null - 2>/dev/null || exit 0
case "$name" in
  mcp__bytequay__review_get_context|mcp__bytequay__codegraph_explore) exit 0 ;;
esac
echo "A review child may only read ByteQuay review context or CodeGraph. Report results to the main agent." >&2
exit 2
