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
import type {
  ProviderFilter as ThreadsProviderFilter,
  RepoFilter as ThreadsRepoFilter,
  StatusFilter as ThreadsStatusFilter,
} from '../threads/ThreadsLeftRail';
import { useWorkspaceNav } from '../pages/useWorkspaceNav';
import WorkspaceBacklogPage from './WorkspaceBacklogPage';
import NewThreadDialog from './NewThreadDialog';
import WorkspaceInsightsPage from './WorkspaceInsightsPage';
import WorkspaceRepoPage, { type WorkspaceRepoSection } from './WorkspaceRepoPage';
import WorkspaceSessionsPage, { type WorkspaceReviewSessionTarget } from './WorkspaceSessionsPage';
import WorkspaceThreadsSurface from './WorkspaceThreadsSurface';
import WorkspaceTodayPage from './WorkspaceTodayPage';
import WorkspaceNotificationsPage from './WorkspaceNotificationsPage';
import WorkspaceMemoryPage from './WorkspaceMemoryPage';
import WorkspaceSettingsPage, { type WorkspaceSettingsSection } from './WorkspaceSettingsPage';
import { workspaceApi } from './workspaceApi';

export type WorkspaceSection =
  | 'today' | 'trunks' | 'pull-requests' | 'issues' | 'sessions'
  | 'backlog' | 'branches' | 'commits' | 'memory' | 'insights'
  | 'notifications' | 'settings'
  /** Temporary route aliases accepted while old callers migrate. */
  | 'home' | 'threads';

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
  /** Open a PR through the canonical workspace route. Repository
   *  identity is resolved by the sidecar, never carried by the renderer. */
  onOpenPr: (prNumber: number) => void;
  /** Canonical workspace issue-detail route. */
  onOpenIssue?: (issueNumber: number) => void;
  prNumber?: number;
  /** Unified PR identity for local-before-push review routes. */
  prId?: string;
  /** Open the pull-requests section with the agent-review column showing. */
  agentColumn?: boolean;
  issueNumber?: number;
  branchName?: string;
  onOpenBranch?: (branchName: string) => void;
  onOpenHarness?: (watchId?: string) => void;
  sessionId?: string;
  onOpenSession?: (sessionId: string) => void;
  onOpenReview?: (target: WorkspaceReviewSessionTarget) => void;
  backlogKey?: string;
  onOpenBacklog?: (key?: string) => void;
  settingsSection?: string;
  onSelectSettingsSection?: (section: WorkspaceSettingsSection) => void;
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
  section, onSelectSection, workspaceId, onOpenThread, onOpenPr,
  onOpenIssue, prNumber, prId, agentColumn, issueNumber, branchName, onOpenBranch,
  sessionId, onOpenSession, onOpenReview,
  backlogKey, onOpenBacklog,
  settingsSection, onSelectSettingsSection, onOpenHarness,
}: Props) {
  const [newThreadOpen, setNewThreadOpen] = useState(false);
  const [pendingBacklogStartKey, setPendingBacklogStartKey] = useState<string | null>(null);

  const { activeWorkspace, rawThreads } = useWorkspaceNav(workspaceId);
  const loaded = activeWorkspace !== null;
  const name = activeWorkspace?.name ?? 'Workspace';
  const activeSection = section === 'home' ? 'today' : section === 'threads' ? 'trunks' : section;
  // threadId → title, so the workspace backlog cards can show a "from <thread>"
  // chip without each card re-fetching the thread.
  const threadNames = new Map(rawThreads.map(t => [t.id, t.title]));

  return (
    <div className="shell full-width wu-workspace-shell">
      <div className="main">
        {activeSection === 'today' && activeWorkspace !== null && (
          <WorkspaceTodayPage
            workspace={activeWorkspace}
            threads={rawThreads}
            onOpenThread={onOpenThread}
            onNewThread={() => setNewThreadOpen(true)}
            onOpenInsights={() => onSelectSection('insights')}
            onOpenMemory={() => onSelectSection('memory')}
          />
        )}
        {activeSection === 'trunks' && (
          <WorkspaceThreadsSurface
            threads={rawThreads}
            loading={!loaded}
            onOpenThread={onOpenThread}
            onNewThread={() => setNewThreadOpen(true)}
          />
        )}
        {isRepoSection(activeSection) && (
          <WorkspaceRepoPage
            workspaceId={workspaceId}
            section={activeSection}
            onOpenPr={onOpenPr}
            onOpenIssue={number => onOpenIssue?.(number)}
            onOpenBranch={onOpenBranch}
            onOpenTrunk={onOpenThread}
            onOpenHarness={onOpenHarness}
            selectedNumber={activeSection === 'pull-requests' ? prNumber : issueNumber}
            selectedPrId={activeSection === 'pull-requests' ? prId : undefined}
            initialAgentView={activeSection === 'pull-requests' ? agentColumn : undefined}
            selectedBranch={activeSection === 'branches' ? branchName : undefined}
            onBackToList={() => onSelectSection(activeSection)}
          />
        )}
        {activeSection === 'sessions' && (
          <WorkspaceSessionsPage
            workspaceId={workspaceId}
            onOpenThread={onOpenThread}
            selectedSessionId={sessionId}
            onOpenSession={onOpenSession}
            onOpenReview={onOpenReview}
            onBackToList={() => onSelectSection('sessions')}
          />
        )}
        {activeSection === 'backlog' && (
          <WorkspaceBacklogPage
            workspaceId={workspaceId}
            threadNames={threadNames}
            selectedKey={backlogKey}
            onOpenItem={onOpenBacklog}
            onOpenThread={onOpenThread}
            onRequestNewTrunk={itemKey => {
              setPendingBacklogStartKey(itemKey);
              setNewThreadOpen(true);
            }}
          />
        )}
        {activeSection === 'memory' && (
          <WorkspaceMemoryPage workspaceId={workspaceId} />
        )}
        {activeSection === 'insights' && (
          <div className="surface"><WorkspaceInsightsPage workspaceId={workspaceId} /></div>
        )}
        {activeSection === 'notifications' && (
          <WorkspaceNotificationsPage
            workspaceId={workspaceId}
            onOpenThread={onOpenThread}
          />
        )}
        {activeSection === 'settings' && (
          activeWorkspace !== null && (
            <WorkspaceSettingsPage
              workspace={activeWorkspace}
              workspaceId={workspaceId}
              section={isSettingsSection(settingsSection) ? settingsSection : 'agents'}
              onSelectSection={onSelectSettingsSection}
              onOpenMemory={() => onSelectSection('memory')}
            />
          )
        )}
      </div>
      {newThreadOpen && (
        <NewThreadDialog
          workspaceId={workspaceId}
          workspaceName={name}
          onClose={() => {
            setNewThreadOpen(false);
            setPendingBacklogStartKey(null);
          }}
          onCreated={async (threadId) => {
            setNewThreadOpen(false);
            if (pendingBacklogStartKey !== null) {
              await workspaceApi.startBacklogItem(
                workspaceId, pendingBacklogStartKey, threadId);
              setPendingBacklogStartKey(null);
            }
            onOpenThread?.(threadId);
          }}
        />
      )}
    </div>
  );
}

function isRepoSection(section: WorkspaceSection): section is WorkspaceRepoSection {
  return section === 'pull-requests' || section === 'issues'
    || section === 'branches' || section === 'commits';
}

function isSettingsSection(value: string | undefined): value is WorkspaceSettingsSection {
  return value === 'general' || value === 'agents' || value === 'notifications'
    || value === 'sync' || value === 'automation'
    || value === 'memory' || value === 'danger';
}

export default WorkspaceShell;
