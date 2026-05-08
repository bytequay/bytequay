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
import { Fragment, useEffect, useState, type KeyboardEvent as ReactKeyboardEvent, type MouseEvent as ReactMouseEvent } from 'react';
import type { LocalActivityEntryDto, LocalBranchDto, LocalCommitDto, LocalCommitFileDto, LocalFileDiffDto, LocalMergeBaseDto, LocalRepoStatusDto } from '../types';
import LogoLoading from '../LogoLoading';
import { formatRelativeTime } from '../pr/utils';
import { DiffFileTreePane } from '../diff/DiffFileTreePane';
import { statusBadgeFromLetter } from '../diffStatusBadge';
import { unionCommitFiles } from '../diff/unionCommitFiles';
import { formatShortSha } from '../diff/commitDisplay';

type Props = {
  owner: string;
  repo: string;
  onBack: () => void;
  /** Open the PR detail page (RepoDetailPage with focused PR) when
   *  the user clicks a branch's PR pill. App-level so we reuse the
   *  same nav target as the PR list flow. */
  onSelectPr?: (owner: string, repo: string, prNumber: number) => void;
  /** When set, the page opens directly on the Commits tab with this
   *  branch's history shown — used for the reverse nav from a PR
   *  detail page back to its head branch. Falls through the normal
   *  remote-only fallback (origin/<name>) if the branch isn't
   *  checked out locally. */
  initialBranch?: string;
};

type Column = 'LOCAL_WORK' | 'READY_FOR_PR' | 'IN_REVIEW' | 'CLEAN_UP';
type Tab = 'branches' | 'commits' | 'activity';

const COLUMNS: { key: Column; label: string; subtitle: string }[] = [
  {
    key: 'LOCAL_WORK',
    label: 'Local work',
    subtitle: 'No upstream — never pushed',
  },
  {
    key: 'READY_FOR_PR',
    label: 'Ready for PR',
    subtitle: 'Pushed, no PR open yet',
  },
  {
    key: 'IN_REVIEW',
    label: 'In review',
    subtitle: 'Open PRs targeting these branches',
  },
  {
    key: 'CLEAN_UP',
    label: 'Clean up',
    subtitle: 'Remote gone or never-pushed and idle',
  },
];

// How many branches to show per column before collapsing the rest
// behind a "Show N more" toggle. Same shape as the PR kanban so
// big repos don't blow out the viewport with hundreds of cards.
const COLLAPSED_LIMIT = 5;

const TABS: { key: Tab; label: string }[] = [
  { key: 'branches', label: 'Branches' },
  { key: 'commits', label: 'Commits' },
  { key: 'activity', label: 'Activity' },
];

/**
 * Repo detail page for a mapped local clone. The Branches tab carries
 * the kanban + action bar; Commits and Activity are skeletons until
 * their backend slices land (commit listing via `git log`, activity
 * stream from local event store).
 *
 * The IN REVIEW column will stay empty until the list-page sync starts
 * capturing PR head refs (deferred — see LocalRepoService.toLocalBranch).
 */
function LocalRepoPage({ owner, repo, onSelectPr, initialBranch }: Props) {
  const [status, setStatus] = useState<LocalRepoStatusDto | null>(null);
  const [branches, setBranches] = useState<LocalBranchDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  // Per-action busy state so each button can show its own spinner
  // without freezing the whole bar; the action bar disables all
  // buttons while any one is running so we don't fire concurrent
  // git ops in the same working tree.
  const [actionState, setActionState] = useState<'idle' | 'fetching' | 'pulling' | 'pushing' | 'branching' | 'switching' | 'creating-pr' | 'checking-out'>('idle');
  const [actionError, setActionError] = useState<string | null>(null);
  // Set when a push failed in a way that suggests force-with-lease
  // would resolve it (non-fast-forward / "updates were rejected").
  // Holds git's stderr so the modal can show the user exactly why.
  const [forcePushPrompt, setForcePushPrompt] = useState<string | null>(null);
  // The +Branch popover is small enough not to warrant a full modal —
  // it's an inline disclosure right under the action bar.
  const [branchFormOpen, setBranchFormOpen] = useState(false);
  const [newBranchName, setNewBranchName] = useState('');
  const [newBranchBase, setNewBranchBase] = useState('');
  const [tab, setTab] = useState<Tab>(
    // Reverse-nav from a PR detail page lands directly on the
    // Commits tab for the PR's head branch.
    initialBranch ? 'commits' : 'branches');
  // Branch the Commits tab is filtered to (null = use HEAD's
  // currentBranch). Card click in the Branches tab sets this and
  // switches the tab; the Commits tab reads it as its revisionKey.
  const [commitsBranch, setCommitsBranch] = useState<string | null>(initialBranch ?? null);
  // Names selected for bulk delete in the Clean up column. Lives on
  // the page rather than per-card so the modal can read the full set
  // and we can clear it after a successful delete.
  // Branch the user has tapped Delete on. Modal is up while non-null;
  // a successful delete clears it. Per-card affordance — bulk delete
  // was removed in favor of one-at-a-time confirms because pushed-
  // branch deletes shouldn't be a wholesale action.
  const [deleteTarget, setDeleteTarget] = useState<LocalBranchDto | null>(null);
  const [deleteBusy, setDeleteBusy] = useState(false);
  // Create-PR modal state. Open while the form is up; the form
  // owns title/body/base/draft and posts back through this page so
  // we can refresh the action bar and (eventually) the IN REVIEW
  // column without a navigation.
  const [createPrOpen, setCreatePrOpen] = useState(false);
  const [createPrResult, setCreatePrResult] = useState<{ number: number; htmlUrl: string } | null>(null);
  // Branch the user clicked to act on. null means "use whatever HEAD
  // currently is". Distinct from currentBranch so a click is purely
  // a UI selection — no git command fires until the user invokes an
  // action that actually needs HEAD on this branch (Push, Pull,
  // Create PR), at which point ByteQuay does `git switch` lazily.
  const [selectedBranch, setSelectedBranch] = useState<string | null>(null);
  // Keyboard-focused branch — distinct from selectedBranch so the
  // user can cycle through cards with j/k without committing the
  // selection until they hit Enter. Null = no keyboard cursor.
  const [focusedBranch, setFocusedBranch] = useState<string | null>(null);
  // Columns the user has expanded past the collapsed cap. Branches
  // beyond {@link COLLAPSED_LIMIT} are hidden until the user clicks
  // "Show N more" — same pattern as the PR kanban.
  const [expandedColumns, setExpandedColumns] = useState<Set<Column>>(() => new Set());

  const reload = async (signal?: { cancelled: boolean }) => {
    const [all, branchList] = await Promise.all([
      window.bridge.listLocalRepos(),
      window.bridge.listLocalBranches(owner, repo),
    ]);
    if (signal?.cancelled) return;
    const match = all.find(r => r.owner === owner && r.repo === repo);
    setStatus(match ?? null);
    setBranches(branchList);
  };

  useEffect(() => {
    const signal = { cancelled: false };
    setStatus(null);
    setBranches(null);
    setError(null);
    reload(signal).catch(e => {
      if (!signal.cancelled) setError(e instanceof Error ? e.message : String(e));
    });
    return () => { signal.cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [owner, repo]);

  // The "effective" branch every HEAD-dependent action targets. Defaults
  // to whatever HEAD currently is; a click on another branch card
  // overrides it without firing git.
  const effectiveBranch = selectedBranch ?? status?.currentBranch ?? null;
  const needsSwitch = selectedBranch !== null
      && status?.currentBranch !== undefined
      && selectedBranch !== status.currentBranch;

  // Switch HEAD to {@code overrideBranch} (or {@link selectedBranch}
  // when the override is null) when it differs from current. Throws
  // on failure so the surrounding try/catch surfaces git's stderr
  // (dirty tree, conflict, etc.) and the caller's action never runs.
  // After a successful switch we clear selectedBranch because HEAD
  // now equals what the user picked — they're back in sync, no
  // banner needed.
  const switchIfNeeded = async (overrideBranch?: string) => {
    const target = overrideBranch ?? selectedBranch;
    if (!target || target === status?.currentBranch) return;
    const fresh = await window.bridge.switchLocalBranch(owner, repo, target);
    setStatus(fresh);
    setSelectedBranch(null);
  };

  const runFetch = async () => {
    setActionState('fetching');
    setActionError(null);
    try {
      const fresh = await window.bridge.fetchLocalRepo(owner, repo);
      setStatus(fresh);
      // Fetch can change ahead/behind counts on every branch.
      const fresher = await window.bridge.listLocalBranches(owner, repo);
      setBranches(fresher);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : String(e));
    } finally {
      setActionState('idle');
    }
  };

  const runPull = async () => {
    setActionState('pulling');
    setActionError(null);
    try {
      await switchIfNeeded();
      const fresh = await window.bridge.pullLocalRepo(owner, repo);
      setStatus(fresh);
      const fresher = await window.bridge.listLocalBranches(owner, repo);
      setBranches(fresher);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : String(e));
    } finally {
      setActionState('idle');
    }
  };

  const runCheckoutRemote = async (name: string) => {
    setActionState('checking-out');
    setActionError(null);
    try {
      const fresh = await window.bridge.checkoutRemoteBranch(owner, repo, name);
      setStatus(fresh);
      const fresher = await window.bridge.listLocalBranches(owner, repo);
      setBranches(fresher);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : String(e));
    } finally {
      setActionState('idle');
    }
  };

  const runPush = async (overrideBranch?: string) => {
    setActionState('pushing');
    setActionError(null);
    try {
      await switchIfNeeded(overrideBranch);
      const fresh = await window.bridge.pushLocalRepo(owner, repo);
      setStatus(fresh);
      const fresher = await window.bridge.listLocalBranches(owner, repo);
      setBranches(fresher);
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      setActionError(msg);
      // git's "non-fast-forward" rejection surfaces with these
      // stderr fragments. When we see them, offer the force-push
      // path instead of leaving the user to copy-paste from a
      // terminal — that's the whole point of this affordance.
      if (looksLikeNonFastForward(msg)) {
        setForcePushPrompt(msg);
      }
    } finally {
      setActionState('idle');
    }
  };

  const openCreatePrForBranch = (name: string) => {
    // Per-card "Create PR" pre-selects the card's branch; the modal
    // then reads `effectiveBranch` and the existing lazy-switch
    // wires the rest. Same code path as click-card → click action-
    // bar Create PR, just collapsed into one click.
    setSelectedBranch(name === status?.currentBranch ? null : name);
    setCreatePrOpen(true);
  };

  const runForcePush = async () => {
    setActionState('pushing');
    setActionError(null);
    setForcePushPrompt(null);
    try {
      await switchIfNeeded();
      const fresh = await window.bridge.pushLocalRepoForce(owner, repo);
      setStatus(fresh);
      const fresher = await window.bridge.listLocalBranches(owner, repo);
      setBranches(fresher);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : String(e));
    } finally {
      setActionState('idle');
    }
  };

  // Open-in-X actions don't need a busy state — they fire-and-forget
  // out of process and finish in milliseconds. Errors (no IDE found,
  // bad path) get reported through the same actionError channel as
  // the git ops so the user sees feedback in one place.
  const runReveal = async () => {
    if (!status?.localClonePath) return;
    setActionError(null);
    try {
      await window.bridge.revealRepoInFinder(status.localClonePath);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : String(e));
    }
  };

  const runTerminal = async () => {
    if (!status?.localClonePath) return;
    setActionError(null);
    try {
      await window.bridge.openRepoInTerminal(status.localClonePath);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : String(e));
    }
  };

  const runIDE = async () => {
    if (!status?.localClonePath) return;
    setActionError(null);
    try {
      await window.bridge.openRepoInIDE(status.localClonePath);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : String(e));
    }
  };

  const runCreatePr = async (payload: { title: string; body: string; base: string; draft: boolean }) => {
    setActionState('creating-pr');
    setActionError(null);
    try {
      await switchIfNeeded();
      const result = await window.bridge.createLocalPullRequest(owner, repo, payload);
      setCreatePrResult(result);
      setCreatePrOpen(false);
      // Refresh the branches list so any IN REVIEW signal the
      // backend grows later picks up the new PR without a manual
      // reload.
      const fresher = await window.bridge.listLocalBranches(owner, repo);
      setBranches(fresher);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : String(e));
    } finally {
      setActionState('idle');
    }
  };

  const runSwitchBranch = async (name: string) => {
    if (actionState !== 'idle') return;
    setActionState('switching');
    setActionError(null);
    try {
      const fresh = await window.bridge.switchLocalBranch(owner, repo, name);
      setStatus(fresh);
      const fresher = await window.bridge.listLocalBranches(owner, repo);
      setBranches(fresher);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : String(e));
    } finally {
      setActionState('idle');
    }
  };

  const runCreateBranch = async () => {
    const name = newBranchName.trim();
    if (!name) return;
    setActionState('branching');
    setActionError(null);
    try {
      const base = newBranchBase.trim();
      const fresh = await window.bridge.createLocalBranch(owner, repo, name, base || undefined);
      setStatus(fresh);
      const fresher = await window.bridge.listLocalBranches(owner, repo);
      setBranches(fresher);
      setNewBranchName('');
      setNewBranchBase('');
      setBranchFormOpen(false);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : String(e));
    } finally {
      setActionState('idle');
    }
  };

  const grouped = groupByColumn(branches ?? []);

  // Branches in the order they render top-to-bottom across columns.
  // j/k cycles through this list — same visual order the user sees,
  // so the keyboard cursor moves predictably.
  const orderedBranchNames = COLUMNS.flatMap(col => grouped[col.key].map(b => b.name));

  // j/k navigation, Enter to commit selection, Esc to clear. Skip
  // when the user is typing (input/textarea/contentEditable) or
  // when the modal is up — we don't want to hijack keys mid-form.
  useEffect(() => {
    if (tab !== 'branches' || branches === null || branches.length === 0) return;
    if (createPrOpen || deleteTarget || forcePushPrompt || branchFormOpen) return;
    const handler = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement | null;
      if (target) {
        const tag = target.tagName;
        if (tag === 'INPUT' || tag === 'TEXTAREA' || target.isContentEditable) return;
      }
      if (e.metaKey || e.ctrlKey || e.altKey) return;
      if (e.key === 'j' || e.key === 'k') {
        e.preventDefault();
        const ordered = orderedBranchNames;
        if (ordered.length === 0) return;
        const cursor = focusedBranch ?? selectedBranch ?? status?.currentBranch ?? null;
        const idx = cursor ? ordered.indexOf(cursor) : -1;
        const step = e.key === 'j' ? 1 : -1;
        const nextIdx = idx < 0 ? (step > 0 ? 0 : ordered.length - 1)
                                : (idx + step + ordered.length) % ordered.length;
        setFocusedBranch(ordered[nextIdx]);
      }
      else if (e.key === 'Enter') {
        if (focusedBranch == null) return;
        e.preventDefault();
        // Same toggle semantics as a card click — match the click
        // handler exactly so the keyboard path can't end up in a
        // state the click path can't reach.
        setSelectedBranch(prev => {
          if (prev === focusedBranch) return null;
          if (status?.currentBranch === focusedBranch) return null;
          return focusedBranch;
        });
      }
      else if (e.key === 'Escape') {
        if (focusedBranch != null || selectedBranch != null) {
          e.preventDefault();
          setFocusedBranch(null);
          setSelectedBranch(null);
        }
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [tab, branches, orderedBranchNames, focusedBranch, selectedBranch,
      status?.currentBranch, createPrOpen, deleteTarget,
      forcePushPrompt, branchFormOpen]);

  // Auto-expand a column when the keyboard cursor lands on a card
  // that the collapsed view would hide. Otherwise j/k onto the 7th
  // branch of a column reads as "the keys did nothing" since the
  // card never renders.
  useEffect(() => {
    if (focusedBranch == null) return;
    for (const col of COLUMNS) {
      const rows = grouped[col.key];
      const idx = rows.findIndex(b => b.name === focusedBranch);
      if (idx >= 0 && idx >= COLLAPSED_LIMIT && !expandedColumns.has(col.key)) {
        setExpandedColumns(prev => {
          const next = new Set(prev);
          next.add(col.key);
          return next;
        });
        break;
      }
    }
  }, [focusedBranch, grouped, expandedColumns]);

  // Scroll the keyboard-focused card into view. The kanban can be
  // taller than the viewport on big repos; without this, j/k off-
  // screen feels like the keys aren't doing anything.
  useEffect(() => {
    if (focusedBranch == null) return;
    const el = document.querySelector(
        `[data-branch-name="${CSS.escape(focusedBranch)}"]`) as HTMLElement | null;
    el?.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
  }, [focusedBranch]);

  const toggleColumnExpanded = (key: Column) => {
    setExpandedColumns(prev => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  const runDeleteBranch = async (name: string, deleteRemote: boolean) => {
    setDeleteBusy(true);
    setActionError(null);
    try {
      const deleted = await window.bridge.deleteLocalBranches(
          owner, repo, [name], deleteRemote);
      if (deleted.length === 0) {
        // Server refused (typically because the user tried to delete
        // the current branch via a stale UI). Surface the no-op so
        // the modal doesn't silently close on success-looking nothing.
        setActionError(`Branch ${name} could not be deleted (still HEAD?)`);
      }
      const fresher = await window.bridge.listLocalBranches(owner, repo);
      setBranches(fresher);
      setDeleteTarget(null);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : String(e));
    } finally {
      setDeleteBusy(false);
    }
  };

  return (
    <div className="local-repo-page">
      <header className="local-repo-page__head">
        <div className="local-repo-page__heading">
          <div className="local-repo-page__title-row">
            <h1 className="local-repo-page__title">
              <span className="local-repo-page__owner">{owner}/</span>
              <span className="local-repo-page__repo">{repo}</span>
            </h1>
            {status?.currentBranch && (
              <code className="local-repo-page__head-chip">
                ⎇ {status.currentBranch}
              </code>
            )}
            {status?.dirtyFileCount != null && status.dirtyFileCount > 0 && (
              <span className="local-repo-page__dirty">
                {status.dirtyFileCount} modified
              </span>
            )}
          </div>
          {status?.localClonePath && (
            <div className="local-repo-page__head-meta">
              <div className="local-repo-page__path" title={status.localClonePath}>
                {status.localClonePath}
              </div>
              <div className="local-repo-page__remote-info">
                {status.upstreamRemoteName ? (
                  <span>
                    <code>origin</code> = your fork ·{' '}
                    <code>{status.upstreamRemoteName}</code> = {owner}/{repo}
                  </span>
                ) : (
                  <span>
                    <code>origin</code> = {owner}/{repo}
                  </span>
                )}
              </div>
            </div>
          )}
        </div>
        {needsSwitch && selectedBranch && (
          <div className="local-repo-page__action-target">
            <span>
              Acting on <code>{selectedBranch}</code>
              {status?.currentBranch && (
                <> — Push / Pull / Create PR will switch from{' '}
                <code>{status.currentBranch}</code> first.</>
              )}
            </span>
            <button
              type="button"
              className="local-repo-page__action-target-clear"
              onClick={() => setSelectedBranch(null)}
            >
              Use current HEAD
            </button>
          </div>
        )}
        <div className="local-repo-page__actions">
          <button
            type="button"
            className="button button--secondary button--sm"
            onClick={() => { void runFetch(); }}
            disabled={actionState !== 'idle' || !status?.localClonePath}
            title="git fetch --all --prune"
          >
            {actionState === 'fetching' ? 'Fetching…' : '↓ Fetch'}
          </button>
          <button
            type="button"
            className="button button--secondary button--sm"
            onClick={() => { void runPull(); }}
            disabled={actionState !== 'idle' || !status?.localClonePath}
            title="git pull --ff-only on the current branch"
          >
            {actionState === 'pulling' ? 'Pulling…' : '↓ Pull'}
          </button>
          <button
            type="button"
            className="button button--secondary button--sm"
            onClick={() => { void runPush(); }}
            disabled={actionState !== 'idle' || !status?.localClonePath}
            title="git push the current branch (auto-sets tracking on first push)"
          >
            {actionState === 'pushing' ? 'Pushing…' : '↑ Push'}
          </button>
          <button
            type="button"
            className="button button--secondary button--sm"
            onClick={() => setBranchFormOpen(v => !v)}
            disabled={actionState !== 'idle' || !status?.localClonePath}
            title="Create a new branch and switch to it"
          >
            + Branch
          </button>
          <span className="local-repo-page__actions-spacer" aria-hidden="true" />
          <button
            type="button"
            className="button button--secondary button--sm"
            onClick={() => { void runReveal(); }}
            disabled={!status?.localClonePath}
            title="Reveal the working-tree folder in Finder"
          >
            Finder
          </button>
          <button
            type="button"
            className="button button--secondary button--sm"
            onClick={() => { void runTerminal(); }}
            disabled={!status?.localClonePath}
            title="Open the repo in iTerm (falls back to Terminal)"
          >
            Terminal
          </button>
          <button
            type="button"
            className="button button--secondary button--sm"
            onClick={() => { void runIDE(); }}
            disabled={!status?.localClonePath}
            title="Open in VS Code / Cursor / JetBrains (first one installed)"
          >
            IDE
          </button>
        </div>
        {branchFormOpen && (
          <div className="local-repo-page__branch-form">
            <div className="local-repo-page__branch-form-row">
              <label>
                <span>Name</span>
                <input
                  type="text"
                  value={newBranchName}
                  onChange={(e) => setNewBranchName(e.target.value)}
                  placeholder="feat/my-change"
                  disabled={actionState === 'branching'}
                  autoFocus
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') { e.preventDefault(); void runCreateBranch(); }
                    if (e.key === 'Escape') { setBranchFormOpen(false); }
                  }}
                />
              </label>
              <label>
                <span>From (optional)</span>
                <input
                  type="text"
                  value={newBranchBase}
                  onChange={(e) => setNewBranchBase(e.target.value)}
                  placeholder={status?.currentBranch ?? 'current HEAD'}
                  disabled={actionState === 'branching'}
                  list="local-repo-page__branch-bases"
                />
                {/* Autocomplete out of the existing branch list — saves
                    the user typing common starting points like
                    upstream/master. */}
                <datalist id="local-repo-page__branch-bases">
                  {branches?.map(b => (
                    <option key={b.name} value={b.name} />
                  ))}
                </datalist>
              </label>
              <div className="local-repo-page__branch-form-actions">
                <button
                  type="button"
                  className="button button--secondary button--sm"
                  onClick={() => { setBranchFormOpen(false); setNewBranchName(''); setNewBranchBase(''); }}
                  disabled={actionState === 'branching'}
                >
                  Cancel
                </button>
                <button
                  type="button"
                  className="button button--primary button--sm"
                  onClick={() => { void runCreateBranch(); }}
                  disabled={actionState === 'branching' || !newBranchName.trim()}
                >
                  {actionState === 'branching' ? 'Creating…' : 'Create'}
                </button>
              </div>
            </div>
          </div>
        )}
        {actionError && (
          <div className="local-repo-page__action-error">{actionError}</div>
        )}
      </header>

      <nav className="local-repo-page__tabs" role="tablist" aria-label="Repo views">
        {TABS.map(t => (
          <button
            key={t.key}
            type="button"
            role="tab"
            aria-selected={tab === t.key}
            className={`local-repo-page__tab${tab === t.key ? ' local-repo-page__tab--active' : ''}`}
            onClick={() => setTab(t.key)}
          >
            {t.label}
          </button>
        ))}
      </nav>

      {tab === 'branches' && (
        <>
          {error && (
            <div className="local-repo-page__error">
              Couldn't load branches: {error}
            </div>
          )}

          {branches === null && !error && (
            <div className="local-repo-page__loading">
              <LogoLoading size={48} label="Loading branches" />
            </div>
          )}

          {branches !== null && branches.length > 0 && (
            <div className="branches-kanban__hint" aria-hidden="true">
              <kbd>j</kbd>/<kbd>k</kbd> cycle · <kbd>Enter</kbd> select · <kbd>Esc</kbd> clear
            </div>
          )}

          {branches !== null && (
            <div className="branches-kanban">
              {COLUMNS.map(col => (
                <BranchColumn
                  key={col.key}
                  label={col.label}
                  subtitle={col.subtitle}
                  column={col.key}
                  branches={grouped[col.key]}
                  onSwitchBranch={runSwitchBranch}
                  switching={actionState === 'switching'}
                  selectedActionBranch={selectedBranch}
                  focusedBranch={focusedBranch}
                  currentBranch={status?.currentBranch ?? null}
                  expanded={expandedColumns.has(col.key)}
                  collapsedLimit={COLLAPSED_LIMIT}
                  onToggleExpanded={() => toggleColumnExpanded(col.key)}
                  onSelectForAction={(name) => {
                    // Card click does two things: navigate to the
                    // Commits tab filtered to this branch (the
                    // primary action — see the v2 mockup), and keep
                    // the lazy "act on this branch" selection for
                    // keyboard nav / Create-PR's lazy-switch banner.
                    setCommitsBranch(name);
                    setTab('commits');
                    setSelectedBranch(prev => {
                      if (prev === name) return null;
                      if (status?.currentBranch === name) return null;
                      return name;
                    });
                  }}
                  onDeleteBranch={setDeleteTarget}
                  onPushBranch={col.key === 'LOCAL_WORK'
                    ? (name) => { void runPush(name); }
                    : undefined}
                  onCreatePrForBranch={col.key === 'READY_FOR_PR'
                    ? (name) => openCreatePrForBranch(name)
                    : undefined}
                  onCheckoutRemote={col.key === 'IN_REVIEW'
                    ? (name) => { void runCheckoutRemote(name); }
                    : undefined}
                  onSelectPr={onSelectPr
                    ? (prNumber) => onSelectPr(owner, repo, prNumber)
                    : undefined}
                />
              ))}
            </div>
          )}
        </>
      )}

      {createPrOpen && effectiveBranch && (
        <CreatePrModal
          owner={owner}
          repo={repo}
          headBranch={effectiveBranch}
          forkBased={!!status?.upstreamRemoteName}
          busy={actionState === 'creating-pr'}
          branches={branches ?? []}
          defaultBranch={status?.defaultBranch ?? null}
          willSwitchFrom={needsSwitch ? status?.currentBranch ?? null : null}
          onCancel={() => setCreatePrOpen(false)}
          onSubmit={runCreatePr}
        />
      )}

      {createPrResult && (
        <CreatePrSuccessToast
          owner={owner}
          repo={repo}
          number={createPrResult.number}
          htmlUrl={createPrResult.htmlUrl}
          onDismiss={() => setCreatePrResult(null)}
        />
      )}

      {deleteTarget && (
        <DeleteBranchModal
          owner={owner}
          repo={repo}
          branch={deleteTarget}
          busy={deleteBusy}
          onCancel={() => setDeleteTarget(null)}
          onConfirm={(deleteRemote) => runDeleteBranch(deleteTarget.name, deleteRemote)}
        />
      )}

      {forcePushPrompt && (
        <ForcePushModal
          owner={owner}
          repo={repo}
          branch={status?.currentBranch}
          stderr={forcePushPrompt}
          busy={actionState === 'pushing'}
          onCancel={() => setForcePushPrompt(null)}
          onConfirm={runForcePush}
        />
      )}

      {tab === 'commits' && (
        <CommitsTab
          owner={owner}
          repo={repo}
          // commitsBranch is set by branch-card click; falls back to
          // HEAD's currentBranch when the user landed on this tab
          // directly. Refetches when either changes.
          revisionKey={commitsBranch ?? status?.currentBranch ?? ''}
        />
      )}
      {tab === 'activity' && <ActivityTab owner={owner} repo={repo} />}
    </div>
  );
}

function looksLikeNonFastForward(stderr: string): boolean {
  // git's non-fast-forward rejection looks like:
  //   ! [rejected]        feat -> feat (non-fast-forward)
  //   error: failed to push some refs to ...
  //   hint: Updates were rejected because the tip of your current branch is behind
  // We match on a couple of stable substrings rather than the full
  // banner so locale changes / version drift don't break detection.
  const lower = stderr.toLowerCase();
  return lower.includes('non-fast-forward')
      || lower.includes('updates were rejected')
      || lower.includes('failed to push some refs');
}

function ForcePushModal({
  owner,
  repo,
  branch,
  stderr,
  busy,
  onCancel,
  onConfirm,
}: {
  owner: string;
  repo: string;
  branch: string | null | undefined;
  stderr: string;
  busy: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  return (
    <div className="force-push-modal" role="dialog" aria-modal="true">
      <div className="force-push-modal__backdrop" onClick={busy ? undefined : onCancel} />
      <div className="force-push-modal__panel">
        <h2 className="force-push-modal__title">Force push with lease?</h2>
        <p className="force-push-modal__body">
          Pushing <code>{branch ?? 'HEAD'}</code> to{' '}
          <code>{owner}/{repo}</code> was rejected because the local
          branch isn't a fast-forward. ByteQuay can retry with{' '}
          <code>--force-with-lease</code>: that overwrites the remote
          tip <strong>only if it still matches what you last fetched</strong>,
          so you won't accidentally clobber a teammate's commit.
        </p>
        <pre className="force-push-modal__stderr">{stderr}</pre>
        <p className="force-push-modal__warning">
          This rewrites remote history for this branch. Anyone who
          already pulled the old tip will need to re-sync.
        </p>
        <div className="force-push-modal__actions">
          <button
            type="button"
            className="button button--secondary button--sm"
            onClick={onCancel}
            disabled={busy}
          >
            Cancel
          </button>
          <button
            type="button"
            className="button button--danger button--sm"
            onClick={onConfirm}
            disabled={busy}
          >
            {busy ? 'Pushing…' : 'Force push with lease'}
          </button>
        </div>
      </div>
    </div>
  );
}

/**
 * Three-pane Commits tab matching docs/mockups/design/local-repo/code-diff.png.
 * Left: commits on `revisionKey`. Middle: files for the selected
 * commit. Right: per-file unified diff. Selecting a commit auto-
 * picks its first file so the right pane never sits blank when
 * there's something to show.
 */
function CommitsTab({
  owner,
  repo,
  revisionKey,
}: {
  owner: string;
  repo: string;
  revisionKey: string;
}) {
  const [commits, setCommits] = useState<LocalCommitDto[] | null>(null);
  const [commitsError, setCommitsError] = useState<string | null>(null);
  // Multi-commit selection. Plain click replaces; ⌘/Ctrl+click toggles.
  // Default once commits load is the tip (newest), so the panes have
  // something to render on first paint.
  const [selectedShas, setSelectedShas] = useState<ReadonlySet<string>>(new Set());
  const [files, setFiles] = useState<LocalCommitFileDto[] | null>(null);
  const [filesError, setFilesError] = useState<string | null>(null);
  const [selectedFile, setSelectedFile] = useState<string | null>(null);
  const [diff, setDiff] = useState<LocalFileDiffDto | null>(null);
  const [diffError, setDiffError] = useState<string | null>(null);
  const [collapsedDirs, setCollapsedDirs] = useState<ReadonlySet<string>>(new Set());
  const [mergeBase, setMergeBase] = useState<LocalMergeBaseDto | null>(null);
  const toggleDir = (path: string) =>
    setCollapsedDirs((prev) => {
      const next = new Set(prev);
      if (next.has(path)) next.delete(path); else next.add(path);
      return next;
    });
  const onCommitClick = (sha: string, additive: boolean) => {
    if (additive) {
      setSelectedShas((prev) => {
        const next = new Set(prev);
        if (next.has(sha)) next.delete(sha); else next.add(sha);
        // Disallow empty selection — falls back to the clicked sha so
        // the panes always have data to show. (Empty == cumulative
        // since branch-point lands in the merge-base slice.)
        if (next.size === 0) next.add(sha);
        return next;
      });
    } else {
      setSelectedShas(new Set([sha]));
    }
  };

  // Reload commits when the branch under inspection changes; reset
  // the per-commit/file state so the right two panes don't show
  // stale content from the previous branch.
  useEffect(() => {
    let cancelled = false;
    setCommits(null);
    setCommitsError(null);
    setSelectedShas(new Set());
    setFiles(null);
    setSelectedFile(null);
    setDiff(null);
    setMergeBase(null);
    const rev = revisionKey || undefined;
    window.bridge.listLocalCommits(owner, repo, rev, 100)
      .then(rows => {
        if (cancelled) return;
        setCommits(rows);
        // Auto-select the tip so the panes have data on first paint —
        // matches the mockup which shows a commit pre-selected.
        if (rows.length > 0) setSelectedShas(new Set([rows[0].sha]));
      })
      .catch(e => { if (!cancelled) setCommitsError(e instanceof Error ? e.message : String(e)); });
    // Best-effort merge-base lookup. We don't surface fetch errors —
    // the divider is a nice-to-have. When it fails (HEAD-only repo,
    // single-commit branch, base unresolvable) we just don't render
    // it. Only meaningful when the user is on a non-default branch.
    if (rev) {
      window.bridge.getLocalMergeBase(owner, repo, rev)
        .then((mb) => { if (!cancelled) setMergeBase(mb); })
        .catch(() => { /* swallow — divider is optional */ });
    }
    return () => { cancelled = true; };
  }, [owner, repo, revisionKey]);

  // Files across the selected commits — single fetch when one is
  // selected, parallel fetch + union when multiple. Auto-picks the
  // first file so the diff pane has something to show.
  useEffect(() => {
    if (selectedShas.size === 0) return;
    let cancelled = false;
    setFiles(null);
    setFilesError(null);
    setSelectedFile(null);
    setDiff(null);
    // Order shas chronologically (oldest → newest) so the latest
    // occurrence wins when unionCommitFiles merges. The list comes
    // back newest-first from git log; reverse + filter by selection.
    const ordered = (commits ?? [])
      .map((c) => c.sha)
      .filter((sha) => selectedShas.has(sha))
      .reverse();
    Promise.all(ordered.map((sha) => window.bridge.listLocalCommitFiles(owner, repo, sha)))
      .then((perCommit) => {
        if (cancelled) return;
        const merged = unionCommitFiles(perCommit, (f) => f.path);
        setFiles(merged);
        if (merged.length > 0) setSelectedFile(merged[0].path);
      })
      .catch((e) => { if (!cancelled) setFilesError(e instanceof Error ? e.message : String(e)); });
    return () => { cancelled = true; };
  }, [owner, repo, selectedShas, commits]);

  // Per-file diff. Single selection → that commit's patch via
  // `git show`. Multiple selection → range diff via
  // `git diff <oldest>^..<newest>` so the user sees the COMBINED
  // changes across the selection, not just the newest commit's
  // changes. Sparse selections (gaps) over-include the un-selected
  // middle commits — git can't produce a true "just-these-commits"
  // diff, and most users select contiguous ranges anyway.
  const ordered = (commits ?? []).filter((c) => selectedShas.has(c.sha));
  const newestSelectedSha = ordered[0]?.sha ?? null;       // commits is newest-first
  const oldestSelectedSha = ordered[ordered.length - 1]?.sha ?? null;
  useEffect(() => {
    if (newestSelectedSha == null || oldestSelectedSha == null || selectedFile == null) return;
    let cancelled = false;
    setDiff(null);
    setDiffError(null);
    const fetchDiff = newestSelectedSha === oldestSelectedSha
      ? window.bridge.getLocalCommitDiff(owner, repo, newestSelectedSha, selectedFile)
      : window.bridge.getLocalCommitRangeDiff(
          owner, repo, oldestSelectedSha, newestSelectedSha, selectedFile);
    fetchDiff
      .then(d => { if (!cancelled) setDiff(d); })
      .catch(e => { if (!cancelled) setDiffError(e instanceof Error ? e.message : String(e)); });
    return () => { cancelled = true; };
  }, [owner, repo, oldestSelectedSha, newestSelectedSha, selectedFile]);

  if (commitsError) {
    return (
      <div className="local-repo-page__error">
        Couldn't load commits: {commitsError}
      </div>
    );
  }
  if (commits === null) {
    return (
      <div className="local-repo-page__loading">
        <LogoLoading size={48} label="Loading commits" />
      </div>
    );
  }
  if (commits.length === 0) {
    return (
      <div className="local-repo-tab-placeholder">
        <div className="local-repo-tab-placeholder__title">No commits</div>
        <p className="local-repo-tab-placeholder__body">
          {revisionKey
            ? <>Branch <code>{revisionKey}</code> has no commits to show.</>
            : 'The current branch has no commits to show, or HEAD is detached and points at no history.'}
        </p>
      </div>
    );
  }
  return (
    <div className="commits-pane">
      <div className="commits-pane__header">
        <span className="commits-pane__label">Showing commits on</span>
        {' '}
        <code className="commits-pane__branch">{revisionKey || 'HEAD'}</code>
      </div>
      <div className="commits-pane__body">
        <aside className="commits-pane__commits">
          <div className="commits-pane__section-header">Commits</div>
          <ol className="commits-list">
            {commits.map(c => (
              <Fragment key={c.sha}>
                {mergeBase?.sha === c.sha && mergeBase.base && (
                  <li className="commits-list__divider" aria-hidden="true">
                    — {revisionKey} branched from <code>{mergeBase.base}</code> —
                  </li>
                )}
                <CommitRow
                  commit={c}
                  selected={selectedShas.has(c.sha)}
                  onClick={(additive) => onCommitClick(c.sha, additive)}
                />
              </Fragment>
            ))}
          </ol>
        </aside>
        <aside className="commits-pane__files">
          <div className="commits-pane__section-header">Files changed</div>
          <CommitsSelectionSummary
            commits={commits}
            selected={selectedShas}
            onClear={() => setSelectedShas(new Set([commits[0].sha]))}
          />
          <DiffFileTreePane
            files={files}
            error={filesError}
            mode="tree"
            pathOf={(f) => f.path}
            statusBadgeOf={(f) => statusBadgeFromLetter(f.status)}
            selectedPath={selectedFile}
            onSelectPath={setSelectedFile}
            collapsedDirs={collapsedDirs}
            onToggleDir={toggleDir}
          />
          {files !== null && files.length > 0 && (
            <CommitFilesTotal files={files} />
          )}
        </aside>
        <section className="commits-pane__diff">
          {diffError && (
            <div className="local-repo-page__error">{diffError}</div>
          )}
          {!diffError && diff === null && selectedFile != null && (
            <div className="commits-pane__placeholder">Loading diff…</div>
          )}
          {!diffError && diff === null && selectedFile == null && files !== null && files.length === 0 && (
            <div className="commits-pane__placeholder">Pick a commit to view its changes</div>
          )}
          {!diffError && diff !== null && (
            <CommitDiffViewer diff={diff} />
          )}
        </section>
      </div>
    </div>
  );
}

function CommitRow({
  commit,
  selected,
  onClick,
}: {
  commit: LocalCommitDto;
  selected: boolean;
  /** True when ⌘/Ctrl was held — caller treats it as additive
   *  (toggle this sha in/out of the selection); else replace. */
  onClick: (additive: boolean) => void;
}) {
  const handleClick = (e: ReactMouseEvent | ReactKeyboardEvent) => {
    onClick(e.metaKey || e.ctrlKey);
  };
  const [copied, setCopied] = useState(false);
  const handleCopy = (e: ReactMouseEvent) => {
    // Don't bubble to the row click handler — copying the sha is a
    // distinct intent from selecting the commit.
    e.stopPropagation();
    void navigator.clipboard.writeText(commit.sha);
    setCopied(true);
    window.setTimeout(() => setCopied(false), 1200);
  };
  return (
    <li
      className={`commit-row${selected ? ' commit-row--selected' : ''}`}
      onClick={handleClick}
      role="button"
      tabIndex={0}
      title="Click to select; ⌘/Ctrl-click to add to a multi-commit selection"
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          handleClick(e);
        }
      }}
    >
      <span
        className="commit-row__author-dot"
        style={{ background: authorColor(commit.authorEmail || commit.authorName) }}
        title={commit.authorName}
        aria-hidden="true"
      />
      <div className="commit-row__main">
        <div className="commit-row__subject">{commit.subject}</div>
        <div className="commit-row__meta">
          <span className="commit-row__author" title={commit.authorEmail}>
            @{commit.authorName}
          </span>
          <span className="commit-row__sep" aria-hidden="true">·</span>
          <code className="commit-row__sha" title={commit.sha}>{commit.shortSha}</code>
          <button
            type="button"
            className="commit-row__copy"
            onClick={handleCopy}
            title={copied ? 'Copied!' : `Copy full SHA (${commit.sha})`}
            aria-label="Copy full SHA"
          >
            {copied ? '✓' : '⎘'}
          </button>
          {commit.authoredAt && (
            <>
              <span className="commit-row__sep" aria-hidden="true">·</span>
              <span className="commit-row__time" title={commit.authoredAt}>
                {formatRelativeTime(commit.authoredAt)}
              </span>
            </>
          )}
        </div>
      </div>
    </li>
  );
}

/** Stable hash → 8-color palette for the author dot. We don't have
 *  GitHub user data on local commits (just author name + email from
 *  git's own ident), so a deterministic per-author tint gives the
 *  user a quick "same person again" cue without an avatar fetch. */
function authorColor(key: string): string {
  // 8-color palette tuned for both light and dark themes — moderate
  // saturation, ~50% lightness so the dot reads against either bg.
  const PALETTE = [
    '#1f6a57', // accent green
    '#cf6900', // amber
    '#1f6feb', // blue
    '#8a5cf5', // purple
    '#cf222e', // red
    '#1a7f37', // forest
    '#996600', // mustard
    '#0e8c8c', // teal
  ];
  let h = 0;
  for (let i = 0; i < key.length; i++) {
    h = ((h << 5) - h + key.charCodeAt(i)) | 0;
  }
  return PALETTE[Math.abs(h) % PALETTE.length];
}

function CommitsSelectionSummary({
  commits,
  selected,
  onClear,
}: {
  commits: LocalCommitDto[];
  selected: ReadonlySet<string>;
  onClear: () => void;
}) {
  if (selected.size === 0) return null;
  if (selected.size === 1) {
    const sha = [...selected][0];
    const c = commits.find((x) => x.sha === sha);
    return (
      <div className="commits-selection-summary">
        <span className="commits-selection-summary__icon" aria-hidden="true">⊞</span>
        <code className="commits-selection-summary__sha">{c?.shortSha ?? formatShortSha(sha)}</code>
        <span className="commits-selection-summary__subject">{c?.subject ?? ''}</span>
      </div>
    );
  }
  return (
    <div className="commits-selection-summary commits-selection-summary--multi">
      <span className="commits-selection-summary__icon" aria-hidden="true">⊞</span>
      <span className="commits-selection-summary__label">
        <b>{selected.size} of {commits.length} commits</b> selected — union diff
      </span>
      <button type="button" className="commits-selection-summary__clear" onClick={onClear}>
        Clear
      </button>
    </div>
  );
}

function CommitFilesTotal({ files }: { files: LocalCommitFileDto[] }) {
  let adds = 0;
  let dels = 0;
  let bin = 0;
  for (const f of files) {
    if (f.additions < 0 || f.deletions < 0) bin += 1;
    else { adds += f.additions; dels += f.deletions; }
  }
  return (
    <div className="commits-pane__files-total">
      Total <span className="commits-pane__add">+{adds}</span>{' '}
      <span className="commits-pane__del">-{dels}</span>{' '}
      in {files.length} file{files.length === 1 ? '' : 's'}
      {bin > 0 && <> ({bin} binary)</>}
    </div>
  );
}

function CommitDiffViewer({ diff }: { diff: LocalFileDiffDto }) {
  // Reuse the PR diff viewer's diff-hunk-line classes so colors and
  // theme overrides (atom-one-dark / warm) match across the two
  // diff surfaces. File headers (--- / +++) and the git "diff "/
  // "index " preamble fold into the same blue-tinted --head
  // treatment as @@ hunk markers.
  const lines = diff.patch.split('\n');
  return (
    <div className="commit-diff">
      <div className="commit-diff__header">
        <code className="commit-diff__path">{diff.path}</code>
        {diff.truncated && (
          <span className="commit-diff__truncated"
                title="Diff was capped server-side; the model only sees the prefix">
            truncated
          </span>
        )}
      </div>
      <pre className="commit-diff__body">
        <div className="commit-diff__body-inner">
        {lines.map((line, i) => {
          let cls = 'diff-hunk-line';
          if (line.startsWith('@@')
              || line.startsWith('+++') || line.startsWith('---')
              || line.startsWith('diff ') || line.startsWith('index ')) {
            cls += ' diff-hunk-line--head';
          }
          else if (line.startsWith('+')) cls += ' diff-hunk-line--add';
          else if (line.startsWith('-')) cls += ' diff-hunk-line--del';
          else cls += ' diff-hunk-line--ctx';
          return <div key={i} className={cls}>{line || ' '}</div>;
        })}
        </div>
      </pre>
    </div>
  );
}

function CreatePrModal({
  owner,
  repo,
  headBranch,
  forkBased,
  busy,
  branches,
  defaultBranch,
  willSwitchFrom,
  onCancel,
  onSubmit,
}: {
  owner: string;
  repo: string;
  headBranch: string;
  forkBased: boolean;
  busy: boolean;
  branches: LocalBranchDto[];
  /** Repo's default branch as resolved from origin/HEAD on the local
   *  clone. Pre-fills the Base field so forks of repos that default
   *  to master (Trino, etc.) don't get a wrong "main" prompt. */
  defaultBranch: string | null;
  /** Set when the user picked a non-HEAD branch via card click and
   *  the submit will run `git switch headBranch` before creating
   *  the PR. Lets the modal warn about the lazy switch instead of
   *  failing surprisingly mid-submit. */
  willSwitchFrom?: string | null;
  onCancel: () => void;
  onSubmit: (payload: { title: string; body: string; base: string; draft: boolean }) => void;
}) {
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [base, setBase] = useState(defaultBranch || 'main');
  const [draft, setDraft] = useState(false);
  // AI-draft flow lives next to the form fields it populates. Busy
  // state is local so the AI button can spin without locking the
  // submit button (and vice versa).
  const [aiBusy, setAiBusy] = useState(false);
  const [aiError, setAiError] = useState<string | null>(null);
  const submitDisabled = busy || aiBusy || !title.trim() || !base.trim();

  // Empty diff is a confusing AI failure — catch the head==base case
  // up front so the user sees a clear hint instead of "no diff".
  const aiHeadEqualsBase = base.trim() === headBranch;
  const runAiDraft = async () => {
    if (!base.trim()) {
      setAiError('Set a base branch first — the AI needs the diff target.');
      return;
    }
    if (aiHeadEqualsBase) {
      setAiError(`Head and base are both '${headBranch}' — switch to a feature branch first.`);
      return;
    }
    setAiBusy(true);
    setAiError(null);
    try {
      const draftResult = await window.bridge.draftLocalPullRequest(
          owner, repo, base.trim(), headBranch);
      setTitle(draftResult.title);
      setBody(draftResult.description);
    } catch (e) {
      setAiError(e instanceof Error ? e.message : String(e));
    } finally {
      setAiBusy(false);
    }
  };
  return (
    <div className="force-push-modal" role="dialog" aria-modal="true">
      <div className="force-push-modal__backdrop" onClick={busy ? undefined : onCancel} />
      <div className="force-push-modal__panel create-pr-modal__panel">
        <h2 className="force-push-modal__title">
          Open pull request
        </h2>
        <p className="force-push-modal__body">
          Open a PR against <code>{owner}/{repo}</code> with{' '}
          <code>{headBranch}</code> as the head ref.
          {forkBased
            ? ' Your fork hosts the branch; the PR is opened cross-fork.'
            : ' Branch and base both live in this repo (no fork involved).'}
          {willSwitchFrom && (
            <>
              {' '}ByteQuay will switch HEAD from{' '}
              <code>{willSwitchFrom}</code> to <code>{headBranch}</code>{' '}
              before opening the PR.
            </>
          )}
        </p>
        <label className="create-pr-modal__field">
          <span>Title</span>
          <input
            type="text"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            disabled={busy}
            autoFocus
            placeholder="Short, imperative — e.g. 'Add support for X'"
          />
        </label>
        <label className="create-pr-modal__field">
          <span>Description</span>
          <textarea
            value={body}
            onChange={(e) => setBody(e.target.value)}
            disabled={busy}
            rows={6}
            placeholder="What does this PR do? Markdown supported."
          />
        </label>
        <label className="create-pr-modal__field">
          <span>Base branch</span>
          <input
            type="text"
            value={base}
            onChange={(e) => setBase(e.target.value)}
            disabled={busy}
            list="create-pr-modal__bases"
            placeholder="main"
          />
          <datalist id="create-pr-modal__bases">
            {branches.map(b => (
              <option key={b.name} value={b.name} />
            ))}
          </datalist>
        </label>
        <label className="create-pr-modal__draft">
          <input
            type="checkbox"
            checked={draft}
            onChange={(e) => setDraft(e.target.checked)}
            disabled={busy}
          />
          <span>Open as draft</span>
        </label>
        {aiError && (
          <div className="create-pr-modal__ai-error">{aiError}</div>
        )}
        <div className="force-push-modal__actions create-pr-modal__actions">
          <button
            type="button"
            className="button button--secondary button--sm create-pr-modal__ai-btn"
            onClick={() => { void runAiDraft(); }}
            disabled={busy || aiBusy || !base.trim() || aiHeadEqualsBase}
            title={aiHeadEqualsBase
              ? `Head and base both equal ${headBranch} — pick a different base or switch branches.`
              : 'Diff HEAD against the base, send to your active LLM, fill the title and description'}
          >
            {aiBusy ? 'Drafting…' : '✨ Run AI'}
          </button>
          <span className="create-pr-modal__action-spacer" aria-hidden="true" />
          <button
            type="button"
            className="button button--secondary button--sm"
            onClick={onCancel}
            disabled={busy}
          >
            Cancel
          </button>
          <button
            type="button"
            className="button button--primary button--sm"
            onClick={() => onSubmit({ title: title.trim(), body, base: base.trim(), draft })}
            disabled={submitDisabled}
          >
            {busy ? 'Opening…' : draft ? 'Open draft PR' : 'Open PR'}
          </button>
        </div>
      </div>
    </div>
  );
}

function CreatePrSuccessToast({
  owner,
  repo,
  number,
  htmlUrl,
  onDismiss,
}: {
  owner: string;
  repo: string;
  number: number;
  htmlUrl: string;
  onDismiss: () => void;
}) {
  return (
    <div className="create-pr-toast" role="status">
      <span>
        Opened <strong>{owner}/{repo}#{number}</strong>
      </span>
      {htmlUrl && (
        <a
          href={htmlUrl}
          target="_blank"
          rel="noreferrer noopener"
          className="create-pr-toast__link"
        >
          View on GitHub
        </a>
      )}
      <button
        type="button"
        className="create-pr-toast__dismiss"
        onClick={onDismiss}
        aria-label="Dismiss"
      >
        ×
      </button>
    </div>
  );
}

function ActivityTab({ owner, repo }: { owner: string; repo: string }) {
  const [entries, setEntries] = useState<LocalActivityEntryDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setEntries(null);
    setError(null);
    window.bridge.listLocalActivity(owner, repo, 100)
      .then(rows => { if (!cancelled) setEntries(rows); })
      .catch(e => { if (!cancelled) setError(e instanceof Error ? e.message : String(e)); });
    return () => { cancelled = true; };
  }, [owner, repo]);

  if (error) {
    return (
      <div className="local-repo-page__error">
        Couldn't load activity: {error}
      </div>
    );
  }
  if (entries === null) {
    return (
      <div className="local-repo-page__loading">
        <LogoLoading size={48} label="Loading activity" />
      </div>
    );
  }
  if (entries.length === 0) {
    return (
      <div className="local-repo-tab-placeholder">
        <div className="local-repo-tab-placeholder__title">No activity yet</div>
        <p className="local-repo-tab-placeholder__body">
          The reflog for this clone is empty — nothing has moved
          HEAD since it was set up.
        </p>
      </div>
    );
  }
  return (
    <ol className="activity-list">
      {entries.map((e, i) => (
        <ActivityRow key={`${e.selector}-${i}`} entry={e} />
      ))}
    </ol>
  );
}

const ACTIVITY_KIND_LABEL: Record<LocalActivityEntryDto['kind'], string> = {
  COMMIT: 'Commit',
  CHECKOUT: 'Checkout',
  MERGE: 'Merge',
  PULL: 'Pull',
  PUSH: 'Push',
  REBASE: 'Rebase',
  RESET: 'Reset',
  BRANCH: 'Branch',
  UNKNOWN: 'Event',
};

const ACTIVITY_KIND_GLYPH: Record<LocalActivityEntryDto['kind'], string> = {
  COMMIT: '●',
  CHECKOUT: '⎇',
  MERGE: '⤭',
  PULL: '↓',
  PUSH: '↑',
  REBASE: '↻',
  RESET: '⤺',
  BRANCH: '⎇',
  UNKNOWN: '•',
};

function activityDescription(entry: LocalActivityEntryDto): string {
  // Strip the "verb:" prefix — the kind label and glyph already
  // convey it, and the trailing description is what the user wants
  // to read. "checkout: moving from main to feat" → "moving from
  // main to feat". Falls back to the full subject if there's no
  // colon.
  const colon = entry.subject.indexOf(':');
  if (colon < 0) return entry.subject;
  return entry.subject.slice(colon + 1).trim() || entry.subject;
}

function ActivityRow({ entry }: { entry: LocalActivityEntryDto }) {
  const kindClass = entry.kind.toLowerCase();
  return (
    <li className={`activity-row activity-row--${kindClass}`}>
      <span className="activity-row__glyph" aria-hidden="true">
        {ACTIVITY_KIND_GLYPH[entry.kind]}
      </span>
      <div className="activity-row__main">
        <div className="activity-row__line">
          <span className="activity-row__kind">{ACTIVITY_KIND_LABEL[entry.kind]}</span>
          <span className="activity-row__desc" title={entry.subject}>
            {activityDescription(entry)}
          </span>
        </div>
        <div className="activity-row__meta">
          <code className="activity-row__sha" title={entry.sha}>{entry.shortSha}</code>
          <span className="activity-row__selector">{entry.selector}</span>
          {entry.at && (
            <span className="activity-row__time" title={entry.at}>
              {formatRelativeTime(entry.at)}
            </span>
          )}
        </div>
      </div>
    </li>
  );
}

function groupByColumn(branches: LocalBranchDto[]): Record<Column, LocalBranchDto[]> {
  const out: Record<Column, LocalBranchDto[]> = {
    LOCAL_WORK: [],
    READY_FOR_PR: [],
    IN_REVIEW: [],
    CLEAN_UP: [],
  };
  for (const b of branches) {
    // Cleanup beats every other column except for the current branch —
    // we never auto-route the branch you're on into Clean up because
    // deleting it isn't a one-click flow we want to encourage.
    if (b.cleanupReason && !b.isCurrent) out.CLEAN_UP.push(b);
    else if (b.linkedPrNumber != null) out.IN_REVIEW.push(b);
    else if (b.hasUpstream) out.READY_FOR_PR.push(b);
    else out.LOCAL_WORK.push(b);
  }
  // Newest activity first within each column — current branch always
  // pinned to the top of its column for quick orientation.
  for (const key of Object.keys(out) as Column[]) {
    out[key].sort((a, b) => {
      if (a.isCurrent !== b.isCurrent) return a.isCurrent ? -1 : 1;
      const ta = a.lastCommitAt ? new Date(a.lastCommitAt).getTime() : 0;
      const tb = b.lastCommitAt ? new Date(b.lastCommitAt).getTime() : 0;
      return tb - ta;
    });
  }
  return out;
}

function BranchColumn({
  label,
  subtitle,
  column,
  branches,
  onSwitchBranch,
  switching,
  selectedActionBranch,
  focusedBranch,
  currentBranch,
  onSelectForAction,
  onDeleteBranch,
  onPushBranch,
  onCreatePrForBranch,
  onCheckoutRemote,
  onSelectPr,
  expanded,
  collapsedLimit,
  onToggleExpanded,
}: {
  label: string;
  subtitle: string;
  column: Column;
  branches: LocalBranchDto[];
  /** Explicit "switch HEAD now" — kept on every card as a secondary
   *  affordance (lets the user hop to a branch before inspecting
   *  it in their IDE without going through the lazy-switch flow). */
  onSwitchBranch?: (name: string) => void;
  switching?: boolean;
  /** Branch the user has clicked to act on (Push / Pull / Create
   *  PR will target it, switching HEAD lazily as part of the
   *  action). Distinct from {@link currentBranch}. */
  selectedActionBranch?: string | null;
  /** Branch currently under the keyboard cursor (j/k). Used purely
   *  for visual highlight; commits to {@link selectedActionBranch}
   *  on Enter handled at the page level. */
  focusedBranch?: string | null;
  currentBranch?: string | null;
  onSelectForAction?: (name: string) => void;
  /** Per-card "Delete" callback. Receives the branch object so the
   *  modal upstream can decide whether to offer the "also delete
   *  remote" checkbox. */
  onDeleteBranch?: (branch: LocalBranchDto) => void;
  /** Per-card primary "Push" — only wired in LOCAL WORK. Click
   *  pushes the card's branch (lazy-switching first when needed). */
  onPushBranch?: (name: string) => void;
  /** Per-card primary "Create PR" — only wired in READY FOR PR.
   *  Click pre-selects the card's branch and opens the Create PR
   *  modal. */
  onCreatePrForBranch?: (name: string) => void;
  /** Per-card "Check out" — only wired in IN_REVIEW for remote-only
   *  cards. Materializes the branch from origin and switches HEAD. */
  onCheckoutRemote?: (name: string) => void;
  /** Click on a card's PR pill jumps to the PR detail page. */
  onSelectPr?: (prNumber: number) => void;
  /** True when the user has expanded this column past the collapsed
   *  cap. False shows only the first {@link collapsedLimit} cards
   *  with a "Show N more" toggle below. */
  expanded?: boolean;
  collapsedLimit?: number;
  onToggleExpanded?: () => void;
}) {
  return (
    <section className={`branches-col branches-col--${column.toLowerCase()}`}>
      <header className="branches-col__head">
        <span className="branches-col__label">{label}</span>
        <span className="branches-col__count">{branches.length}</span>
        <div className="branches-col__sub">{subtitle}</div>
      </header>
      <div className="branches-col__body">
        {branches.length === 0 ? (
          <div className="branches-col__empty">No branches</div>
        ) : (() => {
          const limit = collapsedLimit ?? Number.POSITIVE_INFINITY;
          const visible = expanded ? branches : branches.slice(0, limit);
          const hidden = branches.length - visible.length;
          return (
            <>
              {visible.map(b => (
                <BranchCard
                  key={b.name}
                  branch={b}
                  column={column}
                  onSwitch={onSwitchBranch && !b.isCurrent ? () => onSwitchBranch(b.name) : undefined}
                  switching={switching ?? false}
                  actionSelected={selectedActionBranch === b.name}
                  focused={focusedBranch === b.name}
                  isCurrentHead={currentBranch === b.name}
                  onSelectForAction={onSelectForAction}
                  onDelete={onDeleteBranch && !b.isCurrent && !b.remoteOnly ? () => onDeleteBranch(b) : undefined}
                  onPush={onPushBranch && !b.isCurrent ? () => onPushBranch(b.name) : undefined}
                  onCreatePr={onCreatePrForBranch && !b.isCurrent ? () => onCreatePrForBranch(b.name) : undefined}
                  onCheckout={onCheckoutRemote && b.remoteOnly ? () => onCheckoutRemote(b.name) : undefined}
                  onSelectPr={onSelectPr && b.linkedPrNumber != null
                    ? () => onSelectPr(b.linkedPrNumber!)
                    : undefined}
                />
              ))}
              {(hidden > 0 || expanded) && onToggleExpanded && (
                <button
                  type="button"
                  className="branches-col__more"
                  onClick={onToggleExpanded}
                >
                  {expanded ? 'Show less ↑' : `Show ${hidden} more ↓`}
                </button>
              )}
            </>
          );
        })()}
      </div>
    </section>
  );
}

const CLEANUP_REASON_LABEL: Record<NonNullable<LocalBranchDto['cleanupReason']>, string> = {
  REMOTE_GONE: 'Remote gone',
  IDLE_NEVER_PUSHED: 'Idle · never pushed',
};

function BranchCard({
  branch,
  column,
  onSwitch,
  switching,
  actionSelected,
  focused,
  isCurrentHead,
  onSelectForAction,
  onDelete,
  onPush,
  onCreatePr,
  onCheckout,
  onSelectPr,
}: {
  branch: LocalBranchDto;
  column: Column;
  onSwitch?: () => void;
  switching: boolean;
  /** True when this card is the user's current "act on this
   *  branch" pick (set via {@link onSelectForAction}). */
  actionSelected?: boolean;
  /** True when the keyboard cursor (j/k) is currently on this
   *  card. Distinct from {@link actionSelected}. */
  focused?: boolean;
  isCurrentHead?: boolean;
  onSelectForAction?: (name: string) => void;
  /** Per-card delete (×) — undefined for the current branch. */
  onDelete?: () => void;
  /** Per-card primary "↑ Push" — only on LOCAL WORK cards. */
  onPush?: () => void;
  /** Per-card primary "Create PR ↗" — only on READY FOR PR cards. */
  onCreatePr?: () => void;
  /** Per-card primary "Check out" — only on remote-only IN_REVIEW
   *  cards. Fetches the branch from origin and switches HEAD. */
  onCheckout?: () => void;
  /** Click on the PR pill jumps to the PR detail page. Set when
   *  the branch has a linked PR. */
  onSelectPr?: () => void;
}) {
  const selectable = onSelectForAction !== undefined;
  const cls = [
    'branch-card',
    branch.isCurrent ? 'branch-card--current' : '',
    branch.cleanupReason ? 'branch-card--cleanup' : '',
    selectable ? 'branch-card--selectable' : '',
    actionSelected ? 'branch-card--action-target' : '',
    focused ? 'branch-card--focused' : '',
  ].filter(Boolean).join(' ');
  const cardClick = selectable ? () => onSelectForAction!(branch.name) : undefined;
  // Title hint adapts to context so the affordance is discoverable
  // without a tour: explain what clicking will do.
  let cardTitle: string | undefined;
  if (selectable) {
    if (isCurrentHead) {
      cardTitle = actionSelected
          ? 'Currently the action target (HEAD)'
          : `${branch.name} is the current HEAD — actions target it by default`;
    }
    else {
      cardTitle = actionSelected
          ? `Click again to clear — actions will target HEAD instead`
          : `Click to act on ${branch.name} (will switch HEAD when you Push / Pull / Create PR)`;
    }
  }
  // "Push fast-forwards" — ahead-only cases push as a fast-forward
  // with no rebase needed. Computable from existing ahead/behind
  // without backend support; the diverged-and-clean / diverged-and-
  // conflict pills land in Phase 3.
  const isFastForwardPush = branch.hasUpstream
      && (branch.ahead ?? 0) > 0
      && (branch.behind ?? 0) === 0;
  return (
    <article
      className={cls}
      data-branch-name={branch.name}
      onClick={cardClick}
      onKeyDown={cardClick ? (e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          cardClick();
        }
      } : undefined}
      role={cardClick ? 'button' : undefined}
      tabIndex={cardClick ? 0 : undefined}
      title={cardTitle}
    >
      <header className="branch-card__head">
        <code className="branch-card__name" title={branch.name}>
          {branch.isCurrent && <span className="branch-card__head-dot" aria-hidden="true">●</span>}
          {branch.name}
        </code>
        {actionSelected && (
          <span className="branch-card__acting-pill" aria-label="Acting on this branch">
            ACTING
          </span>
        )}
        {branch.linkedPrNumber != null && (onSelectPr ? (
          <button
            type="button"
            className="branch-card__pr branch-card__pr--clickable"
            onClick={(e) => { e.stopPropagation(); onSelectPr(); }}
            title={`Open PR #${branch.linkedPrNumber}`}
          >
            #{branch.linkedPrNumber}
          </button>
        ) : (
          <span className="branch-card__pr">#{branch.linkedPrNumber}</span>
        ))}
      </header>
      <div className="branch-card__meta">
        {branch.remoteOnly && (
          <span className="branch-card__remote-only"
                title="A PR exists for this branch but it isn't checked out in this clone yet">
            Remote only · not checked out
          </span>
        )}
        {!branch.remoteOnly && branch.lastCommitAt && (
          <span title={branch.lastCommitAt}>
            {formatRelativeTime(branch.lastCommitAt)}
          </span>
        )}
        {branch.commitCount != null && branch.commitCount > 0 && (
          <>
            <span aria-hidden="true">·</span>
            <span className="branch-card__commits"
                  title="Commits on this branch that aren't on the default base">
              {branch.commitCount === 1 ? '1 commit' : `${branch.commitCount} commits`}
            </span>
          </>
        )}
        {branch.hasUpstream && (branch.ahead || branch.behind) && (
          <>
            <span aria-hidden="true">·</span>
            {(branch.ahead ?? 0) > 0 && (
              <span className="branch-card__ahead" title={`${branch.ahead} ahead — local commits not yet on origin`}>
                ↑{branch.ahead}
              </span>
            )}
            {(branch.behind ?? 0) > 0 && (
              <span className="branch-card__behind" title={`${branch.behind} behind — origin has commits not yet local`}>
                ↓{branch.behind}
              </span>
            )}
          </>
        )}
        {isFastForwardPush && (
          <span className="rebase-pill rebase-pill--fwd"
                title="Push will fast-forward — no rebase needed">
            ↑ push fast-forwards
          </span>
        )}
        {branch.rebasePreview === 'CLEAN' && (
          <span className="rebase-pill rebase-pill--clean"
                title="Virtual merge against the rebase target reported no conflicts">
            ✓ rebase clean
          </span>
        )}
        {branch.rebasePreview === 'CONFLICTS' && (
          <span className="rebase-pill rebase-pill--conflicts"
                title="Virtual merge against the rebase target hit file-level conflicts — you'll need to resolve them mid-rebase">
            ⚠ rebase has conflicts
          </span>
        )}
        {branch.rebasePreview === 'UNKNOWN' && (
          <span className="rebase-pill rebase-pill--unknown"
                title="Couldn't preview the rebase — base ref may be missing locally">
            ? rebase unknown
          </span>
        )}
        {!branch.hasUpstream && !branch.cleanupReason && !branch.remoteOnly && (
          <>
            <span aria-hidden="true">·</span>
            <span className="branch-card__no-upstream">never pushed</span>
          </>
        )}
        {branch.cleanupReason && (
          <span className="branch-card__cleanup-reason">
            {CLEANUP_REASON_LABEL[branch.cleanupReason]}
          </span>
        )}
      </div>
      {branch.remoteOnly && onCheckout && (
        <div className="branch-card__foot">
          <button
            type="button"
            className="branch-card__btn branch-card__btn--cta"
            onClick={(e) => { e.stopPropagation(); onCheckout(); }}
            title={`Fetch ${branch.name} from origin and switch HEAD to it`}
          >
            ↓ Check out
          </button>
        </div>
      )}
      {!branch.remoteOnly && (onPush || onCreatePr || onSwitch || onDelete) && !branch.isCurrent && (
        <div className="branch-card__foot">
          {onPush && (
            <button
              type="button"
              className="branch-card__btn branch-card__btn--cta"
              onClick={(e) => { e.stopPropagation(); onPush(); }}
              title={`Push ${branch.name}`}
            >
              ↑ Push
            </button>
          )}
          {onCreatePr && branch.rebasePreview === 'CONFLICTS' && (
            // Rebase preview said this branch conflicts with the base
            // — opening a PR from it would just create one that's
            // immediately marked unmergeable, so swap the CTA out for
            // a disabled Rebase button. The action is unwired for now
            // (no in-app conflict resolution yet — see the deferred
            // merge-conflicts UI work).
            <button
              type="button"
              className="branch-card__btn branch-card__btn--cta"
              disabled
              title={`Resolve rebase conflicts against the base before opening a PR from ${branch.name}`}
            >
              Rebase
            </button>
          )}
          {onCreatePr && branch.rebasePreview !== 'CONFLICTS' && (
            <button
              type="button"
              className="branch-card__btn branch-card__btn--cta"
              onClick={(e) => { e.stopPropagation(); onCreatePr(); }}
              title={`Open a pull request from ${branch.name}`}
            >
              Create PR ↗
            </button>
          )}
          {column === 'CLEAN_UP' && onDelete && (
            // CLEAN UP collapses delete into the primary slot — the
            // whole point of the column is to delete things, no need
            // for a secondary ✕ button next to it.
            <button
              type="button"
              className="branch-card__btn branch-card__btn--danger"
              onClick={(e) => { e.stopPropagation(); onDelete(); }}
            >
              Delete
            </button>
          )}
          {onSwitch && !switching && (
            <button
              type="button"
              className="branch-card__btn"
              onClick={(e) => { e.stopPropagation(); onSwitch(); }}
              title={`Switch HEAD to ${branch.name} now`}
            >
              Switch
            </button>
          )}
          {column !== 'CLEAN_UP' && onDelete && (
            <button
              type="button"
              className="branch-card__btn branch-card__btn--icon"
              onClick={(e) => { e.stopPropagation(); onDelete(); }}
              title={`Delete ${branch.name}`}
              aria-label={`Delete ${branch.name}`}
            >
              ✕
            </button>
          )}
        </div>
      )}
    </article>
  );
}

function DeleteBranchModal({
  owner,
  repo,
  branch,
  busy,
  onCancel,
  onConfirm,
}: {
  owner: string;
  repo: string;
  branch: LocalBranchDto;
  busy: boolean;
  onCancel: () => void;
  onConfirm: (deleteRemote: boolean) => void;
}) {
  const [deleteRemote, setDeleteRemote] = useState(false);
  return (
    <div className="force-push-modal" role="dialog" aria-modal="true">
      <div className="force-push-modal__backdrop" onClick={busy ? undefined : onCancel} />
      <div className="force-push-modal__panel">
        <h2 className="force-push-modal__title">
          Delete branch <code>{branch.name}</code>?
        </h2>
        <p className="force-push-modal__body">
          From <code>{owner}/{repo}</code>. Runs <code>git branch -D</code>{' '}
          locally — irreversible (the local commits become unreachable
          unless they're also on a remote or another branch).
          {branch.cleanupReason && (
            <>
              {' '}This branch is flagged as{' '}
              <strong>{CLEANUP_REASON_LABEL[branch.cleanupReason]}</strong>.
            </>
          )}
        </p>
        {branch.hasUpstream && (
          <label className="create-pr-modal__draft">
            <input
              type="checkbox"
              checked={deleteRemote}
              onChange={(e) => setDeleteRemote(e.target.checked)}
              disabled={busy}
            />
            <span>
              Also delete remote branch (<code>git push origin --delete {branch.name}</code>)
            </span>
          </label>
        )}
        <div className="force-push-modal__actions">
          <button
            type="button"
            className="button button--secondary button--sm"
            onClick={onCancel}
            disabled={busy}
          >
            Cancel
          </button>
          <button
            type="button"
            className="button button--danger button--sm"
            onClick={() => onConfirm(deleteRemote)}
            disabled={busy}
          >
            {busy ? 'Deleting…' : (deleteRemote ? 'Delete local + remote' : 'Delete local')}
          </button>
        </div>
      </div>
    </div>
  );
}

export default LocalRepoPage;
