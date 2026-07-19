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
import { RepoAv } from '../../pulls/atoms';

export function WorkspaceChromeRow({
  onBack, onForward, backEnabled = true, forwardEnabled = false, onToggleSidebar,
}: {
  onBack?: () => void;
  onForward?: () => void;
  backEnabled?: boolean;
  forwardEnabled?: boolean;
  onToggleSidebar?: () => void;
}) {
  return (
    <div className="workspace-page-chrome">
      <span className="workspace-page-chrome__lights">
        <button type="button" aria-label="Close window" onClick={() => { void window.bridge.windowControl('close'); }} />
        <button type="button" aria-label="Minimize window" onClick={() => { void window.bridge.windowControl('minimize'); }} />
        <button type="button" aria-label="Toggle full screen" onClick={() => { void window.bridge.windowControl('zoom'); }} />
      </span>
      <button type="button" className="workspace-page-chrome__button" title="Toggle sidebar"
        aria-label="Toggle sidebar" onClick={onToggleSidebar}>
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor"
          strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
          <rect x="3" y="4" width="18" height="16" rx="2.2" />
          <path d="M9 4v16" />
        </svg>
      </button>
      <span className="workspace-page-chrome__history">
        <button type="button" title="Go back" aria-label="Back" disabled={!backEnabled} onClick={onBack}>
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor"
            strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
            <path d="m15 18-6-6 6-6" />
          </svg>
        </button>
        <button type="button" title="Go forward" aria-label="Forward" disabled={!forwardEnabled} onClick={onForward}>
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor"
            strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
            <path d="m9 18 6-6-6-6" />
          </svg>
        </button>
      </span>
    </div>
  );
}

export function WorkspaceGlobalRows({ onNavigate }: {
  onNavigate?: (destination: 'home' | 'workspaces') => void;
}) {
  return (
    <div className="workspace-page-global-rows">
      <button type="button" onClick={() => onNavigate?.('home')}>
        <span aria-hidden><HomeIcon /></span>Home
      </button>
      <button type="button" onClick={() => onNavigate?.('workspaces')}>
        <span aria-hidden><WorkspacesIcon /></span>Workspaces
      </button>
    </div>
  );
}

export function WorkspaceSwitcherCard({ name, repository, onSwitch }: {
  name: string;
  repository: string;
  onSwitch?: () => void;
}) {
  return (
    <div className="workspace-page-switcher-wrap">
      <button type="button" className="workspace-page-switcher" title={`Open ${name} Today`} onClick={onSwitch}>
        <span className="workspace-page-switcher__tile" aria-hidden>
          {repository.includes('/')
            ? <RepoAv repo={repository} size={28} />
            : name.charAt(0).toUpperCase() || 'B'}
        </span>
        <span className="workspace-page-switcher__copy">
          <span>{name}</span>
          <small>{repository}</small>
        </span>
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#8b949e"
          strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
          <path d="m7 9 5-5 5 5" />
          <path d="m7 15 5 5 5-5" />
        </svg>
      </button>
    </div>
  );
}

export function WorkspaceSidebarFooter({
  user = 'chenjian2664', avatarInitials, notificationCount, showSettings = false,
  onNotifications, onSettings,
}: {
  user?: string;
  avatarInitials?: string;
  notificationCount?: number;
  showSettings?: boolean;
  onNotifications?: () => void;
  onSettings?: () => void;
}) {
  const initials = avatarInitials ?? (user === 'chenjian2664' ? 'CJ' : user
    .split(/[^a-zA-Z0-9]+/).filter(Boolean).slice(0, 2)
    .map(part => part.charAt(0).toUpperCase()).join('') || 'CJ');
  return (
    <div className="workspace-page-footer">
      <button type="button" onClick={onNotifications}>
        <span aria-hidden><NotificationsIcon /></span>
        Notifications
        {notificationCount !== undefined && notificationCount > 0 && <small>{notificationCount}</small>}
      </button>
      {showSettings && (
        <button type="button" onClick={onSettings}>
          <span aria-hidden><SettingsIcon /></span>Settings
        </button>
      )}
      <div className="workspace-page-footer__user">
        <span aria-hidden>{initials}</span>
        <strong>{user}</strong>
      </div>
    </div>
  );
}

export function TrunkLineIcon({ size = 14 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <circle cx="6" cy="12" r="2.3" />
      <path d="M8.5 12H21" />
      <path d="m15 8.5 3.5 3.5-3.5 3.5" />
    </svg>
  );
}

export function ChevronIcon({ direction = 'right', size = 11 }: {
  direction?: 'left' | 'right' | 'down';
  size?: number;
}) {
  const path = direction === 'left' ? 'm15 18-6-6 6-6'
    : direction === 'down' ? 'm6 9 6 6 6-6'
      : 'm9 18 6-6-6-6';
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d={path} />
    </svg>
  );
}

export function CheckCircleIcon({ size = 13 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <circle cx="12" cy="12" r="9" />
      <path d="m8.5 12.5 2.5 2.5 5-5.5" />
    </svg>
  );
}

export function PullRequestBranchIcon({ size = 12 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <circle cx="6" cy="6" r="2.2" />
      <circle cx="18" cy="18" r="2.2" />
      <path d="M6 8.3V12a6 6 0 0 0 6 6h3.5" />
    </svg>
  );
}

export function SidebarRow({ icon, children, trailing, onClick, className = '' }: {
  icon: ReactNode;
  children: ReactNode;
  trailing?: ReactNode;
  onClick?: () => void;
  className?: string;
}) {
  return (
    <button type="button" className={`workspace-page-row ${className}`.trim()} onClick={onClick}>
      <span aria-hidden>{icon}</span>
      <span>{children}</span>
      {trailing !== undefined && <small>{trailing}</small>}
    </button>
  );
}

function HomeIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
      <path d="M3 9.8 12 3l9 6.8" />
      <path d="M5.5 8.8V20a1 1 0 0 0 1 1H17.5a1 1 0 0 0 1-1V8.8" />
    </svg>
  );
}

function WorkspacesIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
      <rect x="3" y="3" width="7" height="7" rx="1.6" />
      <rect x="14" y="3" width="7" height="7" rx="1.6" />
      <rect x="3" y="14" width="7" height="7" rx="1.6" />
      <rect x="14" y="14" width="7" height="7" rx="1.6" />
    </svg>
  );
}

function NotificationsIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
      <path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9" />
      <path d="M10.3 21a1.9 1.9 0 0 0 3.4 0" />
    </svg>
  );
}

function SettingsIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="12" cy="12" r="3" />
      <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
    </svg>
  );
}
