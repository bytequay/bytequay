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
import { useState } from 'react';
import WorkspaceMemoryPage from '../settings/pages/WorkspaceMemoryPage';
import type {
  ProviderFilter as ThreadsProviderFilter,
  RepoFilter as ThreadsRepoFilter,
  StatusFilter as ThreadsStatusFilter,
} from '../threads/ThreadsLeftRail';
import { WorkspaceTopBar } from '../ui/workspace';
import type { WsTab } from '../ui/workspace';
import { logoColorFor, monogram, useWorkspaceNav } from '../pages/useWorkspaceNav';
import WorkspaceBacklogPage from './WorkspaceBacklogPage';
import NewThreadDialog from './NewThreadDialog';
import WorkspaceInsightsPage from './WorkspaceInsightsPage';
import WorkspaceThreadsSurface from './WorkspaceThreadsSurface';

export type WorkspaceSection = 'home' | 'threads' | 'backlog' | 'memory' | 'insights' | 'settings';

/** The workspace's main surfaces are Threads / Backlog / Memory / Insights.
 *  The older home + settings sections fold into Threads (the landing) —
 *  Home is now a top-level nav destination and Settings lives in the
 *  global rail's bottom group. */
function sectionToTab(section: WorkspaceSection): WsTab {
  return section === 'backlog' || section === 'memory' || section === 'insights' ? section : 'threads';
}

type Props = {
  section: WorkspaceSection;
  onSelectSection: (section: WorkspaceSection) => void;
  /** Id of the workspace the user is currently inside. Threads,
   *  memory, insights, and Settings all scope to this id; passed
   *  down to the per-section pages so a workspace switch shows the
   *  right slice. App.tsx owns the value. */
  workspaceId: string;
  /** Fired after the user creates a new workspace from the inline
   *  dialog so App.tsx can flip the active id without making the
   *  user round-trip through the landing grid. */
  onWorkspaceCreated?: (workspaceId: string) => void;
  /** Callback for back-link chips inside the memory proposal banner.
   *  Routes to a review/build thread detail at the app level. */
  onOpenThread?: (threadId: string) => void;
  /** Punch from the new-thread dialog (or the threads section's
   *  "+ New thread" button) into the full create page so the user
   *  can finish picking repo + agent + skills. */
  onOpenThreadCreate?: (params?: { initialPrompt?: string; initialGroupId?: string }) => void;
  /** Open the Phase-9 control bar. Wired here so the left-rail
   *  command-bar placeholder becomes an actual launcher. */
  onOpenControlBar?: () => void;
  /** Navigate up to the top-level Workspaces landing grid. Wired
   *  through the brand chevron ("ByteQuay ▾") so the user can switch
   *  between workspaces or reach the "+ New workspace" tile. */
  onOpenWorkspaceSwitcher?: () => void;

  // ── Threads-section state (passed through to the inlined
  // ThreadsPage when section === 'threads'). Hoisted to the App-level
  // nav so a deep link or browser-style back keeps the user's view. ──
  threadsFilter: ThreadsStatusFilter;
  threadsProvider: ThreadsProviderFilter;
  threadsGroupId: ThreadsRepoFilter; // string | null — same shape
  threadsRepo: ThreadsRepoFilter;
  onThreadsFilterChange: (filter: ThreadsStatusFilter) => void;
  onThreadsProviderChange: (provider: ThreadsProviderFilter) => void;
  onThreadsGroupChange: (groupId: string | null) => void;
  onThreadsRepoChange: (repo: string | null) => void;
  /** Open a PR in the repo view with the threads list as the back
   *  target. */
  onOpenPr: (owner: string, repo: string, prNumber: number) => void;
  /** Open the repo's Issues tab with the threads list as the back
   *  target. */
  onOpenIssues: (owner: string, repo: string) => void;
  /** Jump to Settings → Integrations (used by the threads-list "PAT
   *  missing" affordance). */
  onOpenSettings: () => void;
  /** 2×2 group view's immersive toggle — lifted to App so the global
   *  topbar can hide while the user babysits a group. */
  immersive: boolean;
  onChangeImmersive: (next: boolean) => void;
  /** Hide the workspace's own left rail — set when the global workspace
   *  sidebar already provides navigation (avoids a double rail). */
  hideRail?: boolean;
};

/** Workspace main pane — the design's Workspace → Threads/Memory/Insights
 *  layout. The global left rail (mounted by App) owns navigation between
 *  workspaces and threads; this pane is the full-width main column: a
 *  top bar (workspace logo + repo chips + New thread) over the tab body.
 *  Threads is the landing surface (a list of open thread cards); Memory
 *  and Insights are the other two tabs. */
function WorkspaceShell({
  section, onSelectSection, workspaceId, onOpenThread,
}: Props) {
  const [newThreadOpen, setNewThreadOpen] = useState(false);

  const { activeWorkspace, repos, rawThreads } = useWorkspaceNav(workspaceId);
  const loaded = activeWorkspace !== null;
  const name = activeWorkspace?.name ?? 'Workspace';
  const activeTab = sectionToTab(section);
  // threadId → title, so the workspace backlog cards can show a "from <thread>"
  // chip without each card re-fetching the thread.
  const threadNames = new Map(rawThreads.map(t => [t.id, t.title]));

  return (
    <div className="shell full-width">
      <div className="main">
        <WorkspaceTopBar
          workspace={{ initials: monogram(name).toUpperCase(), color: logoColorFor(name), name }}
          repos={repos}
          threadCount={activeWorkspace?.activeThreadCount}
          activeTab={activeTab}
          onSelectTab={tab => onSelectSection(tab)}
          onNewThread={() => setNewThreadOpen(true)}
        />
        {activeTab === 'threads' && (
          <WorkspaceThreadsSurface
            threads={rawThreads}
            loading={!loaded}
            onOpenThread={onOpenThread}
          />
        )}
        {activeTab === 'backlog' && (
          <WorkspaceBacklogPage
            workspaceId={workspaceId}
            threadNames={threadNames}
            onOpenThread={onOpenThread}
          />
        )}
        {activeTab === 'memory' && (
          <div className="surface">
            <WorkspaceMemoryPage workspaceId={workspaceId} onOpenThread={onOpenThread} />
          </div>
        )}
        {activeTab === 'insights' && (
          <div className="surface"><WorkspaceInsightsPage /></div>
        )}
      </div>
      {newThreadOpen && (
        <NewThreadDialog
          workspaceId={workspaceId}
          workspaceName={name}
          onClose={() => setNewThreadOpen(false)}
          onCreated={(threadId) => {
            setNewThreadOpen(false);
            onOpenThread?.(threadId);
          }}
        />
      )}
    </div>
  );
}

export default WorkspaceShell;
