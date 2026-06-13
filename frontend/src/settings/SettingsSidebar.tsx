/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
      { id: 'credentials', label: 'Credentials', icon: '🔑' },
    ],
  },
  {
    // Single-entry AI group — the "AI" surface owns Usage as an inner
    // tab now that Credentials moved to its own section and Skills
    // moved to its own section. The rail stays shallow.
    label: 'AI',
    links: [
      { id: 'ai-review', label: 'AI', icon: '✦' },
      { id: 'local-ai', label: 'Local AI (ds4)', icon: '◩' },
      { id: 'skills', label: 'Skills', icon: '◆' },
      { id: 'agent-roles', label: 'Agent roles', icon: '⛓' },
      { id: 'concepts', label: 'Concepts', icon: '◇' },
      { id: 'saved-views', label: 'Saved views', icon: '⌕' },
    ],
  },
  {
    label: 'Team',
    links: [
      { id: 'teams', label: 'Teams', icon: '👥' },
      { id: 'watched-repos', label: 'Watched repos', icon: '📦' },
      { id: 'workspace-memory', label: 'Workspace memory', icon: '🧠' },
    ],
  },
  {
    label: 'System',
    links: [
      { id: 'integrations', label: 'Integrations', icon: '🔗' },
      { id: 'email', label: 'Email', icon: '✉️' },
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
