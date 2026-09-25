# Spontaneous Codex development note opening

Two fresh Codex development tasks received the same ordinary request: assess
whether changing `window.py` from an inclusive to an exclusive end check is
safe, implement it only if safe, and run the tests. The prompt did not mention
repository context, notes, memory, the lookup tool, or merged PR #7. Both tasks
used `gpt-6-sol` with high effort at the same frozen commit
`10cb2b1f39220b30f790672f07e0238f73c8c505`. PR automation was disabled.

| Observation | Context Off | Warm context On |
| --- | ---: | ---: |
| Completed Codex turn and development result | Yes | Yes |
| Project note offered | No | Yes, confirmed, 1,789 displayed bytes |
| Agent-initiated note search | 0 | 0 |
| Agent-initiated exact note read | 0 | 1, confirmed, 9,951 bytes |
| Proposed change rejected; worktree clean | Yes | Yes |
| Existing test and independent boundary probe | Pass; `True True` | Pass; `True True` |
| Recorded turn elapsed | 45.156 s | 43.303 s |
| Input / cached input / output tokens | 149,700 / 108,672 / 1,488 | 140,466 / 108,928 / 1,436 |

**Codex spontaneously opened the offered note.** ByteQuay made the
repository-wide `knowledge:project` v1 note available in the warm task's
automatic offer. Codex then called `repository_context_lookup` to READ that
exact note without being asked to use context. It did not need a SEARCH: the
offer already supplied the ID and version. The On usage view recorded one
selected, provided, and opened development task, with zero search hits.

**There is no observed quality gain in this pair.** Both agents inspected the
source and Git history, rejected the proposed change, ran the ordinary
one-test suite, and checked that a shared-boundary sample belongs to both
neighboring windows. Off could infer the risk from the checked-out commit,
which explicitly changed `<` to `<=`. On also read the saved history-linked
note. Its open receipt proves access, not that the note caused its decision.
Both task worktrees remained at the frozen baseline with no tracked or
untracked changes. Provider cost was unavailable; the one-run timing and token
figures cannot support a performance conclusion.

The warm note was copied from the prior explicit Learn of the cached merged
PR #7 and baseline files in the [history case](../repository-context-history-compare/README.md).
The two task databases and workspaces were separate copies of that learned
baseline. The only notebook control difference was `useInDevelopment=false`
in Off. The fixture used a local HTTPS Git server and fake GitHub metadata;
no real GitHub write occurred. A stale completed-learning process marker in
the copied fixture was removed before either scored task started.

[`result.json`](result.json) retains the exact prompt, build hash, settings,
task and turn IDs, confirmed context cards, final answers, development results,
independent focused checks, and metrics. It excludes raw event transcripts,
credentials, and session tokens. The semantic oracle in the history case is
still proposed and not human approved. This case tests a **repository-wide
offered note**. Whether Codex independently SEARCHes for a component note
that the initial offer omitted remains a separate question.
