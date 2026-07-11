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
import { IconBtn, TrunkIcon } from '../primitives';
import type { LogoColor } from '../primitives';

/** The workspace surfaces. */
export type WsTab = 'threads' | 'backlog' | 'memory' | 'insights';

function TabIcon({ tab }: { tab: WsTab }) {
  const paths: Record<WsTab, React.ReactNode> = {
    threads: <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />,
    backlog: (
      <>
        <path d="M8 6h13M8 12h13M8 18h13" />
        <circle cx="3.5" cy="6" r="1.4" />
        <circle cx="3.5" cy="12" r="1.4" />
        <circle cx="3.5" cy="18" r="1.4" />
      </>
    ),
    memory: <path d="M12 3a4 4 0 0 0-4 4 3.5 3.5 0 0 0-2 6.5A3.5 3.5 0 0 0 9 20a3 3 0 0 0 6 0 3.5 3.5 0 0 0 3-6.5A3.5 3.5 0 0 0 16 7a4 4 0 0 0-4-4Z" />,
    insights: <path d="M4 20V10M10 20V4M16 20v-7M22 20H2" />,
  };
  return (
    <svg
      width="15"
      height="15"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
    >
      {paths[tab]}
    </svg>
  );
}

const TABS: { key: WsTab; label: string }[] = [
  { key: 'threads', label: 'Trunks' },
  { key: 'backlog', label: 'Backlog' },
  { key: 'memory', label: 'Memory' },
  { key: 'insights', label: 'Insights' },
];

/** The Trunks · Backlog · Memory · Insights tab bar, wired to the workspace tabs. */
export function WorkspaceTabBar({ active, onSelect, threadCount, backlogCount }: {
  active: WsTab;
  onSelect: (tab: WsTab) => void;
  threadCount?: number;
  backlogCount?: number;
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
          <span className="ic" aria-hidden><TabIcon tab={t.key} /></span>
          {t.label}
          {t.key === 'threads' && threadCount !== undefined && <span className="count">{threadCount}</span>}
          {t.key === 'backlog' && backlogCount !== undefined && backlogCount > 0 && <span className="count">{backlogCount}</span>}
        </button>
      ))}
    </div>
  );
}

/** A repo chip in the workspace header. */
export type RepoChip = { initials: string; color: LogoColor };

/**
 * The workspace main top bar: a dark hero tile + workspace name + a
 * repo-count chip, the New-thread action and pane toggle, and the
 * workspace tab bar beneath. Matches the workspace-detail redesign.
 */
export function WorkspaceTopBar({
  workspace, repos, threadCount, backlogCount, activeTab, onSelectTab, onNewThread, onTogglePane,
}: {
  workspace: { initials: string; color: LogoColor; name: string };
  repos?: RepoChip[];
  threadCount?: number;
  backlogCount?: number;
  activeTab: WsTab;
  onSelectTab: (tab: WsTab) => void;
  onNewThread?: () => void;
  onTogglePane?: () => void;
}) {
  return (
    <div className="ws-topbar">
      <div className="head-row">
        <span className="ws-hero-tile" aria-label={workspace.initials}><TrunkIcon size={15} /></span>
        <span className="ws-title">{workspace.name}</span>
        {repos !== undefined && repos.length > 0 && (
          <span className="ws-repo-chip">
            <TrunkIcon size={12} />
            {repos.length} {repos.length === 1 ? 'repo' : 'repos'}
          </span>
        )}
        <span className="grow" />
        <button type="button" className="btn" onClick={onNewThread}>
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
            <path d="M12 5v14M5 12h14" />
          </svg>
          New thread
        </button>
        {onTogglePane !== undefined && (
          <IconBtn ariaLabel="Toggle right pane" onClick={onTogglePane}>◧</IconBtn>
        )}
      </div>
      <WorkspaceTabBar active={activeTab} onSelect={onSelectTab} threadCount={threadCount} backlogCount={backlogCount} />
    </div>
  );
}
