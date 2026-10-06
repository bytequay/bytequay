# ByteQuay website

The product landing page, user documentation, and design overview are static HTML and CSS in
`dist/`. They need no dependency installation or build step. The logo comes from
the repository's `assets/logo.svg`; typography uses Google Fonts with system
fallbacks. No analytics, account system, or application data is embedded.

From the repository root, preview with:

```sh
python3 -m http.server 4173 --bind 127.0.0.1 --directory website/dist
```

Open `http://127.0.0.1:4173/`. Check all three pages, navigation, topic links,
disclosures, and narrow layouts after edits. The user guide lives in
[`documentation.md`](documentation.md) and is presented in
`dist/documentation.html`. Keep its introduction, product goals, and feature
instructions consistent with the Markdown guide and current app controls.
The canonical high-level design lives in
[`docs/overview/`](../docs/overview/design.md); keep the website summary consistent
with it without publishing implementation notes or unfinished release claims.

`.openai/hosting.json` identifies the existing Sites project and declares the
static directory. Reuse that project for updates. Source synchronization and
deployment should use an isolated Site checkout, not initialize a nested Git
repository here or push the desktop app's source to the hosting repository.
The Site starts private; changing the audience is a separate explicit decision.

Any static host can serve `dist/` as-is. Internal links are relative so the site
can also be served beneath a project path.
