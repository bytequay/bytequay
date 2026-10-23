# CI Autofix shell

The CI Autofix shell attaches to a backend run for a saved ByteQuay workspace,
prints CI failure summaries and agent events, and waits until the run finishes
or needs input. It never starts a run: the backend starts one when a
development task with **Auto-CI fix** pushes its pull request, and when a
cherry-pick task publishes its pull request. The backend owns the run's task
worktree; the shell never submits a local path.

Start ByteQuay in development mode:

```bash
./dev.sh
```

`dev.sh` generates a private desktop-session token and writes it to
`~/Library/Application Support/ByteQuay/desktop-session-token`. In another
terminal, the CI Autofix shell reads that file automatically:

```bash
./scripts/ci-autofix pr bytequay/bytequay 2234 --workspace <workspace-id>
```

The shell defaults to port `9527`. If the backend falls back to another port,
set `BYTEQUAY_BACKEND_PORT` to the port printed by `dev.sh` in this terminal,
or set `BYTEQUAY_BACKEND_URL` to the full printed URL.

Set `BYTEQUAY_DESKTOP_SESSION_TOKEN` explicitly only when the backend was
started another way; the value must match the running backend.

The repository is required because pull-request numbers are scoped to a
repository. The shell loads the named workspace from ByteQuay and rejects a
repository that does not match its monitored repository. It does not inspect
the current directory, branch, repository path, or worktree.

The shell attaches to the backend's non-finished run for that workspace,
repository, and PR, and exits with an error when there is none. Pressing
`Ctrl-C` only detaches; the backend continues monitoring. When a run pauses,
enter a message for the same agent task, `/cancel`, or `/quit`.

The shell consumes only the existing CI Autofix and agent APIs. It prints
normalized CI failure summaries and agent command/tool/output events. Raw
GitHub Actions log blobs remain protected task files and are not exposed by a
new HTTP endpoint.

On attach, the shell may display the backend-generated local task branch from
safe task facts; the internal path remains hidden.
