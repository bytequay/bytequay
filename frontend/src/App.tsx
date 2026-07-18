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
import { Component, useCallback, useEffect, useRef, useState, type ErrorInfo, type ReactNode } from 'react';
import SettingsShell from './settings/SettingsShell';
import NotificationsScreen from './NotificationsScreen';
import TeamDetailPage from './teams/TeamDetailPage';
import TeamHomePage from './teams/TeamHomePage';
import TeamsManagePage from './teams/TeamsManagePage';
import EmailPage from './email/EmailPage';
import ThreadCreatePage from './threads/ThreadCreatePage';
import ControlBar, { type PageContextTag } from './control/ControlBar';
import { Ds4StatusWidget } from './components/Ds4StatusWidget';
import type { ControlDispatch } from './control/actionCatalog';
import ReviewThreadPage from './review/ReviewThreadPage';
import { TrunkRoute } from './pages/TrunkRoute';
import { WorkspaceNavShell } from './pages/WorkspaceNavShell';
import { useThreadTasks } from './pages/useThreadTasks';
import type { WsNavKey } from './ui/workspace';
import { TaskBrainRoute } from './pages/TaskBrainRoute';
import { StageDetailRoute } from './pages/StageDetailRoute';
import TaskCodePage from './threads/TaskCodePage';
import WorkspaceShell, { type WorkspaceSection } from './workspace/WorkspaceShell';
import NewThreadDialog from './workspace/NewThreadDialog';
import { useWorkspaceNav } from './pages/useWorkspaceNav';
import WorkspacesLandingPage from './workspace/WorkspacesLandingPage';
import WorkspaceCreationToasts from './workspace/WorkspaceCreationToasts';
import {
  parseWorkspaceRoute, workspaceRouteHash, type WorkspaceRoute,
} from './workspace/workspaceRoutes';
import type {
  StatusFilter as ThreadsStatusFilter,
  ProviderFilter as ThreadsProviderFilter,
  RepoFilter as ThreadsRepoFilter,
} from './threads/ThreadsLeftRail';
import type { SettingsSection } from './settings/types';
import PullsScreen from './pulls/PullsScreen';
import HomePage from './home/HomePage';
import type { AgentReviewNavTarget } from './pr/localpr/PrDetailsView';
import InAppBrowser from './InAppBrowser';
import FindBar from './FindBar';
import LogoLoading from './LogoLoading';
import OnboardingScreen from './OnboardingScreen';
import { applyTheme, loadTheme } from './themes';
import { useSurfaceVisitCapture } from './footprints/useSurfaceVisitCapture';
import { resumeStop } from './footprints/resume';
import {
  back as navBack, canGoBack, canGoForward, createHistory, current as navCurrent,
  forward as navForward, push as navPush, type NavHistory,
} from './navHistory';

type Status = 'checking' | 'needs-pat' | 'ready';
export type Nav =
  | { view: 'home' }
  /** The unified Pull-requests surface (pulls/PullsScreen). {@code initialPr}
   *  deep-links a specific PR: the screen resolves it and opens its pane
   *  even when the row isn't in the dashboard list ({@code repo} is the
   *  "owner/name" fullName). */
  | { view: 'pulls'; initialPr?: { repo: string; number: number } }
  /** `back` carries the parent screen so the PR-detail breadcrumb
   *  returns the user where they came from — Repository home, Local
   *  repo, Team kanban, or just Home. Defaults to Home when unset. */
  | { view: 'repo'; owner: string; repo: string; prNumber?: number; initialTab?: 'pulls' | 'issues'; diffCommitSha?: string; openDiff?: boolean; back?: Nav }
  | { view: 'teams' }
  | { view: 'team'; teamId: number }
  | { view: 'team-kanban'; teamId: number }
  | { view: 'email' }
  | { view: 'thread-create'; initialGroupId?: string }
  /** When {@code taskId} is omitted the nav lands on the thread's
   *  trunk window (planning altitude); when set, it lands on that
   *  specific task's detail window (the old monolithic detail page
   *  for now — Phase 3 redesigns it as the proper task-detail shell). */
  | { view: 'thread-detail'; threadId: string; taskId?: string }
  /** Task brain view — the per-task "brain" surface (aggregate strip,
   *  stage navigator, brain feed, action rail). Sits alongside the
   *  older task-detail page; the back link returns to that page. */
  | { view: 'task-brain'; threadId: string; taskId: string; initialReviewRoundId?: string }
  /** Stage drill-in — the detailed per-stage view reached from a brain-
   *  view stage chip or a brain-agent response's drill-in chip. */
  | { view: 'stage-detail'; threadId: string; taskId: string; stageId: string }
  /** Standalone code page — the task's commit/diff/files viewer, reached
   *  from a "View code diff" button on the brain view or a stage detail. */
  | { view: 'task-code'; threadId: string; taskId: string; back?: Nav }
  | { view: 'review-thread'; threadId: string; back?: Nav }
  | { view: 'notifications' }
  | { view: 'repos' }
  | { view: 'repository'; owner: string; repo: string }
  | { view: 'local-repo'; owner: string; repo: string; initialBranch?: string }
  | { view: 'settings'; section?: SettingsSection }
  /** Workspace shell. {@code section} picks one of the five inner
   *  surfaces. When section==='threads' the four threadsXxx fields
   *  hold the URL-ish filter state the inlined ThreadsPage reads —
   *  same shape as the old top-level {@code view: 'threads'}, just
   *  reached as a nested surface so threads stay workspace-scoped
   *  per the model doc. */
  | { view: 'workspace';
      section?: WorkspaceSection;
      prNumber?: number;
      /** Open the pull-requests section with the agent-review column
       *  already replacing the work list (deep link from PullsScreen's
       *  "Work with agent" button). */
      agentColumn?: boolean;
      issueNumber?: number;
      sessionId?: string;
      backlogKey?: string;
      branchName?: string;
      settingsSection?: string;
      threadsFilter?: ThreadsStatusFilter;
      threadsProvider?: ThreadsProviderFilter;
      threadsGroupId?: string;
      threadsRepo?: ThreadsRepoFilter;
    }
  /** Top-level "which project brain do I enter?" page. Lives above
   *  any workspace; the global Workspace nav button and the in-shell
   *  brand chevron both route here. While we're single-workspace the
   *  landing always renders; the ambient "skip to the only non-scratch
   *  workspace's Home" behaviour will land back when multi-workspace
   *  creation ships and the visual design is settled. */
  | { view: 'workspaces-landing' };


/**
 * Catches render errors in the routed content so the global topbar
 * stays visible — without this, a thrown render unmounts the entire
 * tree and the user sees nothing but white. Surfaces the message and
 * a "Reset" button that returns the user to Home and re-mounts the
 * route, so they can recover without restarting the app. The fallback
 * resets when navigation changes (resetKey prop).
 */
type ErrorBoundaryProps = {
  resetKey: string;
  onReset: () => void;
  children: ReactNode;
};
type ErrorBoundaryState = { error: Error | null };

class RouteErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { error: null };

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error('Route render error:', error, info);
  }

  componentDidUpdate(prev: ErrorBoundaryProps): void {
    if (prev.resetKey !== this.props.resetKey && this.state.error) {
      this.setState({ error: null });
    }
  }

  render(): ReactNode {
    if (this.state.error) {
      return (
        <div className="route-error">
          <h2>Something went wrong rendering this page.</h2>
          <pre className="route-error__stack">{this.state.error.message}</pre>
          <button
            type="button"
            className="route-error__btn"
            onClick={() => { this.setState({ error: null }); this.props.onReset(); }}
          >
            Go home
          </button>
        </div>
      );
    }
    return this.props.children;
  }
}

/** Storage key the renderer reads / writes when the user enters a
 *  workspace from the landing grid. The CURRENT chip on the landing
 *  card reads this; multi-workspace switching reads it to know which
 *  workspace to drop into. localStorage instead of a backend setting:
 *  v1 is single-user single-device, so survival across restart is
 *  the only requirement. */
const ACTIVE_WORKSPACE_STORAGE_KEY = 'bytequay.workspace.active';

function readActiveWorkspaceId(): string | null {
  try { return window.localStorage.getItem(ACTIVE_WORKSPACE_STORAGE_KEY); }
  catch { return null; }
}

function writeActiveWorkspaceId(id: string): void {
  try { window.localStorage.setItem(ACTIVE_WORKSPACE_STORAGE_KEY, id); }
  catch { /* private browsing — skip silently */ }
}

function workspaceSectionNav(section: WorkspaceSection): WsNavKey {
  if (section === 'home' || section === 'today') return 'today';
  if (section === 'threads' || section === 'trunks') return 'trunks';
  return section;
}

type PublicNavigation = { nav: Nav; workspaceId: string | null };

function publicNavigation(route: WorkspaceRoute): PublicNavigation {
  switch (route.kind) {
    case 'home': return { nav: { view: 'home' }, workspaceId: null };
    // URL compat: the retired Reviews surface's hash lands on Pulls.
    case 'reviews': return { nav: { view: 'pulls' }, workspaceId: null };
    case 'workspaces': return { nav: { view: 'workspaces-landing' }, workspaceId: null };
    case 'workspace':
      return { nav: { view: 'workspace', section: 'today' }, workspaceId: route.workspaceId };
    case 'trunks':
      return {
        nav: route.trunkId === undefined
          ? { view: 'workspace', section: 'trunks' }
          : { view: 'thread-detail', threadId: route.trunkId },
        workspaceId: route.workspaceId,
      };
    case 'pull-request':
      return {
        nav: { view: 'workspace', section: 'pull-requests', prNumber: route.number },
        workspaceId: route.workspaceId,
      };
    case 'issue':
      return {
        nav: { view: 'workspace', section: 'issues', issueNumber: route.number },
        workspaceId: route.workspaceId,
      };
    case 'session':
      return {
        nav: { view: 'workspace', section: 'sessions', sessionId: route.sessionId },
        workspaceId: route.workspaceId,
      };
    case 'backlog':
      return {
        nav: { view: 'workspace', section: 'backlog', backlogKey: route.key },
        workspaceId: route.workspaceId,
      };
    case 'branches':
      return {
        nav: { view: 'workspace', section: 'branches', branchName: route.name },
        workspaceId: route.workspaceId,
      };
    case 'commits':
    case 'memory':
    case 'insights':
    case 'notifications':
      return {
        nav: { view: 'workspace', section: route.kind },
        workspaceId: route.workspaceId,
      };
    case 'settings':
      return {
        nav: { view: 'workspace', section: 'settings', settingsSection: route.section ?? 'agents' },
        workspaceId: route.workspaceId,
      };
    case 'legacy-repo':
      if (route.owner === undefined || route.repo === undefined) {
        return { nav: { view: 'workspaces-landing' }, workspaceId: null };
      }
      return {
        nav: route.page === 'branches'
          ? { view: 'local-repo', owner: route.owner, repo: route.repo }
          : {
              view: 'repo',
              owner: route.owner,
              repo: route.repo,
              initialTab: route.page === 'issues' ? 'issues' : 'pulls',
            },
        workspaceId: null,
      };
  }
}

function publicRoute(nav: Nav, workspaceId: string | null): WorkspaceRoute | null {
  switch (nav.view) {
    case 'home': return { kind: 'home' };
    case 'workspaces-landing': return { kind: 'workspaces' };
    case 'thread-detail':
    case 'task-brain':
    case 'stage-detail':
    case 'task-code':
      return workspaceId === null ? null : {
        kind: 'trunks', workspaceId, trunkId: nav.threadId,
      };
    case 'workspace': {
      if (workspaceId === null) return { kind: 'workspaces' };
      const section = nav.section === 'home' ? 'today' : nav.section === 'threads' ? 'trunks'
        : nav.section ?? 'today';
      switch (section) {
        case 'today': return { kind: 'workspace', workspaceId };
        case 'trunks': return { kind: 'trunks', workspaceId };
        case 'pull-requests':
          return { kind: 'pull-request', workspaceId, number: nav.prNumber };
        case 'issues':
          return { kind: 'issue', workspaceId, number: nav.issueNumber };
        case 'sessions':
          return { kind: 'session', workspaceId, sessionId: nav.sessionId };
        case 'backlog': return { kind: 'backlog', workspaceId, key: nav.backlogKey };
        case 'branches': return { kind: 'branches', workspaceId, name: nav.branchName };
        case 'commits': return { kind: 'commits', workspaceId };
        case 'memory': return { kind: 'memory', workspaceId };
        case 'insights': return { kind: 'insights', workspaceId };
        case 'notifications': return { kind: 'notifications', workspaceId };
        case 'settings': return { kind: 'settings', workspaceId, section: nav.settingsSection ?? 'agents' };
      }
      return null;
    }
    case 'repos': return { kind: 'legacy-repo' };
    case 'repository':
      return { kind: 'legacy-repo', owner: nav.owner, repo: nav.repo, page: 'pulls' };
    case 'local-repo':
      return { kind: 'legacy-repo', owner: nav.owner, repo: nav.repo, page: 'branches' };
    default: return null;
  }
}

/** Per-task memory of the last sub-surface the user viewed (brain, a specific
 *  stage, or the code page), so reopening a task lands back where they left
 *  off instead of always on the brain. Persisted so it survives a reload. */
const TASK_VIEW_KEY = (taskId: string) => `bq.task.lastView.${taskId}`;

/** Record the surface for a task nav, so the next open can restore it. */
function rememberTaskView(nav: Nav): void {
  if (typeof window === 'undefined') return;
  if (nav.view !== 'task-brain' && nav.view !== 'stage-detail' && nav.view !== 'task-code') {
    return;
  }
  // Store only view + stageId — never the recursive `back` chain.
  const value = nav.view === 'stage-detail'
    ? { view: nav.view, stageId: nav.stageId }
    : { view: nav.view };
  try { window.localStorage.setItem(TASK_VIEW_KEY(nav.taskId), JSON.stringify(value)); }
  catch { /* private browsing — skip */ }
}

/** The nav target for opening a task: the last surface the user viewed for it
 *  (a stage, the code page), or the brain page as the default/fallback. */
function lastTaskNav(threadId: string, taskId: string): Nav {
  const fallback: Nav = { view: 'task-brain', threadId, taskId };
  if (typeof window === 'undefined') return fallback;
  try {
    const raw = window.localStorage.getItem(TASK_VIEW_KEY(taskId));
    if (raw === null) return fallback;
    const v = JSON.parse(raw) as { view?: string; stageId?: string };
    if (v.view === 'stage-detail' && typeof v.stageId === 'string' && v.stageId.length > 0) {
      return { view: 'stage-detail', threadId, taskId, stageId: v.stageId };
    }
    if (v.view === 'task-code') return { view: 'task-code', threadId, taskId };
    return fallback;
  }
  catch { return fallback; }
}

function App() {
  const initialNavigation = useRef(
    publicNavigation(parseWorkspaceRoute(typeof window === 'undefined' ? '' : window.location.hash)),
  ).current;
  const [status, setStatus] = useState<Status>('checking');
  const [nav, setNavRaw] = useState<Nav>(initialNavigation.nav);
  // Browser-style back/forward over the Nav state. Every setNav pushes
  // onto the stack (truncating any forward branch, like Chrome); the
  // sidebar arrows + ⌘[ / ⌘] walk it. The ref mutates only alongside a
  // setNavRaw call, so render-time reads of can-go flags stay fresh.
  const navHistoryRef = useRef<NavHistory<Nav>>(createHistory<Nav>(initialNavigation.nav));
  const setNav = useCallback((next: Nav | ((prev: Nav) => Nav)) => {
    const h = navHistoryRef.current;
    const resolved = typeof next === 'function' ? next(navCurrent(h)) : next;
    navHistoryRef.current = navPush(h, resolved);
    setNavRaw(resolved);
  }, []);
  const goBack = useCallback(() => {
    navHistoryRef.current = navBack(navHistoryRef.current);
    setNavRaw(navCurrent(navHistoryRef.current));
  }, []);
  const goForward = useCallback(() => {
    navHistoryRef.current = navForward(navHistoryRef.current);
    setNavRaw(navCurrent(navHistoryRef.current));
  }, []);
  // Left rail fold — the chrome-row panel toggle collapses it to a strip.
  const [railCollapsed, setRailCollapsed] = useState(false);
  // The code-diff page is wide; fold the rail when it opens so the diff gets
  // the room, and expand it again when the user leaves. Only acts on the
  // enter/leave transition, so a manual toggle elsewhere is left alone.
  const prevViewRef = useRef(nav.view);
  useEffect(() => {
    const was = prevViewRef.current;
    prevViewRef.current = nav.view;
    if (nav.view === 'task-code' && was !== 'task-code') setRailCollapsed(true);
    else if (was === 'task-code' && nav.view !== 'task-code') setRailCollapsed(false);
  }, [nav.view]);

  // Remember the last task sub-surface (brain / stage / code) per task, so
  // clicking the task again returns there instead of the brain default.
  useEffect(() => { rememberTaskView(nav); }, [nav]);

  // The open thread + (when inside one) its task — the left rail nests the
  // active task's stages under the thread row so the user can jump to a stage.
  const navThreadId = 'threadId' in nav ? nav.threadId : null;
  const navTaskId = 'taskId' in nav ? nav.taskId : undefined;
  const threadTasks = useThreadTasks(navThreadId);

  // Records a footprint whenever nav lands on a tracked surface (PR
  // kanban, a PR, a task, a thread). Single capture point; fire-and-forget.
  useSurfaceVisitCapture(nav);

  // Current nav kept in a ref so the document-level click delegate below
  // can read it for the `back` breadcrumb without re-subscribing each render.
  const navRef = useRef(nav);
  navRef.current = nav;

  // Internal PR links: markdown.ts rewrites bare github.com PR URLs in
  // comment / description bodies into chips carrying data-pr-owner/repo/
  // number. A single delegated listener turns a plain left-click on any of
  // them into in-app navigation to the PR detail page, instead of letting
  // main.ts route the href to the embedded github.com browser. Modifier
  // clicks fall through so "open in new window" still works via the href.
  useEffect(() => {
    const onClick = (e: MouseEvent) => {
      if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) {
        return;
      }
      const target = e.target as HTMLElement | null;
      const link = target?.closest<HTMLElement>('[data-pr-number]');
      if (!link) return;
      const owner = link.dataset.prOwner;
      const repo = link.dataset.prRepo;
      const prNumber = Number(link.dataset.prNumber);
      if (!owner || !repo || !Number.isFinite(prNumber) || prNumber <= 0) return;
      e.preventDefault();
      setNav({ view: 'repo', owner, repo, prNumber, back: navRef.current });
    };
    document.addEventListener('click', onClick);
    return () => document.removeEventListener('click', onClick);
  }, []);

  // Open a thread, routing standalone AgentReview owners first, legacy
  // review-pass threads second, and development threads to the trunk.
  //
  // This is every "open a thread" click's single entry point — the PR
  // page's linked-task chip, the sidebar's thread rows, footprint resume —
  // and it's async (the ownership lookup is a network round-trip). If an
  // earlier click's lookup is still in flight when a newer one fires (e.g.
  // clicking a PR's linked task, then quickly clicking a different thread
  // in the sidebar before the first lookup returns), the earlier call must
  // not win the race and land the user on the wrong thread once it finally
  // resolves — only the MOST RECENT request may navigate.
  const openThreadRequestRef = useRef(0);
  const openThread = (threadId: string) => {
    const back = nav;
    const request = ++openThreadRequestRef.current;
    void (async () => {
      try {
        const review = await window.bridge.getAgentReviewByThread(threadId);
        if (openThreadRequestRef.current !== request) return;
        if (review !== null && review.review.owner_task_id !== null) {
          setNav({
            view: 'task-brain',
            threadId,
            taskId: review.review.owner_task_id,
          });
          return;
        }
        if (review !== null) {
          const bundle = await window.bridge.getLocalPrBundle(review.review.pr_id);
          if (openThreadRequestRef.current !== request) return;
          const prNumber = bundle?.pr.remotePrNumber;
          const workspaceId = review.review.workspace_id;
          if (prNumber !== null && prNumber !== undefined && workspaceId !== null) {
            setActiveWorkspaceId(workspaceId);
            writeActiveWorkspaceId(workspaceId);
            setNav({
              view: 'workspace', section: 'pull-requests', prNumber, agentColumn: true,
            });
            return;
          }
        }

        const pass = await window.bridge.getReviewPassByThread(threadId);
        if (openThreadRequestRef.current !== request) return;
        setNav(pass !== null
          ? { view: 'review-thread', threadId, back }
          : { view: 'thread-detail', threadId });
      }
      catch {
        if (openThreadRequestRef.current === request) setNav({ view: 'thread-detail', threadId });
      }
    })();
  };

  const openStartedReview = (threadId: string | null) => {
    if (threadId !== null) {
      openThread(threadId);
      return;
    }
    setNav({ view: 'pulls' });
  };

  /** Open a stage-backed run's own log. Detached artifact runs route through
   *  their owning surface and do not have a StageDetailRoute. */
  const openRun = (threadId: string, taskId: string) => (runId: string) => {
    void window.bridge.getAgentRun(runId)
      .then(run => {
        if (run.stageId !== null) {
          setNav({ view: 'stage-detail', threadId, taskId, stageId: run.stageId });
        }
      })
      .catch(() => { /* transient; the click is a no-op on failure */ });
  };
  /** Which workspace the user last entered. Drives the CURRENT chip
   *  on the landing grid. Set when the user picks a card. */
  const [activeWorkspaceId, setActiveWorkspaceId] = useState<string | null>(
    () => initialNavigation.workspaceId ?? readActiveWorkspaceId());

  // Repo/Repository/LocalRepo are compatibility inputs only. Resolve the
  // repository's sole workspace, then immediately replace them with a
  // canonical workspace route. An unconnected PR deep-links into the
  // unified Pulls surface; no retired standalone-repository page is
  // rendered.
  useEffect(() => {
    if (nav.view === 'repos') {
      setNav({ view: 'workspaces-landing' });
      return;
    }
    if (nav.view !== 'repo' && nav.view !== 'repository' && nav.view !== 'local-repo') {
      return;
    }
    let cancelled = false;
    const owner = nav.owner;
    const repo = nav.repo;
    const fullName = `${owner}/${repo}`.toLowerCase();
    void window.bridge.listWorkspaces()
      .then(cards => {
        if (cancelled) return;
        const workspace = cards.find(card =>
          card.repository?.fullName.toLowerCase() === fullName);
        if (workspace === undefined) {
          setNav(nav.view === 'repo' && nav.prNumber !== undefined
            ? { view: 'pulls', initialPr: { repo: `${owner}/${repo}`, number: nav.prNumber } }
            : { view: 'workspaces-landing' });
          return;
        }
        setActiveWorkspaceId(workspace.id);
        writeActiveWorkspaceId(workspace.id);
        if (nav.view === 'local-repo') {
          setNav({
            view: 'workspace',
            section: 'branches',
            branchName: nav.initialBranch,
          });
          return;
        }
        if (nav.view === 'repo') {
          setNav({
            view: 'workspace',
            section: nav.initialTab === 'issues' ? 'issues' : 'pull-requests',
            prNumber: nav.prNumber,
          });
          return;
        }
        setNav({ view: 'workspace', section: 'today' });
      })
      .catch(() => {
        if (!cancelled) {
          setNav(nav.view === 'repo' && nav.prNumber !== undefined
            ? { view: 'pulls', initialPr: { repo: `${owner}/${repo}`, number: nav.prNumber } }
            : { view: 'workspaces-landing' });
        }
      });
    return () => { cancelled = true; };
  }, [nav, setNav]);

  // Keep the public hash in step with the internal Nav while compatibility
  // routes still exist. pushState gives Electron real browser history; the
  // existing rail arrows continue to walk the in-memory stack.
  useEffect(() => {
    const route = publicRoute(nav, activeWorkspaceId);
    if (route === null) return;
    const hash = workspaceRouteHash(route);
    if (window.location.hash !== hash) window.history.pushState(null, '', hash);
  }, [activeWorkspaceId, nav]);

  useEffect(() => {
    const applyHash = () => {
      const target = publicNavigation(parseWorkspaceRoute(window.location.hash));
      if (target.workspaceId !== null) {
        setActiveWorkspaceId(target.workspaceId);
        writeActiveWorkspaceId(target.workspaceId);
      }
      navHistoryRef.current = navPush(navHistoryRef.current, target.nav);
      setNavRaw(target.nav);
    };
    window.addEventListener('popstate', applyHash);
    window.addEventListener('hashchange', applyHash);
    return () => {
      window.removeEventListener('popstate', applyHash);
      window.removeEventListener('hashchange', applyHash);
    };
  }, []);
  /** The CURRENTLY-VIEWED thread's own workspace, once resolved by whatever
   *  page loaded it (e.g. TrunkRoute's own `getTask`). Distinct from
   *  {@code activeWorkspaceId}: opening a thread from outside the workspace
   *  flow (a PR's linked-task chip, footprint resume) must show ITS
   *  workspace in the sidebar without silently reassigning which workspace
   *  the landing grid's CURRENT chip points to — that only changes when the
   *  user explicitly picks a card. Reset on every thread change so a
   *  still-loading thread never shows the PREVIOUS thread's workspace. */
  const [viewedThreadWorkspaceId, setViewedThreadWorkspaceId] = useState<string | null>(null);
  // Placed before any early return so the reset runs on every render
  // regardless of `status` — hooks can't follow a conditional return.
  const viewedThreadIdRef = useRef<string | undefined>(undefined);
  const currentThreadId = 'threadId' in nav ? nav.threadId : undefined;
  if (viewedThreadIdRef.current !== currentThreadId) {
    viewedThreadIdRef.current = currentThreadId;
    setViewedThreadWorkspaceId(null);
  }
  const [fatal, setFatal] = useState<string | null>(null);
  /** Phase-9 control bar. ⌘K opens it; ControlBar's onDispatch
   *  routes a typed payload back here into setNav. Keeps the bar
   *  free of nav state knowledge. */
  const [controlBarOpen, setControlBarOpen] = useState(false);
  // Left-rail "+ New thread" — opens the same modal as the workspace
  // top bar's button instead of routing to the standalone create page.
  const [newThreadDialogOpen, setNewThreadDialogOpen] = useState(false);
  // ⌘F / Ctrl+F opens the in-page find bar (Electron ships no browser find).
  const [findOpen, setFindOpen] = useState(false);
  // URL of the in-app browser overlay, or null when closed. Set by the
  // main process whenever a link is clicked in the React UI; cleared by
  // the × button on the InAppBrowser toolbar.
  const [inAppUrl, setInAppUrl] = useState<string | null>(null);
  const [fullScreen, setFullScreen] = useState<boolean>(false);
  // Unread-notification badge on the Notifications nav button. Polls
  // every 20s while the app is open so the badge stays roughly fresh
  // without hammering the local backend. The notifications page does
  // its own faster poll while it's the visible view.
  const [unreadNotificationCount, setUnreadNotificationCount] = useState<number>(0);
  // Global keybindings: ⌘K opens the control bar, ⌘N starts a new
  // thread. Both ignore the press when an input/textarea/contentEditable
  // has focus and the key isn't meta-modified — the user typing K in a
  // text field shouldn't summon the bar.
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (!(e.metaKey || e.ctrlKey)) return;
      if (e.key === 'k' || e.key === 'K') {
        e.preventDefault();
        setControlBarOpen(open => !open);
        return;
      }
      if (e.key === 'f' || e.key === 'F') {
        // ⌘F = find in page. Always opens (even from a text field) so it
        // matches the browser shortcut it stands in for; the bar's own
        // input takes focus on open.
        e.preventDefault();
        setFindOpen(true);
        return;
      }
      if (e.key === 'n' || e.key === 'N') {
        // ⌘N = new thread. Routes through the existing thread-create
        // page; the workspace shell's NewThreadDialog can also open
        // it via its onContinueFullForm hand-off, so behaviour stays
        // consistent across surfaces.
        e.preventDefault();
        setNav({ view: 'thread-create' });
        return;
      }
      if (e.key === '[' || e.key === ']') {
        // ⌘[ / ⌘] = history back / forward, matching the browser.
        e.preventDefault();
        if (e.key === '[') goBack();
        else goForward();
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [setNav, goBack, goForward]);

  /** Tags the current page registers with the control bar, displayed
   *  as "on #tag-1 #tag-2…" above the input. Tags are derived from
   *  the active nav state so the bar's context tracks the user
   *  without any per-page registration code today. Future commits
   *  can replace this with a page-element registry that pages
   *  populate themselves. */
  const contextTags: PageContextTag[] = ((): PageContextTag[] => {
    switch (nav.view) {
      case 'home':           return [{ label: 'home', kind: 'scope' }];
      case 'workspace':      return [
        { label: 'workspace-bytequay', kind: 'scope' },
        { label: nav.section ?? 'home', kind: 'scope' },
      ];
      case 'workspaces-landing': return [{ label: 'workspaces', kind: 'scope' }];
      case 'pulls':          return [{ label: 'pull-requests', kind: 'scope' }];
      case 'thread-detail':  return [{ label: 'thread', kind: 'entity' }];
      case 'thread-create':  return [{ label: 'new-thread', kind: 'scope' }];
      case 'repos':          return [{ label: 'repos', kind: 'scope' }];
      case 'repository':     return [{ label: `${nav.owner}-${nav.repo}`, kind: 'entity' }];
      case 'email':          return [{ label: 'email', kind: 'scope' }];
      case 'notifications':  return [{ label: 'notifications', kind: 'scope' }];
      case 'settings':       return [{ label: 'settings', kind: 'scope' }];
      case 'review-thread':  return [{ label: 'review-thread', kind: 'entity' }];
      default:               return [];
    }
  })();

  /** Route a ControlBar dispatch into setNav. Lives at the App level
   *  so the catalog stays a static module — no React context or
   *  prop drilling. */
  const handleControlDispatch = (d: ControlDispatch) => {
    switch (d.kind) {
      case 'nav.home':            setNav({ view: 'home' }); break;
      case 'nav.workspace':       setNav({ view: 'workspace', section: d.section }); break;
      case 'nav.threads':         setNav({ view: 'workspace', section: 'threads' }); break;
      case 'nav.pull-requests':   setNav({ view: 'pulls' }); break;
      case 'nav.repos':           setNav({ view: 'repos' }); break;
      case 'nav.email':           setNav({ view: 'email' }); break;
      case 'nav.notifications':   setNav({ view: 'notifications' }); break;
      case 'nav.settings':        setNav({ view: 'settings' }); break;
      case 'create.thread':       setNav({ view: 'thread-create' }); break;
    }
  };

  useEffect(() => {
    let cancelled = false;
    const refresh = async () => {
      try {
        const unread = await window.bridge.listUnreadNotifications();
        if (!cancelled) setUnreadNotificationCount(unread.length);
      }
      catch { /* non-fatal — leave the badge unchanged */ }
    };
    void refresh();
    const id = window.setInterval(() => { void refresh(); }, 20_000);
    return () => { cancelled = true; window.clearInterval(id); };
  }, []);
  // threads-group immersive mode is lifted to App so the global topbar
  // can be hidden underneath. Persisted to localStorage so the user
  // returns to their preferred chrome state between sessions.
  const [threadsImmersive, setThreadsImmersiveState] = useState<boolean>(() => {
    try { return window.localStorage.getItem('bytequay.threads.groupImmersive') === '1'; }
    catch { return false; }
  });
  const setThreadsImmersive = useCallback((next: boolean) => {
    setThreadsImmersiveState(next);
    try { window.localStorage.setItem('bytequay.threads.groupImmersive', next ? '1' : '0'); }
    catch { /* private browsing — fine to skip */ }
  }, []);
  // Immersive is only meaningful when actively inside a group. Drop it
  // whenever the user navigates away so the chrome shows up again next
  // time the page mounts.
  useEffect(() => {
    if (!threadsImmersive) return;
    const inGroup = nav.view === 'workspace'
        && nav.section === 'threads'
        && nav.threadsGroupId !== undefined;
    if (!inGroup) setThreadsImmersive(false);
  }, [nav, threadsImmersive, setThreadsImmersive]);
  // The group page hides the topbar entirely in immersive mode. Esc
  // (handled inside ThreadGroupPage) brings it back.
  const hideTopbar = threadsImmersive
    && nav.view === 'workspace'
    && nav.section === 'threads'
    && nav.threadsGroupId !== undefined;

  useEffect(() => {
    applyTheme(loadTheme());
  }, []);

  useEffect(() => {
    const unsub = window.bridge.onInAppOpenRequest(({ url }) => setInAppUrl(url));
    return unsub;
  }, []);

  // bytequay:// links injected into email bodies (see EmailHtmlEnricher)
  // arrive here as { action, params }. Currently only "pr-diff" exists —
  // it jumps straight to the repo PR-detail view with the email as the
  // back-target so the breadcrumb returns the user to their inbox.
  useEffect(() => {
    const unsub = window.bridge.onAppNavRequest(({ action, params }) => {
      if (action === 'open' && params.path) {
        const target = publicNavigation(parseWorkspaceRoute(params.path));
        if (target.workspaceId !== null) {
          setActiveWorkspaceId(target.workspaceId);
          writeActiveWorkspaceId(target.workspaceId);
        }
        setNav(target.nav);
        return;
      }
      if (action !== 'pr-diff') return;
      const owner = params.owner;
      const repo = params.repo;
      const prNumber = parseInt(params.pr ?? '', 10);
      if (!owner || !repo || !Number.isFinite(prNumber)) return;
      setNav(prev => ({
        view: 'repo',
        owner,
        repo,
        prNumber,
        initialTab: 'pulls',
        diffCommitSha: params.sha || undefined,
        back: prev,
      }));
    });
    return unsub;
  }, []);

  useEffect(() => {
    const unsub = window.bridge.onFullScreenChange(({ isFullScreen }) => setFullScreen(isFullScreen));
    // Recover the initial state in case the main process's did-finish-load
    // push fired before this listener was registered (cold start launched
    // straight into fullscreen, slow first React render, etc.) — without
    // this, the topbar's brand mark stays hidden even though the window is
    // fullscreen.
    void window.bridge.getFullScreenState().then(setFullScreen).catch(() => {});
    return unsub;
  }, []);

  useEffect(() => {
    const check = async () => {
      if (!window.bridge) {
        setFatal('window.bridge is undefined — preload script did not load.');
        return;
      }
      try {
        const has = await window.bridge.hasPat();
        setStatus(has ? 'ready' : 'needs-pat');
      } catch (e) {
        setFatal(e instanceof Error ? e.message : String(e));
      }
    };
    void check();
  }, []);

  // Computed (and its hook) unconditionally, ahead of the early returns
  // below — calling useWorkspaceNav after a conditional return would
  // change the hook count between renders and crash the whole app.
  const inWorkspaceFlow = nav.view === 'workspace'
    || nav.view === 'thread-detail' || nav.view === 'task-brain'
    || nav.view === 'stage-detail' || nav.view === 'task-code';
  // The viewed thread's own workspace wins once resolved (e.g. by
  // TrunkRoute) — a thread opened from outside the workspace flow (a PR's
  // linked-task chip, footprint resume) shows ITS workspace's rail instead
  // of whatever workspace the landing grid was last pointed at.
  const sidebarWorkspaceId = inWorkspaceFlow
    ? (viewedThreadWorkspaceId ?? activeWorkspaceId) : null;
  const { activeWorkspace: sidebarWorkspace } = useWorkspaceNav(sidebarWorkspaceId);

  if (fatal) {
    return (
      <div style={{ padding: '2rem', color: '#b00020' }}>
        <h2>Startup error</h2>
        <pre style={{ whiteSpace: 'pre-wrap' }}>{fatal}</pre>
      </div>
    );
  }

  if (status === 'checking') {
    return (
      <div className="app-loading">
        <LogoLoading size={88} />
      </div>
    );
  }

  // First-run: no PAT yet — branded onboarding screen with the
  // animated logo, welcome copy, and a single PAT field.
  if (status === 'needs-pat') {
    return (
      <OnboardingScreen
        onSaved={() => {
          setNav({ view: 'home' });
          setStatus('ready');
        }}
      />
    );
  }

  // Ready: global app shell with the workspace-model left nav.
  const selectedThreadId = currentThreadId;
  const sidebarActiveNav: WsNavKey | undefined = (() => {
    switch (nav.view) {
      case 'home': return 'home';
      case 'workspaces-landing': return 'workspaces';
      case 'workspace': return workspaceSectionNav(nav.section ?? 'today');
      case 'thread-detail': case 'task-brain': case 'stage-detail': case 'task-code': return 'trunks';
      case 'pulls': return 'pulls';
      case 'repos': case 'repository': case 'local-repo': return 'repos';
      case 'email': return 'email';
      case 'notifications': return 'notifications';
      case 'settings': return 'settings';
      default: return undefined;
    }
  })();

  // Task-owned full-page surfaces render their own sidebar, so the global
  // workspace rail steps aside for them. Cast to a plain string so this alias
  // doesn't narrow `nav` inside the rail JSX.
  const ownsTaskSidebar = (nav.view as string) === 'stage-detail'
    || (nav.view as string) === 'task-brain';

  const openAgentReview = (target: AgentReviewNavTarget) => {
    setActiveWorkspaceId(target.workspaceId);
    writeActiveWorkspaceId(target.workspaceId);
    if (target.taskId !== null) {
      setNav({
        view: 'task-brain',
        threadId: target.threadId,
        taskId: target.taskId,
        initialReviewRoundId: target.roundId,
      });
      return;
    }
    setNav({
      view: 'workspace',
      section: 'pull-requests',
      prNumber: target.prNumber,
      agentColumn: true,
    });
  };

  const usesWorkspaceRedesign = nav.view === 'workspaces-landing'
    || nav.view === 'workspace'
    || nav.view === 'thread-detail'
    || nav.view === 'task-brain'
    || nav.view === 'stage-detail'
    || nav.view === 'task-code';
  const resolvingLegacyRepo = nav.view === 'repos' || nav.view === 'repo'
    || nav.view === 'repository' || nav.view === 'local-repo';

  return (
    <div className={`app-shell${usesWorkspaceRedesign ? ' workspace-redesign' : ''}`}>
      <div className="app-window-drag-region" aria-hidden="true" />
      {!hideTopbar && !ownsTaskSidebar && (
        <WorkspaceNavShell
          activeWorkspaceId={sidebarWorkspaceId}
          selectedThreadId={selectedThreadId}
          /* The selected thread expands to show ALL its tasks (a thread can
             run several at once). Stage rows are intentionally omitted — the
             sidebar is a session tree (threads → tasks only); stage navigation
             lives on the task brain's live-plan diagram (design decision #3). */
          tasks={inWorkspaceFlow ? threadTasks : []}
          selectedTaskId={navTaskId}
          activeNav={sidebarActiveNav}
          notificationCount={unreadNotificationCount}
          collapsed={railCollapsed}
          onToggleCollapse={() => setRailCollapsed(c => !c)}
          onBack={goBack}
          onForward={goForward}
          backEnabled={canGoBack(navHistoryRef.current)}
          forwardEnabled={canGoForward(navHistoryRef.current)}
          onResumeVisit={stop => resumeStop(stop, {
            openPrKanban: () => setNav({ view: 'pulls' }),
            openPr: (owner, repo, prNumber) =>
              setNav({ view: 'repo', owner, repo, prNumber, back: { view: 'home' } }),
            openTask: (threadId, taskId) => setNav(lastTaskNav(threadId, taskId)),
            openThread,
          })}
          onOpenPr={(owner, repo, prNumber) =>
            setNav({ view: 'repo', owner, repo, prNumber, back: { view: 'home' } })}
          onNavigate={key => {
            switch (key) {
              case 'home': setNav({ view: 'home' }); break;
              case 'workspaces':
                // Always the all-workspaces landing — jumping into the
                // last-worked workspace on a second click read as
                // unpredictable. The workspace itself is one click away
                // on its landing card.
                setNav({ view: 'workspaces-landing' });
                break;
              case 'pulls': setNav({ view: 'pulls' }); break;
              case 'automations': break; // no Automations surface yet
              case 'repos': setNav({ view: 'repos' }); break;
              case 'email': setNav({ view: 'email' }); break;
              case 'notifications':
                setNav(sidebarWorkspaceId === null
                  ? { view: 'notifications' }
                  : { view: 'workspace', section: 'notifications' });
                break;
              case 'settings':
                setNav(sidebarWorkspaceId === null
                  ? { view: 'settings' }
                  : { view: 'workspace', section: 'settings', settingsSection: 'agents' });
                break;
              case 'today': setNav({ view: 'workspace', section: 'today' }); break;
              case 'trunks': setNav({ view: 'workspace', section: 'trunks' }); break;
              case 'pull-requests': setNav({ view: 'workspace', section: 'pull-requests' }); break;
              case 'issues': setNav({ view: 'workspace', section: 'issues' }); break;
              case 'backlog': setNav({ view: 'workspace', section: 'backlog' }); break;
              case 'branches': setNav({ view: 'workspace', section: 'branches' }); break;
              case 'commits': setNav({ view: 'workspace', section: 'commits' }); break;
              case 'sessions': setNav({ view: 'workspace', section: 'sessions' }); break;
              case 'memory': setNav({ view: 'workspace', section: 'memory' }); break;
              case 'insights': setNav({ view: 'workspace', section: 'insights' }); break;
            }
          }}
          onOpenThread={openThread}
          onOpenTask={taskId => {
            if (navThreadId !== null) {
              setNav(lastTaskNav(navThreadId, taskId));
            }
          }}
          // Clicking the workspace card stays in the workspace (from a
          // thread/task it returns to its surface; on it, a no-op).
          // Switching workspaces happens on the Workspaces landing page.
          onSwitchWorkspace={() => setNav({ view: 'workspaces-landing' })}
          onNewThread={() => setNewThreadDialogOpen(true)}
        />
      )}
      <div className="app-content">
        <RouteErrorBoundary
          resetKey={JSON.stringify(nav)}
          onReset={() => setNav({ view: 'home' })}
        >
        {resolvingLegacyRepo && (
          <div className="app-loading"><LogoLoading size={56} /></div>
        )}
        {nav.view === 'home' && (
          <HomePage
            onSelectRepo={(owner, repo, prNumber) =>
              // Specific PR → deep-link straight to the PR detail page.
              // Bare repo click → land on the unified repository home,
              // which now owns the per-repo overview surface.
              prNumber != null
                ? setNav({ view: 'repo', owner, repo, prNumber, back: { view: 'home' } })
                : setNav({ view: 'repository', owner, repo })}
            onOpenWorkspacePr={(workspaceId, prNumber) => {
              setActiveWorkspaceId(workspaceId);
              writeActiveWorkspaceId(workspaceId);
              setNav({ view: 'workspace', section: 'pull-requests', prNumber });
            }}
            onOpenRemoteReview={(owner, repo, prNumber) => setNav({
              view: 'pulls', initialPr: { repo: `${owner}/${repo}`, number: prNumber },
            })}
            onGoToMyPrs={() => setNav({ view: 'pulls' })}
            onOpenTeam={(teamId) => setNav({ view: 'team', teamId })}
            onGoToTeams={() => setNav({ view: 'teams' })}
            onOpenTask={(threadId, taskId) => setNav(lastTaskNav(threadId, taskId))}
            onOpenNotifications={() => setNav({ view: 'notifications' })}
          />
        )}
        {nav.view === 'pulls' && (
          <PullsScreen
            initialPr={nav.initialPr}
            onOpenWorkspacePr={(repo, prNumber, opts) => {
              // Same repo → workspace resolution as the legacy-repo redirect
              // effect: the workspace whose repository fullName matches.
              const fullName = repo.toLowerCase();
              void window.bridge.listWorkspaces()
                .then(cards => {
                  const workspace = cards.find(card =>
                    card.repository?.fullName.toLowerCase() === fullName);
                  if (workspace === undefined) return;
                  setActiveWorkspaceId(workspace.id);
                  writeActiveWorkspaceId(workspace.id);
                  setNav({
                    view: 'workspace',
                    section: 'pull-requests',
                    prNumber,
                    agentColumn: opts.agent || undefined,
                  });
                })
                .catch(() => { /* transient; the click is a no-op on failure */ });
            }}
          />
        )}
        {nav.view === 'email' && (
          <EmailPage
            onOpenIntegrationsSettings={() => setNav({ view: 'settings', section: 'integrations' })}
            onOpenLinkedRef={ref => {
              if (ref.kind === 'COMMIT') {
                // No in-app commit-detail route yet — open the
                // github.com commit page in the system browser.
                void window.bridge.openExternal(ref.url);
                return;
              }
              const number = parseInt(ref.slug, 10);
              setNav({
                view: 'repo',
                owner: ref.owner,
                repo: ref.repo,
                prNumber: ref.kind === 'PR' && Number.isFinite(number) ? number : undefined,
                initialTab: ref.kind === 'PR' ? 'pulls' : 'issues',
                back: nav,
              });
            }}
          />
        )}
        {nav.view === 'thread-create' && activeWorkspaceId && (
          <ThreadCreatePage
            workspaceId={activeWorkspaceId}
            initialGroupId={nav.initialGroupId ?? null}
            onBack={() => setNav({
              view: 'workspace', section: 'threads',
              threadsGroupId: nav.initialGroupId,
            })}
            // Created from inside a group → land back on the group
            // view so the new tile shows up next to its siblings.
            // Created standalone → drop the user on the single-thread
            // detail page so they can babysit the run directly.
            onCreated={threadId => setNav(nav.initialGroupId !== undefined
              ? { view: 'workspace', section: 'threads', threadsGroupId: nav.initialGroupId }
              : { view: 'thread-detail', threadId })}
          />
        )}
        {nav.view === 'thread-detail' && (
          <TrunkRoute
            threadId={nav.threadId}
            onOpenTask={taskId => setNav(lastTaskNav(nav.threadId, taskId))}
            onWorkspaceResolved={setViewedThreadWorkspaceId}
          />
        )}
        {nav.view === 'task-brain' && (
          <TaskBrainRoute
            threadId={nav.threadId}
            taskId={nav.taskId}
            initialReviewRoundId={nav.initialReviewRoundId}
            onOpenStage={stageId => setNav({
              view: 'stage-detail', threadId: nav.threadId, taskId: nav.taskId, stageId,
            })}
            onOpenCode={() => setNav({
              view: 'task-code', threadId: nav.threadId, taskId: nav.taskId, back: nav,
            })}
            onOpenRun={openRun(nav.threadId, nav.taskId)}
            onClosed={() => setNav({ view: 'thread-detail', threadId: nav.threadId })}
            onBack={() => setNav({ view: 'thread-detail', threadId: nav.threadId })}
            onForward={goForward}
            backEnabled={canGoBack(navHistoryRef.current)}
            forwardEnabled={canGoForward(navHistoryRef.current)}
          />
        )}
        {nav.view === 'task-code' && (
          <TaskCodePage
            threadId={nav.threadId}
            taskId={nav.taskId}
            stageId={nav.back?.view === 'stage-detail' ? nav.back.stageId : undefined}
            onBack={() => setNav(nav.back ?? {
              view: 'task-brain', threadId: nav.threadId, taskId: nav.taskId,
            })}
          />
        )}
        {nav.view === 'stage-detail' && (
          <StageDetailRoute
            taskId={nav.taskId}
            stageId={nav.stageId}
            threadId={nav.threadId}
            onOpenCode={() => setNav({
              view: 'task-code', threadId: nav.threadId, taskId: nav.taskId, back: nav,
            })}
            onOpenStage={stageId => setNav({
              view: 'stage-detail', threadId: nav.threadId, taskId: nav.taskId, stageId,
            })}
            onOpenRun={openRun(nav.threadId, nav.taskId)}
            onBack={() => setNav({ view: 'thread-detail', threadId: nav.threadId })}
            onForward={goForward}
            backEnabled={canGoBack(navHistoryRef.current)}
            forwardEnabled={canGoForward(navHistoryRef.current)}
            onOpenBrain={() => setNav({
              view: 'task-brain', threadId: nav.threadId, taskId: nav.taskId,
            })}
            onOpenAgentReview={roundId => setNav({
              view: 'task-brain', threadId: nav.threadId, taskId: nav.taskId,
              initialReviewRoundId: roundId,
            })}
          />
        )}
        {nav.view === 'notifications' && (
          <NotificationsScreen
            onOpenThread={openThread}
            onOpenReviewThread={threadId => setNav({
              view: 'review-thread', threadId, back: nav,
            })}
          />
        )}
        {nav.view === 'review-thread' && (
          <ReviewThreadPage
            threadId={nav.threadId}
            onBack={() => setNav(nav.back ?? { view: 'home' })}
            onOpenPr={(owner, repo, prNumber) => setNav({
              view: 'repo', owner, repo, prNumber, back: nav,
            })}
            onOpenDiff={(owner, repo, prNumber) => setNav({
              view: 'repo', owner, repo, prNumber, openDiff: true, back: nav,
            })}
          />
        )}
        {nav.view === 'workspaces-landing' && (
          <WorkspacesLandingPage
            currentWorkspaceId={activeWorkspaceId}
            onEnterWorkspace={(id) => {
              setActiveWorkspaceId(id);
              writeActiveWorkspaceId(id);
              setNav({ view: 'workspace', section: 'today' });
            }}
          />
        )}
        {nav.view === 'workspace' && activeWorkspaceId && (
          <WorkspaceShell
            section={nav.section ?? 'today'}
            workspaceId={activeWorkspaceId}
            prNumber={nav.prNumber}
            agentColumn={nav.agentColumn}
            issueNumber={nav.issueNumber}
            branchName={nav.branchName}
            sessionId={nav.sessionId}
            backlogKey={nav.backlogKey}
            settingsSection={nav.settingsSection}
            onSelectSettingsSection={settingsSection => setNav({
              view: 'workspace', section: 'settings', settingsSection,
            })}
            onOpenSession={sessionId => setNav({
              view: 'workspace', section: 'sessions', sessionId,
            })}
            onOpenBacklog={backlogKey => setNav({
              view: 'workspace', section: 'backlog', backlogKey,
            })}
            onWorkspaceCreated={(newId) => {
              // A workspace created from the inline dialog becomes the
              // active one immediately so the user lands in its empty
              // home rather than the previous workspace's data.
              setActiveWorkspaceId(newId);
              writeActiveWorkspaceId(newId);
              setNav({ view: 'workspace', section: 'today' });
            }}
            onSelectSection={section => setNav({ view: 'workspace', section })}
            onOpenThread={openThread}
            onOpenThreadCreate={(params) => setNav({
              view: 'thread-create',
              initialGroupId: params?.initialGroupId,
            })}
            onOpenControlBar={() => setControlBarOpen(true)}
            onOpenWorkspaceSwitcher={() => setNav({ view: 'workspaces-landing' })}
            // The workspace Threads section reads & writes the four
            // URL-ish filter fields off the workspace nav so a deep
            // link or a back-button keeps the user's view.
            threadsFilter={nav.threadsFilter ?? 'ALL'}
            threadsProvider={nav.threadsProvider ?? null}
            threadsGroupId={nav.threadsGroupId ?? null}
            threadsRepo={nav.threadsRepo ?? null}
            onThreadsFilterChange={filter => setNav({
              view: 'workspace', section: 'threads',
              threadsFilter: filter,
              threadsProvider: nav.threadsProvider,
              threadsGroupId: nav.threadsGroupId,
              threadsRepo: nav.threadsRepo,
            })}
            onThreadsProviderChange={provider => setNav({
              view: 'workspace', section: 'threads',
              threadsProvider: provider,
              threadsGroupId: nav.threadsGroupId,
              threadsRepo: nav.threadsRepo,
            })}
            onThreadsGroupChange={groupId => setNav({
              view: 'workspace', section: 'threads',
              threadsGroupId: groupId ?? undefined,
              threadsRepo: nav.threadsRepo,
            })}
            onThreadsRepoChange={repo => setNav({
              view: 'workspace', section: 'threads',
              threadsRepo: repo ?? undefined,
            })}
            onOpenPr={prNumber => setNav({
              view: 'workspace', section: 'pull-requests', prNumber,
            })}
            onOpenIssue={issueNumber => setNav({
              view: 'workspace', section: 'issues', issueNumber,
            })}
            onOpenBranch={branchName => setNav({
              view: 'workspace', section: 'branches', branchName,
            })}
            onOpenSettings={() => setNav({ view: 'settings', section: 'integrations' })}
            immersive={threadsImmersive}
            onChangeImmersive={setThreadsImmersive}
            hideRail
          />
        )}
        {nav.view === 'teams' && (
          <TeamsManagePage
            onOpenTeam={(teamId) => setNav({ view: 'team', teamId })}
            onBack={() => setNav({ view: 'pulls' })}
          />
        )}
        {nav.view === 'team' && (
          <TeamHomePage
            teamId={nav.teamId}
            onOpenKanban={() => setNav({ view: 'team-kanban', teamId: nav.teamId })}
            onSelectPr={(owner, repo, prNumber) => setNav({ view: 'repo', owner, repo, prNumber, back: nav })}
            onBack={() => setNav({ view: 'teams' })}
          />
        )}
        {nav.view === 'team-kanban' && (
          <TeamDetailPage
            teamId={nav.teamId}
            onBack={() => setNav({ view: 'team', teamId: nav.teamId })}
            onOpenLocalBranch={(owner, repo, branch) =>
              setNav({ view: 'local-repo', owner, repo, initialBranch: branch })}
            onStartReview={openStartedReview}
            onOpenAgentReview={openAgentReview}
            workspaceId={activeWorkspaceId}
          />
        )}
        {nav.view === 'settings' && (
          <SettingsShell
            section={nav.section ?? 'account'}
            workspaceId={activeWorkspaceId}
            onSelectSection={(section) => setNav({ view: 'settings', section })}
            onOpenTeam={(teamId) => setNav({ view: 'team', teamId })}
            onOpenThread={openThread}
            onClearPat={async () => {
              await window.bridge.clearPat();
              setNav({ view: 'home' });
              setStatus('needs-pat');
            }}
          />
        )}
        </RouteErrorBoundary>
      </div>
      {inAppUrl && (
        <InAppBrowser url={inAppUrl} onClose={() => setInAppUrl(null)} fullScreen={fullScreen} />
      )}
      {newThreadDialogOpen && sidebarWorkspaceId !== null && (
        <NewThreadDialog
          workspaceId={sidebarWorkspaceId}
          workspaceName={sidebarWorkspace?.name ?? 'Workspace'}
          onClose={() => setNewThreadDialogOpen(false)}
          onCreated={(threadId) => {
            setNewThreadDialogOpen(false);
            openThread(threadId);
          }}
        />
      )}
      <ControlBar
        open={controlBarOpen}
        onClose={() => setControlBarOpen(false)}
        onDispatch={handleControlDispatch}
        contextTags={contextTags}
      />
      <FindBar open={findOpen} onClose={() => setFindOpen(false)} />
      <Ds4StatusWidget
        hidden={fullScreen}
        onOpenManagement={() => setNav({ view: 'settings', section: 'local-ai' })}
      />
      <WorkspaceCreationToasts
        onOpenWorkspace={workspaceId => {
          setActiveWorkspaceId(workspaceId);
          writeActiveWorkspaceId(workspaceId);
          setNav({ view: 'workspace', section: 'today' });
        }}
      />
    </div>
  );
}

export default App;
