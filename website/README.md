# ByteQuay website

The product landing page, user documentation, design overview, and Lab pages are
static HTML and CSS in `dist/`, with a small script for switching app
screenshots. They need no dependency installation or build step. The logo comes
from the repository's `assets/logo.svg`; typography uses Google Fonts with
system fallbacks. No analytics, account system, or application data is embedded.

From the repository root, preview with:

```sh
python3 -m http.server 4173 --bind 127.0.0.1 --directory website/dist
```

Open `http://127.0.0.1:4173/`. Check the landing page, Docs index, seven topic
guides, design page, and both Lab pages; also check screenshot switching and
full-size image links, navigation, topic links, disclosures, and narrow layouts
after edits. The user guide lives in [`documentation.md`](documentation.md) and
is presented in the Docs index (`dist/documentation.html`) and seven focused
`dist/guide-*.html` pages. Keep their instructions consistent with the Markdown
guide and current app controls. Prefer a short introduction and three or four
steps per task; leave implementation contracts in source. The Docs index keeps
old topic links working through `doc-links.js`; use direct guide links elsewhere
on the website. `dist/lab.html` introduces the Lab; `dist/lab-guide.html`
explains setup, measurements, experiments, and follow-up. Keep them aligned with
the Python toolkit and its runbook in `scripts/review-calibration/`. The four
`dist/assets/lab-*.svg` images are scalable dashboard illustrations, with
synthetic example numbers and generic project labels. They explain the current
resource, experiment, context, and follow-up views; they are not actual app
screenshots or measured benchmark results. Keep the sample-data label in each
illustration, and preserve full-size image links. Do not embed private Lab data
or make unmeasured improvement claims. The two public design documents are
[`docs/design.md`](../docs/design.md) and
[`docs/architecture.md`](../docs/architecture.md). Keep their durable product
intent and architecture separate from user-facing feature descriptions. Do not
publish internal specifications, implementation notes, or unverified release
claims.

The seven `dist/assets/app-*.jpg` images show development tasks, PR review,
cherry-pick, the Assistant, Epics, private review discussions, and link previews using
the app's actual React components and dark palette. Each is captured at 2880 ×
1800, rendering a 1440 × 900 workspace at 2× scale. The page offers
full-resolution views with feature descriptions. Goals remains an experiment under
implementation: keep only its status notice, not a showcase or usage guide.

Capture through an isolated browser fixture with sample bridge data, blocked
external images and connections, and no live provider or backend. Development
uses `AgentTaskScreen` with WorkBench Changes; review uses `AgentReviewTab`;
cherry-pick uses its confirmation view; the Assistant uses `AssistantPanel`
with anonymous conversation, receipt, and waiting-card fixtures; Epics use
`WorkspaceIssuesScreen` in its zoom overlay with `openOnWork`, over Workspace
home. Keep the zoom controls and workspace backdrop visible in Epic captures.
The focused review-discussion image uses `ReviewThreadCard`,
`PrivateThreadControls`, and `PrivateThreadForward`; the link-preview image uses
`ReferenceCard` over `LocalCard` and a review thread. Both use the fictional
`example/harbor` repository with generic contributor labels and initial avatars.
These are illustrative sample outcomes, not live provider results. Never capture
credentials or private active work. Use generic contributor labels, not real
personal names or usernames. Keep screenshot provenance here; omit capture
metadata and technical captions from the landing page.

`.openai/hosting.json` identifies the existing Sites project and declares the
static directory. Reuse that project for updates. Source synchronization and
deployment should use an isolated Site checkout, not initialize a nested Git
repository here or push the desktop app's source to the hosting repository. The
Site starts private; changing the audience is a separate explicit decision.

Any static host can serve `dist/` as-is. Internal links are relative so the site
can also be served beneath a project path.
