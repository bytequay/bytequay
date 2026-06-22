# ByteQuay docs website

The documentation site for ByteQuay, built with [Docusaurus](https://docusaurus.io/).

## Structure

```
docs/website/
├── docs/                  # Main docs (Usage)
│   ├── intro.md           # Overview (site root /docs)
│   └── usage/             # Usage / guides section
├── release-notes/         # Release notes (served at /releases), one page per version
├── src/
│   ├── css/custom.css     # Theme variables
│   └── pages/index.tsx    # Landing page
├── static/img/            # Logo, favicon, images
├── docusaurus.config.ts   # Site config + navbar + plugins
├── sidebars.ts            # Usage sidebar
└── sidebarsReleases.ts    # Release notes sidebar
```

Two sections:

- **Usage** — task-oriented guides (navbar: *Usage*).
- **Release notes** — versioned changelog at `/releases` (navbar: *Release notes*).

## Develop

```bash
cd docs/website
npm install        # first time only
npm start          # dev server with hot reload at http://localhost:3000
```

## Build

```bash
npm run build      # static site into ./build
npm run serve      # preview the production build locally
```

## Adding content

- **A usage/guide page**: add a markdown file under `docs/usage/`, then list its
  `id` in `sidebars.ts` (`usageSidebar`).
- **A release**: copy `release-notes/0.1.0.md` to the new version, fill it in,
  and add its `id` to the `Releases` category in `sidebarsReleases.ts`.

## Deploy

Any static host works (GitHub Pages, Netlify, Vercel, Cloudflare Pages). Set
`url`, `baseUrl`, `organizationName`, and `projectName` in
`docusaurus.config.ts` for your target before deploying.
