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
import ThreadDetailPage from './threads/ThreadDetailPage';
import { TrunkRoute } from './pages/TrunkRoute';
import { WorkspaceNavShell } from './pages/WorkspaceNavShell';
import { useThreadTasks } from './pages/useThreadTasks';
import type { WsNavKey } from './ui/workspace';
import { TaskBrainRoute } from './pages/TaskBrainRoute';
import { StageDetailRoute } from './pages/StageDetailRoute';
import TaskCodePage from './threads/TaskCodePage';
import WorkspaceShell, { type WorkspaceSection } from './workspace/WorkspaceShell';
import WorkspacesLandingPage from './workspace/WorkspacesLandingPage';
import type {
  StatusFilter as ThreadsStatusFilter,
  ProviderFilter as ThreadsProviderFilter,
  RepoFilter as ThreadsRepoFilter,
} from './threads/ThreadsLeftRail';
import type { SettingsSection } from './settings/types';
import PullRequestList from './PullRequestList';
import HomePage from './HomePage';
import RepoDetailPage from './RepoDetailPage';
import ReposPage from './repos/ReposPage';
import RepositoryPage from './repos/RepositoryPage';
import LocalRepoPage from './repos/LocalRepoPage';
import InAppBrowser from './InAppBrowser';
import LogoLoading from './LogoLoading';
import OnboardingScreen from './OnboardingScreen';
import { applyTheme, loadTheme } from './themes';
import { useSurfaceVisitCapture } from './footprints/useSurfaceVisitCapture';

type Status = 'checking' | 'needs-pat' | 'ready';
export type Nav =
  | { view: 'home' }
  | { view: 'my-prs' }
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
  | { view: 'task-brain'; threadId: string; taskId: string }
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

function clearActiveWorkspaceId(): void {
  try { window.localStorage.removeItem(ACTIVE_WORKSPACE_STORAGE_KEY); }
  catch { /* private browsing — skip silently */ }
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
  const [status, setStatus] = useState<Status>('checking');
  const [nav, setNav] = useState<Nav>({ view: 'home' });
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

  // Open a thread, routing review threads (flow=REVIEW) to the panel
  // page and everything else to the trunk. A review thread exposes a
  // review pass via getReviewPassByThread; build/trunk threads return
  // null, so they fall through to the regular thread-detail view. Keeps
  // the assign-review dialog, PR-row jumps, and the rail all landing on
  // the right surface without each caller needing to know the flow.
  const openThread = (threadId: string) => {
    const back = nav;
    void window.bridge.getReviewPassByThread(threadId)
      .then(pass => setNav(pass !== null
        ? { view: 'review-thread', threadId, back }
        : { view: 'thread-detail', threadId }))
      .catch(() => setNav({ view: 'thread-detail', threadId }));
  };
  /** Which workspace the user last entered. Drives the CURRENT chip
   *  on the landing grid. Set when the user picks a card. */
  const [activeWorkspaceId, setActiveWorkspaceId] = useState<string | null>(
    () => readActiveWorkspaceId());
  const [fatal, setFatal] = useState<string | null>(null);
  /** Phase-9 control bar. ⌘K opens it; ControlBar's onDispatch
   *  routes a typed payload back here into setNav. Keeps the bar
   *  free of nav state knowledge. */
  const [controlBarOpen, setControlBarOpen] = useState(false);
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
      if (e.key === 'n' || e.key === 'N') {
        // ⌘N = new thread. Routes through the existing thread-create
        // page; the workspace shell's NewThreadDialog can also open
        // it via its onContinueFullForm hand-off, so behaviour stays
        // consistent across surfaces.
        e.preventDefault();
        setNav({ view: 'thread-create' });
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, []);

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
      case 'my-prs':         return [{ label: 'pull-requests', kind: 'scope' }];
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
      case 'nav.pull-requests':   setNav({ view: 'my-prs' }); break;
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
  const inWorkspaceFlow = nav.view === 'workspace'
    || nav.view === 'thread-detail' || nav.view === 'task-brain'
    || nav.view === 'stage-detail' || nav.view === 'task-code';
  const sidebarWorkspaceId = inWorkspaceFlow ? activeWorkspaceId : null;
  const selectedThreadId = 'threadId' in nav ? nav.threadId : undefined;
  const sidebarActiveNav: WsNavKey | undefined = (() => {
    switch (nav.view) {
      case 'home': return 'home';
      case 'workspaces-landing': case 'workspace': return 'workspaces';
      case 'thread-detail': case 'task-brain': case 'stage-detail': case 'task-code': return 'workspaces';
      case 'my-prs': return 'my-work';
      case 'repos': case 'repository': case 'local-repo': return 'repos';
      case 'email': return 'email';
      case 'notifications': return 'notifications';
      case 'settings': return 'settings';
      default: return undefined;
    }
  })();

  // Stage / brain pages render their own task-scoped sidebar (the live-plan
  // diagram), so the global workspace rail steps aside for them. Cast to a
  // plain string so this alias doesn't narrow `nav` inside the rail JSX.
  const ownsTaskSidebar = (nav.view as string) === 'stage-detail'
    || (nav.view as string) === 'task-brain';

  return (
    <div className="app-shell">
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
          footer={{
            initials: 'CJ',
            name: 'You',
            onSettings: () => setNav({ view: 'settings' }),
            onChat: () => setNav({ view: 'notifications' }),
          }}
          onNavigate={key => {
            switch (key) {
              case 'home': setNav({ view: 'home' }); break;
              case 'workspaces':
                // Up one level: from inside a thread/task, return to the active
                // workspace's surface (its thread list / memory / insights) in
                // one click, rather than bouncing out to the all-workspaces
                // landing and forcing the user to re-enter. Only go to the
                // landing when already on the workspace surface (or there's no
                // active workspace).
                setNav(activeWorkspaceId !== null && nav.view !== 'workspace'
                  ? { view: 'workspace', section: 'threads' }
                  : { view: 'workspaces-landing' });
                break;
              case 'my-work': setNav({ view: 'my-prs' }); break;
              case 'automations': break; // no Automations surface yet
              case 'repos': setNav({ view: 'repos' }); break;
              case 'email': setNav({ view: 'email' }); break;
              case 'notifications': setNav({ view: 'notifications' }); break;
              case 'settings': setNav({ view: 'settings' }); break;
            }
          }}
          onEnterWorkspace={id => {
            setActiveWorkspaceId(id);
            writeActiveWorkspaceId(id);
            setNav({ view: 'workspace', section: 'threads' });
          }}
          onOpenThread={threadId => setNav({ view: 'thread-detail', threadId })}
          onOpenTask={taskId => {
            if (navThreadId !== null) {
              setNav(lastTaskNav(navThreadId, taskId));
            }
          }}
          onSwitchWorkspace={() => setNav(nav.view !== 'workspace'
            ? { view: 'workspace', section: 'threads' }  // from a thread/task → into the workspace
            : { view: 'workspaces-landing' })}            // already on it → the overview, to switch

          onNewWorkspace={() => setNav({ view: 'workspaces-landing' })}
          onNewThread={() => setNav({ view: 'thread-create' })}
          onDeleteWorkspace={(id, name) => {
            const ok = window.confirm(
              `Delete workspace "${name}" and everything in it?\n\n`
              + 'This permanently removes its threads, tasks, messages, '
              + 'backlog, and worktrees, and stops any running agents. '
              + 'This cannot be undone.');
            if (!ok) return;
            void window.bridge.deleteWorkspace(id)
              .then(() => {
                // Dropping the active workspace: clear the selection so the app
                // falls back to the overview rather than a now-dead id. The
                // polled rail removes the row on its own.
                if (activeWorkspaceId === id) {
                  setActiveWorkspaceId(null);
                  clearActiveWorkspaceId();
                  setNav({ view: 'workspaces-landing' });
                }
              })
              .catch((e: unknown) => {
                window.alert(`Couldn't delete workspace: ${e instanceof Error ? e.message : String(e)}`);
              });
          }}
        />
      )}
      <div className="app-content">
        <RouteErrorBoundary
          resetKey={JSON.stringify(nav)}
          onReset={() => setNav({ view: 'home' })}
        >
        {nav.view === 'home' && (
          <HomePage
            onSelectRepo={(owner, repo, prNumber) =>
              // Specific PR → deep-link straight to the PR detail page.
              // Bare repo click → land on the unified repository home,
              // which now owns the per-repo overview surface.
              prNumber != null
                ? setNav({ view: 'repo', owner, repo, prNumber, back: { view: 'home' } })
                : setNav({ view: 'repository', owner, repo })}
            onGoToMyPrs={() => setNav({ view: 'my-prs' })}
            onOpenTeam={(teamId) => setNav({ view: 'team', teamId })}
            onGoToTeams={() => setNav({ view: 'teams' })}
            onOpenTask={(threadId, taskId) => setNav(lastTaskNav(threadId, taskId))}
            onOpenThread={openThread}
          />
        )}
        {nav.view === 'my-prs' && (
          <PullRequestList
            onGoToTeams={() => setNav({ view: 'teams' })}
            onOpenLocalBranch={(owner, repo, branch) =>
              setNav({ view: 'local-repo', owner, repo, initialBranch: branch })}
            onOpenSettings={() => setNav({ view: 'settings' })}
            onStartReview={threadId => setNav({
              view: 'review-thread', threadId, back: nav,
            })}
            workspaceId={activeWorkspaceId}
          />
        )}
        {nav.view === 'repo' && (
          <RepoDetailPage
            owner={nav.owner}
            repo={nav.repo}
            initialPrNumber={nav.prNumber}
            initialTab={nav.initialTab}
            initialDiffCommitSha={nav.diffCommitSha}
            initialOpenDiff={nav.openDiff}
            onOpenLocalBranch={(owner, repo, branch) =>
              setNav({ view: 'local-repo', owner, repo, initialBranch: branch })}
            // PR → thread jump. The linked-thread chip in the PR header
            // calls this; we preserve the current repo nav as the
            // back target so closing the thread can return cleanly.
            onOpenThread={openThread}
            // The AI panel-review button on a PR row routes the user
            // to the new review thread; we preserve the repo nav as
            // the back target so the user can return.
            onStartReview={threadId => setNav({
              view: 'review-thread', threadId, back: nav,
            })}
            workspaceId={activeWorkspaceId}
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
        {nav.view === 'thread-create' && (
          <ThreadCreatePage
            workspaceId={activeWorkspaceId ?? 'ws-default'}
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
          />
        )}
        {nav.view === 'task-brain' && (
          <TaskBrainRoute
            threadId={nav.threadId}
            taskId={nav.taskId}
            onOpenStage={stageId => setNav({
              view: 'stage-detail', threadId: nav.threadId, taskId: nav.taskId, stageId,
            })}
            onOpenCode={() => setNav({
              view: 'task-code', threadId: nav.threadId, taskId: nav.taskId, back: nav,
            })}
            onClosed={() => setNav({ view: 'thread-detail', threadId: nav.threadId })}
            onBack={() => setNav({ view: 'thread-detail', threadId: nav.threadId })}
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
            onBack={() => setNav({ view: 'thread-detail', threadId: nav.threadId })}
            onOpenBrain={() => setNav({
              view: 'task-brain', threadId: nav.threadId, taskId: nav.taskId,
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
              setNav({ view: 'workspace', section: 'home' });
            }}
          />
        )}
        {nav.view === 'workspace' && (
          <WorkspaceShell
            section={nav.section ?? 'home'}
            workspaceId={activeWorkspaceId ?? 'ws-default'}
            onWorkspaceCreated={(newId) => {
              // A workspace created from the inline dialog becomes the
              // active one immediately so the user lands in its empty
              // home rather than the previous workspace's data.
              setActiveWorkspaceId(newId);
              writeActiveWorkspaceId(newId);
              setNav({ view: 'workspace', section: 'home' });
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
            onOpenPr={(owner, repo, prNumber) => setNav({
              view: 'repo', owner, repo, prNumber,
              back: {
                view: 'workspace', section: 'threads',
                threadsGroupId: nav.threadsGroupId,
                threadsFilter: nav.threadsFilter,
                threadsProvider: nav.threadsProvider,
                threadsRepo: nav.threadsRepo,
              },
            })}
            onOpenIssues={(owner, repo) => setNav({
              view: 'repo', owner, repo, initialTab: 'issues',
              back: {
                view: 'workspace', section: 'threads',
                threadsGroupId: nav.threadsGroupId,
                threadsFilter: nav.threadsFilter,
                threadsProvider: nav.threadsProvider,
                threadsRepo: nav.threadsRepo,
              },
            })}
            onOpenSettings={() => setNav({ view: 'settings', section: 'integrations' })}
            immersive={threadsImmersive}
            onChangeImmersive={setThreadsImmersive}
            hideRail
          />
        )}
        {nav.view === 'repos' && (
          <ReposPage
            onSelectRepo={(owner, repo) => setNav({ view: 'repository', owner, repo })}
          />
        )}
        {nav.view === 'repository' && (
          <RepositoryPage
            owner={nav.owner}
            repo={nav.repo}
            onBack={() => setNav({ view: 'repos' })}
            onOpenPrs={(owner, repo) => setNav({ view: 'repo', owner, repo, initialTab: 'pulls', back: nav })}
            onOpenIssues={(owner, repo) => setNav({ view: 'repo', owner, repo, initialTab: 'issues', back: nav })}
            onOpenBranches={(owner, repo) => setNav({ view: 'local-repo', owner, repo })}
            onSelectPr={(owner, repo, prNumber) => setNav({ view: 'repo', owner, repo, prNumber, back: nav })}
          />
        )}
        {nav.view === 'local-repo' && (
          <LocalRepoPage
            owner={nav.owner}
            repo={nav.repo}
            // Back-target is the repository page (the repo's
            // overview/PRs/issues hub) rather than the repos list —
            // that's the natural parent now and keeps the
            // breadcrumb chain short. Repos list is still one tab
            // away on the topbar for users who want to jump out.
            onBack={() => setNav({ view: 'repository', owner: nav.owner, repo: nav.repo })}
            onSelectPr={(owner, repo, prNumber) => setNav({ view: 'repo', owner, repo, prNumber, back: nav })}
            initialBranch={nav.initialBranch}
          />
        )}
        {nav.view === 'teams' && (
          <TeamsManagePage
            onOpenTeam={(teamId) => setNav({ view: 'team', teamId })}
            onBack={() => setNav({ view: 'my-prs' })}
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
            onStartReview={threadId => setNav({
              view: 'review-thread', threadId, back: nav,
            })}
            workspaceId={activeWorkspaceId}
          />
        )}
        {nav.view === 'settings' && (
          <SettingsShell
            section={nav.section ?? 'account'}
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
      <ControlBar
        open={controlBarOpen}
        onClose={() => setControlBarOpen(false)}
        onDispatch={handleControlDispatch}
        contextTags={contextTags}
      />
      <Ds4StatusWidget
        hidden={fullScreen}
        onOpenManagement={() => setNav({ view: 'settings', section: 'local-ai' })}
      />
    </div>
  );
}

export default App;
