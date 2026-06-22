import type {SidebarsConfig} from '@docusaurus/plugin-content-docs';

// Release notes sidebar. List newest version first; add a new entry per
// release (one page per version).
const sidebars: SidebarsConfig = {
  releasesSidebar: [
    'index',
    {
      type: 'category',
      label: 'Releases',
      collapsed: false,
      items: ['0.1.0'],
    },
  ],
};

export default sidebars;
