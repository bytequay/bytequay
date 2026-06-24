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
import { useCallback, useEffect, useState } from 'react';
import type { WorkspaceDto } from '../types';
import WorkspaceMemoryPage from '../settings/pages/WorkspaceMemoryPage';
import ThreadsPage from '../threads/ThreadsPage';
import type {
  ProviderFilter as ThreadsProviderFilter,
  RepoFilter as ThreadsRepoFilter,
  StatusFilter as ThreadsStatusFilter,
} from '../threads/ThreadsLeftRail';
import AssignReviewTaskDialog from './AssignReviewTaskDialog';
import NewThreadDialog from './NewThreadDialog';
import NewWorkspaceDialog from './NewWorkspaceDialog';
import WorkspaceHomePage from './WorkspaceHomePage';
import WorkspaceInsightsPage from './WorkspaceInsightsPage';
import WorkspaceLeftRail from './WorkspaceLeftRail';
import WorkspaceSettingsPage from './WorkspaceSettingsPage';

export type WorkspaceSection = 'home' | 'threads' | 'memory' | 'insights' | 'settings';

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

/** Calm-language workspace shell. The "Workspace" entry in the global
 *  topbar mounts this; the user moves between the 5 inner sections
 *  (Home · Threads · Memory · Insights · Settings) via the left rail.
 *  Threads are workspace-scoped per the model doc — the section
 *  renders the existing ThreadsPage inline rather than punching out to
 *  a top-level page. */
function WorkspaceShell({
  section, onSelectSection, workspaceId, onWorkspaceCreated,
  onOpenThread, onOpenThreadCreate, onOpenControlBar,
  onOpenWorkspaceSwitcher,
  threadsFilter, threadsProvider, threadsGroupId, threadsRepo,
  onThreadsFilterChange, onThreadsProviderChange, onThreadsGroupChange, onThreadsRepoChange,
  onOpenPr, onOpenIssues, onOpenSettings,
  immersive, onChangeImmersive, hideRail = false,
}: Props) {
  const [newThreadOpen, setNewThreadOpen] = useState(false);
  const [newThreadInitialGroupId, setNewThreadInitialGroupId] = useState<string | undefined>(undefined);
  const [newWorkspaceOpen, setNewWorkspaceOpen] = useState(false);
  const [assignReviewOpen, setAssignReviewOpen] = useState(false);
  const { hasLiveThread, hasUnreadThread } = useRailThreadSignals();

  // Track the active workspace so the rail brand + home page title
  // reflect the workspace the user is inside (and update on rename
  // without a reload). The shell is the single fetcher; pages
  // receive name + id as props.
  const [workspace, setWorkspace] = useState<WorkspaceDto | null>(null);
  const loadWorkspace = useCallback(async () => {
    try {
      const next = await window.bridge.getWorkspace(workspaceId);
      setWorkspace(next);
    }
    catch { /* keep stale name on transient failure */ }
  }, [workspaceId]);
  useEffect(() => { void loadWorkspace(); }, [loadWorkspace]);
  const workspaceName = workspace?.name ?? '';

  return (
    <section className="workspace-shell">
      {!hideRail && (
      <WorkspaceLeftRail
        active={section}
        onSelect={onSelectSection}
        // The brand chevron is the workspace switcher per the
        // landing design. We pass through onOpenWorkspaceSwitcher
        // when it's wired; the rail falls back to opening the New
        // Workspace dialog inline when it isn't, so older mounts of
        // the shell still work.
        onOpenWorkspaceSwitcher={onOpenWorkspaceSwitcher}
        onOpenNewWorkspace={() => setNewWorkspaceOpen(true)}
        onOpenControlBar={onOpenControlBar}
        hasLiveThread={hasLiveThread}
        hasUnreadThread={hasUnreadThread}
        workspaceName={workspaceName}
        workspaceId={workspaceId}
      />
      )}
      <div className="workspace-content">
        {section === 'home' && (
          <WorkspaceHomePage
            workspaceId={workspaceId}
            workspaceName={workspaceName === '' ? 'Workspace' : workspaceName}
            onSelectSection={onSelectSection}
            onNewThread={() => setNewThreadOpen(true)}
            onAssignReview={() => setAssignReviewOpen(true)}
            onOpenThread={onOpenThread}
          />
        )}
        {section === 'threads' && (
          <ThreadsPage
            workspaceId={workspaceId}
            filter={threadsFilter}
            provider={threadsProvider}
            groupId={threadsGroupId}
            repo={threadsRepo}
            onFilterChange={onThreadsFilterChange}
            onProviderChange={onThreadsProviderChange}
            onGroupChange={onThreadsGroupChange}
            onRepoChange={onThreadsRepoChange}
            onSelectTask={(threadId) => onOpenThread?.(threadId)}
            onOpenPr={onOpenPr}
            onOpenIssues={onOpenIssues}
            onOpenSettings={onOpenSettings}
            onNewTask={(initialGroupId) => {
              if (initialGroupId !== undefined) {
                setNewThreadInitialGroupId(initialGroupId);
              }
              setNewThreadOpen(true);
            }}
            immersive={immersive}
            onChangeImmersive={onChangeImmersive}
          />
        )}
        {section === 'memory' && (
          <WorkspaceMemoryPage workspaceId={workspaceId} onOpenThread={onOpenThread} />
        )}
        {section === 'insights' && <WorkspaceInsightsPage />}
        {section === 'settings' && <WorkspaceSettingsPage />}
      </div>
      {newThreadOpen && (
        <NewThreadDialog
          workspaceId={workspaceId}
          workspaceName={workspaceName === '' ? 'Workspace' : workspaceName}
          onClose={() => {
            setNewThreadOpen(false);
            setNewThreadInitialGroupId(undefined);
          }}
          onCreated={(threadId) => {
            setNewThreadOpen(false);
            setNewThreadInitialGroupId(undefined);
            onOpenThread?.(threadId);
          }}
          initialGroupId={newThreadInitialGroupId}
        />
      )}
      {newWorkspaceOpen && (
        <NewWorkspaceDialog
          onClose={() => setNewWorkspaceOpen(false)}
          onCreated={(newId) => {
            setNewWorkspaceOpen(false);
            onWorkspaceCreated?.(newId);
          }}
        />
      )}
      {assignReviewOpen && (
        <AssignReviewTaskDialog
          workspaceId={workspaceId}
          onClose={() => setAssignReviewOpen(false)}
          onStarted={(threadId) => {
            setAssignReviewOpen(false);
            onOpenThread?.(threadId);
          }}
        />
      )}
    </section>
  );
}

/** Quick poll for the two rail nav signals (live + unread). Re-runs
 *  on a 15s cadence so the dots don't go stale after a thread finishes
 *  or parks — a streaming WebSocket would be cheaper, but the existing
 *  bridge already gives us a snappy list endpoint so polling is the
 *  short path. Home does its own fetch for the card data, so this is
 *  a small extra cost. Hoist to a shared store if it grows further. */
function useRailThreadSignals() {
  const [signals, setSignals] = useState<{ hasLiveThread: boolean; hasUnreadThread: boolean }>({
    hasLiveThread: false,
    hasUnreadThread: false,
  });

  useEffect(() => {
    let cancelled = false;
    const tick = () => {
      window.bridge.listTasks().then(threads => {
        if (cancelled) return;
        const hasLive = threads.some(t => t.status === 'RUNNING');
        const hasUnread = threads.some(t =>
          t.status === 'AWAITING' || t.activeTask?.status === 'AWAITING');
        setSignals({ hasLiveThread: hasLive, hasUnreadThread: hasUnread });
      }).catch(() => {});
    };
    tick();
    const id = window.setInterval(tick, 15_000);
    return () => { cancelled = true; window.clearInterval(id); };
  }, []);

  return signals;
}

export default WorkspaceShell;
