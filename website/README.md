# ByteQuay website

The product landing page, user documentation, and design overview are static HTML and CSS in
`dist/`, with a small script for switching app screenshots. They need no dependency installation or build step. The logo comes from
the repository's `assets/logo.svg`; typography uses Google Fonts with system
fallbacks. No analytics, account system, or application data is embedded.

From the repository root, preview with:

```sh
python3 -m http.server 4173 --bind 127.0.0.1 --directory website/dist
```

Open `http://127.0.0.1:4173/`. Check all three pages, screenshot switching and
full-size image links, navigation, topic links, disclosures, and narrow layouts after edits. The user guide lives in
[`documentation.md`](documentation.md) and is presented in
`dist/documentation.html`. Keep its introduction, product goals, and feature
instructions consistent with the Markdown guide and current app controls.
The two public design documents are [`docs/design.md`](../docs/design.md) and
[`docs/architecture.md`](../docs/architecture.md). Keep their durable product
intent and architecture separate from user-facing feature descriptions. Do not
publish internal specifications, implementation notes, or unverified release claims.

The five `dist/assets/app-*.jpg` images show development tasks, PR review,
cherry-pick, Goals, and Epics using the app's actual React components and dark
palette. Each is captured at 2880 × 1800, rendering a 1440 × 900 workspace at
2× scale. The page offers full-resolution views with feature descriptions.

Capture through an isolated browser fixture with sample bridge data, blocked
external images and connections, and no live provider or backend. Development
uses `AgentTaskScreen` with WorkBench Changes; review uses `AgentReviewTab`;
cherry-pick uses its confirmation view; planning uses `GoalPanel` and `EpicView`.
These are illustrative sample outcomes, not live provider results. Never capture
credentials or private active work. Keep the sample-data label visible on the site.

`.openai/hosting.json` identifies the existing Sites project and declares the
static directory. Reuse that project for updates. Source synchronization and
deployment should use an isolated Site checkout, not initialize a nested Git
repository here or push the desktop app's source to the hosting repository.
The Site starts private; changing the audience is a separate explicit decision.

Any static host can serve `dist/` as-is. Internal links are relative so the site
can also be served beneath a project path.
