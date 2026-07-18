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
import CurrentUserAvatar from '../../CurrentUserAvatar';
import { TrafficLights } from '../shell';
import { useFullScreen } from '../../useFullScreen';

/** Drag-to-resize bounds + the persisted-width storage key. */
const MIN_RAIL_WIDTH = 200;
const MAX_RAIL_WIDTH = 460;
const DEFAULT_RAIL_WIDTH = 250;
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
  | 'home' | 'workspaces' | 'reviews' | 'my-work' | 'automations'
  | 'repos' | 'email' | 'notifications' | 'settings'
  | 'today' | 'trunks' | 'pull-requests' | 'issues' | 'backlog'
  | 'branches' | 'commits' | 'sessions' | 'memory' | 'insights';

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

const TOP_NAV: { key: WsNavKey; ic: ReactNode; label: string }[] = [
  { key: 'home', ic: <SidebarIcon kind="home" />, label: 'Home' },
  { key: 'workspaces', ic: WORKSPACES_ICON, label: 'Workspaces' },
  { key: 'reviews', ic: <SidebarIcon kind="reviews" />, label: 'Reviews' },
  { key: 'my-work', ic: <SidebarIcon kind="work" />, label: 'My work' },
];

const BOTTOM_NAV: { key: WsNavKey; ic: ReactNode; label: string }[] = [
  { key: 'notifications', ic: <SidebarIcon kind="notifications" />, label: 'Notifications' },
  { key: 'settings', ic: <SidebarIcon kind="settings" />, label: 'Settings' },
];

/**
 * The workspace-model sidebar shell: traffic lights, the fixed top nav
 * (Home / Workspaces / My work), a body that swaps between the workspace
 * list and a workspace's threads, and a compact secondary nav.
 * Identical across every frame — built once. When a workspace is open the
 * Workspaces item shows a "← back" hint (it's the way back to the
 * overview).
 */
export function WorkspaceNavSidebar({
  activeNav, onNavigate, backHint = false, children, notificationCount,
  collapsed = false, onBack, onForward, backEnabled, forwardEnabled, onToggleCollapse,
  workspaceMode = false, hideBottomNav = false,
}: {
  activeNav?: WsNavKey;
  onNavigate?: (key: WsNavKey) => void;
  /** Show the "← back" hint on the Workspaces item (inside a workspace). */
  backHint?: boolean;
  /** The body: a WorkspaceList, or a WorkspaceSwitcher + ThreadList. */
  children: ReactNode;
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
  /** The workspace rail owns its grouped destinations inside children. */
  workspaceMode?: boolean;
  /** Trunk detail uses the source rail without the notifications row. */
  hideBottomNav?: boolean;
}) {
  const fullScreen = useFullScreen();

  // Drag the right edge to resize. Width is local + persisted so it sticks
  // across reloads; collapsed mode ignores it (the strip is CSS-sized).
  const [width, setWidth] = useState(readStoredWidth);
  const drag = useRef<{ startX: number; startW: number; width: number } | null>(null);
  const onResizeDown = (e: ReactPointerEvent<HTMLDivElement>) => {
    e.preventDefault();
    drag.current = { startX: e.clientX, startW: width, width };
    e.currentTarget.setPointerCapture(e.pointerId);
  };
  const onResizeMove = (e: ReactPointerEvent<HTMLDivElement>) => {
    if (drag.current === null) return;
    const nextWidth = clampWidth(drag.current.startW + (e.clientX - drag.current.startX));
    drag.current.width = nextWidth;
    setWidth(nextWidth);
  };
  const onResizeEnd = () => {
    if (drag.current === null) return;
    const nextWidth = drag.current.width;
    drag.current = null;
    if (typeof window !== 'undefined') {
      window.localStorage.setItem(RAIL_WIDTH_KEY, String(nextWidth));
    }
  };
  const resizeWithKeyboard = (delta: number) => {
    const nextWidth = clampWidth(width + delta);
    setWidth(nextWidth);
    if (typeof window !== 'undefined') {
      window.localStorage.setItem(RAIL_WIDTH_KEY, String(nextWidth));
    }
  };

  const navItem = (n: { key: WsNavKey; ic: ReactNode; label: string }) => (
    <div
      key={n.key}
      className={n.key === activeNav ? 'sb-nav-item active' : 'sb-nav-item'}
      role="button"
      tabIndex={0}
      title={n.label}
      aria-label={n.label}
      onClick={() => onNavigate?.(n.key)}
      onKeyDown={event => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          onNavigate?.(n.key);
        }
      }}
    >
      <span className="ic" aria-hidden>{n.ic}</span>
      <span className="lbl">{n.label}</span>
      {n.key === 'workspaces' && backHint && <span className="kbd">← back</span>}
      {n.key === 'notifications' && notificationCount !== undefined && notificationCount > 0 && (
        <span className="kbd">{notificationCount > 99 ? '99+' : notificationCount}</span>
      )}
    </div>
  );

  return (
    // The `.shell` class is what scopes every sidebar-chrome rule
    // (.sb-nav, .ws-list, .thread-item, …). This nav is the app's single
    // left rail, mounted outside any per-surface shell grid, so it
    // carries its own `.shell shell-rail` wrapper to pick up that chrome
    // while rendering as a plain fixed-width column (see v3-workspace.css).
    <div className={[
      'shell', 'shell-rail',
      workspaceMode ? 'workspace-mode' : 'global-mode',
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
          hideNavArrows={workspaceMode && (activeNav !== 'trunks' || hideBottomNav)}
        />
        {/* Folded, the primary destinations stay reachable as an icon-only
            column (title attr carries the label as a tooltip); the
            workspace body, secondary nav, and footer drop out. */}
        {!workspaceMode && <div className="sb-nav">{TOP_NAV.map(navItem)}</div>}
        {!collapsed && (
          <>
            {children}
            <div className="sb-spacer" />
            {!hideBottomNav && (
              <div className="sb-nav sb-nav--bottom">
                {BOTTOM_NAV.map(navItem)}
              </div>
            )}
            {workspaceMode && (
              <div className="ws-user-footer">
                <CurrentUserAvatar size={26} className="ws-user-avatar" />
                <span className="ws-user-name">chenjian2664</span>
              </div>
            )}
          </>
        )}
      </aside>
      {!collapsed && (
        <div
          className="rail-resize"
          role="separator"
          aria-orientation="vertical"
          aria-label="Resize sidebar"
          aria-valuemin={MIN_RAIL_WIDTH}
          aria-valuemax={MAX_RAIL_WIDTH}
          aria-valuenow={width}
          tabIndex={0}
          onPointerDown={onResizeDown}
          onPointerMove={onResizeMove}
          onPointerUp={onResizeEnd}
          onPointerCancel={onResizeEnd}
          onKeyDown={event => {
            if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
            event.preventDefault();
            resizeWithKeyboard(event.key === 'ArrowLeft' ? -10 : 10);
          }}
        />
      )}
    </div>
  );
}

function SidebarIcon({ kind }: {
  kind: 'home' | 'reviews' | 'work' | 'notifications' | 'settings';
}) {
  const size = kind === 'notifications' || kind === 'settings' ? 15 : 16;
  const paths = {
    home: <><path d="M3 9.8 12 3l9 6.8" /><path d="M5.5 8.8V20a1 1 0 0 0 1 1H17.5a1 1 0 0 0 1-1V8.8" /></>,
    reviews: <><path d="M12 3v18" /><path d="M7 21h10" /><path d="M3 7h3.5L12 5l5.5 2H21" />
      <path d="m6.5 7-2.8 6a3 3 0 0 0 5.6 0L6.5 7z" />
      <path d="m17.5 7-2.8 6a3 3 0 0 0 5.6 0L17.5 7z" /></>,
    work: <><rect x="3" y="4" width="6.5" height="6.5" rx="1.4" />
      <path d="m3.6 7.1 1.4 1.4 2.6-2.8" />
      <path d="M12.5 6.2h8.5M12.5 12h8.5M12.5 17.8h8.5" /></>,
    notifications: <><path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9" />
      <path d="M10.3 21a1.94 1.94 0 0 0 3.4 0" /></>,
    settings: <><circle cx="12" cy="12" r="3" /><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-2.82 1.17V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 8 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 3.6 15H3.5a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 5 8.6l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 5.6h.09A1.65 1.65 0 0 0 10 3.6V3.5a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 20.4 9h.1a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" /></>,
  } satisfies Record<typeof kind, ReactNode>;
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth={kind === 'reviews' ? 1.6 : 1.7}
      strokeLinecap="round" strokeLinejoin="round">
      {paths[kind]}
    </svg>
  );
}
