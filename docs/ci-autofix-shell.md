# CI Autofix shell

> **Current behavior:** the shell still submits the caller's checkout. The
> approved managed-worktree migration in
> [CI Autofix Service Design](design/ci-autofix-service.md) changes it to select
> a saved ByteQuay workspace and lets the backend provision the task worktree.

The CI Autofix shell starts or reattaches to a backend run for the current Git
worktree, prints CI failure summaries and agent events, and waits until the run
finishes or needs input.

Start ByteQuay in development mode:

```bash
./dev.sh
```

`dev.sh` generates a private desktop-session token and writes it to
`~/Library/Application Support/ByteQuay/desktop-session-token`. In another
terminal, the CI Autofix shell reads that file automatically:

```bash
./scripts/ci-autofix pr bytequay/bytequay 2234
```

Set `BYTEQUAY_DESKTOP_SESSION_TOKEN` explicitly only when the backend was
started another way; the value must match the running backend.

Run the command from the pull request's clean worktree. The repository is
required because pull-request numbers are scoped to a repository. The shell
rejects a repository that does not match the worktree's GitHub `origin`. It
defaults to the `claude` lane and three fix attempts because that lane enforces
the CI Autofix filesystem, network, GitHub-write, and CI-memory policy. It shows
the repository, PR, worktree, branch, and write permissions before asking for
confirmation. The shell checks the selected lane's readiness and `CI_AUTOFIX`
capability before asking for consent; the `codex` lane is currently manual-task
only. Use `--max-attempts 1` to override the attempt limit.

If the backend already has a non-finished run for that repository and PR, the
shell reattaches without creating or reauthorizing another run. Pressing
`Ctrl-C` only detaches; the backend continues monitoring. When a run pauses,
enter a message for the same agent task, `/cancel`, or `/quit`.

The shell consumes only the existing CI Autofix and agent APIs. It prints
normalized CI failure summaries and agent command/tool/output events. Raw
GitHub Actions log blobs remain protected task files and are not exposed by a
new HTTP endpoint.

After the managed-worktree migration, the command requires a saved workspace
selection (for example `--workspace <workspace-id>`). The repository argument
remains a human-readable PR scope and must match that workspace, but the shell
no longer inspects or submits its current directory, branch, repository path,
or worktree. It displays the backend-generated local branch from safe task
facts; the internal path remains hidden. Reattach resolves the same workspace
from the durable run rather than from the shell's current checkout.
