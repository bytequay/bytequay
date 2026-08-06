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
import { useEffect, useState, type ReactNode } from 'react';
import Avatar from '../Avatar';
import { githubHandle } from './CommitEditorUi';
import {
  workspaceApi,
  type CherryPickPlanDto,
  type PlannedCommitDto,
  type UpstreamCherryPickJobDto,
  type UpstreamCommitDto,
  type UpstreamCommitsDto,
  type WorkspaceRepositoryDto,
} from './workspaceApi';

export function UpstreamCommitHistory({
  rows, query, loading, range, rangeExpanded, onExpandRange, onToggle,
  hasMore = false, paging = false, onLoadMore,
}: {
  rows: UpstreamCommitDto[];
  query: string;
  loading: boolean;
  range: [number, number] | null;
  rangeExpanded: boolean;
  onExpandRange: () => void;
  onToggle: (index: number) => void;
  hasMore?: boolean;
  paging?: boolean;
  onLoadMore?: () => void;
}) {
  const needle = query.trim().toLowerCase();
  const shown = rows.map((commit, index) => ({ commit, index }))
    .filter(({ commit }) => `${commit.subject} ${commit.sha} ${commit.authorName}`.toLowerCase().includes(needle));
  let previousDay = '';
  const hideMiddle = needle.length === 0 && range !== null && !rangeExpanded && range[1] - range[0] > 5;
  const hiddenCount = range === null ? 0 : Math.max(0, range[1] - range[0] - 3);
  return (
    <div
      className="wu-upstream-commit-list"
      onScroll={event => {
        if (!hasMore || paging || onLoadMore === undefined) return;
        const el = event.currentTarget;
        // Ask early enough that the next page is usually there before the
        // user reaches the end.
        if (el.scrollHeight - el.scrollTop - el.clientHeight < 400) onLoadMore();
      }}>
      {loading && <BodyMessage>Loading upstream commits…</BodyMessage>}
      {!loading && shown.map(({ commit, index }) => {
        if (hideMiddle && range !== null && index > range[0] + 1 && index < range[1] - 1) {
          if (index !== range[0] + 2) return null;
          return (
            <button type="button" className="wu-upstream-range-gap" key={`gap-${commit.sha}`} onClick={onExpandRange}>
              <strong>⋯ {hiddenCount} more selected commits</strong>
              <code>{rows[range[0] + 2]?.shortSha}…{rows[range[1] - 2]?.shortSha}</code>
              <span>show</span>
            </button>
          );
        }
        const day = commit.groupLabel ?? commitDay(commit.committedAt);
        const showDay = day !== previousDay;
        previousDay = day;
        const checked = range !== null && index >= range[0] && index <= range[1];
        return (
          <div className="wu-upstream-commit-wrap" key={commit.sha}>
            {showDay && <h2>{day}</h2>}
            {commit.tags.map(tag => (
              <div className="wu-upstream-tag" key={`${commit.sha}-${tag}`}>
                <span aria-hidden /> <code>{tag}</code><i />
              </div>
            ))}
            <label className={`wu-upstream-commit${checked ? ' selected' : ''}`}>
              <input type="checkbox" checked={checked} onChange={() => onToggle(index)} />
              <span className="wu-upstream-checkbox" aria-hidden>{checked ? '✓' : ''}</span>
              <code>{commit.shortSha}</code>
              <strong>{commit.subject}</strong>
              {commit.picked && <i title="Matched by Upstream-PR trailer">✓ in fork</i>}
              <span className="wu-upstream-author">
                <Avatar login={githubHandle(commit.authorName, commit.authorEmail)} size={16} />
                {commit.authorName}
              </span>
              <time>{commit.committedAt === null ? '' : relative(commit.committedAt)}</time>
            </label>
          </div>
        );
      })}
      {!loading && shown.length === 0 && <BodyMessage>No upstream commits found.</BodyMessage>}
      {paging && <BodyMessage>Loading more…</BodyMessage>}
      {!loading && !paging && hasMore && onLoadMore !== undefined && (
        // A visible fallback: a filter can leave the list too short to scroll,
        // and then the scroll handler never fires.
        <button type="button" className="wu-upstream-load-more" onClick={onLoadMore}>
          Load more commits
        </button>
      )}
    </div>
  );
}

export function UpstreamCherryPicker({
  workspaceId, repo, snapshot, commits, fromSha, toSha, onClose, onOpenHarness,
}: {
  workspaceId: string;
  repo: WorkspaceRepositoryDto;
  snapshot: UpstreamCommitsDto;
  commits: UpstreamCommitDto[];
  /** When set, the range is resolved server-side and may reach commits the
   *  list has not loaded. Takes precedence over the checkbox selection. */
  fromSha?: string;
  toSha?: string;
  onClose: () => void;
  onOpenHarness?: (watchId?: string) => void;
}) {
  const byRange = fromSha !== undefined && toSha !== undefined;
  const [targetBranch, setTargetBranch] = useState(() => suggestedTarget(snapshot, commits));
  const [openDraftPr, setOpenDraftPr] = useState(true);
  const [createHarnessWatch, setCreateHarnessWatch] = useState(true);
  const [budgetUsd, setBudgetUsd] = useState('5.00');
  const [prDescription, setPrDescription] = useState('');
  const [skipStartsWith, setSkipStartsWith] = useState('');
  const [skipContains, setSkipContains] = useState('');
  const [plan, setPlan] = useState<CherryPickPlanDto | null>(null);
  const [planning, setPlanning] = useState(false);
  const [planError, setPlanError] = useState<string | null>(null);
  const [job, setJob] = useState<UpstreamCherryPickJobDto | null>(null);
  const [discoveringJob, setDiscoveringJob] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const skipped = commits.filter(commit => commit.picked).length;
  const parsedBudget = Number(budgetUsd);
  const budgetValid = !createHarnessWatch
    || (Number.isFinite(parsedBudget) && parsedBudget >= 0.10 && parsedBudget <= 100);

  useEffect(() => {
    let cancelled = false;
    void workspaceApi.upstreamCherryPicks(workspaceId)
      .then(jobs => {
        if (cancelled) return;
        const active = jobs.find(candidate => candidate.status === 'QUEUED'
          || candidate.status === 'RUNNING'
          || candidate.status === 'PAUSED_CONFLICT');
        setJob(active ?? jobs.find(candidate => candidate.status === 'FAILED') ?? null);
      })
      .catch(() => { /* Starting a new job remains available if discovery fails. */ })
      .finally(() => { if (!cancelled) setDiscoveringJob(false); });
    return () => { cancelled = true; };
  }, [workspaceId]);

  useEffect(() => {
    if (job === null || (job.status !== 'QUEUED' && job.status !== 'RUNNING')) return undefined;
    let cancelled = false;
    let timer: number | undefined;
    const poll = () => {
      timer = window.setTimeout(() => {
        void workspaceApi.upstreamCherryPick(workspaceId, job.jobId)
          .then(next => {
            if (!cancelled) {
              setError(null);
              setJob(next);
            }
          })
          .catch(reason => {
            if (!cancelled) {
              setError(errorMessage(reason));
              poll();
            }
          });
      }, 900);
    };
    poll();
    return () => {
      cancelled = true;
      if (timer !== undefined) window.clearTimeout(timer);
    };
  }, [job, workspaceId]);

  const startsWithTerms = splitTerms(skipStartsWith);
  const containsTerms = splitTerms(skipContains);
  const selection = byRange
    ? { fromSha, toSha, skipStartsWith: startsWithTerms, skipContains: containsTerms }
    : {
      shas: commits.map(commit => commit.sha),
      skipStartsWith: startsWithTerms,
      skipContains: containsTerms,
    };
  // The plan is only valid for the filters it was run with; editing either box
  // clears it so a stale preview can never be mistaken for the current one.
  useEffect(() => { setPlan(null); setPlanError(null); }, [skipStartsWith, skipContains]);

  const runDryRun = () => {
    setPlanning(true);
    setPlanError(null);
    void workspaceApi.previewUpstreamCherryPick(workspaceId, {
      sourceBranch: snapshot.revision,
      ...selection,
    }).then(setPlan)
      .catch(reason => setPlanError(errorMessage(reason)))
      .finally(() => setPlanning(false));
  };

  return (
    <div className="wu-modal-backdrop wu-upstream-cherry-backdrop" role="presentation" onMouseDown={onClose}>
      <section className="wu-upstream-cherry" role="dialog" aria-modal="true" aria-labelledby="upstream-cherry-title"
        onMouseDown={event => event.stopPropagation()}>
        <header><span aria-hidden>⑂</span><h2 id="upstream-cherry-title">Cherry-pick into {repo.repo}</h2>
          <button type="button" onClick={onClose}>×</button></header>
        {job === null ? (
          <div className="wu-upstream-cherry__body">
            <div className="wu-upstream-cherry__summary">
              <strong>{byRange
                ? `Range from ${snapshot.upstreamWorkspaceName}/${snapshot.revision}`
                : `${commits.length} commits from ${snapshot.upstreamWorkspaceName}/${snapshot.revision}`}</strong>
              <code>{byRange
                ? `${fromSha?.slice(0, 10)}…${toSha?.slice(0, 10)}`
                : `${commits[0]?.shortSha}…${commits.at(-1)?.shortSha}`}</code>
              <span>{byRange
                ? 'Run a dry run to see the resolved commits.'
                : <>{skipped} already in {repo.repo} will be <b>skipped</b>.</>}</span>
            </div>
            <label className="wu-upstream-cherry__branch">
              <strong>TARGET BRANCH</strong>
              <span><input value={targetBranch} onChange={event => setTargetBranch(event.target.value)} />
                <small>new branch from <code>{repo.fullName}/{defaultBranch(repo)}</code></small></span>
            </label>
            <label className="wu-upstream-cherry__branch">
              <strong>SKIP COMMITS WHOSE SUBJECT…</strong>
              <span className="wu-upstream-cherry__filters">
                <label>starts with
                  <input aria-label="Skip commits whose subject starts with"
                    value={skipStartsWith} onChange={event => setSkipStartsWith(event.target.value)} />
                </label>
                <label>contains
                  <input aria-label="Skip commits whose subject contains"
                    value={skipContains} onChange={event => setSkipContains(event.target.value)} />
                </label>
              </span>
            </label>
            <div className="wu-upstream-cherry__dryrun">
              <button type="button" onClick={runDryRun} disabled={planning}>
                {planning ? 'Checking…' : 'Dry run'}
              </button>
              {plan === null
                ? <small>See exactly which commits will be picked and which skipped.</small>
                : (
                  <div className="wu-upstream-cherry__plan">
                    <PlanList label="will be cherry-picked" tone="pick" defaultOpen
                      rows={plan.commits.filter(entry => entry.pick)} />
                    <PlanList label="skipped" tone="skip"
                      rows={plan.commits.filter(entry => !entry.pick)} />
                  </div>
                )}
              {planError !== null && <span className="wu-form-error">{planError}</span>}
            </div>
            <CherryOption label="Open a draft PR"
              detail={`${repo.fullName}/${defaultBranch(repo)} ← ${targetBranch || 'new branch'} · one Upstream-PR trailer per pick.`}
              checked={openDraftPr} onChange={value => {
                setOpenDraftPr(value);
                if (!value) setCreateHarnessWatch(false);
              }} />
            <CherryOption label="Watch with CI Harness"
              detail="Loops on CI, applies proposals, verifies every fix, and ends with a handoff. It never pushes."
              checked={createHarnessWatch} disabled={!openDraftPr}
              extra={<label className="wu-upstream-cherry__budget">budget $<input aria-label="Harness budget in dollars"
                aria-invalid={!budgetValid} inputMode="decimal" value={budgetUsd}
                onChange={event => setBudgetUsd(event.target.value)} /></label>}
              onChange={setCreateHarnessWatch} />
            {!budgetValid && <span className="wu-form-error">Harness budget must be $0.10–$100.00.</span>}
            {openDraftPr && (
              <label className="wu-upstream-cherry__branch">
                <strong>PULL REQUEST DESCRIPTION</strong>
                <span><textarea rows={4} value={prDescription}
                  placeholder="Why this bump, what reviewers should watch for…"
                  onChange={event => setPrDescription(event.target.value)} />
                  <small>optional · provenance and trailers are appended automatically</small></span>
              </label>
            )}
            <p className="wu-upstream-cherry__guard">⌾ Runs in an app-owned worktree, never your checkout · backup ref before history rewrites · conflicts pause for you.</p>
            {error !== null && <span className="wu-form-error">{error}</span>}
          </div>
        ) : (
          <div className={`wu-upstream-cherry__result is-${job.status.toLowerCase()}`}>
            <strong>{jobStatusTitle(job)}</strong>
            <p>{job.errorMessage ?? jobResultCopy(job)}</p>
            {job.prNumber !== null && job.prUrl !== null && (
              <button type="button" onClick={() => { void window.bridge.openInAppBrowser(job.prUrl as string); }}>
                Open draft PR #{job.prNumber}
              </button>
            )}
            {job.conflictPaths.length > 0 && <code>{job.conflictPaths.join(' · ')}</code>}
            {error !== null && <span className="wu-form-error">{error}{
              job.status === 'QUEUED' || job.status === 'RUNNING' ? ' · retrying…' : ''
            }</span>}
          </div>
        )}
        <footer>
          <span>{createHarnessWatch ? 'Creates a Harness watch on the draft PR.' : 'No remote is changed without confirmation.'}</span>
          <button type="button" onClick={onClose}>{job === null
            ? 'Cancel'
            : job.status === 'QUEUED' || job.status === 'RUNNING' ? 'Close' : 'Done'}</button>
          {job === null && (
            <button type="button" className="wu-primary-button"
              disabled={busy || discoveringJob || targetBranch.trim().length === 0 || !budgetValid}
              onClick={() => {
                setBusy(true);
                setError(null);
                void workspaceApi.createUpstreamCherryPick(workspaceId, {
                  sourceBranch: snapshot.revision,
                  targetBranch: targetBranch.trim(),
                  ...selection,
                  prDescription: prDescription.trim() === '' ? null : prDescription.trim(),
                  openDraftPr,
                  createHarnessWatch,
                  budgetMilliUsd: createHarnessWatch ? Math.round(parsedBudget * 1000) : null,
                }).then(setJob)
                  .catch(reason => setError(errorMessage(reason)))
                  .finally(() => setBusy(false));
              }}>{busy ? 'Starting…' : 'Start cherry-pick'}</button>
          )}
          {job?.status === 'PAUSED_CONFLICT' && (
            <button type="button" className="wu-primary-button" disabled={busy} onClick={() => {
              setBusy(true);
              setError(null);
              void workspaceApi.resumeUpstreamCherryPick(workspaceId, job.jobId)
                .then(setJob)
                .catch(reason => setError(errorMessage(reason)))
                .finally(() => setBusy(false));
            }}>{busy ? 'Resuming…' : 'Resume after resolving'}</button>
          )}
          {job?.status === 'FAILED' && (
            <button type="button" disabled={busy} onClick={() => {
              setError(null);
              setJob(null);
            }}>Start another</button>
          )}
          {job?.status === 'FAILED' && (
            <button type="button" className="wu-primary-button" disabled={busy} onClick={() => {
              setBusy(true);
              setError(null);
              void workspaceApi.retryUpstreamCherryPick(workspaceId, job.jobId)
                .then(setJob)
                .catch(reason => setError(errorMessage(reason)))
                .finally(() => setBusy(false));
            }}>{busy ? 'Retrying…' : 'Retry cherry-pick'}</button>
          )}
          {job?.harnessWatchId !== null && job?.harnessWatchId !== undefined && (
            <button type="button" className="wu-primary-button"
              onClick={() => onOpenHarness?.(job.harnessWatchId as string)}>Open CI Harness</button>
          )}
        </footer>
      </section>
    </div>
  );
}

function CherryOption({ label, detail, checked, disabled = false, extra, onChange }: {
  label: string;
  detail: string;
  checked: boolean;
  disabled?: boolean;
  extra?: ReactNode;
  onChange: (checked: boolean) => void;
}) {
  return (
    <div className={`wu-upstream-cherry__option${checked ? ' selected' : ''}${disabled ? ' disabled' : ''}`}>
      <button type="button" role="switch" aria-label={label} aria-checked={checked} disabled={disabled}
        className={`wu-switch${checked ? ' on' : ''}`} onClick={() => onChange(!checked)}><i /></button>
      <span><strong>{label}</strong><small>{detail}</small></span>
      {extra}
    </div>
  );
}

export function contiguousRangeAfterToggle(
  current: [number, number] | null,
  index: number,
): [number, number] | null {
  if (current === null) return [index, index];
  const [start, end] = current;
  if (index < start) return [index, end];
  if (index > end) return [start, index];
  if (start === end) return null;
  if (index === start) return [start + 1, end];
  if (index === end) return [start, end - 1];
  return [start, index - 1];
}

export function rangeLabel(
  commitsNewestFirst: UpstreamCommitDto[],
  olderBoundary?: UpstreamCommitDto,
): string {
  const tags = commitsNewestFirst.flatMap(commit => commit.tags);
  const newestTag = tags.at(0);
  const inRangeOldestTag = tags.at(-1);
  const oldestTag = inRangeOldestTag !== undefined && inRangeOldestTag !== newestTag
    ? inRangeOldestTag : olderBoundary?.tags[0];
  return newestTag !== undefined && oldestTag !== undefined && newestTag !== oldestTag
    ? `${oldestTag} → ${newestTag}` : 'contiguous range';
}

function PlanList({ label, tone, rows, defaultOpen = false }: {
  label: string;
  tone: 'pick' | 'skip';
  rows: PlannedCommitDto[];
  defaultOpen?: boolean;
}) {
  return (
    <details className={`wu-plan-list is-${tone}`} open={defaultOpen && rows.length > 0}>
      <summary><b>{rows.length}</b> {label}</summary>
      {rows.length === 0
        ? <p className="wu-plan-list__empty">None.</p>
        : (
          <ul>
            {rows.map(entry => (
              <li key={entry.sha}>
                <code>{entry.shortSha}</code>
                <span title={entry.subject}>{entry.subject}</span>
                {entry.skipReason !== null && <em>{entry.skipReason}</em>}
              </li>
            ))}
          </ul>
        )}
    </details>
  );
}

/** Comma-separated filter terms; blanks dropped so an empty box filters nothing. */
function splitTerms(value: string): string[] {
  return value.split(',').map(term => term.trim()).filter(term => term.length > 0);
}

function suggestedTarget(snapshot: UpstreamCommitsDto, commits: UpstreamCommitDto[]): string {
  const tag = commits.find(commit => commit.tags.length > 0)?.tags[0];
  const suffix = tag?.match(/\d+(?:\.\d+)*/)?.[0]?.replaceAll('.', '-') ?? 'update';
  return `${snapshot.upstreamWorkspaceName.toLowerCase().replace(/[^a-z0-9-]/g, '-')}-${suffix}`;
}

function defaultBranch(repo: WorkspaceRepositoryDto): string {
  return (repo.local.defaultBranch ?? repo.defaultBaseBranch ?? 'main').replace(/^origin\//, '');
}

function commitDay(value: string | null): string {
  if (value === null) return 'Earlier';
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? 'Earlier' : parsed.toLocaleDateString(undefined, {
    month: 'short', day: 'numeric', year: 'numeric',
  });
}

function jobStatusTitle(job: UpstreamCherryPickJobDto): string {
  if (job.status === 'COMPLETED') return 'Cherry-pick complete';
  if (job.status === 'PAUSED_CONFLICT') return 'Conflict needs you';
  if (job.status === 'FAILED') return 'Cherry-pick failed';
  return 'Cherry-pick in progress';
}

function jobResultCopy(job: UpstreamCherryPickJobDto): string {
  if (job.status === 'COMPLETED') {
    const result = `${job.appliedCount} applied, ${job.skippedCount} skipped on ${job.resultBranch}.`;
    return job.prNumber === null
      ? `${result} The branch remains local; no remote was changed.`
      : `${result} Draft PR #${job.prNumber} was opened.`;
  }
  if (job.status === 'PAUSED_CONFLICT') {
    return `Paused in ${job.worktreePath ?? 'the isolated worktree'} for ${job.conflictPaths.length} conflict${job.conflictPaths.length === 1 ? '' : 's'}.`;
  }
  if (job.status === 'FAILED') return 'Retry to continue from the last durable commit.';
  return 'The local operation is still running.';
}

function BodyMessage({ children }: { children: ReactNode }) {
  return <div className="wu-body-message">{children}</div>;
}

function relative(iso: string): string {
  const elapsed = Date.now() - Date.parse(iso);
  if (!Number.isFinite(elapsed)) return '';
  if (elapsed < 60_000) return 'now';
  if (elapsed < 3_600_000) return `${Math.floor(elapsed / 60_000)}m`;
  if (elapsed < 86_400_000) return `${Math.floor(elapsed / 3_600_000)}h`;
  return `${Math.floor(elapsed / 86_400_000)}d`;
}

function errorMessage(value: unknown): string {
  return value instanceof Error ? value.message : String(value);
}
