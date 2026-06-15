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
import { Component, useCallback, useEffect, useState, type ErrorInfo, type ReactNode } from 'react';
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
import ThreadTrunkPage from './threads/ThreadTrunkPage';
import TaskDetailPage from './threads/TaskDetailPage';
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

type Status = 'checking' | 'needs-pat' | 'ready';
type Nav =
  | { view: 'home' }
  | { view: 'my-prs' }
  /** `back` carries the parent screen so the PR-detail breadcrumb
   *  returns the user where they came from — Repository home, Local
   *  repo, Team kanban, or just Home. Defaults to Home when unset. */
  | { view: 'repo'; owner: string; repo: string; prNumber?: number; initialTab?: 'pulls' | 'issues'; diffCommitSha?: string; back?: Nav }
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

type GlobalTopbarProps = {
  nav: Nav;
  onNav: (nav: Nav) => void;
  /** True while the main window is in macOS native fullscreen — the
   *  inset traffic lights vanish in that state, so we draw a small
   *  brand mark in the otherwise-empty 78px reserve. */
  fullScreen: boolean;
  /** Unread-notification count rendered as a badge on the
   *  Notifications nav button. Zero hides the badge. */
  unreadNotificationCount: number;
};

/**
 * Static, animation-free version of the LogoOnboarding mark, sized
 * for the topbar. Lives here rather than in its own component because
 * the topbar is the only consumer and the SVG is small enough that a
 * separate file would be more friction than reuse.
 */
function TopbarBrandMark({ size = 30 }: { size?: number }) {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 100 100"
      width={size}
      height={size}
      role="img"
      aria-label="ByteQuay"
    >
      <rect x="10" y="10" width="80" height="80" rx="14" fill="#7C3AED" />
      <path d="M 10 68 Q 28 60 42 68 T 72 68 L 90 68 L 90 90 L 10 90 Z" fill="#8B5CF6" />
      <line x1="30" y1="26" x2="42" y2="54" stroke="#FFFFFF" strokeWidth="4" strokeLinecap="round" />
      <line x1="44" y1="26" x2="56" y2="54" stroke="#FFFFFF" strokeWidth="4" strokeLinecap="round" opacity="0.7" />
      <line x1="58" y1="26" x2="70" y2="54" stroke="#FFFFFF" strokeWidth="4" strokeLinecap="round" />
    </svg>
  );
}

const BYTEQUAY_REPO_URL = 'https://github.com/chenjian2664/bytequay';

/**
 * Canonical GitHub octocat mark. Inlined as currentColor so it picks
 * up the wordmark slot's text color and adapts to theme tokens.
 */
function GithubMark({ size = 18 }: { size?: number }) {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 16 16"
      width={size}
      height={size}
      role="img"
      aria-label="ByteQuay on GitHub"
      fill="currentColor"
    >
      <path
        fillRule="evenodd"
        clipRule="evenodd"
        d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82a7.66 7.66 0 014 0c1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z"
      />
    </svg>
  );
}

/** Friendly label for the breadcrumb's "← parent" target. Returns
 *  null for repo-style parents (Repository, etc.) so the caller can
 *  fall back to the owner/repo string for those — that's the
 *  established muscle memory. */
function breadcrumbLabel(back: Nav | undefined): string | null {
  if (!back) return null;
  switch (back.view) {
    case 'email': return 'Email';
    case 'workspace': return back.section === 'threads' ? 'Threads' : 'Workspace';
    case 'home': return 'Home';
    case 'my-prs': return 'My PRs';
    case 'notifications': return 'Notifications';
    case 'teams': return 'Teams';
    case 'repos': return 'Repos';
    default: return null;
  }
}

function GlobalTopbar({ nav, onNav, fullScreen, unreadNotificationCount }: GlobalTopbarProps) {
  return (
    <div className={`global-topbar${fullScreen ? ' global-topbar--fullscreen' : ''}`}>
      {fullScreen && (
        <button
          className="global-topbar__mark"
          onClick={() => onNav({ view: 'home' })}
          title="Home"
          type="button"
        >
          <TopbarBrandMark />
        </button>
      )}
      <div className="global-topbar__left">
        {/* Plain href (no target="_blank") so main.ts's will-navigate
            handler intercepts the click and opens the in-app browser
            overlay — keeps the user inside the app shell with a × to
            close. The wordmark used to route to Home; the right-side
            "Home" nav button still does that, and in fullscreen the
            traffic-light brand mark also routes home. */}
        <a
          className="global-topbar__brand global-topbar__brand--btn"
          href={BYTEQUAY_REPO_URL}
          title="ByteQuay on GitHub"
        >
          <GithubMark />
        </a>
        {nav.view === 'repo' && (
          <button
            className="global-topbar__breadcrumb"
            onClick={() => onNav(nav.back ?? { view: 'home' })}
          >
            ← {breadcrumbLabel(nav.back) ?? `${nav.owner}/${nav.repo}`}
          </button>
        )}
        {nav.view === 'thread-create' && (
          <button
            className="global-topbar__breadcrumb"
            onClick={() => onNav({
              view: 'workspace', section: 'threads',
              threadsGroupId: nav.initialGroupId,
            })}
            title="Back to threads (Esc)"
          >
            ← Threads
          </button>
        )}
        {nav.view === 'workspace' && nav.section === 'threads'
            && nav.threadsGroupId !== undefined && (
          <button
            className="global-topbar__breadcrumb"
            onClick={() => onNav({ view: 'workspace', section: 'threads' })}
            title="Back to all threads"
          >
            ← All threads
          </button>
        )}
        {/* Portal target: child screens (e.g. PullRequestList) mount
            their own context-back button here via createPortal so it
            sits next to the brand/breadcrumb without forcing the page
            state up into App. */}
        <div id="global-topbar-extra" className="global-topbar__extra" />
      </div>
      <nav className="global-topbar__nav">
        <button
          className={`global-nav-btn${nav.view === 'home' ? ' global-nav-btn--active' : ''}`}
          onClick={() => onNav({ view: 'home' })}
        >
          Home
        </button>
        <button
          className={`global-nav-btn${
            nav.view === 'workspace' || nav.view === 'workspaces-landing'
              ? ' global-nav-btn--active'
              : ''}`}
          onClick={() => onNav({ view: 'workspaces-landing' })}
          title="Workspaces — pick a project brain to drop into"
        >
          Workspace
        </button>
        <button
          className={`global-nav-btn${nav.view === 'my-prs' ? ' global-nav-btn--active' : ''}`}
          onClick={() => onNav({ view: 'my-prs' })}
        >
          Pull requests
        </button>
        <button
          className={`global-nav-btn${nav.view === 'repos' ? ' global-nav-btn--active' : ''}`}
          onClick={() => onNav({ view: 'repos' })}
          title="Local repos"
        >
          Repos
        </button>
        <button
          className={`global-nav-btn${nav.view === 'email' ? ' global-nav-btn--active' : ''}`}
          onClick={() => onNav({ view: 'email' })}
          title="Email"
        >
          Email
        </button>
        <button
          className={`global-nav-btn${nav.view === 'notifications' ? ' global-nav-btn--active' : ''}`}
          onClick={() => onNav({ view: 'notifications' })}
          title="Notifications"
        >
          Notifications
          {unreadNotificationCount > 0 && (
            <span className="global-nav-btn__badge" aria-label={`${unreadNotificationCount} unread`}>
              {unreadNotificationCount > 99 ? '99+' : unreadNotificationCount}
            </span>
          )}
        </button>
        <button
          className={`global-nav-btn${nav.view === 'settings' ? ' global-nav-btn--active' : ''}`}
          onClick={() => onNav({ view: 'settings' })}
        >
          Settings
        </button>
      </nav>
    </div>
  );
}

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

function App() {
  const [status, setStatus] = useState<Status>('checking');
  const [nav, setNav] = useState<Nav>({ view: 'home' });

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

  // Ready: global app shell with persistent topbar
  return (
    <div className="app-shell">
      {!hideTopbar && (
        <GlobalTopbar
          nav={nav}
          onNav={setNav}
          fullScreen={fullScreen}
          unreadNotificationCount={unreadNotificationCount}
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
          />
        )}
        {nav.view === 'my-prs' && (
          <PullRequestList
            onGoToTeams={() => setNav({ view: 'teams' })}
            onOpenLocalBranch={(owner, repo, branch) =>
              setNav({ view: 'local-repo', owner, repo, initialBranch: branch })}
            onOpenSettings={() => setNav({ view: 'settings' })}
          />
        )}
        {nav.view === 'repo' && (
          <RepoDetailPage
            owner={nav.owner}
            repo={nav.repo}
            initialPrNumber={nav.prNumber}
            initialTab={nav.initialTab}
            initialDiffCommitSha={nav.diffCommitSha}
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
        {nav.view === 'thread-detail' && nav.taskId === undefined && (
          <ThreadTrunkPage
            threadId={nav.threadId}
            onBack={() => setNav({ view: 'workspace', section: 'threads' })}
            onOpenTask={taskId => setNav({
              view: 'thread-detail', threadId: nav.threadId, taskId,
            })}
          />
        )}
        {nav.view === 'thread-detail' && nav.taskId !== undefined && (
          <TaskDetailPage
            threadId={nav.threadId}
            taskId={nav.taskId}
            onBackToTrunk={() => setNav({
              view: 'thread-detail', threadId: nav.threadId,
            })}
            onOpenPr={(owner, repo, prNumber) => setNav({
              view: 'repo', owner, repo, prNumber, back: nav,
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
