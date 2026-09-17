# Code & Tests implementation checklist

Temporary tracker for [Code & Tests](code-tests.md). Delete it when every phase
has shipped.

- [x] Phase 0: mechanical extractions (no behaviour change). `fileTree.ts`,
      `ReviewLineComposer`, `PendingReviewCard`, `utils.MavenModules`; mvn
      verify and frontend lint/tsc/test green.
- [x] Phase 1: read. `/files`, `/file`; tab, tree, CodeMirror view. mvn verify,
      frontend lint/tsc/test and `npm run package` green.
- [x] Phase 2: navigate. `/symbols`, `/outline`, bundle patch (`patches=2`);
      palette, outline, ⌘-click chooser, Tests tree. Same gates green. Not yet
      exercised in the live app (needs a `./dev.sh` restart for main/backend).
- [ ] Phase 3: run and log. Backend runs, claim, recovery, store, routes and
      review guards in, `mvn verify` green; frontend run panel aligned (offsets
      are characters). Seven review findings fixed (stuck claim after a failed
      final save or release, buffers dropped at a review's next run, bounded
      Surefire messages, identifier-only class names, claim kept after a failed
      start, End vs an open Git operation, login-shell cache and pipe hang).
      Agent-command mirroring (5.4) built: `background` flag from Claude,
      shell-word detection, window read of every report directory, lane-based
      Codex skip, mirrored status table; `mvn verify` and frontend
      lint/tsc/tests green on 2026-09-16. Live gate passed on 2026-09-16 against
      the dev app (PR 31102): a class run PASSED with 12 rows, a stopped
      method run STOPPED and released its claim, a second start was refused
      with 409, and the review agent's `./mvnw` command was mirrored as an
      AGENT METHOD row. Real traffic: the Codex review of PR 29382 mirrored 7
      commands (PASSED with 24 rows, FAILED_ASSERTION, failed builds without
      rows). Claude's first plain `./mvnw` in a session often fails with
      "Unable to locate a Java Runtime" until the agent exports `JAVA_HOME`
      itself; those attempts mirror as rowless results, later builds succeed. The one-time rebuild of `agent_task`
      and `agent_process_launch` ran on the user's database on 2026-09-16:
      both CHECK lists now accept `LOCAL_RUN`, schema and rows otherwise
      unchanged, backup in the app data directory.
- [x] Phase 4: comment and edit. Inline composer and cards verified in the
      browser pane (pending card at R24, diff-line blocker, Send to agent
      off-diff); unsaved bar, conflict, discard. Backend `PUT /file`,
      `POST /discard`, `savedExperiment`, `mutateWorkingCopy`, End's
      uncommitted refusal; `mvn verify` green.
- [ ] Phase 5: terminal. Main `ReviewTerminals`, bridge, xterm view with
      Run | Terminal switch, packaging (layout and an Electron-runtime spawn
      from the packaged archive confirmed), backend `POST /code/terminal`.
      Live: `POST /code/terminal` answers the worktree (401 without the
      session token, 409 for an ended review), and a login-shell PTY spawned
      there under Electron 41 runs `echo` with Java on PATH and no
      `BYTEQUAY_*` variables. Missing: typing into the Terminal panel of the
      running app (the window cannot be driven from the agent).
- [x] Canonical-design amendments from section 9 are applied to their documents
      (items 1-6)
