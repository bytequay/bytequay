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
import { useEffect, useRef, useState, type ReactNode } from 'react';
import Avatar from '../Avatar';
import { relativeTime } from '../relativeTime';
import { githubHandle } from './CommitEditorUi';
import { message } from './WorkspaceRepoUi';
import { isFlowRun } from './syncRunModel';
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
              <time>{relativeTime(commit.committedAt, { suffix: false })}</time>
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
  workspaceId, repo, snapshot, commits, fromSha, toSha, onClose, onOpenSync,
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
  onOpenSync?: (jobId: string) => void;
}) {
  const byRange = fromSha !== undefined && toSha !== undefined;
  const [repairTurns, setRepairTurns] = useState('');
  const [prTitle, setPrTitle] = useState('');
  const [prDescription, setPrDescription] = useState('');
  const [skipStartsWith, setSkipStartsWith] = useState(['']);
  const [skipContains, setSkipContains] = useState(['']);
  const [plan, setPlan] = useState<CherryPickPlanDto | null>(null);
  const [planning, setPlanning] = useState(false);
  const [planError, setPlanError] = useState<string | null>(null);
  const [job, setJob] = useState<UpstreamCherryPickJobDto | null>(null);
  /** A run already going elsewhere in this workspace — shown, never blocking. */
  const [liveJob, setLiveJob] = useState<UpstreamCherryPickJobDto | null>(null);
  const [discoveringJob, setDiscoveringJob] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Reuse only while retrying one uncertain POST. A completed POST clears the
  // key, so reopening the picker for the same commits starts a new run.
  const pendingCommandId = useRef<string | null>(null);
  const skipped = commits.filter(commit => commit.picked).length;
  const uncapped = repairTurns.trim() === '';
  const parsedTurns = Number(repairTurns);
  const turnsValid = uncapped || (Number.isInteger(parsedTurns)
    && parsedTurns >= 0 && parsedTurns <= 500);

  useEffect(() => {
    let cancelled = false;
    // Both sources: a run started before the cutover is still running on the
    // path that started it, and it is worth reporting here just the same.
    void Promise.all([
      workspaceApi.upstreamSyncs(workspaceId).catch(noRuns),
      workspaceApi.upstreamCherryPicks(workspaceId).catch(noRuns),
    ])
      .then(([flow, legacy]) => [...flow, ...legacy])
      .then(jobs => {
        if (cancelled) return;
        // A closed run is over whatever its status says. Closing only stamped
        // closedAt and left the status at PAUSED_CONFLICT, so a finished run
        // still looked parked and held this dialog open on it forever.
        const open = jobs.filter(candidate => candidate.closedAt === null);
        // A run actively going is worth showing — that is how progress survives
        // an app restart — and so is a failed one, which offers its retry. Both
        // have "Start another" to get back to the picker.
        setJob(open.find(candidate => candidate.status === 'QUEUED'
          || candidate.status === 'RUNNING'
          || candidate.status === 'FAILED') ?? null);
        // A parked run is different: it is waiting on a human somewhere else and
        // has no way back to the picker, so it used to hold this dialog shut on
        // an unrelated range. It is reported instead. Runs are independent —
        // each has its own worktree and the backend refuses nothing but a
        // branch name already taken.
        setLiveJob(open.find(candidate => candidate.status === 'PAUSED_CONFLICT') ?? null);
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
        void (isFlowRun(job.jobId)
          ? workspaceApi.upstreamSyncRun(workspaceId, job.jobId)
            .then(detail => detail.job)
          : workspaceApi.upstreamCherryPick(workspaceId, job.jobId))
          .then(next => {
            if (!cancelled) {
              setError(null);
              setJob(next);
            }
          })
          .catch(reason => {
            if (!cancelled) {
              setError(message(reason));
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
  // The plan is only valid for the filters it was run with; editing any box
  // clears it so a stale preview can never be mistaken for the current one.
  const filterKey = `${startsWithTerms.join('\n')} ${containsTerms.join('\n')}`;
  useEffect(() => { setPlan(null); setPlanError(null); }, [filterKey]);

  const runDryRun = () => {
    setPlanning(true);
    setPlanError(null);
    void workspaceApi.previewUpstreamCherryPick(workspaceId, {
      sourceBranch: snapshot.revision,
      ...selection,
    }).then(setPlan)
      .catch(reason => setPlanError(message(reason)))
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
            {/* The run names its own branch and worktree when it starts, from
                the same launch record that pins its base — so there is nothing
                to type here, and a field that did nothing would be a lie. */}
            <p className="wu-upstream-cherry__note">
              The run creates its own branch from <code>{repo.fullName}/
                {defaultBranch(repo)}</code> and picks in an isolated worktree.
            </p>
            <label className="wu-upstream-cherry__branch">
              <strong>SKIP COMMITS WHOSE SUBJECT…</strong>
              <span className="wu-upstream-cherry__filters">
                <SkipRules label="starts with" rules={skipStartsWith} onChange={setSkipStartsWith} />
                <SkipRules label="contains" rules={skipContains} onChange={setSkipContains} />
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
            <label className="wu-upstream-cherry__branch">
              <strong>CONFLICT-REPAIR TURNS</strong>
              <span className="wu-upstream-cherry__budget"><input
                aria-label="Conflict repair turns"
                aria-invalid={!turnsValid} inputMode="numeric" value={repairTurns}
                placeholder="No limit"
                onChange={event => setRepairTurns(event.target.value)} />
                <small>Optional cap on agent turns — one is spent per conflict
                  repaired. Leave blank and the range runs to the end; set a
                  cap and the run pauses for you when it is spent.</small>
              </span>
            </label>
            {!turnsValid && (
              <span className="wu-form-error">Repair turns must be blank or 0–500.</span>
            )}
            <label className="wu-upstream-cherry__branch">
              <strong>PULL REQUEST TITLE</strong>
              <span><input maxLength={256} value={prTitle}
                placeholder="Sync 5 upstream commits"
                onChange={event => setPrTitle(event.target.value)} />
                <small>optional · left blank, the run names the PR itself</small></span>
            </label>
            <label className="wu-upstream-cherry__branch">
              <strong>PULL REQUEST DESCRIPTION</strong>
              <span><textarea rows={4} value={prDescription}
                placeholder="Why this bump, what reviewers should watch for…"
                onChange={event => setPrDescription(event.target.value)} />
                <small>optional · the range and its provenance are appended
                  automatically</small></span>
            </label>
            <p className="wu-upstream-cherry__guard">⌾ Runs in an app-owned worktree, never your checkout ·
              conflicts are repaired and re-checked before the range moves on ·
              nothing is pushed until you authorize the first push.</p>
            {liveJob !== null && (
              <p className="wu-upstream-cherry__note">
                A sync run is parked on <code>{liveJob.resultBranch}</code>, waiting on you.
                It has its own worktree — starting this range does not touch it.
              </p>
            )}
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
          <span>No remote is changed without confirmation.</span>
          <button type="button" onClick={onClose}>{job === null
            ? 'Cancel'
            : job.status === 'QUEUED' || job.status === 'RUNNING' ? 'Close' : 'Done'}</button>
          {job === null && (
            <button type="button" className="wu-primary-button"
              disabled={busy || discoveringJob || !turnsValid}
              onClick={() => {
                setBusy(true);
                setError(null);
                const commandId = pendingCommandId.current
                  ?? globalThis.crypto.randomUUID();
                pendingCommandId.current = commandId;
                // The run owns a range, so it is confirmed as an explicit list
                // of commits. The dry run is what resolves a typed range and
                // the filters into that list — including commits the visible
                // page never loaded.
                void workspaceApi.previewUpstreamCherryPick(workspaceId, {
                  sourceBranch: snapshot.revision,
                  ...selection,
                })
                  .then(resolved => {
                    const picked = resolved.commits.filter(entry => entry.pick);
                    if (picked.length === 0) {
                      throw new Error('The filters leave no commit to pick.');
                    }
                    return workspaceApi.createUpstreamSync(workspaceId, {
                      commandId,
                      commits: picked.map(entry => ({
                        sha: entry.sha, subject: entry.subject,
                      })),
                      goalText: prDescription.trim() === ''
                        ? `Cherry-pick ${picked.length} commit${
                          picked.length === 1 ? '' : 's'} from ${
                          snapshot.upstreamWorkspaceName}/${snapshot.revision}`
                        : prDescription.trim(),
                      prTitle: prTitle.trim() === ''
                        ? undefined : prTitle.trim(),
                      sourceRemote: snapshot.upstreamRepoFullName,
                      // The dry run answers oldest first, which is the order
                      // the picks are applied in.
                      sourceFromRef: picked[0].sha,
                      sourceToRef: picked[picked.length - 1].sha,
                      targetRef: defaultBranch(repo),
                      repairTurnBudget: uncapped ? undefined : parsedTurns,
                    });
                  })
                  .then(next => {
                    pendingCommandId.current = null;
                    setJob(next);
                  })
                  .catch(reason => setError(message(reason)))
                  .finally(() => setBusy(false));
              }}>{busy ? 'Starting…' : 'Start cherry-pick'}</button>
          )}
          {job?.status === 'PAUSED_CONFLICT' && !isFlowRun(job.jobId) && (
            <button type="button" className="wu-primary-button" disabled={busy} onClick={() => {
              setBusy(true);
              setError(null);
              void workspaceApi.resumeUpstreamCherryPick(workspaceId, job.jobId)
                .then(setJob)
                .catch(reason => setError(message(reason)))
                .finally(() => setBusy(false));
            }}>{busy ? 'Resuming…' : 'Resume after resolving'}</button>
          )}
          {job?.status === 'FAILED' && (
            <button type="button" disabled={busy} onClick={() => {
              setError(null);
              setJob(null);
            }}>Start another</button>
          )}
          {job?.status === 'FAILED' && !isFlowRun(job.jobId) && (
            <button type="button" className="wu-primary-button" disabled={busy} onClick={() => {
              setBusy(true);
              setError(null);
              void workspaceApi.retryUpstreamCherryPick(workspaceId, job.jobId)
                .then(setJob)
                .catch(reason => setError(message(reason)))
                .finally(() => setBusy(false));
            }}>{busy ? 'Retrying…' : 'Retry cherry-pick'}</button>
          )}
          {job !== null && (
            // A started run opens in its own upstream-sync cockpit.
            <button type="button" className="wu-primary-button"
              onClick={() => onOpenSync?.(job.jobId)}>Open sync run</button>
          )}
        </footer>
      </section>
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

/** A source that cannot answer contributes nothing, never a failed load. */
function noRuns(): UpstreamCherryPickJobDto[] {
  return [];
}

/** One row per rule, still comma-splitting each so a pasted list keeps working;
 *  blanks are dropped, which is what makes an untouched row filter nothing. */
function splitTerms(rules: string[]): string[] {
  return rules.flatMap(rule => rule.split(','))
    .map(term => term.trim()).filter(term => term.length > 0);
}

/** A stack of subject rules with a "+" that adds another; any non-blank row
 *  skips a commit, so several rules mean several subjects skipped. */
function SkipRules({ label, rules, onChange }: {
  label: string;
  rules: string[];
  onChange: (rules: string[]) => void;
}) {
  return (
    <label>{label}
      {rules.map((rule, index) => (
        // Rows are identified by position: nothing reorders them, and the
        // value is the only thing that changes.
        <input key={index} value={rule}
          aria-label={`Skip commits whose subject ${label}${index === 0 ? '' : ` ${index + 1}`}`}
          onChange={event => onChange(rules.map(
            (old, position) => (position === index ? event.target.value : old)))} />
      ))}
      <button type="button" className="wu-upstream-cherry__add-rule"
        aria-label={`Add a rule for subjects that ${label}`}
        onClick={() => onChange([...rules, ''])}>+</button>
    </label>
  );
}

function short(sha: string | undefined): string | null {
  const value = (sha ?? '').trim().toLowerCase();
  return /^[0-9a-f]{7,40}$/.test(value) ? value.slice(0, 7) : null;
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
  if (job.status === 'CLOSED') return 'Run closed';
  if (job.status === 'COMPLETED') return 'Cherry-pick complete';
  if (job.status === 'PAUSED_CONFLICT') return 'Conflict needs you';
  if (job.status === 'FAILED') return 'Cherry-pick failed';
  return 'Cherry-pick in progress';
}

function jobResultCopy(job: UpstreamCherryPickJobDto): string {
  if (job.status === 'CLOSED') return 'This run is closed and no operation is running.';
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
