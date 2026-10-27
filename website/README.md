# ByteQuay website

The product landing page, user documentation, design overview, and Lab pages are
static HTML and CSS in `dist/`. They need no dependency installation or build
step. The logo comes from the repository's `assets/logo.svg`; typography uses
Geist and Geist Mono from Google Fonts with system fallbacks. Icons are inline
[Phosphor](https://phosphoricons.com) paths (MIT). No analytics, account
system, or application data is embedded.

The landing page stays short: a headline with one line, one app screenshot,
four feature rows led by the Assistant and Today, and link cards, each with a
sentence or less. Framed bands separate the sections, feature rows alternate
sides, and screenshots sit on flat gray panels. Put details in the guides, not
on the landing page.

Keep the visual system closed. Backgrounds are flat and come from the dark
values at the top of `style.css`; hero headings carry the only gradient, on
their text. The logo's violet is the one accent. Font sizes use the Tailwind
type scale with its paired line heights, and spacing uses 0, 2, 4, 8, 12, 16,
24, 32, 40, 48, 64, 80, or 96 pixels. Motion uses the shared `--ease` curve,
300 ms for color and 700 ms for the one-time section rise in `reveal.js`,
which keeps the fade and drops the movement under reduced motion. Labels use
sentence case.

## Publish with GitHub Pages

The public address is [bytequay.github.io/bytequay](https://bytequay.github.io/bytequay/).
Enable it once in the repository's **Settings → Pages → Build and deployment**
by selecting **GitHub Actions** as the source.

The [Deploy website workflow](../.github/workflows/website.yml) publishes
`website/dist/` when that directory or the workflow changes on `main`. It can
also run manually from **Actions → Deploy website → Run workflow**. Wait for
the deployment to succeed before opening the site. No app build is needed.

## Preview and edit

From the repository root, preview with:

```sh
python3 -m http.server 4173 --bind 127.0.0.1 --directory website/dist
```

Open `http://127.0.0.1:4173/`. Check the landing page, Docs index, seven topic
guides, design page, both Lab pages, and `404.html`; also check navigation,
topic links, section reveals, the still cursor under reduced motion, and
narrow layouts after edits. GitHub Pages serves `404.html` for missing pages;
its relative links resolve for missing top-level pages only. The user guide lives in [`documentation.md`](documentation.md) and
is presented in the Docs index (`dist/documentation.html`) and seven focused
`dist/guide-*.html` pages. Keep their instructions consistent with the Markdown
guide and current app controls. Keep the repository README headline and summary
aligned with the landing page. Prefer a short introduction and three or four
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

The `dist/assets/app-*.jpg` images show development tasks, PR review,
cherry-pick, the Assistant, Today, Epics, private review discussions, and link
previews using the app's actual React components and dark palette. Most are
captured at 2880 × 1800, rendering a 1440 × 900 workspace at 2× scale. The
landing page shows development, the Assistant, Today, review, and private
discussion.

`app-assistant.jpg`, its animated `app-assistant.webp`, and `app-today.jpg`
come from the browser tests' screenshot modes, cropped from the dark captures:
from `frontend/`, run `src/assistant/assistantBrowser.test.ts` with
`BYTEQUAY_ASSISTANT_SCREENSHOTS=<folder>` or `src/home/todayBrowser.test.ts`
with `BYTEQUAY_TODAY_SCREENSHOTS=<folder>` through `npx vitest run`. The
animation cycles the default, cards, and timeline views; the landing page
shows the still image when reduced motion is preferred. Leave out views whose
sample text names a real repository.

`app-dictation.gif` and its reduced-motion still `app-dictation.jpg` come from
`src/assistant/dictationMovieBrowser.test.ts`: from `frontend/`, run it with
`BYTEQUAY_DICTATION_FRAMES=<folder>` through `npx vitest run`. It writes each
dark 2× frame as a PNG, plus `holds.json` with each frame's hold in
milliseconds. Encode the frames in order at 1100 × 700 with one 256-color
palette per frame and no dithering, for example with Pillow's fast octree
quantizer; one shared palette turns the window buttons and running spinner
gray. The still is `frame-08.png`, where the strip shows the words as heard.

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
