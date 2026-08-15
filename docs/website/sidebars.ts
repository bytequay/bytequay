import type {SidebarsConfig} from '@docusaurus/plugin-content-docs';

// Sidebar for the main docs instance:
//   usageSidebar -> how to use ByteQuay
const sidebars: SidebarsConfig = {
  usageSidebar: [
    'intro',
    {
      type: 'category',
      label: 'Usage',
      collapsed: false,
      items: [
        'usage/getting-started',
        'usage/pr-dashboard',
      ],
    },
  ],
};

export default sidebars;
