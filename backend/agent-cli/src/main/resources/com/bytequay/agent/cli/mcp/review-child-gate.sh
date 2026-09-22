#!/bin/sh
# ByteQuay review child gate, run by Claude Code as a PreToolUse hook.
#
# The task's MCP token cannot tell the root agent from a child it starts, and a
# child inherits every MCP tool. Claude Code marks a child's tool call with
# agent_id in the hook input. This gate keeps every ByteQuay tool that saves or
# changes state with the root agent, and admits only read-only Explore children.
# plutil parses the JSON; a call it cannot read is refused.

payload=$(cat)
name=$(printf '%s' "$payload" | /usr/bin/plutil -extract tool_name raw -o - - 2>/dev/null) || {
  echo "ByteQuay could not read this tool call, so it was refused." >&2
  exit 2
}
case "$name" in
  Agent|Task)
    kind=$(printf '%s' "$payload" | /usr/bin/plutil -extract tool_input.subagent_type raw -o - - 2>/dev/null)
    [ "$kind" = "Explore" ] && exit 0
    echo "A review may start only read-only Explore subagents, for searching and exploring." >&2
    exit 2
    ;;
esac
printf '%s' "$payload" | /usr/bin/plutil -extract agent_id raw -o /dev/null - 2>/dev/null || exit 0
case "$name" in
  @CHILD_TOOLS@) exit 0 ;;
esac
echo "This ByteQuay tool belongs to the main agent. Report your result to it instead." >&2
exit 2
