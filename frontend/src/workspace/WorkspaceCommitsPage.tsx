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
import { useEffect, useMemo, useState } from 'react';
import type {
  LocalCommitDetailDto,
  LocalCommitDto,
  LocalCommitFileDto,
} from '../types';
import {
  workspaceApi,
  type CherryPickResultDto,
  type UpstreamCommitsDto,
  type WorkspaceBranchDto,
  type WorkspaceRelationDto,
  type WorkspaceRepositoryDto,
} from './workspaceApi';
import {
  contiguousRangeAfterToggle,
  rangeLabel,
  UpstreamCherryPicker,
  UpstreamCommitHistory,
} from './WorkspaceUpstreamCommits';
import {
  BodyMessage,
  BranchCheckIcon,
  BranchIcon,
  ChevronDownIcon,
  ExternalIcon,
  PageHeader,
  SearchIcon,
  isToday,
  message,
  prInitials,
  relative,
} from './WorkspaceRepoUi';

type Props = {
  workspaceId: string;
  repo: WorkspaceRepositoryDto;
  onOpenTrunk?: (trunkId: string) => void;
  onOpenHarness?: (watchId?: string) => void;
};

export default function WorkspaceCommitsPage({
  workspaceId,
  repo,
  onOpenTrunk,
  onOpenHarness,
}: Props) {
  const visualFrame = document.documentElement.dataset.workspaceVisualFrame;
  const visualCommitStudy = visualFrame === '3g' || visualFrame === '4a';
  const [rows, setRows] = useState<LocalCommitDto[]>([]);
  const [branches, setBranches] = useState<WorkspaceBranchDto[]>([]);
  const [branch, setBranch] = useState(visualCommitStudy
    ? 'master'
    : repo.local.currentBranch
    ?? repo.local.defaultBranch?.replace(/^origin\//, '')
    ?? repo.defaultBaseBranch?.replace(/^origin\//, '')
    ?? 'HEAD');
  const [selected, setSelected] = useState<LocalCommitDto | null>(null);
  const [detail, setDetail] = useState<LocalCommitDetailDto | null>(null);
  const [files, setFiles] = useState<LocalCommitFileDto[]>([]);
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [cherryOpen, setCherryOpen] = useState(false);
  const [source, setSource] = useState<'fork' | 'upstream'>('fork');
  const [relation, setRelation] = useState<WorkspaceRelationDto | null>(null);
  const [upstream, setUpstream] = useState<UpstreamCommitsDto | null>(null);
  const [upstreamRange, setUpstreamRange] = useState<[number, number] | null>(null);
  const [rangeExpanded, setRangeExpanded] = useState(false);
  const [upstreamCherryOpen, setUpstreamCherryOpen] = useState(false);

  useEffect(() => {
    let cancelled = false;
    void workspaceApi.relation(workspaceId)
      .then(next => { if (!cancelled) setRelation(next); })
      .catch(() => { if (!cancelled) setRelation(null); });
    return () => { cancelled = true; };
  }, [workspaceId]);

  useEffect(() => {
    if (source !== 'fork') return undefined;
    let cancelled = false;
    setLoading(true);
    void Promise.all([
      workspaceApi.commits(workspaceId, branch === 'HEAD' ? undefined : branch),
      workspaceApi.branches(workspaceId),
    ])
      .then(([result, nextBranches]) => {
        if (!cancelled) {
          setRows(result);
          setBranches(nextBranches);
          setSelected(document.documentElement.dataset.workspaceVisualFrame === '4a'
            ? result[0] ?? null
            : null);
          setError(null);
        }
      })
      .catch(reason => { if (!cancelled) setError(message(reason)); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [branch, source, workspaceId]);

  useEffect(() => {
    if (source !== 'upstream') return undefined;
    let cancelled = false;
    setLoading(true);
    setUpstreamRange(null);
    setRangeExpanded(false);
    void workspaceApi.upstreamCommits(workspaceId)
      .then(next => {
        if (cancelled) return;
        setUpstream(next);
        setError(null);
      })
      .catch(reason => { if (!cancelled) setError(message(reason)); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [source, workspaceId]);

  useEffect(() => {
    if (source !== 'fork' || selected === null) {
      setDetail(null);
      setFiles([]);
      return;
    }
    setDetail(null);
    setFiles([]);
    let cancelled = false;
    void Promise.all([
      workspaceApi.commit(workspaceId, selected.sha),
      workspaceApi.commitFiles(workspaceId, selected.sha),
    ]).then(([nextDetail, nextFiles]) => {
      if (!cancelled) {
        setDetail(nextDetail);
        setFiles(nextFiles);
      }
    }).catch(reason => { if (!cancelled) setError(message(reason)); });
    return () => { cancelled = true; };
  }, [workspaceId, selected, source]);

  const shown = rows.filter(row => `${row.subject} ${row.sha} ${row.authorName}`
    .toLowerCase().includes(query.trim().toLowerCase()));
  const groups = useMemo(() => groupCommits(shown), [shown]);

  return (
    <section className="wu-page wu-commits wu-commit-history">
      <PageHeader title="Commits">
        <span className="wu-commit-source" role="group" aria-label="Commit source">
          <button type="button" className={source === 'fork' ? 'active' : ''}
            onClick={() => setSource('fork')}>{repo.repo}</button>
          <button type="button" className={source === 'upstream' ? 'active' : ''}
            disabled={relation === null || !relation.commitsEnabled}
            title={relation === null ? 'Link an upstream workspace in Settings → Relations' : undefined}
            onClick={() => setSource('upstream')}>
            <span aria-hidden>⑂</span>{relation?.upstreamWorkspaceName ?? 'upstream'}<small>UPSTREAM</small>
          </button>
        </span>
        {source === 'fork' ? (
          <label className="wu-branch-select"><BranchIcon />
            <select value={branch} onChange={event => setBranch(event.target.value)}>
              {branches.length === 0 && <option value={branch}>{branch}</option>}
              {branches.filter(candidate => !candidate.remoteOnly).map(candidate => (
                <option value={candidate.name} key={candidate.name}>{candidate.name}</option>
              ))}
            </select>
            <span>{branch}</span>
            <ChevronDownIcon />
          </label>
        ) : (
          <span className="wu-branch-select is-static"><BranchIcon />
            <span>{upstream?.revision ?? 'default'}</span>
          </span>
        )}
        <label className="wu-search">
          <SearchIcon />
          <input value={query} onChange={event => setQuery(event.target.value)} placeholder="Search commits…" />
        </label>
      </PageHeader>
      {source === 'upstream' && upstream !== null && (
        <div className="wu-upstream-banner">
          <span aria-hidden>⑂</span>
          <span>Reading from upstream workspace <b>{upstream.upstreamWorkspaceName}</b>
            {' '}({upstream.upstreamRepoFullName}) — read-only
            {upstream.lastFetchedAt !== null && <> · fetched {relative(upstream.lastFetchedAt)}</>}</span>
          <i>{upstream.notInForkCount.toLocaleString()} not in {repo.repo}</i>
          <button type="button" onClick={() => {
            window.location.hash = `#/workspace/${encodeURIComponent(workspaceId)}/settings/relations`;
          }}>Manage relation</button>
        </div>
      )}
      {error !== null && <div className="wu-inline-error">{error}</div>}
      {source === 'upstream' ? (
        <UpstreamCommitHistory
          rows={upstream?.commits ?? []}
          query={query}
          loading={loading}
          range={upstreamRange}
          rangeExpanded={rangeExpanded}
          onExpandRange={() => setRangeExpanded(true)}
          onToggle={index => {
            setUpstreamRange(current => contiguousRangeAfterToggle(current, index));
            setRangeExpanded(false);
          }}
        />
      ) : <div className="wu-commit-history__groups">
        {loading ? <BodyMessage>Loading commits…</BodyMessage> : [...groups].map(([day, commits]) => (
          <section key={day}>
            <h2>{day}</h2>
            <div className="wu-commit-history__list">
              {commits.map(commit => {
                const open = selected?.sha === commit.sha;
                const agent = isAgentCommit(commit);
                const expandedPresentation = selected !== null;
                const ciStatus = commit.ciStatus ?? 'passed';
                return (
                  <article className={open ? 'expanded' : ''} key={commit.sha}>
                    <button type="button" className="wu-commit-history__row"
                      onClick={() => setSelected(open ? null : commit)}>
                      {expandedPresentation && (
                        <span className="wu-disclosure" aria-hidden>
                          <svg width="12" height="12" viewBox="0 0 24 24" fill="none"
                            stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                            <path d={open ? 'm6 9 6 6 6-6' : 'm9 18 6-6-6-6'} />
                          </svg>
                        </span>
                      )}
                      {expandedPresentation && (
                        agent
                          ? <span className="wu-agent-avatar"><CommitAgentIcon /></span>
                          : <CommitAvatarLetters name={commit.authorName} />
                      )}
                      <code>{commit.shortSha}</code>
                      <strong>{commit.subject}</strong>
                      {agent
                        ? <i className="wu-agent-pill">agent</i>
                        : <span className="wu-commit-author">{commit.authorName}</span>}
                      {!open && (
                        <b className={`wu-ci-mark ${ciStatus}`} aria-label={ciStatus}>
                          {ciStatus === 'failed'
                            ? <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                              strokeWidth="2.4" strokeLinecap="round"><path d="M18 6 6 18M6 6l12 12" /></svg>
                            : <BranchCheckIcon />}
                        </b>
                      )}
                      <time>{commit.displayTime ?? (commit.authoredAt === null ? '' : relative(commit.authoredAt))}</time>
                    </button>
                    {open && (
                      <div className="wu-commit-expanded">
                        <div className="wu-commit-expanded__body">
                          {detail?.body?.trim() || detail?.subject || commit.subject}
                          {extractPrNumber(commit.subject) !== null && !detail?.body?.includes('Refs task') && (
                            <p>Refs task history · reviewed with pull request #{extractPrNumber(commit.subject)}.</p>
                          )}
                        </div>
                        <div className="wu-commit-expanded__footer">
                          <span>
                            <b>+{files.reduce((sum, file) => sum + Math.max(file.additions, 0), 0)}</b>
                            {' '}
                            <i>−{files.reduce((sum, file) => sum + Math.max(file.deletions, 0), 0)}</i>
                            {' · '}{files.length} files
                          </span>
                          {agent && (
                            <small>
                              committed by <b>{commit.authorName}</b> on behalf of {commit.onBehalfOf ?? 'this workspace'}
                            </small>
                          )}
                          <span className="wu-row-spacer" />
                          <button type="button" onClick={() => { void navigator.clipboard.writeText(commit.sha); }}>
                            Copy SHA
                          </button>
                          <button type="button" onClick={() => setCherryOpen(true)}>Cherry-pick…</button>
                          <button type="button" onClick={() => {
                            void window.bridge.openInAppBrowser(`https://github.com/${repo.fullName}/commit/${commit.sha}`);
                          }}>GitHub<ExternalIcon /></button>
                        </div>
                      </div>
                    )}
                  </article>
                );
              })}
            </div>
          </section>
        ))}
        {!loading && rows.length === 0 && <BodyMessage>No commits found in the local clone.</BodyMessage>}
      </div>}
      {source === 'upstream' && upstreamRange !== null && upstream !== null && (
        <div className="wu-upstream-cherry-bar">
          <strong>{upstreamRange[1] - upstreamRange[0] + 1} commits</strong>
          <span>{rangeLabel(
            upstream.commits.slice(upstreamRange[0], upstreamRange[1] + 1),
            upstream.commits[upstreamRange[1] + 1],
          )}</span>
          <code>{upstream.commits[upstreamRange[0]]?.shortSha}…{upstream.commits[upstreamRange[1]]?.shortSha}</code>
          <button type="button" onClick={() => setUpstreamRange(null)}>Clear</button>
          <button type="button" onClick={() => setUpstreamCherryOpen(true)}>
            <span aria-hidden>⑂</span> Cherry-pick into {repo.repo}…
          </button>
        </div>
      )}
      {cherryOpen && selected !== null && (
        <CommitCherryPicker
          workspaceId={workspaceId}
          sourceBranch={branch}
          commit={selected}
          branches={branches}
          onOpenTrunk={onOpenTrunk}
          onClose={() => setCherryOpen(false)}
        />
      )}
      {upstreamCherryOpen && upstream !== null && upstreamRange !== null && (
        <UpstreamCherryPicker
          workspaceId={workspaceId}
          repo={repo}
          snapshot={upstream}
          commits={upstream.commits.slice(upstreamRange[0], upstreamRange[1] + 1)}
          onClose={() => setUpstreamCherryOpen(false)}
          onOpenHarness={onOpenHarness}
        />
      )}
    </section>
  );
}

function CommitCherryPicker({
  workspaceId,
  sourceBranch,
  commit,
  branches,
  onOpenTrunk,
  onClose,
}: {
  workspaceId: string;
  sourceBranch: string;
  commit: LocalCommitDto;
  branches: WorkspaceBranchDto[];
  onOpenTrunk?: (trunkId: string) => void;
  onClose: () => void;
}) {
  const choices = branches.filter(candidate => !candidate.remoteOnly && candidate.name !== sourceBranch);
  const [target, setTarget] = useState(choices[0]?.name ?? '');
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<CherryPickResultDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  return (
    <div className="wu-modal-backdrop wu-commit-cherry-backdrop" role="presentation" onMouseDown={onClose}>
      <section className="wu-commit-cherry" role="dialog" aria-modal="true"
        onMouseDown={event => event.stopPropagation()}>
        <header><h2>Cherry-pick commit</h2><button type="button" onClick={onClose}>×</button></header>
        <div className="wu-commit-cherry__commit">
          <code>{commit.shortSha}</code><strong>{commit.subject}</strong>
        </div>
        {result === null ? (
          <>
            <label>Target branch
              <select value={target} onChange={event => setTarget(event.target.value)}>
                {choices.map(candidate => <option value={candidate.name} key={candidate.name}>{candidate.name}</option>)}
              </select>
            </label>
            <p>The operation runs in an isolated worktree and never pushes automatically.</p>
            {error !== null && <span className="wu-form-error">{error}</span>}
            <footer>
              <button type="button" onClick={onClose}>Cancel</button>
              <button type="button" disabled={busy || target.length === 0} onClick={() => {
                setBusy(true);
                setError(null);
                void workspaceApi.cherryPick(workspaceId, sourceBranch, target, [commit.sha])
                  .then(setResult)
                  .catch(reason => setError(message(reason)))
                  .finally(() => setBusy(false));
              }}>{busy ? 'Cherry-picking…' : 'Cherry-pick'}</button>
            </footer>
          </>
        ) : (
          <div className={`wu-commit-cherry__result ${result.status}`}>
            <strong>{result.status === 'done' ? 'Cherry-pick complete' : 'Conflict needs a fix'}</strong>
            <p>{result.status === 'done'
              ? `Created local branch ${result.resultBranch}. Nothing was pushed.`
              : `Kept ${result.worktreePath ?? 'the isolated worktree'} and queued a CI-fix session.`}</p>
            <footer>
              {result.trunkId !== null && (
                <button type="button" onClick={() => onOpenTrunk?.(result.trunkId as string)}>
                  Open fix session
                </button>
              )}
              <button type="button" onClick={onClose}>Done</button>
            </footer>
          </div>
        )}
      </section>
    </div>
  );
}

function CommitAvatarLetters({ name }: { name: string }) {
  return (
    <span className="wu-author-avatar" aria-hidden>
      {prInitials(name)}
    </span>
  );
}

function groupCommits(rows: LocalCommitDto[]): Map<string, LocalCommitDto[]> {
  const groups = new Map<string, LocalCommitDto[]>();
  for (const row of rows) {
    const day = row.groupLabel ?? (row.authoredAt === null ? 'Earlier' : isToday(row.authoredAt)
      ? 'Today'
      : new Intl.DateTimeFormat(undefined, { weekday: 'long', month: 'short', day: 'numeric' })
        .format(new Date(row.authoredAt)));
    groups.set(day, [...(groups.get(day) ?? []), row]);
  }
  return groups;
}

function isAgentCommit(commit: LocalCommitDto): boolean {
  return `${commit.authorName} ${commit.authorEmail}`.toLowerCase().includes('agent')
    || commit.authorEmail.toLowerCase().includes('bytequay');
}

function extractPrNumber(subject: string): number | null {
  const match = /\(#(\d+)\)\s*$/.exec(subject);
  return match === null ? null : Number(match[1]);
}

function CommitAgentIcon() {
  return (
    <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <rect x="5" y="9" width="14" height="10" rx="2" />
      <path d="M12 5v4" />
      <circle cx="12" cy="4" r="1" />
      <path d="M9 13.5h.01M15 13.5h.01" />
    </svg>
  );
}
