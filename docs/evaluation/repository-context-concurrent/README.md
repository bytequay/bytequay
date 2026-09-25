# Concurrent review and development resource sample

A disposable ByteQuay backend started one PR review and one development task against the same frozen, three-file repository. Their Claude provider turns overlapped for **16.082 seconds**. Both turns completed, and the review was accepted. Repository context was enabled, but the project notebook had no knowledge or memory entry before or after the pair. This measures **concurrent missing-note behavior**, not the overhead or quality effect of delivering a saved note.

| Observation | Paired sample |
| --- | ---: |
| API start separation | 0.105 ms |
| Review run wall time / provider turns | 42.973 s / 2 |
| Development turn elapsed / provider turns | 16.532 s / 1 |
| Review input / cached input / output tokens | 327,947 / 307,472 / 2,922 |
| Development input / cached input / output tokens | 137,614 / 127,957 / 1,031 |
| Review packet delivery sizes | 2,070 and 1,440 UTF-8 bytes |
| Saved notebook / review-run JSON-column size | 3,482 / 11,490 UTF-8 bytes |
| Review source-read receipts / selected notes | 0 / 0 |
| Development context receipts | One 230-byte OFFER and one 327-byte SEARCH; no READ |
| Maximum sampled backend-process-tree RSS / reported CPU sum | 2,688,352 KiB / 124.9% |

The resource sampler took 166 snapshots, about 0.5 seconds apart, across an 88-second window containing both tasks. Each snapshot selected the disposable backend JVM and all live descendants, including provider and short-lived child processes. The largest snapshot contained eight processes. RSS is the **sum** of their reported resident sizes and can double-count shared memory. The sampler retained no per-PID values, so JVM and provider peaks cannot be separated; the reported maximum is neither a true peak nor context-attributable memory. Two review and one development rate-limit warnings also prevent interpreting the observed wall times as stable latency.

The live pair did not instrument SQLite connection acquisition, transaction duration, or the busy handler. A separate, focused one-connection SQLite test in [the acceptance record](../../design/repository-context-acceptance.md#local-storage-and-contention-checks) measured pool/transaction admission after an intentional 150 ms connection hold at 158.879 and 155.686 ms in two fresh runs. Those values include Spring setup and pool acquisition; **direct SQLite busy-handler time remains unavailable**. They are separate test observations, not measurements from this live pair. One simultaneous pair cannot establish sustained throughput, attributable On/Off overhead, or a quality gain.

[`result.json`](result.json) retains exact receipt and sample counts without credentials, provider transcripts, or private paths. The fixture used the frozen corpus from [the history comparison](../repository-context-history-compare/README.md), seeded its cached PR rows in a disposable SQLite database, and served GitHub identity/repository metadata through a local GET-only proxy. Its bare Git remote was served over local read-only HTTPS. Local request logs contain only GET requests (four fake GitHub API requests and 21 Git-object requests across setup and execution); they do not distinguish the paired interval from setup. No real GitHub write was configured or observed.

To repeat the sample, create the history corpus with its `fixture.py`, serve a bare repository with `refs/pull/8/head` at the frozen PR head using `fixture.py serve-git` from the [closed-issue fixture](../repository-context-resolved-issue/README.md), and seed its PR cache into a fresh Hibernate-created SQLite database with the history case's `fixture.py seed`. The workspace's saved clone URL, checked-out `origin`, and fake repository API `clone_url` must all equal that local HTTPS URL. Start a backend with an isolated database, workspace root, agent runtime, session token, local fake GitHub proxy, and background scheduling disabled. Set Claude as the review and development provider. Start `POST /api/agent-review` and `POST /agent/tasks` through a thread barrier, then retain their task/run IDs, events, review context, notebook projection, and usage receipts. Sample `ps` RSS/CPU for the backend PID and descendants until both provider turns finish. Never seed or use the personal ByteQuay database for this measurement.
