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
import { Fragment, useEffect, useRef, useState, type KeyboardEvent as ReactKeyboardEvent, type MouseEvent as ReactMouseEvent } from 'react';
import type { LocalActivityEntryDto, LocalBranchDto, LocalCommitDetailDto, LocalCommitDto, LocalCommitFileDto, LocalFileDiffDto, LocalMergeBaseDto, LocalRepoStatusDto, RepoMetaDto } from '../types';
import Avatar from '../Avatar';
import LogoLoading from '../LogoLoading';
import AddRepoModal from './AddRepoModal';
import { formatRelativeTime } from '../pr/utils';
import { DiffFileTreePane, type FilesPaneMode } from '../diff/DiffFileTreePane';
import { statusBadgeFromLetter } from '../diffStatusBadge';
import { unionCommitFiles } from '../diff/unionCommitFiles';
import { formatShortSha } from '../diff/commitDisplay';
import { parseUnifiedDiff } from '../diffParse';
import ResizeHandle from '../ResizeHandle';

// Persisted widths for the Commits-tab 3-pane layout. Same pattern as
// DiffViewerScreen — left/middle column widths are user-controlled via
// drag handles, the right diff column takes whatever remains so it
// always grows with the viewport. localStorage keys are scoped to this
// view so they don't collide with the PR diff viewer's keys.
const COMMITS_TAB_LEFT_KEY = 'bq.localRepo.commitsTab.leftWidth';
const COMMITS_TAB_MID_KEY = 'bq.localRepo.commitsTab.midWidth';
// Shared with the PR diff viewer (DiffViewerScreen.tsx) so the user's
// Tree/Flat preference applies across both surfaces.
const FILES_MODE_KEY = 'settings:diff-files-mode';
const COMMITS_TAB_LEFT_DEFAULT = 320;
const COMMITS_TAB_MID_DEFAULT = 280;
const COMMITS_TAB_LEFT_MIN = 200;
const COMMITS_TAB_LEFT_MAX = 600;
const COMMITS_TAB_MID_MIN = 180;
const COMMITS_TAB_MID_MAX = 600;

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
function LocalRepoPage({ owner, repo, onBack, onSelectPr, initialBranch }: Props) {
  const [status, setStatus] = useState<LocalRepoStatusDto | null>(null);
  const [branches, setBranches] = useState<LocalBranchDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  // True when this repo has no local clone mapped yet. We render a
  // mapping affordance instead of letting the branch/commit loads error.
  const [unmapped, setUnmapped] = useState(false);
  // Open while the user maps a clone (locate or clone fresh).
  const [mapCloneOpen, setMapCloneOpen] = useState(false);
  // Repo-level GitHub metadata, fetched lazily once per page load.
  // We only read parent.{owner,name,defaultBranch} for the fork →
  // upstream view-focus dropdown next to the title; failure is
  // best-effort (no dropdown when meta is missing — no other UI
  // depends on this field on this page).
  const [meta, setMeta] = useState<RepoMetaDto | null>(null);
  // Whether the fork/upstream popover is open. Closes on item-click,
  // outside-click, or ESC.
  const [focusMenuOpen, setFocusMenuOpen] = useState(false);
  const focusMenuRef = useRef<HTMLDivElement | null>(null);
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
    const all = await window.bridge.listLocalRepos();
    if (signal?.cancelled) return;
    const match = all.find(r => r.owner === owner && r.repo === repo) ?? null;
    setStatus(match);
    // No clone mapped yet — listLocalBranches throws for an unmapped
    // repo, so skip it and surface the mapping flow instead of an error.
    if (match == null || match.localClonePath == null) {
      setUnmapped(true);
      setBranches(null);
      return;
    }
    setUnmapped(false);
    const branchList = await window.bridge.listLocalBranches(owner, repo);
    if (signal?.cancelled) return;
    setBranches(branchList);
  };

  useEffect(() => {
    const signal = { cancelled: false };
    setStatus(null);
    setBranches(null);
    setError(null);
    setUnmapped(false);
    setMapCloneOpen(false);
    reload(signal).catch(e => {
      if (!signal.cancelled) setError(e instanceof Error ? e.message : String(e));
    });
    return () => { signal.cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [owner, repo]);

  // Best-effort fetch of repo meta — only used to know whether this
  // repo is a fork (via parentOwner/parentName) and what the upstream's
  // default branch is for the commits-tab revision swap. Page renders
  // fine without it; we just don't show the dropdown.
  useEffect(() => {
    let cancelled = false;
    setMeta(null);
    window.bridge.getRepoMeta(owner, repo)
      .then(m => { if (!cancelled) setMeta(m); })
      .catch(() => { /* swallow — no dropdown without meta */ });
    return () => { cancelled = true; };
  }, [owner, repo]);

  // Close the focus-toggle popover on outside click / ESC.
  useEffect(() => {
    if (!focusMenuOpen) return;
    const onClick = (e: MouseEvent) => {
      if (focusMenuRef.current && !focusMenuRef.current.contains(e.target as Node)) {
        setFocusMenuOpen(false);
      }
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setFocusMenuOpen(false);
    };
    window.addEventListener('mousedown', onClick);
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('mousedown', onClick);
      window.removeEventListener('keydown', onKey);
    };
  }, [focusMenuOpen]);

  const isForkWithParent = meta?.parentOwner != null && meta?.parentName != null;
  const storedFocus: 'fork' | 'upstream' = status?.viewFocus ?? 'fork';
  // Upstream is only actionable when the local clone has a remote
  // pointing at the parent — otherwise we have nowhere to fetch
  // upstream commits from. Surface the dropdown either way (so the
  // user knows the repo is a fork) but disable the upstream item.
  const upstreamRemoteMissing = !status?.upstreamRemoteName;
  // What the UI should *display* as active — coerce upstream back to
  // fork when the upstream item is non-functional, even if a stored
  // 'upstream' lingers from a previous configuration. The persisted
  // value is left alone so adding the remote later restores intent.
  const activeFocus: 'fork' | 'upstream' = (storedFocus === 'upstream' && upstreamRemoteMissing)
    ? 'fork'
    : storedFocus;
  // The upstream-derived ref the commits tab queries when the user is
  // in upstream view: `<remote>/<branch>` (e.g. `upstream/master`).
  // Null when we don't have all three pieces — we fall back to HEAD.
  const upstreamRevision = activeFocus === 'upstream'
      && status?.upstreamRemoteName
      && meta?.parentDefaultBranch
    ? `${status.upstreamRemoteName}/${meta.parentDefaultBranch}`
    : null;

  const handleSelectFocus = async (next: 'fork' | 'upstream') => {
    setFocusMenuOpen(false);
    if (next === activeFocus) return;
    try {
      const fresh = await window.bridge.setViewFocus(owner, repo, next);
      setStatus(fresh);
      // Reset the kanban-selected branch so the commits tab reverts to
      // the new default ref (HEAD or upstream/<branch>) instead of
      // sticking on whatever branch the user clicked previously.
      setCommitsBranch(null);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

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

  // No local clone mapped yet — offer the mapping flow rather than
  // erroring on the branch/commit loads (which require a clone).
  if (unmapped) {
    return (
      <div className="local-repo-page">
        <header className="local-repo-page__head">
          <nav className="local-repo-page__breadcrumb">
            <button type="button" className="local-repo-page__back" onClick={onBack}>
              ← {owner}/{repo}
            </button>
          </nav>
        </header>
        <div className="local-repo-page__unmapped">
          <div className="local-repo-page__unmapped-msg">
            No local clone mapped for <code>{owner}/{repo}</code> yet — branches and
            commits need one.
          </div>
          <button
            type="button"
            className="button button--primary button--sm"
            onClick={() => setMapCloneOpen(true)}
          >
            Map a local clone…
          </button>
        </div>
        {mapCloneOpen && (
          <AddRepoModal
            owner={owner}
            repo={repo}
            onClose={() => setMapCloneOpen(false)}
            onMapped={(mapped) => {
              setStatus(mapped);
              setUnmapped(false);
              setMapCloneOpen(false);
              const signal = { cancelled: false };
              void reload(signal).catch(e => setError(e instanceof Error ? e.message : String(e)));
            }}
          />
        )}
      </div>
    );
  }

  return (
    <div className={`local-repo-page${tab === 'commits' ? ' local-repo-page--wide' : ''}`}>
      <header className="local-repo-page__head">
        {/* Breadcrumb back to the repository overview page. Mirrors
            RepositoryPage's "← Repos" so the back-affordance stays
            consistent across the repo nav chain. App.tsx wires
            onBack to { view: 'repository', owner, repo }. */}
        <nav className="local-repo-page__breadcrumb">
          <button
            type="button"
            className="local-repo-page__back"
            onClick={onBack}
          >
            ← {owner}/{repo}
          </button>
        </nav>
        <div className="local-repo-page__heading">
          <div className="local-repo-page__title-row">
            {isForkWithParent ? (
              <div className="local-repo-page__focus" ref={focusMenuRef}>
                <button
                  type="button"
                  className="local-repo-page__title local-repo-page__title--toggle"
                  onClick={() => setFocusMenuOpen(o => !o)}
                  aria-haspopup="menu"
                  aria-expanded={focusMenuOpen}
                  title={activeFocus === 'upstream'
                    ? `Viewing ${meta!.parentOwner}/${meta!.parentName} — click to switch`
                    : `Viewing your fork — click to switch to ${meta!.parentOwner}/${meta!.parentName}`}
                >
                  {activeFocus === 'upstream' ? (
                    <>
                      <span className="local-repo-page__owner">{meta!.parentOwner}/</span>
                      <span className="local-repo-page__repo">{meta!.parentName}</span>
                    </>
                  ) : (
                    <>
                      <span className="local-repo-page__owner">{owner}/</span>
                      <span className="local-repo-page__repo">{repo}</span>
                    </>
                  )}
                  <span className="local-repo-page__focus-caret" aria-hidden="true">▾</span>
                </button>
                {focusMenuOpen && (
                  <div className="local-repo-page__focus-menu" role="menu">
                    <button
                      type="button"
                      role="menuitemradio"
                      aria-checked={activeFocus === 'fork'}
                      className={`local-repo-page__focus-item${activeFocus === 'fork' ? ' local-repo-page__focus-item--active' : ''}`}
                      onClick={() => { void handleSelectFocus('fork'); }}
                    >
                      <span className="local-repo-page__focus-check">{activeFocus === 'fork' ? '✓' : ''}</span>
                      <span className="local-repo-page__focus-name">{owner}/{repo}</span>
                      <span className="local-repo-page__focus-tag">fork</span>
                    </button>
                    <button
                      type="button"
                      role="menuitemradio"
                      aria-checked={activeFocus === 'upstream'}
                      disabled={upstreamRemoteMissing}
                      title={upstreamRemoteMissing
                        ? `Add a git remote pointing at ${meta!.parentOwner}/${meta!.parentName} to enable upstream view`
                        : undefined}
                      className={
                        'local-repo-page__focus-item'
                        + (activeFocus === 'upstream' ? ' local-repo-page__focus-item--active' : '')
                        + (upstreamRemoteMissing ? ' local-repo-page__focus-item--disabled' : '')
                      }
                      onClick={() => {
                        if (upstreamRemoteMissing) return;
                        void handleSelectFocus('upstream');
                      }}
                    >
                      <span className="local-repo-page__focus-check">{activeFocus === 'upstream' ? '✓' : ''}</span>
                      <span className="local-repo-page__focus-name">{meta!.parentOwner}/{meta!.parentName}</span>
                      <span className="local-repo-page__focus-tag">
                        {upstreamRemoteMissing ? 'no upstream remote' : 'upstream'}
                      </span>
                    </button>
                  </div>
                )}
              </div>
            ) : (
              <h1 className="local-repo-page__title">
                <span className="local-repo-page__owner">{owner}/</span>
                <span className="local-repo-page__repo">{repo}</span>
              </h1>
            )}
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
          // the upstream-derived ref (when the user has the focus
          // toggle on upstream) and finally to HEAD's currentBranch.
          // Refetches when any of these change.
          revisionKey={commitsBranch ?? upstreamRevision ?? status?.currentBranch ?? ''}
          branches={branches}
          dirtyFileCount={status?.dirtyFileCount ?? null}
        />
      )}
      {tab === 'activity' && <ActivityTab owner={owner} repo={repo} />}
    </div>
  );
}

/**
 * Backend's resolveLogRevision throws a stable string when a branch
 * isn't local and isn't on origin yet. Pull the branch name out so the
 * commits panel can offer a "Fetch <name>" button instead of dumping
 * the raw error. Returns null when the error string doesn't match.
 */
function parseMissingBranch(error: string): string | null {
  const m = /Couldn't resolve branch '([^']+)' locally/.exec(error);
  return m ? m[1] : null;
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
  branches,
  dirtyFileCount,
}: {
  owner: string;
  repo: string;
  revisionKey: string;
  branches: LocalBranchDto[] | null;
  /** Working-tree dirty count from {@code git status --porcelain},
   *  surfaced on the Changes tab label so the user knows whether
   *  there's anything to review without first clicking the tab. Null
   *  when the repo is unmapped. */
  dirtyFileCount: number | null;
}) {
  const [commits, setCommits] = useState<LocalCommitDto[] | null>(null);
  const [commitsError, setCommitsError] = useState<string | null>(null);
  // Bumped after a successful "fetch and retry" so the commits useEffect
  // re-runs without the user having to re-navigate. Lets the missing-
  // branch CTA below surface a recovery flow inline.
  const [reloadCounter, setReloadCounter] = useState(0);
  const [fetchingMissingBranch, setFetchingMissingBranch] = useState(false);
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
  const [filesMode, setFilesMode] = useState<FilesPaneMode>(() =>
    localStorage.getItem(FILES_MODE_KEY) === 'flat' ? 'flat' : 'tree',
  );
  const switchFilesMode = (next: FilesPaneMode) => {
    setFilesMode(next);
    localStorage.setItem(FILES_MODE_KEY, next);
  };
  const [mergeBase, setMergeBase] = useState<LocalMergeBaseDto | null>(null);
  // Left-pane mode toggle. 'history' = commits list (default); 'changes'
  // = working-tree (uncommitted) files. The middle + right panes are
  // shared — they just switch which data source feeds them.
  const [mode, setMode] = useState<'history' | 'changes'>('history');
  // Compare-branches base. When set (only meaningful in History mode),
  // the middle/right panes show `git diff <compareBase>..<branch>`
  // instead of the selected commits' changes — for "what's different
  // between this branch and main" without leaving the page.
  const [compareBase, setCompareBase] = useState<string | null>(null);
  // Lazy-fetched subject + body for the patch-detail card. Refreshed
  // whenever the single-selected commit changes; cleared when the
  // user moves into multi-select (the card is only meaningful for
  // a single commit, the multi-select chip takes its slot instead).
  const [commitDetail, setCommitDetail] = useState<LocalCommitDetailDto | null>(null);
  // User-resizable column widths. Loaded once from localStorage so the
  // user's last layout sticks across navigations.
  const [leftWidth, setLeftWidth] = useState<number>(() => {
    const raw = localStorage.getItem(COMMITS_TAB_LEFT_KEY);
    const n = raw ? Number(raw) : NaN;
    return Number.isFinite(n) ? n : COMMITS_TAB_LEFT_DEFAULT;
  });
  const [midWidth, setMidWidth] = useState<number>(() => {
    const raw = localStorage.getItem(COMMITS_TAB_MID_KEY);
    const n = raw ? Number(raw) : NaN;
    return Number.isFinite(n) ? n : COMMITS_TAB_MID_DEFAULT;
  });
  const bodyRef = useRef<HTMLDivElement | null>(null);
  const handleLeftResize = (clientX: number) => {
    const rect = bodyRef.current?.getBoundingClientRect();
    if (!rect) return;
    const next = Math.max(COMMITS_TAB_LEFT_MIN, Math.min(COMMITS_TAB_LEFT_MAX, clientX - rect.left));
    setLeftWidth(next);
    localStorage.setItem(COMMITS_TAB_LEFT_KEY, String(next));
  };
  const handleMidResize = (clientX: number) => {
    const rect = bodyRef.current?.getBoundingClientRect();
    if (!rect) return;
    // The middle pane sits to the right of the (already-resizable) left
    // pane and its own left-edge handle, so we measure from the right
    // edge of the left handle's column rather than the body's left.
    const midStart = rect.left + leftWidth + 5;
    const next = Math.max(COMMITS_TAB_MID_MIN, Math.min(COMMITS_TAB_MID_MAX, clientX - midStart));
    setMidWidth(next);
    localStorage.setItem(COMMITS_TAB_MID_KEY, String(next));
  };
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
  }, [owner, repo, revisionKey, reloadCounter]);

  // Files for the middle pane. History mode → union of selected
  // commits' files (or the full <compareBase>..<branch> range when
  // compare-branches is active). Changes mode → working-tree
  // (uncommitted) files. Auto-picks the first file so the diff
  // pane has something to show.
  useEffect(() => {
    let cancelled = false;
    setFiles(null);
    setFilesError(null);
    setSelectedFile(null);
    setDiff(null);
    if (mode === 'changes') {
      window.bridge.listLocalWorkingTreeFiles(owner, repo)
        .then((rows) => {
          if (cancelled) return;
          setFiles(rows);
          if (rows.length > 0) setSelectedFile(rows[0].path);
        })
        .catch((e) => { if (!cancelled) setFilesError(e instanceof Error ? e.message : String(e)); });
      return () => { cancelled = true; };
    }
    if (compareBase != null && revisionKey) {
      window.bridge.listLocalRangeFiles(owner, repo, compareBase, revisionKey)
        .then((rows) => {
          if (cancelled) return;
          setFiles(rows);
          if (rows.length > 0) setSelectedFile(rows[0].path);
        })
        .catch((e) => { if (!cancelled) setFilesError(e instanceof Error ? e.message : String(e)); });
      return () => { cancelled = true; };
    }
    if (selectedShas.size === 0) return;
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
  }, [owner, repo, mode, compareBase, revisionKey, selectedShas, commits]);

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
    if (selectedFile == null) return;
    let cancelled = false;
    setDiff(null);
    setDiffError(null);
    let fetchDiff: Promise<LocalFileDiffDto>;
    if (mode === 'changes') {
      fetchDiff = window.bridge.getLocalWorkingTreeDiff(owner, repo, selectedFile);
    }
    else if (compareBase != null && revisionKey) {
      // Compare-branches: git diff <compareBase>..<branch> -- file.
      // Uses the dedicated range-diff endpoint (no ^ shift) since
      // <branch>^ would point at the parent of the branch tip
      // rather than at the branch itself.
      fetchDiff = window.bridge.getLocalRangeDiff(
          owner, repo, compareBase, revisionKey, selectedFile);
    }
    else {
      if (newestSelectedSha == null || oldestSelectedSha == null) return;
      fetchDiff = newestSelectedSha === oldestSelectedSha
        ? window.bridge.getLocalCommitDiff(owner, repo, newestSelectedSha, selectedFile)
        : window.bridge.getLocalCommitRangeDiff(
            owner, repo, oldestSelectedSha, newestSelectedSha, selectedFile);
    }
    fetchDiff
      .then(d => { if (!cancelled) setDiff(d); })
      .catch(e => { if (!cancelled) setDiffError(e instanceof Error ? e.message : String(e)); });
    return () => { cancelled = true; };
  }, [owner, repo, mode, compareBase, revisionKey, oldestSelectedSha, newestSelectedSha, selectedFile]);

  // Patch-detail card data — only fetched in single-select mode.
  // Multi-select shows the union-diff chip in the same slot.
  const detailSha = selectedShas.size === 1 ? newestSelectedSha : null;
  useEffect(() => {
    if (detailSha == null) {
      setCommitDetail(null);
      return;
    }
    let cancelled = false;
    setCommitDetail(null);
    window.bridge.getLocalCommitDetail(owner, repo, detailSha)
      .then(d => { if (!cancelled) setCommitDetail(d); })
      .catch(() => { /* card is best-effort — don't surface errors */ });
    return () => { cancelled = true; };
  }, [owner, repo, detailSha]);

  if (commitsError) {
    // The backend throws a specific message when a branch isn't local
    // and isn't on origin yet — usually because the user clicked into
    // a PR's head branch from the PR detail page before fetching it.
    // Surface a recovery CTA instead of a raw error string.
    const missingBranch = parseMissingBranch(commitsError);
    if (missingBranch) {
      return (
        <div className="local-repo-tab-placeholder">
          <div className="local-repo-tab-placeholder__title">Branch isn't available locally</div>
          <p className="local-repo-tab-placeholder__body">
            <code>{missingBranch}</code> isn't checked out and isn't on
            origin yet. Fetch from origin to pull it in, then we'll
            reload the commits automatically.
          </p>
          <div style={{ marginTop: 16 }}>
            <button
              type="button"
              className="button button--primary button--sm"
              disabled={fetchingMissingBranch}
              onClick={async () => {
                setFetchingMissingBranch(true);
                try {
                  await window.bridge.fetchLocalRepo(owner, repo);
                  setReloadCounter(c => c + 1);
                } catch (e) {
                  setCommitsError(e instanceof Error ? e.message : String(e));
                } finally {
                  setFetchingMissingBranch(false);
                }
              }}
            >
              {fetchingMissingBranch ? 'Fetching…' : `Fetch ${missingBranch}`}
            </button>
          </div>
        </div>
      );
    }
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
      <div
        className="commits-pane__body"
        ref={bodyRef}
        style={{
          gridTemplateColumns: `${leftWidth}px 5px ${midWidth}px 5px minmax(0, 1fr)`,
        }}
      >
        <aside className="commits-pane__commits">
          <div className="commits-pane__mode-tabs" role="tablist">
            <button
              type="button"
              role="tab"
              aria-selected={mode === 'changes'}
              className={`commits-pane__mode-tab${mode === 'changes' ? ' commits-pane__mode-tab--active' : ''}`}
              onClick={() => setMode('changes')}
            >
              Changes{(() => {
                // Live count from the loaded file list when the
                // user is in Changes mode; otherwise fall back to
                // the status row's dirty count so the badge stays
                // visible even from the History tab.
                const n = mode === 'changes' && files != null ? files.length : dirtyFileCount;
                return n != null ? ` (${n})` : '';
              })()}
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={mode === 'history'}
              className={`commits-pane__mode-tab${mode === 'history' ? ' commits-pane__mode-tab--active' : ''}`}
              onClick={() => setMode('history')}
            >
              History
            </button>
          </div>
          {mode === 'history' && (
            <div className="commits-pane__compare">
              <select
                className="commits-pane__compare-select"
                value={compareBase ?? ''}
                onChange={(e) => setCompareBase(e.target.value || null)}
                title="Compare this branch against another branch"
              >
                <option value="">Select branch to compare…</option>
                {(branches ?? [])
                  .filter((b) => b.name !== revisionKey)
                  .map((b) => (
                    <option key={b.name} value={b.name}>{b.name}</option>
                  ))}
              </select>
              {compareBase && (
                <button
                  type="button"
                  className="commits-pane__compare-clear"
                  onClick={() => setCompareBase(null)}
                  title="Clear comparison"
                >
                  ✕
                </button>
              )}
            </div>
          )}
          {mode === 'changes' ? (
            <div className="commits-pane__changes-empty">
              {files == null && !filesError && 'Reading working tree…'}
              {filesError && <span className="commits-pane__changes-error">{filesError}</span>}
              {files != null && files.length === 0 && (
                <span>No uncommitted changes.</span>
              )}
              {files != null && files.length > 0 && (
                <span>{files.length} file{files.length === 1 ? '' : 's'} changed in the working tree.</span>
              )}
            </div>
          ) : (
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
          )}
        </aside>
        <ResizeHandle onResize={handleLeftResize} ariaLabel="Resize commits panel" />
        <aside className="commits-pane__files">
          {mode === 'history' && compareBase == null && (
            selectedShas.size > 1 ? (
              <CommitsSelectionSummary
                commits={commits}
                selected={selectedShas}
                onClear={() => setSelectedShas(new Set([commits[0].sha]))}
              />
            ) : (
              <PatchDetailCard
                commit={commits.find(c => selectedShas.has(c.sha)) ?? null}
                detail={commitDetail}
              />
            )
          )}
          {mode === 'history' && compareBase != null && (
            <div className="commits-selection-summary commits-selection-summary--multi">
              <span className="commits-selection-summary__icon" aria-hidden="true">⇄</span>
              <span className="commits-selection-summary__label">
                Comparing <code>{revisionKey}</code> against <code>{compareBase}</code>
              </span>
            </div>
          )}
          <div className="commits-pane__section-header">
            <span className="commits-pane__section-title">
              {mode === 'changes'
                ? 'Working tree'
                : compareBase != null
                  ? 'Files in range'
                  : 'Files changed'}
              {files != null && files.length > 0 ? ` (${files.length})` : ''}
            </span>
            <div
              className="diff-viewer__mode-toggle commits-pane__mode-toggle"
              role="tablist"
              aria-label="File list layout"
            >
              <button
                type="button"
                role="tab"
                className={`diff-viewer__mode-btn${filesMode === 'tree' ? ' diff-viewer__mode-btn--active' : ''}`}
                onClick={() => switchFilesMode('tree')}
                aria-selected={filesMode === 'tree'}
                title="Tree — group by directory, compact single-child chains"
              >
                Tree
              </button>
              <button
                type="button"
                role="tab"
                className={`diff-viewer__mode-btn${filesMode === 'flat' ? ' diff-viewer__mode-btn--active' : ''}`}
                onClick={() => switchFilesMode('flat')}
                aria-selected={filesMode === 'flat'}
                title="Flat — one row per file, full path on each row"
              >
                Flat
              </button>
            </div>
          </div>
          <DiffFileTreePane
            files={files}
            error={filesError}
            mode={filesMode}
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
        <ResizeHandle onResize={handleMidResize} ariaLabel="Resize files panel" />
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
      <CommitAuthorAvatar
        name={commit.authorName}
        email={commit.authorEmail}
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

/** GitHub's no-reply commit emails encode the author's login. Two
 *  formats in the wild:
 *    12345+username@users.noreply.github.com  (post-2017 default)
 *    username@users.noreply.github.com         (legacy "private email")
 *  Returns the login when the address matches, null otherwise. */
function gitHubLoginFromEmail(email: string | null | undefined): string | null {
  if (!email) return null;
  const m = /^(?:\d+\+)?([^@]+)@users\.noreply\.github\.com$/.exec(email);
  return m ? m[1] : null;
}

/** Stable hash → 8-color palette for the per-author tint when we
 *  can't resolve a GitHub avatar. Same idea as the old author-dot:
 *  give the user a "same person again" cue at a glance. */
function authorColor(key: string): string {
  const PALETTE = [
    '#1f6a57', '#cf6900', '#1f6feb', '#8a5cf5',
    '#cf222e', '#1a7f37', '#996600', '#0e8c8c',
  ];
  let h = 0;
  for (let i = 0; i < key.length; i++) {
    h = ((h << 5) - h + key.charCodeAt(i)) | 0;
  }
  return PALETTE[Math.abs(h) % PALETTE.length];
}

/** Avatar slot for a local commit row. When the author's email is a
 *  GitHub no-reply address we know the login and can use the real
 *  GitHub avatar (cheap CDN hit, cached by the renderer). Otherwise
 *  we fall back to a tinted-initial circle keyed by the author's
 *  ident — same per-author cue the colored dot used to give. */
function CommitAuthorAvatar({ name, email }: { name: string; email: string | null | undefined }) {
  const login = gitHubLoginFromEmail(email);
  if (login) {
    return <Avatar login={login} size={20} className="commit-row__avatar" />;
  }
  const initial = (name?.trim().charAt(0) || '?').toUpperCase();
  return (
    <span
      className="commit-row__avatar avatar avatar--fallback"
      style={{
        width: 20,
        height: 20,
        fontSize: 10,
        background: authorColor(email || name),
        color: '#fff',
      }}
      aria-label={name}
      title={name}
      role="img"
    >
      {initial}
    </span>
  );
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

/** Patch-detail card — sits at the top of the middle pane in
 *  single-commit mode and shows subject + full message body.
 *  Per docs/mockups/design/local-repo/code-diff-v3.png. The body
 *  is folded behind a "View full message" toggle when it's long
 *  enough that the file list below would otherwise scroll out of
 *  reach. Detail is lazy-fetched, so we show subject-only chrome
 *  on the first paint while it lands. */
function PatchDetailCard({
  commit,
  detail,
}: {
  commit: LocalCommitDto | null;
  detail: LocalCommitDetailDto | null;
}) {
  const [expanded, setExpanded] = useState(false);
  if (!commit) return null;
  // Prefer detail.subject when it has loaded — handles the rare case
  // where the listCommits row's subject differs from `git log -1 %s`
  // on the same sha (shouldn't happen normally; defensive). Fall back
  // to the row's subject so the card shows something immediately.
  const subject = detail?.subject ?? commit.subject;
  const body = (detail?.body ?? '').trimEnd();
  const FOLD_THRESHOLD = 400;
  const long = body.length > FOLD_THRESHOLD;
  const shown = !long || expanded ? body : body.slice(0, FOLD_THRESHOLD).replace(/\n[^\n]*$/, '') + '…';
  return (
    <div className="patch-detail-card">
      <div className="patch-detail-card__subject">{subject}</div>
      {body && (
        <pre className="patch-detail-card__body">{shown}</pre>
      )}
      {long && (
        <button
          type="button"
          className="patch-detail-card__fold"
          onClick={() => setExpanded(e => !e)}
        >
          {expanded ? 'Show less' : 'View full message'}
        </button>
      )}
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
  // Parse the unified patch into hunks so each line gets a real
  // (oldLine, newLine) pair — same data shape the PR diff viewer
  // works with (see diffParse.ts). Render with the .diff-row*
  // classes from ai.css so the gutter geometry matches.
  const hunks = parseUnifiedDiff(diff.patch);
  let adds = 0;
  let dels = 0;
  for (const h of hunks) {
    for (const r of h.rows) {
      if (r.kind === 'add') adds++;
      else if (r.kind === 'del') dels++;
    }
  }
  return (
    <div className="commit-diff">
      <div className="commit-diff__header">
        <code className="commit-diff__path">{diff.path}</code>
        <span className="commit-diff__stats">
          <span className="commits-pane__add">+{adds}</span>{' '}
          <span className="commits-pane__del">−{dels}</span>
        </span>
        {diff.truncated && (
          <span className="commit-diff__truncated"
                title="Diff was capped server-side; the rest is omitted">
            truncated
          </span>
        )}
      </div>
      <div className="commit-diff__body">
        {hunks.map((hunk, hi) => (
          <div key={hi} className="commit-diff__hunk">
            <div className="diff-row diff-row--hunk-header">
              <span className="diff-row__gutter" />
              <span className="diff-row__gutter" />
              <span className="diff-row__content">{hunk.header}</span>
            </div>
            {hunk.rows.map((row, ri) => {
              if (row.kind === 'hunk-header') return null; // already rendered above
              const cls = `diff-row diff-row--${row.kind}`;
              const sigil = row.kind === 'add' ? '+' : row.kind === 'del' ? '−' : ' ';
              return (
                <div key={ri} className={cls}>
                  <span className="diff-row__gutter">{row.oldLine ?? ''}</span>
                  <span className="diff-row__gutter">{row.newLine ?? ''}</span>
                  <span className="diff-row__content">
                    <span className="diff-row__sigil">{sigil}</span>
                    {row.content}
                  </span>
                </div>
              );
            })}
          </div>
        ))}
        {hunks.length === 0 && (
          <div className="commit-diff__empty">No diff content for this file.</div>
        )}
      </div>
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
