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
import { useCallback, useEffect, useState } from 'react';
import {
  workspaceApi,
  type UpstreamCommitsDto,
  type WorkspaceBranchDto,
  type WorkspaceRelationDto,
  type WorkspaceRepositoryDto,
} from './workspaceApi';
import ForkCommits from './WorkspaceForkCommits';
import WorkspaceRelationsSettings, { WORKSPACE_RELATION_CHANGED } from './WorkspaceRelationsSettings';
import {
  contiguousRangeAfterToggle,
  rangeLabel,
  UpstreamCherryPicker,
  UpstreamCommitHistory,
} from './WorkspaceUpstreamCommits';
import {
  BranchIcon,
  ChevronDownIcon,
  PageHeader,
  SearchIcon,
  message,
  relative,
} from './WorkspaceRepoUi';

type Tab = 'commits' | 'relations';

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
  const [tab, setTab] = useState<Tab>('commits');
  const [branches, setBranches] = useState<WorkspaceBranchDto[]>([]);
  const [branch, setBranch] = useState(visualCommitStudy
    ? 'master'
    : repo.local.currentBranch
    ?? repo.local.defaultBranch?.replace(/^origin\//, '')
    ?? repo.defaultBaseBranch?.replace(/^origin\//, '')
    ?? 'HEAD');
  const [query, setQuery] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [source, setSource] = useState<'fork' | 'upstream'>('fork');
  const [relation, setRelation] = useState<WorkspaceRelationDto | null>(null);
  const [upstream, setUpstream] = useState<UpstreamCommitsDto | null>(null);
  const [upstreamLoading, setUpstreamLoading] = useState(false);
  const [upstreamRange, setUpstreamRange] = useState<[number, number] | null>(null);
  const [rangeExpanded, setRangeExpanded] = useState(false);
  const [upstreamCherryOpen, setUpstreamCherryOpen] = useState(false);

  const loadRelation = useCallback(() => {
    void workspaceApi.relation(workspaceId)
      .then(setRelation)
      .catch(() => setRelation(null));
  }, [workspaceId]);

  useEffect(() => {
    loadRelation();
    // Linking or unlinking happens on the Relations tab of this same page, so
    // the source picker has to follow it without a reload.
    const onChanged = () => loadRelation();
    window.addEventListener(WORKSPACE_RELATION_CHANGED, onChanged);
    return () => window.removeEventListener(WORKSPACE_RELATION_CHANGED, onChanged);
  }, [loadRelation]);

  useEffect(() => {
    let cancelled = false;
    void workspaceApi.branches(workspaceId)
      .then(next => { if (!cancelled) setBranches(next); })
      .catch(reason => { if (!cancelled) setError(message(reason)); });
    return () => { cancelled = true; };
  }, [workspaceId]);

  useEffect(() => {
    if (source !== 'upstream') return undefined;
    let cancelled = false;
    setUpstreamLoading(true);
    setUpstreamRange(null);
    setRangeExpanded(false);
    void workspaceApi.upstreamCommits(workspaceId)
      .then(next => {
        if (cancelled) return;
        setUpstream(next);
        setError(null);
      })
      .catch(reason => { if (!cancelled) setError(message(reason)); })
      .finally(() => { if (!cancelled) setUpstreamLoading(false); });
    return () => { cancelled = true; };
  }, [source, workspaceId]);

  const upstreamLinked = relation !== null && relation.commitsEnabled;

  return (
    <section className="wu-page wu-commits wu-commit-history">
      <PageHeader title="Commits">
        <nav className="wu-commits-tabs" role="tablist" aria-label="Commits sections">
          <button type="button" role="tab" aria-selected={tab === 'commits'}
            className={tab === 'commits' ? 'active' : ''} onClick={() => setTab('commits')}>Commits</button>
          <button type="button" role="tab" aria-selected={tab === 'relations'}
            className={tab === 'relations' ? 'active' : ''} onClick={() => setTab('relations')}>Relations</button>
        </nav>
        {tab === 'commits' && (
          <>
            <span className="wu-commit-source" role="group" aria-label="Commit source">
              <button type="button" className={source === 'fork' ? 'active' : ''}
                onClick={() => setSource('fork')}>{repo.repo}</button>
              <button type="button" className={source === 'upstream' ? 'active' : ''}
                title={upstreamLinked ? undefined : 'Link an upstream workspace on the Relations tab'}
                onClick={() => {
                  if (upstreamLinked) setSource('upstream');
                  else setTab('relations');
                }}>
                <span aria-hidden>⑂</span>
                {relation?.upstreamWorkspaceName ?? 'Link upstream'}
                {upstreamLinked && <small>UPSTREAM</small>}
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
          </>
        )}
      </PageHeader>
      {tab === 'relations' ? (
        <div className="wu-commits-relations">
          <WorkspaceRelationsSettings workspaceId={workspaceId} repoName={repo.fullName} />
        </div>
      ) : (
        <>
          {source === 'upstream' && upstream !== null && (
            <div className="wu-upstream-banner">
              <span aria-hidden>⑂</span>
              <span>Reading from upstream workspace <b>{upstream.upstreamWorkspaceName}</b>
                {' '}({upstream.upstreamRepoFullName}) — read-only
                {upstream.lastFetchedAt !== null && <> · fetched {relative(upstream.lastFetchedAt)}</>}</span>
              <i>{upstream.notInForkCount.toLocaleString()} not in {repo.repo}</i>
              <button type="button" onClick={() => setTab('relations')}>Manage relation</button>
            </div>
          )}
          {error !== null && <div className="wu-inline-error">{error}</div>}
          {source === 'upstream' ? (
            <UpstreamCommitHistory
              rows={upstream?.commits ?? []}
              query={query}
              loading={upstreamLoading}
              range={upstreamRange}
              rangeExpanded={rangeExpanded}
              onExpandRange={() => setRangeExpanded(true)}
              onToggle={index => {
                setUpstreamRange(current => contiguousRangeAfterToggle(current, index));
                setRangeExpanded(false);
              }}
            />
          ) : (
            <ForkCommits workspaceId={workspaceId} repo={repo} branch={branch}
              branches={branches} query={query} onOpenTrunk={onOpenTrunk} />
          )}
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
        </>
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
