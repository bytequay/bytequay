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
import type { ReactNode } from 'react';
import { Avatar } from '../primitives';

/** App-level nav destinations in the sidebar's top section. */
export type SidebarNavKey = 'home' | 'my-work' | 'automations';

/** The window-chrome row: the macOS traffic-light dots + the sidebar
 *  collapse toggle on the left, and back/forward on the right. The dots
 *  stand in for the native macOS buttons — hidden behind the real ones
 *  while windowed, shown (red/yellow/green) only in fullscreen where the
 *  OS hides its own (driven by the `.is-fullscreen` class on the rail). */
export function TrafficLights({ onBack, onForward, onToggleCollapse }: {
  onBack?: () => void;
  onForward?: () => void;
  onToggleCollapse?: () => void;
}) {
  return (
    <div className="sb-traffic">
      <div className="dots" aria-hidden>
        <span className="r" /><span className="y" /><span className="g" />
      </div>
      <span
        className="sb-toggle"
        role="button"
        tabIndex={0}
        aria-label="Toggle sidebar"
        onClick={onToggleCollapse}
      >
        <svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden>
          <rect x="2" y="3" width="12" height="10" rx="2.2" stroke="currentColor" strokeWidth="1.3" />
          <line x1="6.4" y1="3.4" x2="6.4" y2="12.6" stroke="currentColor" strokeWidth="1.3" />
        </svg>
      </span>
      <div className="nav-arrows">
        <span role="button" tabIndex={0} aria-label="Back" onClick={onBack}>
          <svg width="8" height="14" viewBox="0 0 8 14" fill="none" aria-hidden>
            <path d="M6.5 1.5 1.5 7l5 5.5" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </span>
        <span role="button" tabIndex={0} aria-label="Forward" onClick={onForward}>
          <svg width="8" height="14" viewBox="0 0 8 14" fill="none" aria-hidden>
            <path d="M1.5 1.5 6.5 7l-5 5.5" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </span>
      </div>
    </div>
  );
}

const NAV_ITEMS: { key: SidebarNavKey; icon: string; label: string; kbd?: string }[] = [
  { key: 'home', icon: '⌂', label: 'Home' },
  { key: 'my-work', icon: '▤', label: 'My work' },
  { key: 'automations', icon: '⚙', label: 'Automations' },
];

/** The fixed app-level nav: Home / My work / Automations / Search. */
export function SidebarNav({ activeKey, onNavigate }: {
  activeKey?: SidebarNavKey;
  onNavigate?: (key: SidebarNavKey) => void;
}) {
  return (
    <div className="sb-nav">
      {NAV_ITEMS.map(item => (
        <button
          key={item.key}
          type="button"
          className={item.key === activeKey ? 'sb-nav-item active' : 'sb-nav-item'}
          onClick={() => onNavigate?.(item.key)}
        >
          <span className="ic" aria-hidden>{item.icon}</span>
          <span>{item.label}</span>
          {item.kbd !== undefined && <span className="kbd">{item.kbd}</span>}
        </button>
      ))}
    </div>
  );
}

/** The "Threads" section header + scrollable list wrapper. Children are
 *  the {@link ThreadItem} rows. */
export function ThreadsSection({ onNewThread, children }: {
  onNewThread?: () => void;
  children: ReactNode;
}) {
  return (
    <div className="sb-section">
      <div className="sb-section-h">
        <span className="nm">Threads</span>
        <span className="actions">
          <span role="button" tabIndex={0} aria-label="Filter threads">⛚</span>
          <span role="button" tabIndex={0} aria-label="New thread" onClick={onNewThread}>+</span>
        </span>
      </div>
      <div className="session-list">{children}</div>
    </div>
  );
}

/** Collapsible "Closed (N)" folder pinned at the bottom of the threads
 *  list. Children are the closed-thread rows, shown when expanded. */
export function ClosedFolder({ count, expanded = false, onToggle, children }: {
  count: number;
  expanded?: boolean;
  onToggle?: () => void;
  children?: ReactNode;
}) {
  return (
    <div className="closed-folder">
      <button type="button" className="folder-row" onClick={onToggle} aria-expanded={expanded}>
        <span className="chev" aria-hidden>{expanded ? '▾' : '▸'}</span>
        <span className="ic" aria-hidden>📁</span>
        <span>Closed</span>
        <span className="count">{count}</span>
      </button>
      {expanded && children !== undefined && <div className="session-list">{children}</div>}
    </div>
  );
}

/** User footer: avatar + name + chat/settings actions. */
export function SidebarFooter({ initials, name, onChat, onSettings }: {
  initials: string;
  name: string;
  onChat?: () => void;
  onSettings?: () => void;
}) {
  return (
    <div className="sb-footer">
      <Avatar initials={initials} size={26} hue="amber" label={name} />
      <span className="name">{name}</span>
      <span className="actions">
        <span role="button" tabIndex={0} aria-label="Messages" onClick={onChat}>💬</span>
        <span role="button" tabIndex={0} aria-label="Settings" onClick={onSettings}>⚙</span>
      </span>
    </div>
  );
}

/** The narrow toggle button shown only when the sidebar is collapsed.
 *  Always rendered; CSS hides it in the expanded state. */
export function SidebarToggleBar({ onToggleCollapse }: { onToggleCollapse?: () => void }) {
  return (
    <div className="sb-toggle-row">
      <button type="button" className="toggle-btn" aria-label="Expand sidebar" onClick={onToggleCollapse}>◧</button>
    </div>
  );
}

/**
 * The left navigation column. Composes the traffic lights, app nav, the
 * threads tree (passed as children), an optional Closed folder, and the
 * user footer — plus the collapsed-state toggle bar. Purely
 * presentational: the host owns nav/selection/collapse state.
 */
export function Sidebar({
  children, closed, footer,
  activeNav, onNavigate, onNewThread, onToggleCollapse, onBack, onForward,
}: {
  /** The {@link ThreadItem} rows for the threads list. */
  children: ReactNode;
  /** A {@link ClosedFolder} element, or omitted when there are none. */
  closed?: ReactNode;
  footer: { initials: string; name: string; onChat?: () => void; onSettings?: () => void };
  activeNav?: SidebarNavKey;
  onNavigate?: (key: SidebarNavKey) => void;
  onNewThread?: () => void;
  onToggleCollapse?: () => void;
  onBack?: () => void;
  onForward?: () => void;
}) {
  return (
    <aside className="sidebar">
      <TrafficLights onBack={onBack} onForward={onForward} onToggleCollapse={onToggleCollapse} />
      <SidebarToggleBar onToggleCollapse={onToggleCollapse} />
      <SidebarNav activeKey={activeNav} onNavigate={onNavigate} />
      <ThreadsSection onNewThread={onNewThread}>{children}</ThreadsSection>
      {closed}
      <SidebarFooter {...footer} />
    </aside>
  );
}
