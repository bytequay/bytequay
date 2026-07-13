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
import { useRef, useState } from 'react';
import type { PointerEvent as ReactPointerEvent, ReactNode } from 'react';
import { SidebarFooter, TrafficLights } from '../shell';
import { useFullScreen } from '../../useFullScreen';

/** Drag-to-resize bounds + the persisted-width storage key. */
const MIN_RAIL_WIDTH = 200;
const MAX_RAIL_WIDTH = 460;
const DEFAULT_RAIL_WIDTH = 222;
const RAIL_WIDTH_KEY = 'bq.rail-width';

const clampWidth = (w: number) => Math.min(MAX_RAIL_WIDTH, Math.max(MIN_RAIL_WIDTH, w));

function readStoredWidth(): number {
  if (typeof window === 'undefined') return DEFAULT_RAIL_WIDTH;
  const raw = window.localStorage.getItem(RAIL_WIDTH_KEY);
  const n = raw === null ? NaN : Number(raw);
  return Number.isFinite(n) ? clampWidth(n) : DEFAULT_RAIL_WIDTH;
}

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

/** The Workspaces nav row's icon, as a crisp SVG — the ▦ glyph above
 *  renders as a near-solid blob at 15px in this font, so it's swapped
 *  in here (and reused as the Workspaces page's own title icon) rather
 *  than relying on the font glyph. */
export const WORKSPACES_ICON = (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
    strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
    <rect x="3" y="3" width="7" height="7" rx="1.6" />
    <rect x="14" y="3" width="7" height="7" rx="1.6" />
    <rect x="3" y="14" width="7" height="7" rx="1.6" />
    <rect x="14" y="14" width="7" height="7" rx="1.6" />
  </svg>
);

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
  activeNav, onNavigate, backHint = false, children, footer, notificationCount,
  collapsed = false, onBack, onForward, backEnabled, forwardEnabled, onToggleCollapse,
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
  /** Folded to a narrow strip — only the chrome row (with the toggle to
   *  re-expand) shows; the nav body + footer are hidden. */
  collapsed?: boolean;
  onBack?: () => void;
  onForward?: () => void;
  /** Dim the corresponding arrow when the history edge is reached. */
  backEnabled?: boolean;
  forwardEnabled?: boolean;
  onToggleCollapse?: () => void;
}) {
  const fullScreen = useFullScreen();

  // Drag the right edge to resize. Width is local + persisted so it sticks
  // across reloads; collapsed mode ignores it (the strip is CSS-sized).
  const [width, setWidth] = useState(readStoredWidth);
  const drag = useRef<{ startX: number; startW: number } | null>(null);
  const onResizeDown = (e: ReactPointerEvent<HTMLDivElement>) => {
    e.preventDefault();
    drag.current = { startX: e.clientX, startW: width };
    e.currentTarget.setPointerCapture(e.pointerId);
  };
  const onResizeMove = (e: ReactPointerEvent<HTMLDivElement>) => {
    if (drag.current === null) return;
    setWidth(clampWidth(drag.current.startW + (e.clientX - drag.current.startX)));
  };
  const onResizeUp = (e: ReactPointerEvent<HTMLDivElement>) => {
    if (drag.current === null) return;
    drag.current = null;
    e.currentTarget.releasePointerCapture(e.pointerId);
    if (typeof window !== 'undefined') window.localStorage.setItem(RAIL_WIDTH_KEY, String(width));
  };

  const navItem = (n: { key: WsNavKey; ic: string; label: string }) => (
    <button
      key={n.key}
      type="button"
      className={n.key === activeNav ? 'sb-nav-item active' : 'sb-nav-item'}
      title={n.label}
      aria-label={n.label}
      onClick={() => onNavigate?.(n.key)}
    >
      <span className="ic" aria-hidden>{n.key === 'workspaces' ? WORKSPACES_ICON : n.ic}</span>
      <span className="lbl">{n.label}</span>
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
    <div className={[
      'shell', 'shell-rail',
      fullScreen ? 'is-fullscreen' : '',
      collapsed ? 'sidebar-collapsed' : '',
    ].filter(Boolean).join(' ')}
      style={collapsed ? undefined : { width }}
    >
      <aside className="sidebar">
        <TrafficLights
          onBack={onBack}
          onForward={onForward}
          backEnabled={backEnabled}
          forwardEnabled={forwardEnabled}
          onToggleCollapse={onToggleCollapse}
        />
        {/* Folded, the primary destinations stay reachable as an icon-only
            column (title attr carries the label as a tooltip); the
            workspace body, secondary nav, and footer drop out. */}
        <div className="sb-nav">{TOP_NAV.map(navItem)}</div>
        {!collapsed && (
          <>
            {children}
            <div className="sb-spacer" />
            <div className="sb-nav">{BOTTOM_NAV.map(navItem)}</div>
            <SidebarFooter {...footer} />
          </>
        )}
      </aside>
      {!collapsed && (
        <div
          className="rail-resize"
          role="separator"
          aria-orientation="vertical"
          aria-label="Resize sidebar"
          onPointerDown={onResizeDown}
          onPointerMove={onResizeMove}
          onPointerUp={onResizeUp}
        />
      )}
    </div>
  );
}
