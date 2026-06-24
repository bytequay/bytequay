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

/** Nav destinations. No Search (removed in this model). The first four
 *  are the primary (top) group; the rest sit in the bottom group. */
export type WsNavKey =
  | 'home' | 'workspaces' | 'my-work' | 'automations'
  | 'repos' | 'email' | 'notifications' | 'settings';

const TOP_NAV: { key: WsNavKey; ic: string; label: string }[] = [
  { key: 'home', ic: '⌂', label: 'Home' },
  { key: 'workspaces', ic: '▦', label: 'Workspaces' },
  { key: 'my-work', ic: '▤', label: 'My work' },
  { key: 'automations', ic: '⚙', label: 'Automations' },
];

const BOTTOM_NAV: { key: WsNavKey; ic: string; label: string }[] = [
  { key: 'repos', ic: '⎇', label: 'Repos' },
  { key: 'email', ic: '✉', label: 'Email' },
  { key: 'notifications', ic: '🔔', label: 'Notifications' },
  { key: 'settings', ic: '⚙', label: 'Settings' },
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
  activeNav, onNavigate, backHint = false, children, footer, notificationCount, onBack, onForward, onToggleCollapse,
}: {
  activeNav?: WsNavKey;
  onNavigate?: (key: WsNavKey) => void;
  /** Show the "← back" hint on the Workspaces item (inside a workspace). */
  backHint?: boolean;
  /** The body: a WorkspaceList, or a WorkspaceSwitcher + ThreadList. */
  children: ReactNode;
  footer: { initials: string; name: string; onChat?: () => void; onSettings?: () => void };
  /** Unread badge on the bottom Notifications item. */
  notificationCount?: number;
  onBack?: () => void;
  onForward?: () => void;
  onToggleCollapse?: () => void;
}) {
  const navItem = (n: { key: WsNavKey; ic: string; label: string }) => (
    <button
      key={n.key}
      type="button"
      className={n.key === activeNav ? 'sb-nav-item active' : 'sb-nav-item'}
      onClick={() => onNavigate?.(n.key)}
    >
      <span className="ic" aria-hidden>{n.ic}</span>
      <span>{n.label}</span>
      {n.key === 'workspaces' && backHint && <span className="kbd">← back</span>}
      {n.key === 'notifications' && notificationCount !== undefined && notificationCount > 0 && (
        <span className="kbd">{notificationCount > 99 ? '99+' : notificationCount}</span>
      )}
    </button>
  );

  return (
    // The `.shell` class is what scopes every sidebar-chrome rule
    // (.sb-nav, .ws-list, .thread-item, …). This nav is the app's single
    // left rail, mounted outside any per-surface shell grid, so it
    // carries its own `.shell shell-rail` wrapper to pick up that chrome
    // while rendering as a plain fixed-width column (see v3-workspace.css).
    <div className="shell shell-rail">
      <aside className="sidebar">
        <TrafficLights onBack={onBack} onForward={onForward} onToggleCollapse={onToggleCollapse} />
        <div className="sb-nav">{TOP_NAV.map(navItem)}</div>
        {children}
        <div className="sb-spacer" />
        <div className="sb-nav">{BOTTOM_NAV.map(navItem)}</div>
        <SidebarFooter {...footer} />
      </aside>
    </div>
  );
}
