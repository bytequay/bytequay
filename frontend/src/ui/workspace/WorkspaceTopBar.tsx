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
import { IconBtn, Logo } from '../primitives';
import type { LogoColor } from '../primitives';

/** The three workspace surfaces. */
export type WsTab = 'threads' | 'memory' | 'insights';

const TABS: { key: WsTab; ic: string; label: string }[] = [
  { key: 'threads', ic: '💭', label: 'Threads' },
  { key: 'memory', ic: '🧠', label: 'Memory' },
  { key: 'insights', ic: '📊', label: 'Insights' },
];

/** The Threads · Memory · Insights tab bar, wired to the workspace tabs. */
export function WorkspaceTabBar({ active, onSelect, threadCount }: {
  active: WsTab;
  onSelect: (tab: WsTab) => void;
  threadCount?: number;
}) {
  return (
    <div className="ws-tabs">
      {TABS.map(t => (
        <button
          key={t.key}
          type="button"
          className={t.key === active ? 'ws-tab active' : 'ws-tab'}
          onClick={() => onSelect(t.key)}
        >
          <span className="ic" aria-hidden>{t.ic}</span>
          {t.label}
          {t.key === 'threads' && threadCount !== undefined && <span className="count">{threadCount}</span>}
        </button>
      ))}
    </div>
  );
}

/** A repo chip in the workspace header. */
export type RepoChip = { initials: string; color: LogoColor };

/**
 * The workspace main top bar: the workspace logo + name + its repo chips,
 * a New-thread action and pane toggle, and the workspace tab bar beneath.
 */
export function WorkspaceTopBar({
  workspace, repos, threadCount, activeTab, onSelectTab, onNewThread, onTogglePane,
}: {
  workspace: { initials: string; color: LogoColor; name: string };
  repos?: RepoChip[];
  threadCount?: number;
  activeTab: WsTab;
  onSelectTab: (tab: WsTab) => void;
  onNewThread?: () => void;
  onTogglePane?: () => void;
}) {
  return (
    <div className="ws-topbar">
      <div className="head-row">
        <Logo initials={workspace.initials} color={workspace.color} size="lg" />
        <span className="ws-title">{workspace.name}</span>
        {repos !== undefined && repos.length > 0 && (
          <span className="ws-repos">
            ·
            {repos.map((r, i) => <Logo key={i} initials={r.initials} color={r.color} size="sm" />)}
            {repos.length} repos
          </span>
        )}
        <span className="grow" />
        <button type="button" className="btn" onClick={onNewThread}>
          <span className="ic" aria-hidden>+</span>New thread
        </button>
        {onTogglePane !== undefined && (
          <IconBtn ariaLabel="Toggle right pane" onClick={onTogglePane}>◧</IconBtn>
        )}
      </div>
      <WorkspaceTabBar active={activeTab} onSelect={onSelectTab} threadCount={threadCount} />
    </div>
  );
}
