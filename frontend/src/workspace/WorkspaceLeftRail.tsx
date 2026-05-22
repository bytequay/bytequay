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
  /** Open the new-workspace modal. Triggered from the brand row at
   *  the top of the rail — that slot is the natural home for the
   *  workspace switcher in multi-workspace mode. */
  onOpenNewWorkspace?: () => void;
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
function WorkspaceLeftRail({ active, onSelect, onOpenNewWorkspace }: Props) {
  return (
    <aside className="workspace-rail" aria-label="Workspace navigation">
      <button
        type="button"
        className="workspace-rail__brand"
        onClick={onOpenNewWorkspace}
        disabled={!onOpenNewWorkspace}
        title={onOpenNewWorkspace ? 'New workspace…' : undefined}
        style={brandButtonStyle}
      >
        <span className="workspace-rail__brand-badge" aria-hidden>B</span>
        <span className="workspace-rail__brand-name">ByteQuay</span>
        <span className="workspace-rail__brand-chevron" aria-hidden>▾</span>
      </button>
      <div
        className="workspace-rail__commandbar"
        role="button"
        tabIndex={0}
        aria-label="Command bar (coming in Phase 9)"
        title="Command bar — Phase 9 builds the action grammar"
      >
        <span>Type a command or ask…</span>
        <span className="workspace-rail__commandbar-key">⌘K</span>
      </div>

      <div className="workspace-rail__section-label">Workspace</div>
      <nav className="workspace-rail__items">
        {ITEMS.map(item => (
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
          </button>
        ))}
      </nav>

      <div className="workspace-rail__user">
        <span className="workspace-rail__user-avatar" aria-hidden>JC</span>
        <span className="workspace-rail__user-name">jian</span>
        <span className="workspace-rail__user-menu" aria-hidden>…</span>
      </div>
    </aside>
  );
}

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
