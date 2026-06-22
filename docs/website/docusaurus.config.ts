import {themes as prismThemes} from 'prism-react-renderer';
import type {Config} from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';

// ByteQuay documentation site config.
// A docs sidebar (Usage) plus a dedicated Release notes section.

const config: Config = {
  title: 'ByteQuay',
  tagline: 'A calmer home for pull requests, reviews, and AI coding tasks',
  favicon: 'img/favicon.svg',

  // Set the production url of your site here.
  url: 'https://docs.bytequay.dev',
  // The /<baseUrl>/ pathname under which your site is served.
  baseUrl: '/',

  // GitHub pages deployment config. Update these to your repo if you deploy there.
  organizationName: 'bytequay',
  projectName: 'bytequay',

  onBrokenLinks: 'warn',
  onBrokenMarkdownLinks: 'warn',

  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  presets: [
    [
      'classic',
      {
        docs: {
          // The docs (Usage) live under ./docs and use sidebars.ts.
          sidebarPath: './sidebars.ts',
          routeBasePath: 'docs',
          // Point this at your repo to enable "Edit this page" links.
          editUrl:
            'https://github.com/bytequay/bytequay/tree/main/docs/website/',
        },
        blog: false,
        theme: {
          customCss: './src/css/custom.css',
        },
      } satisfies Preset.Options,
    ],
  ],

  // A second docs instance dedicated to release notes, so it gets its own
  // sidebar and its own top-level URL (/releases).
  plugins: [
    [
      '@docusaurus/plugin-content-docs',
      {
        id: 'releases',
        path: 'release-notes',
        routeBasePath: 'releases',
        sidebarPath: './sidebarsReleases.ts',
        editUrl:
          'https://github.com/bytequay/bytequay/tree/main/docs/website/',
      },
    ],
  ],

  themeConfig: {
    image: 'img/logo.svg',
    colorMode: {
      defaultMode: 'light',
      respectPrefersColorScheme: true,
    },
    navbar: {
      title: 'ByteQuay',
      logo: {
        alt: 'ByteQuay logo',
        src: 'img/logo.svg',
      },
      items: [
        {
          type: 'docSidebar',
          sidebarId: 'usageSidebar',
          position: 'left',
          label: 'Usage',
        },
        {
          to: '/releases',
          label: 'Release notes',
          position: 'left',
          activeBaseRegex: '/releases',
        },
        {
          href: 'https://github.com/bytequay/bytequay',
          label: 'GitHub',
          position: 'right',
        },
      ],
    },
    footer: {
      style: 'dark',
      links: [
        {
          title: 'Docs',
          items: [
            {label: 'Usage', to: '/docs/usage/getting-started'},
            {label: 'Release notes', to: '/releases'},
          ],
        },
        {
          title: 'Project',
          items: [
            {label: 'GitHub', href: 'https://github.com/bytequay/bytequay'},
          ],
        },
      ],
      copyright: `Copyright © ${new Date().getFullYear()} ByteQuay.`,
    },
    prism: {
      theme: prismThemes.github,
      darkTheme: prismThemes.dracula,
    },
  } satisfies Preset.ThemeConfig,
};

export default config;
