import type { SettingsSection } from './types';

type LinkDef = { id: SettingsSection; label: string; icon: string };
type GroupDef = { label: string; links: LinkDef[] };

// Section grouping comes straight from the mockup: Personal / Review / System.
// Order is meaningful — most-used first. Add a new section by extending
// SettingsSection in `types.ts` and putting it in the right group here.
const GROUPS: GroupDef[] = [
  {
    label: 'Personal',
    links: [
      { id: 'account', label: 'Account', icon: '👤' },
      { id: 'appearance', label: 'Appearance', icon: '🎨' },
      { id: 'github-token', label: 'GitHub token', icon: '🔑' },
    ],
  },
  {
    label: 'Review',
    links: [
      { id: 'teams', label: 'Teams', icon: '👥' },
      { id: 'ai-review', label: 'AI review', icon: '✨' },
      { id: 'watched-repos', label: 'Watched repos', icon: '📦' },
    ],
  },
  {
    label: 'System',
    links: [
      { id: 'integrations', label: 'Integrations', icon: '🔗' },
      { id: 'help', label: 'Help & feedback', icon: '🛟' },
    ],
  },
];

type Props = {
  active: SettingsSection;
  onSelect: (section: SettingsSection) => void;
};

function SettingsSidebar({ active, onSelect }: Props) {
  return (
    <aside className="settings-shell-sidebar">
      {GROUPS.map(group => (
        <div key={group.label}>
          <div className="settings-shell-sidebar__group">{group.label}</div>
          {group.links.map(link => (
            <button
              key={link.id}
              type="button"
              className={
                'settings-shell-sidebar__link' +
                (active === link.id ? ' settings-shell-sidebar__link--active' : '')
              }
              onClick={() => onSelect(link.id)}
            >
              <span className="settings-shell-sidebar__icon" aria-hidden="true">{link.icon}</span>
              {link.label}
            </button>
          ))}
        </div>
      ))}
    </aside>
  );
}

export default SettingsSidebar;
