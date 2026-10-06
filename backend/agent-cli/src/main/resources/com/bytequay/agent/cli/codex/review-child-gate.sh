#!/bin/sh
# Codex supplies agent_id to PreToolUse for child calls. Its task MCP token does not.
# Children explore and communicate; saved review and memory changes stay with the root.

payload=$(/bin/cat)
name=$(printf '%s' "$payload" | /usr/bin/plutil -extract tool_name raw -o - - 2>/dev/null) || {
  echo "ByteQuay could not read this tool call, so it was refused." >&2
  exit 2
}
printf '%s' "$payload" | /usr/bin/plutil -extract agent_id raw -o /dev/null - 2>/dev/null || exit 0
case "$name" in
  mcp__bytequay__review_get_context|mcp__bytequay__code_query) exit 0 ;;
  mcp__bytequay__repository_memory|repository_memory)
    action=$(printf '%s' "$payload" | /usr/bin/plutil -extract tool_input.action raw -o - - 2>/dev/null)
    case "$action" in SEARCH|READ) exit 0 ;; esac
    ;;
  Bash|write_stdin) exit 0 ;;
  collaborationsend_message|send_message)
    target=$(printf '%s' "$payload" | /usr/bin/plutil -extract tool_input.target raw -o - - 2>/dev/null)
    root=$(printf '%s' "$payload" | /usr/bin/plutil -extract session_id raw -o - - 2>/dev/null)
    [ "$target" = "/root" ] && exit 0
    [ -n "$root" ] && [ "$target" = "$root" ] && exit 0
    ;;
esac
echo "ByteQuay review child scope: use context, IDE code queries, memory SEARCH/READ, sandboxed source searches, or messages to the main agent. Save changes and permission requests for the main agent. Return missing evidence in your final answer; do not retry denied calls." >&2
exit 2
