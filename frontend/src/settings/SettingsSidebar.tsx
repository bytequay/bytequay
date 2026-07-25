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
import { useEffect, useState } from 'react';
import type { SettingsSection } from './types';

type LinkDef = {
  id: SettingsSection;
  label: string;
  /** 24x24 outline path. Line icons replace the emoji rail glyphs. */
  icon: string;
  /** Renders indented under the entry above it — Local AI sits under AI. */
  child?: boolean;
  badge?: string;
};
type GroupDef = { label: string; links: LinkDef[] };

const GROUPS: GroupDef[] = [
  {
    label: 'Personal',
    links: [
      { id: 'account', label: 'Account', icon: 'M4.8 20.2a7.2 7.2 0 0 1 14.4 0M12 11.6a3.8 3.8 0 1 0 0-7.6 3.8 3.8 0 0 0 0 7.6' },
      { id: 'appearance', label: 'Appearance', icon: 'M12 3.2v1.9M12 18.9v1.9M4.6 12h1.9M17.5 12h1.9M6.7 6.7l1.3 1.3M15.9 15.9l1.4 1.4M6.7 17.3l1.3-1.3M15.9 8.1l1.4-1.4M12 8.2a3.8 3.8 0 1 0 0 7.6 3.8 3.8 0 0 0 0-7.6' },
      { id: 'credentials', label: 'Credentials', icon: 'M18.4 9.4a3.7 3.7 0 1 0 0-7.4 3.7 3.7 0 0 0 0 7.4M15.8 8.4 5 19.2v2.3h2.6v-2.3h2.5v-2.4h2.4' },
    ],
  },
  {
    label: 'AI',
    links: [
      { id: 'ai-review', label: 'AI', icon: 'M12 4c.7 3.6 2.9 5.8 6.5 6.5-3.6.7-5.8 2.9-6.5 6.5-.7-3.6-2.9-5.8-6.5-6.5C9.1 9.8 11.3 7.6 12 4Z' },
      {
        id: 'local-ai',
        label: 'Local AI (ds4)',
        child: true,
        badge: 'EXP',
        icon: 'M7.5 5h9a2.5 2.5 0 0 1 2.5 2.5v9a2.5 2.5 0 0 1-2.5 2.5h-9A2.5 2.5 0 0 1 5 16.5v-9A2.5 2.5 0 0 1 7.5 5ZM9.8 9.8h4.4v4.4H9.8zM10 2.4V5M14 2.4V5M10 19v2.6M14 19v2.6M2.4 10H5M2.4 14H5M19 10h2.6M19 14h2.6',
      },
      { id: 'skills', label: 'Skills', icon: 'M12 3.4 20.6 12 12 20.6 3.4 12z' },
      { id: 'agent-roles', label: 'Agent roles', icon: 'M3.5 7.5h9M16.5 7.5h4M3.5 16.5h4M11.5 16.5h9M14.5 4.8v5.4M8.5 13.8v5.4' },
    ],
  },
  {
    label: 'System',
    links: [
      { id: 'watched-repos', label: 'Watched repos', icon: 'M12 3.2l8 4.3v9L12 20.8l-8-4.3v-9zM4.3 7.6 12 11.9l7.7-4.3M12 11.9v8.9' },
      { id: 'integrations', label: 'Integrations', icon: 'M9.6 14.4 14.4 9.6M8.6 12.2 6 14.8a3.6 3.6 0 0 0 5.1 5.1l2.6-2.6M15.4 11.8 18 9.2a3.6 3.6 0 0 0-5.1-5.1l-2.6 2.6' },
      { id: 'workspace-memory', label: 'Workspace memory', icon: 'M12 3.6c3.9 0 7 1.1 7 2.5S15.9 8.6 12 8.6 5 7.5 5 6.1s3.1-2.5 7-2.5ZM5 6.1v11.8c0 1.4 3.1 2.5 7 2.5s7-1.1 7-2.5V6.1M5 12c0 1.4 3.1 2.5 7 2.5s7-1.1 7-2.5' },
      { id: 'help', label: 'Help & feedback', icon: 'M12 3.6a8.4 8.4 0 1 0 0 16.8 8.4 8.4 0 0 0 0-16.8ZM12 8.6a3.4 3.4 0 1 0 0 6.8 3.4 3.4 0 0 0 0-6.8M6.1 6.1l3.5 3.5M14.4 14.4l3.5 3.5M17.9 6.1l-3.5 3.5M9.6 14.4l-3.5 3.5' },
    ],
  },
];

type Props = {
  active: SettingsSection;
  onSelect: (section: SettingsSection) => void;
};

function SettingsSidebar({ active, onSelect }: Props) {
  const [query, setQuery] = useState('');
  const [version, setVersion] = useState<string | null>(null);

  useEffect(() => {
    window.bridge.getAppVersion()
      .then(v => setVersion(v.version))
      .catch(() => { /* footer just omits the version */ });
  }, []);

  const q = query.trim().toLowerCase();
  const groups = GROUPS
    .map(g => ({ ...g, links: g.links.filter(l => q === '' || l.label.toLowerCase().includes(q)) }))
    .filter(g => g.links.length > 0);

  return (
    <nav className="sv2-rail" aria-label="Settings sections">
      <div className="sv2-rail__head">
        <div className="sv2-rail__title">
          Settings
          <span className="sv2-rail__chord">⌘,</span>
        </div>
        <div className="sv2-rail__search">
          <SearchIcon />
          <input
            value={query}
            onChange={e => setQuery(e.target.value)}
            placeholder="Filter settings"
            aria-label="Filter settings"
          />
          {q !== '' && (
            <button type="button" className="sv2-rail__clear" aria-label="Clear filter" onClick={() => setQuery('')}>
              <ClearIcon />
            </button>
          )}
        </div>
      </div>

      <div className="sv2-rail__body">
        {groups.map(group => (
          <div className="sv2-rail__group" key={group.label}>
            <div className="sv2-rail__group-label">{group.label}</div>
            {group.links.map(link => (
              <button
                key={link.id}
                type="button"
                aria-current={active === link.id ? 'page' : undefined}
                className={
                  'sv2-rail__link'
                  + (active === link.id ? ' sv2-rail__link--active' : '')
                  + (link.child === true ? ' sv2-rail__link--child' : '')
                }
                onClick={() => onSelect(link.id)}
              >
                <span className="sv2-rail__icon" aria-hidden="true">
                  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
                    <path d={link.icon} />
                  </svg>
                </span>
                <span className="sv2-rail__label">{link.label}</span>
                {link.badge !== undefined && (
                  <span className="sv2-rail__badge" title="Experimental">{link.badge}</span>
                )}
              </button>
            ))}
          </div>
        ))}
        {groups.length === 0 && (
          <div className="sv2-rail__empty">No settings match “{query.trim()}”.</div>
        )}
      </div>

      <div className="sv2-rail__foot">
        <span>ByteQuay {version ?? '—'} · local backend up</span>
        <button type="button" onClick={() => { void window.bridge.openInAppBrowser('https://github.com/bytequay/bytequay/releases'); }}>
          Check for updates
        </button>
      </div>
    </nav>
  );
}

function SearchIcon() {
  return (
    <span style={{ display: 'inline-flex', flexShrink: 0 }} aria-hidden="true">
      <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
        <circle cx="11" cy="11" r="6.5" />
        <path d="m16 16 4.5 4.5" />
      </svg>
    </span>
  );
}

function ClearIcon() {
  return (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" aria-hidden="true">
      <path d="M6 6l12 12" />
      <path d="M18 6 6 18" />
    </svg>
  );
}

export default SettingsSidebar;
