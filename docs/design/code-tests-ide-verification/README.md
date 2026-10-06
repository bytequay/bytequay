# Modular IDE feasibility evidence

These files support the [refactor plan](../code-tests-ide-refactor.md). They are
integration-fixture evidence, not results from the production ByteQuay app.

This page describes the original feasibility fixture. The newer
[native runtime report](runtime-gate.md) records the pinned implementation,
resolved TextMate failure, real language/debug checks, automatic project writes
and the approved real-worktree execution boundary.

- `ui-results.json`: development-server checks using the actual
  `LocalReviewThreadCard`, a synthetic VS Code TestController and DAP adapter, and
  an SCM provider populated from three temporary Git commits. The browser also
  verified reply Markdown preview, light/dark switching and visible history rows.
- `prototype.jpg`: that development fixture with ByteQuay's card, the selected
  local error text and native Git history graph. It is not the finished UI design.
- `production-ui-results.json`: the original production-build smoke test. It records
  the TextMate runtime errors before their fix; do not interpret the earlier empty
  error list as a production pass.
- `probe-source.zip`: small source/configuration/lockfile snapshot for reproduction.
  It excludes dependencies and generated bundles. The fixture imports ByteQuay's
  existing frontend files and installed React dependencies; adjust the repository
  path and the temporary-directory aliases when reproducing elsewhere.

## Environment and results

The fixture pins the modular VS Code packages to 37.3.3 and Vite to 5.4.21, uses
the repository's React 19 installation, and ran under Node 24.5.0/npm 11.5.1 on
macOS. It did not connect to a real remote/Node extension-host server, install
Java/Rust/Go bundles, or send a review reply/agent message to the backend.

`vite build` exited successfully after using ES workers, resolving the `cssValue`
import explicitly, and selecting the shipped browser-compatible terminal
ligature entry. The large main chunk was approximately 11.8 MB minified/3.1 MB
gzip. Build warnings included large chunks and a browser-externalized
`node:fs/promises` import from `@vscode/diff`; real diff loading also needs the
runtime gate. The latter warning was not established as harmless by this probe.

Serving `dist` with `vite preview` exposed a separate tokenization failure:

```text
TypeError: Cannot destructure property 'applyStateStackDiff' of
'(intermediate value)' as it is undefined.
```

The generated main bundle contains this shape:

```js
const { applyStateStackDiff, INITIAL } = await preload(async () => {
  const { applyStateStackDiff, INITIAL } = await import('./main-<hash>.js');
  return { applyStateStackDiff, INITIAL };
}).then(n => n.main);
```

That transform loses the `main` namespace expected by the library. The source
imports the namespace and then destructures its `main` export. Inspecting Vite 5's
`dynamicImportTreeshakenRE`/preload transform identifies the compatibility point;
excluding `@codingame` from CommonJS conversion did not solve it. The later
implementation resolves this specific transform with a regression-tested plugin.
The complete application cutover remains gated as described in the native report.

The raw result timestamps and generated IDs belong to the temporary fixtures.
All test failures were deliberate (`double(3)` expected 7, returned 6); the DAP
adapter supplied synthetic locals and stack frames. A successful synthetic Stop
only verifies the UI sent `disconnect`, not that a real OS process tree was killed.
