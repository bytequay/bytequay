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
import type { WorkspaceSection } from './WorkspaceShell';

type Props = {
  active: WorkspaceSection;
  onSelect: (section: WorkspaceSection) => void;
  /** Navigate up to the Workspaces landing grid. The brand chevron
   *  ("ByteQuay ▾") fires this when wired — that's the workspace
   *  switcher. Falls back to {@link onOpenNewWorkspace} when not
   *  provided so older mounts still surface a useful click. */
  onOpenWorkspaceSwitcher?: () => void;
  /** Open the new-workspace modal. Legacy entry for the brand chevron;
   *  superseded by {@link onOpenWorkspaceSwitcher} once the landing
   *  page is wired. The dialog itself remains the inline creation
   *  path for callers that punch straight to "+ New workspace". */
  onOpenNewWorkspace?: () => void;
  /** Open the Phase-9 control bar. The command-bar placeholder in
   *  the rail acts as the launcher when this is provided; ⌘K still
   *  works globally either way. */
  onOpenControlBar?: () => void;
  /** True when at least one thread is currently RUNNING. The Threads
   *  nav item grows a pulsing green dot at its right edge. */
  hasLiveThread?: boolean;
  /** True when at least one thread is parked at the publish gate /
   *  needs attention but nothing is actively running. The Threads
   *  nav item grows a static purple dot at its right edge. Ignored
   *  when {@link hasLiveThread} is also set — live wins. */
  hasUnreadThread?: boolean;
  /** Active workspace's display name. Shown next to the brand badge
   *  so the rail reflects which workspace the user is inside. */
  workspaceName?: string;
  /** Active workspace's stable id (the slug-prefixed value embedded
   *  in every thread/task id). Surfaced as a small mono caption under
   *  the brand name so users can see and copy the immutable handle —
   *  the name on top is renameable, the id below is forever. */
  workspaceId?: string;
};

type Item = { id: WorkspaceSection; label: string; icon: ReactNode };

/** Tiny stroke-only glyphs — 16px box, 1.6 stroke, currentColor so the
 *  rail's hover / active text colour drives the icon colour too. Kept
 *  inline as bare SVG (no icon library dep) since each shape is one or
 *  two paths. */
function strokeIcon(d: string) {
  return (
    <svg
      width="16"
      height="16"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d={d} />
    </svg>
  );
}

const HOME_ICON = strokeIcon('M4 11l8-7 8 7v9a1 1 0 0 1-1 1h-4v-6h-6v6H5a1 1 0 0 1-1-1v-9z');
const THREADS_ICON = (
  <svg
    width="16" height="16" viewBox="0 0 24 24" fill="none"
    stroke="currentColor" strokeWidth="1.6"
    strokeLinecap="round" strokeLinejoin="round"
  >
    <path d="M3 5h11a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1H7l-4 3V5z" />
    <path d="M8 17v2a1 1 0 0 0 1 1h10l2 2v-9a1 1 0 0 0-1-1h-2" />
  </svg>
);
const MEMORY_ICON = strokeIcon('M5 4h12a2 2 0 0 1 2 2v14H7a2 2 0 0 1-2-2V4zm0 14a2 2 0 0 0 2 2');
const INSIGHTS_ICON = strokeIcon('M4 4v16h16M8 16v-4M12 16V8M16 16v-7');
const SETTINGS_ICON = (
  <svg
    width="16" height="16" viewBox="0 0 24 24" fill="none"
    stroke="currentColor" strokeWidth="1.6"
    strokeLinecap="round" strokeLinejoin="round"
  >
    <circle cx="12" cy="12" r="3" />
    <path d="M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M5.6 18.4l2.1-2.1M16.3 7.7l2.1-2.1" />
  </svg>
);

const ITEMS: Item[] = [
  { id: 'home', label: 'Home', icon: HOME_ICON },
  { id: 'threads', label: 'Threads', icon: THREADS_ICON },
  { id: 'memory', label: 'Memory', icon: MEMORY_ICON },
  { id: 'insights', label: 'Insights', icon: INSIGHTS_ICON },
  { id: 'settings', label: 'Settings', icon: SETTINGS_ICON },
];

/** Workspace-scoped left rail. The brand chip + command-bar
 *  placeholder + the 5 nav items + the user chip at the bottom.
 *  Command bar is intentionally a placeholder for Phase 9 — it
 *  surfaces in the chrome now so the visual hierarchy matches the
 *  mockup, but clicking it doesn't open the command palette yet. */
function WorkspaceLeftRail({
  active, onSelect, onOpenWorkspaceSwitcher, onOpenNewWorkspace, onOpenControlBar,
  hasLiveThread = false, hasUnreadThread = false,
  workspaceName, workspaceId,
}: Props) {
  const displayName = workspaceName !== undefined && workspaceName.length > 0
      ? workspaceName
      : 'ByteQuay';
  const brandInitial = displayName.slice(0, 1).toUpperCase();
  // Prefer the new switcher hook; fall back to the legacy
  // new-workspace dialog for callers that haven't migrated yet.
  const brandClick = onOpenWorkspaceSwitcher ?? onOpenNewWorkspace;
  const brandTitle = onOpenWorkspaceSwitcher
    ? 'Switch workspaces…'
    : onOpenNewWorkspace ? 'New workspace…' : undefined;
  return (
    <aside className="workspace-rail" aria-label="Workspace navigation">
      <button
        type="button"
        className="workspace-rail__brand"
        onClick={brandClick}
        disabled={!brandClick}
        title={brandTitle}
        style={brandButtonStyle}
      >
        <span className="workspace-rail__brand-badge" aria-hidden>{brandInitial}</span>
        <span className="workspace-rail__brand-text">
          <span className="workspace-rail__brand-name">{displayName}</span>
          {workspaceId !== undefined && workspaceId.length > 0 && (
            <span className="workspace-rail__brand-id" title={workspaceId}>
              {workspaceId}
            </span>
          )}
        </span>
        <span className="workspace-rail__brand-chevron" aria-hidden>▾</span>
      </button>
      <button
        type="button"
        className="workspace-rail__commandbar"
        onClick={onOpenControlBar}
        disabled={!onOpenControlBar}
        aria-label="Open command bar"
        style={commandbarButtonStyle}
      >
        <span>Type a command or ask…</span>
        <span className="workspace-rail__commandbar-key">⌘K</span>
      </button>

      <div className="workspace-rail__section-label">Workspace</div>
      <nav className="workspace-rail__items">
        {ITEMS.map(item => {
          const showLive = item.id === 'threads' && hasLiveThread;
          const showUnread = item.id === 'threads' && !hasLiveThread && hasUnreadThread;
          return (
            <button
              key={item.id}
              type="button"
              className={`workspace-rail__item${
                active === item.id ? ' workspace-rail__item--active' : ''}`}
              onClick={() => onSelect(item.id)}
              aria-current={active === item.id ? 'page' : undefined}
            >
              <span className="workspace-rail__item-icon" aria-hidden>{item.icon}</span>
              <span>{item.label}</span>
              {showLive && (
                <span
                  className="workspace-rail__item-dot workspace-rail__item-dot--live"
                  aria-label="threads are live"
                />
              )}
              {showUnread && (
                <span
                  className="workspace-rail__item-dot workspace-rail__item-dot--unread"
                  aria-label="threads need attention"
                />
              )}
            </button>
          );
        })}
      </nav>

      <div className="workspace-rail__user">
        <span className="workspace-rail__user-avatar" aria-hidden>JC</span>
        <span className="workspace-rail__user-name">jian</span>
        <span className="workspace-rail__user-menu" aria-hidden>…</span>
      </div>
    </aside>
  );
}

const commandbarButtonStyle: React.CSSProperties = {
  font: 'inherit',
  color: 'inherit',
  width: '100%',
  textAlign: 'left',
};

/* The brand chip used to have an inline `background: transparent`
 * override. The design treats it as a card so the background, border
 * and shadow now live in workspace.css; this style only keeps the
 * reset-button hygiene (no native button chrome, full-width hit area). */
const brandButtonStyle: React.CSSProperties = {
  border: 'none',
  width: '100%',
  cursor: 'pointer',
  font: 'inherit',
  color: 'inherit',
  textAlign: 'left',
};

export default WorkspaceLeftRail;
