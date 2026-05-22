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
};

type Item = { id: WorkspaceSection; label: string; icon: string };

const ITEMS: Item[] = [
  { id: 'home', label: 'Home', icon: '•' },
  { id: 'threads', label: 'Threads', icon: '▢' },
  { id: 'memory', label: 'Memory', icon: '◇' },
  { id: 'insights', label: 'Insights', icon: '△' },
  { id: 'settings', label: 'Settings', icon: '●' },
];

/** Workspace-scoped left rail. The brand chip + command-bar
 *  placeholder + the 5 nav items + the user chip at the bottom.
 *  Command bar is intentionally a placeholder for Phase 9 — it
 *  surfaces in the chrome now so the visual hierarchy matches the
 *  mockup, but clicking it doesn't open the command palette yet. */
function WorkspaceLeftRail({
  active, onSelect, onOpenWorkspaceSwitcher, onOpenNewWorkspace, onOpenControlBar,
  hasLiveThread = false, hasUnreadThread = false,
}: Props) {
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
        <span className="workspace-rail__brand-badge" aria-hidden>B</span>
        <span className="workspace-rail__brand-name">ByteQuay</span>
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

const brandButtonStyle: React.CSSProperties = {
  border: 'none',
  background: 'transparent',
  width: '100%',
  cursor: 'pointer',
  font: 'inherit',
  color: 'inherit',
  textAlign: 'left',
};

export default WorkspaceLeftRail;
