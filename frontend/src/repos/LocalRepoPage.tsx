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
import { useEffect, useState } from 'react';
import type { LocalActivityEntryDto, LocalBranchDto, LocalCommitDto, LocalRepoStatusDto } from '../types';
import LogoLoading from '../LogoLoading';
import { formatRelativeTime } from '../pr/utils';

type Props = {
  owner: string;
  repo: string;
  onBack: () => void;
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
const COLLAPSED_LIMIT = 6;

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
function LocalRepoPage({ owner, repo }: Props) {
  const [status, setStatus] = useState<LocalRepoStatusDto | null>(null);
  const [branches, setBranches] = useState<LocalBranchDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  // Per-action busy state so each button can show its own spinner
  // without freezing the whole bar; the action bar disables all
  // buttons while any one is running so we don't fire concurrent
  // git ops in the same working tree.
  const [actionState, setActionState] = useState<'idle' | 'fetching' | 'pulling' | 'pushing' | 'branching' | 'switching' | 'creating-pr'>('idle');
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
  const [tab, setTab] = useState<Tab>('branches');
  // Names selected for bulk delete in the Clean up column. Lives on
  // the page rather than per-card so the modal can read the full set
  // and we can clear it after a successful delete.
  const [selectedForCleanup, setSelectedForCleanup] = useState<Set<string>>(() => new Set());
  const [cleanupConfirmOpen, setCleanupConfirmOpen] = useState(false);
  const [cleanupBusy, setCleanupBusy] = useState(false);
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

  // Switch HEAD to selectedBranch when the next action requires it.
  // Throws on failure so the surrounding try/catch surfaces git's
  // stderr (dirty tree, conflict, etc.) and the caller's action
  // never runs. After a successful switch we clear selectedBranch
  // because HEAD now equals what the user picked — they're back in
  // sync, no banner needed.
  const switchIfNeeded = async () => {
    if (!needsSwitch || !selectedBranch) return;
    const fresh = await window.bridge.switchLocalBranch(owner, repo, selectedBranch);
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

  const runPush = async () => {
    setActionState('pushing');
    setActionError(null);
    try {
      await switchIfNeeded();
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
    if (createPrOpen || cleanupConfirmOpen || forcePushPrompt || branchFormOpen) return;
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
      status?.currentBranch, createPrOpen, cleanupConfirmOpen,
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

  const toggleCleanupSelected = (name: string) => {
    setSelectedForCleanup(prev => {
      const next = new Set(prev);
      if (next.has(name)) next.delete(name);
      else next.add(name);
      return next;
    });
  };

  const selectAllCleanup = (rows: LocalBranchDto[]) => {
    setSelectedForCleanup(prev => {
      // Toggle: if every visible cleanup branch is already selected,
      // clear the set; otherwise select them all.
      const allSelected = rows.length > 0 && rows.every(r => prev.has(r.name));
      if (allSelected) return new Set();
      return new Set(rows.map(r => r.name));
    });
  };

  const runDeleteCleanup = async () => {
    setCleanupBusy(true);
    setActionError(null);
    try {
      const names = Array.from(selectedForCleanup);
      const deleted = await window.bridge.deleteLocalBranches(owner, repo, names);
      // Drop deleted names from selection; anything still selected
      // got skipped server-side (no longer a cleanup candidate) and
      // the refreshed list will reflect that on its next render.
      setSelectedForCleanup(new Set(names.filter(n => !deleted.includes(n))));
      const fresher = await window.bridge.listLocalBranches(owner, repo);
      setBranches(fresher);
      setCleanupConfirmOpen(false);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : String(e));
    } finally {
      setCleanupBusy(false);
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
          <button
            type="button"
            className="button button--primary button--sm"
            onClick={() => setCreatePrOpen(true)}
            disabled={actionState !== 'idle' || !status?.localClonePath || !status?.currentBranch}
            title="Open a pull request from the current branch"
          >
            Create PR
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
                  selected={col.key === 'CLEAN_UP' ? selectedForCleanup : undefined}
                  onToggleSelected={col.key === 'CLEAN_UP' ? toggleCleanupSelected : undefined}
                  onSelectAll={col.key === 'CLEAN_UP' ? () => selectAllCleanup(grouped.CLEAN_UP) : undefined}
                  onDeleteSelected={col.key === 'CLEAN_UP' && selectedForCleanup.size > 0
                    ? () => setCleanupConfirmOpen(true) : undefined}
                  onSwitchBranch={runSwitchBranch}
                  switching={actionState === 'switching'}
                  selectedActionBranch={selectedBranch}
                  focusedBranch={focusedBranch}
                  currentBranch={status?.currentBranch ?? null}
                  expanded={expandedColumns.has(col.key)}
                  collapsedLimit={COLLAPSED_LIMIT}
                  onToggleExpanded={() => toggleColumnExpanded(col.key)}
                  onSelectForAction={(name) => {
                    // Click the current branch's card to clear the
                    // selection (= "act on HEAD again"); click any
                    // other card to mark it as the action target.
                    setSelectedBranch(prev => {
                      if (prev === name) return null;
                      if (status?.currentBranch === name) return null;
                      return name;
                    });
                  }}
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

      {cleanupConfirmOpen && (
        <CleanupConfirmModal
          owner={owner}
          repo={repo}
          branches={(grouped.CLEAN_UP ?? []).filter(b => selectedForCleanup.has(b.name))}
          busy={cleanupBusy}
          onCancel={() => setCleanupConfirmOpen(false)}
          onConfirm={runDeleteCleanup}
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
          // Refetch when the current branch flips so the user sees
          // the right history after switching with + Branch / pull.
          revisionKey={status?.currentBranch ?? ''}
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
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setCommits(null);
    setError(null);
    // Pass undefined revision so the backend uses HEAD — saves a
    // round-trip when revisionKey is the empty string (detached or
    // not-yet-loaded state).
    const rev = revisionKey || undefined;
    window.bridge.listLocalCommits(owner, repo, rev, 100)
      .then(rows => { if (!cancelled) setCommits(rows); })
      .catch(e => { if (!cancelled) setError(e instanceof Error ? e.message : String(e)); });
    return () => { cancelled = true; };
  }, [owner, repo, revisionKey]);

  if (error) {
    return (
      <div className="local-repo-page__error">
        Couldn't load commits: {error}
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
          The current branch has no commits to show, or HEAD is
          detached and points at no history.
        </p>
      </div>
    );
  }
  return (
    <ol className="commits-list">
      {commits.map(c => <CommitRow key={c.sha} commit={c} />)}
    </ol>
  );
}

function CommitRow({ commit }: { commit: LocalCommitDto }) {
  return (
    <li className="commit-row">
      <code className="commit-row__sha" title={commit.sha}>{commit.shortSha}</code>
      <div className="commit-row__main">
        <div className="commit-row__subject">{commit.subject}</div>
        <div className="commit-row__meta">
          <span className="commit-row__author" title={commit.authorEmail}>
            {commit.authorName}
          </span>
          {commit.authoredAt && (
            <span className="commit-row__time" title={commit.authoredAt}>
              {formatRelativeTime(commit.authoredAt)}
            </span>
          )}
        </div>
      </div>
    </li>
  );
}

function CreatePrModal({
  owner,
  repo,
  headBranch,
  forkBased,
  busy,
  branches,
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
  const [base, setBase] = useState('main');
  const [draft, setDraft] = useState(false);
  const submitDisabled = busy || !title.trim() || !base.trim();
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
  selected,
  onToggleSelected,
  onSelectAll,
  onDeleteSelected,
  onSwitchBranch,
  switching,
  selectedActionBranch,
  focusedBranch,
  currentBranch,
  onSelectForAction,
  expanded,
  collapsedLimit,
  onToggleExpanded,
}: {
  label: string;
  subtitle: string;
  column: Column;
  branches: LocalBranchDto[];
  /** When set, the column is in selection mode and rows render with
   *  checkboxes. Currently only CLEAN UP opts in. */
  selected?: Set<string>;
  onToggleSelected?: (name: string) => void;
  onSelectAll?: () => void;
  onDeleteSelected?: () => void;
  /** Explicit "switch HEAD now" — surfaced as a small button on
   *  cleanup cards so the user can hop to a candidate before
   *  inspecting it in their IDE. */
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
  /** True when the user has expanded this column past the collapsed
   *  cap. False shows only the first {@link collapsedLimit} cards
   *  with a "Show N more" toggle below. */
  expanded?: boolean;
  collapsedLimit?: number;
  onToggleExpanded?: () => void;
}) {
  const allSelected = selected !== undefined
      && branches.length > 0
      && branches.every(b => selected.has(b.name));
  return (
    <section className={`branches-col branches-col--${column.toLowerCase()}`}>
      <header className="branches-col__head">
        <span className="branches-col__label">{label}</span>
        <span className="branches-col__count">{branches.length}</span>
        <div className="branches-col__sub">{subtitle}</div>
        {selected && branches.length > 0 && (
          <div className="branches-col__bulkbar">
            <label className="branches-col__bulk-toggle">
              <input
                type="checkbox"
                checked={allSelected}
                onChange={onSelectAll}
                aria-label={allSelected ? 'Deselect all' : 'Select all'}
              />
              <span>{allSelected ? 'Clear' : 'All'}</span>
            </label>
            <button
              type="button"
              className="button button--danger button--sm"
              onClick={onDeleteSelected}
              disabled={!onDeleteSelected}
              title="Delete selected branches"
            >
              Delete{selected.size > 0 ? ` (${selected.size})` : ''}
            </button>
          </div>
        )}
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
                  selected={selected?.has(b.name) ?? false}
                  onToggleSelected={selected && onToggleSelected
                    ? () => onToggleSelected(b.name) : undefined}
                  onSwitch={onSwitchBranch && !b.isCurrent ? () => onSwitchBranch(b.name) : undefined}
                  switching={switching ?? false}
                  actionSelected={selectedActionBranch === b.name}
                  focused={focusedBranch === b.name}
                  isCurrentHead={currentBranch === b.name}
                  onSelectForAction={onSelectForAction}
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
  selected,
  onToggleSelected,
  onSwitch,
  switching,
  actionSelected,
  focused,
  isCurrentHead,
  onSelectForAction,
}: {
  branch: LocalBranchDto;
  selected: boolean;
  onToggleSelected?: () => void;
  onSwitch?: () => void;
  switching: boolean;
  /** True when this card is the user's current "act on this
   *  branch" pick (set via {@link onSelectForAction}). Renders a
   *  visible highlight so the user knows the action bar will
   *  target this card on next Push / Pull / Create PR. */
  actionSelected?: boolean;
  /** True when the keyboard cursor (j/k) is currently on this
   *  card. Distinct from {@link actionSelected} — the cursor only
   *  commits to selection on Enter. */
  focused?: boolean;
  isCurrentHead?: boolean;
  onSelectForAction?: (name: string) => void;
}) {
  const switchable = onSwitch !== undefined && !switching;
  // Click toggles the action-selection. Cards in CLEAN UP keep the
  // checkbox as primary affordance, so we don't hijack their card
  // click. Same goes for cards with no onSelectForAction handler.
  const selectable = onSelectForAction !== undefined && !onToggleSelected;
  const cls = [
    'branch-card',
    branch.isCurrent ? 'branch-card--current' : '',
    branch.cleanupReason ? 'branch-card--cleanup' : '',
    selected ? 'branch-card--selected' : '',
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
        {onToggleSelected && (
          <input
            type="checkbox"
            className="branch-card__check"
            checked={selected}
            onChange={onToggleSelected}
            onClick={(e) => e.stopPropagation()}
            aria-label={`Select ${branch.name}`}
          />
        )}
        <code className="branch-card__name" title={branch.name}>
          {branch.isCurrent && <span className="branch-card__head-dot" aria-hidden="true">●</span>}
          {branch.name}
        </code>
        {branch.linkedPrNumber != null && (
          <span className="branch-card__pr">#{branch.linkedPrNumber}</span>
        )}
        {onToggleSelected && switchable && (
          // CLEAN UP cards keep the checkbox as primary action; an
          // explicit Switch button covers the "actually I want to
          // hop onto this branch first" escape hatch.
          <button
            type="button"
            className="branch-card__switch"
            onClick={(e) => { e.stopPropagation(); onSwitch?.(); }}
            title={`Switch to ${branch.name}`}
          >
            Switch
          </button>
        )}
      </header>
      <div className="branch-card__meta">
        {branch.lastCommitAt && (
          <span title={branch.lastCommitAt}>
            {formatRelativeTime(branch.lastCommitAt)}
          </span>
        )}
        {branch.hasUpstream && (branch.ahead || branch.behind) && (
          <span className="branch-card__sync">
            {(branch.ahead ?? 0) > 0 && <span title={`${branch.ahead} ahead`}>↑{branch.ahead}</span>}
            {(branch.behind ?? 0) > 0 && <span title={`${branch.behind} behind`}>↓{branch.behind}</span>}
          </span>
        )}
        {!branch.hasUpstream && !branch.cleanupReason && (
          <span className="branch-card__no-upstream">never pushed</span>
        )}
        {branch.cleanupReason && (
          <span className="branch-card__cleanup-reason">
            {CLEANUP_REASON_LABEL[branch.cleanupReason]}
          </span>
        )}
      </div>
    </article>
  );
}

function CleanupConfirmModal({
  owner,
  repo,
  branches,
  busy,
  onCancel,
  onConfirm,
}: {
  owner: string;
  repo: string;
  branches: LocalBranchDto[];
  busy: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  return (
    <div className="force-push-modal" role="dialog" aria-modal="true">
      <div className="force-push-modal__backdrop" onClick={busy ? undefined : onCancel} />
      <div className="force-push-modal__panel">
        <h2 className="force-push-modal__title">
          Delete {branches.length} branch{branches.length === 1 ? '' : 'es'}?
        </h2>
        <p className="force-push-modal__body">
          From <code>{owner}/{repo}</code>. This runs{' '}
          <code>git branch -D</code> locally — the remote isn't
          touched. Branches still flagged as cleanup candidates when
          this fires will be deleted; anything that isn't (e.g. someone
          pushed in the meantime) will be silently skipped.
        </p>
        <ul className="cleanup-modal__list">
          {branches.map(b => (
            <li key={b.name}>
              <code>{b.name}</code>
              {b.cleanupReason && (
                <span className="cleanup-modal__reason">
                  {CLEANUP_REASON_LABEL[b.cleanupReason]}
                </span>
              )}
            </li>
          ))}
        </ul>
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
            disabled={busy || branches.length === 0}
          >
            {busy ? 'Deleting…' : `Delete ${branches.length}`}
          </button>
        </div>
      </div>
    </div>
  );
}

export default LocalRepoPage;
