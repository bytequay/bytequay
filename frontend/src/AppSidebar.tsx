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
import { useState, type ReactNode } from 'react';

export type AppPage = 'home' | 'pulls' | 'settings';

const NAV_ITEMS: Array<{ page: AppPage; label: string; icon: ReactNode }> = [
  { page: 'home', label: 'Home', icon: <HomeIcon /> },
  { page: 'pulls', label: 'Pull requests', icon: <PullsIcon /> },
];

export default function AppSidebar({ page, onNavigate }: {
  page: AppPage;
  onNavigate: (page: AppPage) => void;
}) {
  const [collapsed, setCollapsed] = useState(false);

  const navItem = ({ page: destination, label, icon }: typeof NAV_ITEMS[number]) => (
    <button
      key={destination}
      type="button"
      className={`app-sidebar__item${page === destination ? ' is-active' : ''}`}
      aria-label={label}
      title={label}
      onClick={() => onNavigate(destination)}
    >
      <span aria-hidden="true">{icon}</span>
      <span className="app-sidebar__label">{label}</span>
    </button>
  );

  return (
    <aside className={`app-sidebar${collapsed ? ' is-collapsed' : ''}`}>
      <div className="app-sidebar__traffic">
        <div className="app-sidebar__lights" aria-label="Window controls">
          <button type="button" aria-label="Close" onClick={() => { void window.bridge.controlWindow('close'); }} />
          <button type="button" aria-label="Minimize" onClick={() => { void window.bridge.controlWindow('minimize'); }} />
          <button type="button" aria-label="Toggle fullscreen" onClick={() => { void window.bridge.controlWindow('zoom'); }} />
        </div>
        <button
          type="button"
          className="app-sidebar__toggle"
          aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
          title={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
          onClick={() => setCollapsed(value => !value)}
        >
          <PanelIcon />
        </button>
      </div>
      <nav className="app-sidebar__nav" aria-label="Main navigation">
        {NAV_ITEMS.map(navItem)}
      </nav>
      <div className="app-sidebar__spacer" />
      <nav className="app-sidebar__nav app-sidebar__nav--bottom" aria-label="Application navigation">
        {navItem({ page: 'settings', label: 'Settings', icon: <SettingsIcon /> })}
      </nav>
    </aside>
  );
}

function Icon({ children }: { children: ReactNode }) {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
      {children}
    </svg>
  );
}

function HomeIcon() {
  return <Icon><path d="M3 9.8 12 3l9 6.8" /><path d="M5.5 8.8V20a1 1 0 0 0 1 1H17.5a1 1 0 0 0 1-1V8.8" /></Icon>;
}

function PullsIcon() {
  return (
    <Icon>
      <rect x="3" y="4" width="6.5" height="6.5" rx="1.4" />
      <path d="m3.6 7.1 1.4 1.4 2.6-2.8M12.5 6.2h8.5M12.5 12h8.5M12.5 17.8h8.5" />
    </Icon>
  );
}

function SettingsIcon() {
  return (
    <Icon>
      <circle cx="12" cy="12" r="3" />
      <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-2.82 1.17V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 8 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 3.6 15H3.5a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 5 8.6l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 5.6h.09A1.65 1.65 0 0 0 10 3.6V3.5a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 20.4 9h.1a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
    </Icon>
  );
}

function PanelIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 16 16" fill="none" aria-hidden="true">
      <rect x="2" y="3" width="12" height="10" rx="2.2" stroke="currentColor" strokeWidth="1.3" />
      <path d="M6.4 3.4v9.2" stroke="currentColor" strokeWidth="1.3" />
    </svg>
  );
}
