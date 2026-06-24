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
import { SidebarFooter, TrafficLights } from '../shell';

/** Top-level nav destinations. No Search (removed in this model). */
export type WsNavKey = 'home' | 'workspaces' | 'my-work' | 'automations';

const NAV: { key: WsNavKey; ic: string; label: string }[] = [
  { key: 'home', ic: '⌂', label: 'Home' },
  { key: 'workspaces', ic: '▦', label: 'Workspaces' },
  { key: 'my-work', ic: '▤', label: 'My work' },
  { key: 'automations', ic: '⚙', label: 'Automations' },
];

/**
 * The workspace-model sidebar shell: traffic lights, the fixed top nav
 * (Home / Workspaces / My work / Automations), a body that swaps between
 * the workspace list and a workspace's threads, and the user footer.
 * Identical across every frame — built once. When a workspace is open the
 * Workspaces item shows a "← back" hint (it's the way back to the
 * overview).
 */
export function WorkspaceNavSidebar({
  activeNav, onNavigate, backHint = false, children, footer, onBack, onForward, onToggleCollapse,
}: {
  activeNav?: WsNavKey;
  onNavigate?: (key: WsNavKey) => void;
  /** Show the "← back" hint on the Workspaces item (inside a workspace). */
  backHint?: boolean;
  /** The body: a WorkspaceList, or a WorkspaceSwitcher + ThreadList. */
  children: ReactNode;
  footer: { initials: string; name: string; onChat?: () => void; onSettings?: () => void };
  onBack?: () => void;
  onForward?: () => void;
  onToggleCollapse?: () => void;
}) {
  return (
    <aside className="sidebar">
      <TrafficLights onBack={onBack} onForward={onForward} onToggleCollapse={onToggleCollapse} />
      <div className="sb-nav">
        {NAV.map(n => (
          <button
            key={n.key}
            type="button"
            className={n.key === activeNav ? 'sb-nav-item active' : 'sb-nav-item'}
            onClick={() => onNavigate?.(n.key)}
          >
            <span className="ic" aria-hidden>{n.ic}</span>
            <span>{n.label}</span>
            {n.key === 'workspaces' && backHint && <span className="kbd">← back</span>}
          </button>
        ))}
      </div>
      {children}
      <div className="sb-spacer" />
      <SidebarFooter {...footer} />
    </aside>
  );
}
