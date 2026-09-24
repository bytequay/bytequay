# Concurrent review and development with saved context

One isolated Claude review and one development task started together against
the same frozen three-file repository. Both were admitted, their first provider
turns overlapped for **23.898 seconds**, the review reached `ACCEPTED`, and the
development turn completed. The review selected and received
`knowledge:window` version 2; the development task received an offer, searched
for the window decision, and opened that exact version. Its `SEARCH` and `READ`
receipts are confirmed. This demonstrates concurrent delivery and lookup of a
saved note, not a quality or throughput gain.

| Observation | This pair |
| --- | ---: |
| Review run / provider turns | 77.395 s / 2 |
| Development turn / provider turns | 24.402 s / 1 |
| Review input / cached input / output tokens | 512,926 / 466,873 / 7,349 |
| Development input / cached input / output tokens | 211,295 / 195,335 / 1,706 |
| Confirmed review packet sizes | 5,930 and 3,133 UTF-8 bytes |
| Confirmed development search / read sizes | 861 / 10,251 UTF-8 bytes |
| Review selected source reads | 3 (one history, two repository files) |
| Notebook JSON before / after | 13,245 / 23,356 bytes |
| Review-run context JSON | 20,084 bytes |
| Maximum sampled JVM RSS / matching Claude RSS sum during overlap | 2,013,424 / 750,304 KiB |

The review also **built previously missing project knowledge** while reusing
the warm component note. Its source tree had changed at the PR head, so the
selected note was marked `VERSION_MISMATCH`. The project build explains much
of the notebook growth and makes this a mixed warm/cold sample. It cannot
isolate the cost of only reading a saved note. The usage view recorded one
review and one development task with confirmed note delivery or opening.

The sampler took 192 snapshots, aiming for 0.5-second spacing. Forty-four
fell within the overlapping provider turns. It separated the backend JVM from
its descendants and grouped executable names matching Claude. The maximum
observed during overlap was 2,013,424 KiB for the JVM, 750,304 KiB summed
across matching Claude processes, and 108.4% reported CPU across those
processes. These are sampled values, not true peaks; RSS sums may count shared
pages more than once, and name matching may include readiness or update
processes. Review and development events mentioned rate limits twice and once,
respectively. The single pair cannot establish stable latency or throughput.

The backend used a one-connection SQLite pool. Its live pool acquisition,
transaction write, and busy-handler waits were not exposed, so
[`result.json`](result.json) records them as unknown. At the final read, the
database held 11 context-usage facts, and its database and WAL files were
544,768 and 2,896,392 bytes. These file sizes include all fixture and agent
activity and do not measure per-note storage or write wait.

The local fake GitHub API accepted only GET requests; the local HTTPS Git
server rejected writes. Their logs contain three GitHub GETs and six Git GETs
across setup and execution, with zero writes. No real GitHub credential,
repository, or user database was used. The raw provider events, credentials,
session token, and process snapshots remain outside this artifact.

To reproduce the fixture, use the frozen repository and cached PRs from
[the component comparison](../repository-context-spontaneous-component/README.md).
Clone its completed Learn database and local Git corpus into a new disposable
directory, keep `main` at `35089cdc274efee74d89682271c3b15f6ba12bdb`
and `refs/pull/8/head` at `aedc3d9eb56df040e9d7d9385aa91538c73c4284`,
and remove only a copied process-launch marker whose turn and task are both
complete and whose worktree was removed. Serve the bare Git repository over
read-only local HTTPS, point the workspace clone and fake GitHub repository
metadata to that URL, and start a fresh backend with the copied notebook,
isolated SQLite/runtime/token, scheduling disabled, and Claude Sonnet/Low.
Start `POST /api/agent-review` for PR #8 and `POST /agent/tasks` for a
development question about `sensors/window.py` through a thread barrier.
Read the final review, development context cards, usage view, turn metrics,
and per-process `ps` samples only after both provider turns finish. This
sample used one pair and did not create an Off arm.
