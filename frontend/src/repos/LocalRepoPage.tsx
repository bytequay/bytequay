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
import type { LocalBranchDto, LocalCommitDto, LocalRepoStatusDto } from '../types';
import LogoLoading from '../LogoLoading';
import { formatRelativeTime } from '../pr/utils';

type Props = {
  owner: string;
  repo: string;
  onBack: () => void;
};

type Column = 'LOCAL_WORK' | 'READY_FOR_PR' | 'IN_REVIEW';
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
];

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
function LocalRepoPage({ owner, repo, onBack }: Props) {
  const [status, setStatus] = useState<LocalRepoStatusDto | null>(null);
  const [branches, setBranches] = useState<LocalBranchDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  // Per-action busy state so each button can show its own spinner
  // without freezing the whole bar; the action bar disables all
  // buttons while any one is running so we don't fire concurrent
  // git ops in the same working tree.
  const [actionState, setActionState] = useState<'idle' | 'fetching' | 'pulling' | 'pushing' | 'branching'>('idle');
  const [actionError, setActionError] = useState<string | null>(null);
  // The +Branch popover is small enough not to warrant a full modal —
  // it's an inline disclosure right under the action bar.
  const [branchFormOpen, setBranchFormOpen] = useState(false);
  const [newBranchName, setNewBranchName] = useState('');
  const [newBranchBase, setNewBranchBase] = useState('');
  const [tab, setTab] = useState<Tab>('branches');

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
      const fresh = await window.bridge.pushLocalRepo(owner, repo);
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

  return (
    <div className="local-repo-page">
      <header className="local-repo-page__head">
        <button
          type="button"
          className="local-repo-page__back"
          onClick={onBack}
        >
          ← Repos
        </button>
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
          <div className="local-repo-page__path" title={status.localClonePath}>
            {status.localClonePath}
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

          {branches !== null && (
            <div className="branches-kanban">
              {COLUMNS.map(col => (
                <BranchColumn
                  key={col.key}
                  label={col.label}
                  subtitle={col.subtitle}
                  column={col.key}
                  branches={grouped[col.key]}
                />
              ))}
            </div>
          )}
        </>
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
      {tab === 'activity' && <ActivityTab />}
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

// Placeholder until the local activity store ships. The intent is a
// chronological feed of repo events the app cares about: branches
// created/deleted, pushes, PRs opened/merged from this clone.
function ActivityTab() {
  return (
    <div className="local-repo-tab-placeholder">
      <div className="local-repo-tab-placeholder__title">Activity</div>
      <p className="local-repo-tab-placeholder__body">
        A feed of repo-level activity (pushes, branch lifecycle, PR
        events touching this clone) will appear here. Coming in a
        follow-up slice.
      </p>
    </div>
  );
}

function groupByColumn(branches: LocalBranchDto[]): Record<Column, LocalBranchDto[]> {
  const out: Record<Column, LocalBranchDto[]> = {
    LOCAL_WORK: [],
    READY_FOR_PR: [],
    IN_REVIEW: [],
  };
  for (const b of branches) {
    if (b.linkedPrNumber != null) out.IN_REVIEW.push(b);
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
}: {
  label: string;
  subtitle: string;
  column: Column;
  branches: LocalBranchDto[];
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
        ) : (
          branches.map(b => <BranchCard key={b.name} branch={b} />)
        )}
      </div>
    </section>
  );
}

function BranchCard({ branch }: { branch: LocalBranchDto }) {
  return (
    <article className={`branch-card${branch.isCurrent ? ' branch-card--current' : ''}`}>
      <header className="branch-card__head">
        <code className="branch-card__name" title={branch.name}>
          {branch.isCurrent && <span className="branch-card__head-dot" aria-hidden="true">●</span>}
          {branch.name}
        </code>
        {branch.linkedPrNumber != null && (
          <span className="branch-card__pr">#{branch.linkedPrNumber}</span>
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
        {!branch.hasUpstream && (
          <span className="branch-card__no-upstream">never pushed</span>
        )}
      </div>
    </article>
  );
}

export default LocalRepoPage;
