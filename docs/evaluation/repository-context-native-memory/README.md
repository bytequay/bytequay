# Native-memory isolation acceptance check

Two fresh Codex tasks and two fresh Claude tasks ran through ByteQuay in the
same disposable workspace. For each provider, task 1 received an arbitrary
one-time marker and repeated it. Task 2 was a separate task and provider
thread; its prompt and worktree did not contain that marker. Both task 2
responses said `UNKNOWN` and did not reproduce the marker. The ByteQuay
notebook remained at revision zero with no entries, and neither provider
called a tool during the canary.

This checks that an earlier ByteQuay task did not visibly leak its marker into
a later task in this setup. It does not prove the absence of provider-held
memory, pretrained knowledge, or other behavior under different provider
versions, settings, prompts, or tasks.

| Provider | Requested model | Fresh threads | First task saw and repeated marker | Second task input contained marker | Second task response contained marker | Second task said `UNKNOWN` |
| --- | --- | --- | --- | --- | --- | --- |
| Codex | `gpt-6-luna`, low effort | Yes | Yes | No | No | Yes |
| Claude | `sonnet`, High effort | Yes | Yes | No | No | Yes |

ByteQuay launched Codex App Server with `memories` disabled and sent the same
feature setting when starting each thread. Its forced `CODEX_HOME` points at
the ByteQuay profile. ByteQuay launched Claude with its forced
`CLAUDE_CONFIG_DIR`, an empty setting-source list, and a new session ID for
each new task. The generated Claude settings observed during both turns had
`autoMemoryEnabled: false`. The corresponding configuration is in
`CodexLaunchProfile`, `CodexCliDriver`, `ClaudeCliDriver`, and
`PermissionBroker`; tests cover the generated settings and Codex launch and
resume configuration.

The fixture used a local Git repository and a fake GitHub endpoint. Its
request log contained only GET requests; the bare remote and all four task
worktrees stayed unchanged. No real GitHub repository or account was written.
The backend build hash and sanitized task, event, input, response, profile,
and worktree observations are in [`result.json`](result.json). The response
lengths and hashes there cover the recorded assistant-message stream,
including partial messages, rather than only the final display message. The
raw marker, token, prompts, transcripts, and profile contents are not saved
in the repository.

The local client initially omitted `workspaceId` while polling the first
Codex task after launch. It resumed collecting that same completed task; this
was a test-client error and did not create an extra provider turn.
