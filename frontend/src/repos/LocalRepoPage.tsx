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
import type { LocalBranchDto, LocalRepoStatusDto } from '../types';
import LogoLoading from '../LogoLoading';
import { formatRelativeTime } from '../pr/utils';

type Props = {
  owner: string;
  repo: string;
  onBack: () => void;
};

type Column = 'LOCAL_WORK' | 'READY_FOR_PR' | 'IN_REVIEW';

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

/**
 * Repo detail page for a mapped local clone. v1 surfaces the branches
 * kanban only — Pull / Push / Fetch / + Branch / Create PR action bar,
 * plus the Commits and Activity tabs from the design doc, are
 * follow-up commits.
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
  const [actionState, setActionState] = useState<'idle' | 'fetching' | 'pulling'>('idle');
  const [actionError, setActionError] = useState<string | null>(null);

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
        </div>
        {actionError && (
          <div className="local-repo-page__action-error">{actionError}</div>
        )}
      </header>

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
