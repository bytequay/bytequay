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
import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  workspaceApi,
  type UpstreamCommitsDto,
  type UpstreamRefsDto,
  type WorkspaceBranchDto,
  type WorkspaceRelationDto,
  type WorkspaceRepositoryDto,
} from './workspaceApi';
import {
  CommitAuthorPicker,
  CommitBranchPicker,
  type CommitAuthorTally,
} from './CommitEditorUi';
import WorkspaceCommitEditor from './WorkspaceCommitEditor';
import WorkspaceWorkingTree from './WorkspaceWorkingTree';
import { LinkUpstreamDialog, WORKSPACE_RELATION_CHANGED } from './WorkspaceRelationsSettings';
import {
  contiguousRangeAfterToggle,
  rangeLabel,
  UpstreamCherryPicker,
  UpstreamCommitHistory,
} from './WorkspaceUpstreamCommits';
import {
  PageHeader,
  SearchIcon,
  message,
  relative,
} from './WorkspaceRepoUi';

type Props = {
  workspaceId: string;
  repo: WorkspaceRepositoryDto;
  onOpenTrunk?: (trunkId: string) => void;
  onOpenHarness?: (watchId?: string) => void;
  onOpenIssue?: (issueNumber: number) => void;
};

export default function WorkspaceCommitsPage({
  workspaceId,
  repo,
  onOpenTrunk,
  onOpenHarness,
  onOpenIssue,
}: Props) {
  const visualFrame = document.documentElement.dataset.workspaceVisualFrame;
  const visualCommitStudy = visualFrame === '3g' || visualFrame === '4a';
  const [linkOpen, setLinkOpen] = useState(false);
  const [branches, setBranches] = useState<WorkspaceBranchDto[]>([]);
  const [branch, setBranch] = useState(visualCommitStudy
    ? 'master'
    : repo.local.currentBranch
    ?? repo.local.defaultBranch?.replace(/^origin\//, '')
    ?? repo.defaultBaseBranch?.replace(/^origin\//, '')
    ?? 'HEAD');
  const [upstreamRefs, setUpstreamRefs] = useState<UpstreamRefsDto | null>(null);
  /** Set once the user picks a branch, so the fork default below can never
   *  yank the list out from under them. */
  const [branchPicked, setBranchPicked] = useState(false);
  const [query, setQuery] = useState('');
  // The commit editor's two filters both live in this header, next to
  // each other; the editor reports the author list it can offer.
  const [author, setAuthor] = useState('all');
  const [authors, setAuthors] = useState<CommitAuthorTally[]>([]);
  const [tab, setTab] = useState<'commits' | 'uncommitted'>('commits');
  const [uncommitted, setUncommitted] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [source, setSource] = useState<'fork' | 'upstream'>('fork');
  const [relation, setRelation] = useState<WorkspaceRelationDto | null>(null);
  const [upstream, setUpstream] = useState<UpstreamCommitsDto | null>(null);
  /** Branch selected in the linked upstream workspace; null reads its default. */
  const [upstreamRevision, setUpstreamRevision] = useState<string | null>(null);
  const [upstreamWorkspaceBranches, setUpstreamWorkspaceBranches] = useState<string[]>([]);
  const [upstreamLoading, setUpstreamLoading] = useState(false);
  const [upstreamRange, setUpstreamRange] = useState<[number, number] | null>(null);
  const [rangeExpanded, setRangeExpanded] = useState(false);
  const [upstreamCherryOpen, setUpstreamCherryOpen] = useState(false);
  const [fromSha, setFromSha] = useState('');
  const [toSha, setToSha] = useState('');
  // A typed range is resolved by the backend against full history, so it can
  // reach commits older than the page has loaded.
  const [shaRangeOpen, setShaRangeOpen] = useState(false);

  const loadRelation = useCallback(() => {
    void workspaceApi.relation(workspaceId)
      .then(setRelation)
      .catch(() => setRelation(null));
  }, [workspaceId]);

  useEffect(() => {
    loadRelation();
    // Relations are managed in workspace settings, but this page can link the
    // first upstream, so the source picker follows either surface's change.
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
    let cancelled = false;
    void workspaceApi.upstreamBranches(workspaceId)
      .then(next => { if (!cancelled) setUpstreamRefs(next); })
      .catch(() => { /* Direct clones simply have no upstream group to show. */ });
    return () => { cancelled = true; };
  }, [workspaceId]);

  // On a fork the interesting history is the upstream's, not the fork's:
  // that is what you read to decide what to cherry-pick down. The fork's
  // own branches stay one click away in the same picker.
  useEffect(() => {
    const head = upstreamRefs?.defaultBranch;
    if (head === null || head === undefined || branchPicked || visualCommitStudy) return;
    setBranch(head);
  }, [upstreamRefs, branchPicked, visualCommitStudy]);

  useEffect(() => {
    if (source !== 'upstream') return undefined;
    let cancelled = false;
    setUpstreamLoading(true);
    setUpstreamRange(null);
    setRangeExpanded(false);
    void workspaceApi.upstreamCommits(workspaceId, upstreamRevision ?? undefined)
      .then(next => {
        if (cancelled) return;
        setUpstream(next);
        setError(null);
      })
      .catch(reason => { if (!cancelled) setError(message(reason)); })
      .finally(() => { if (!cancelled) setUpstreamLoading(false); });
    return () => { cancelled = true; };
  }, [source, workspaceId, upstreamRevision]);

  // Every branch the upstream clone carries, so a release line can be read
  // and cherry-picked from — not just whatever it happens to have checked out.
  useEffect(() => {
    const linkedId = relation?.upstreamWorkspaceId;
    if (linkedId === undefined || linkedId === null) {
      setUpstreamWorkspaceBranches([]);
      return undefined;
    }
    let cancelled = false;
    setUpstreamRevision(null);
    void workspaceApi.relationBranches(workspaceId)
      .then(next => {
        if (!cancelled) setUpstreamWorkspaceBranches(Array.isArray(next) ? next : []);
      })
      .catch(() => { if (!cancelled) setUpstreamWorkspaceBranches([]); });
    return () => { cancelled = true; };
  }, [relation?.upstreamWorkspaceId, workspaceId]);

  const upstreamLinked = relation !== null && relation.commitsEnabled;
  const isFork = (upstreamRefs?.remote ?? null) !== null;
  // Local branches the viewed history can be copied onto, base branch first
  // since landing upstream work on it is the common case. Memoized because
  // the editor keys its target default off identity.
  const cherryPickTargets = useMemo(() => {
    const base = repo.defaultBaseBranch?.replace(/^origin\//, '') ?? null;
    const names = branches
      .filter(candidate => !candidate.remoteOnly && candidate.name !== branch)
      .map(candidate => candidate.name);
    return base !== null && names.includes(base)
      ? [base, ...names.filter(name => name !== base)]
      : names;
  }, [branches, branch, repo.defaultBaseBranch]);

  return (
    <section className="wu-page wu-commits wu-commit-history">
      <PageHeader title="Commits">
        <span className="wu-commit-source" role="group" aria-label="Commit source">
          <button type="button" className={source === 'fork' ? 'active' : ''}
            title={isFork ? `Fork of ${repo.fullName}` : undefined}
            onClick={() => setSource('fork')}>
            {isFork && <span aria-hidden>⑂</span>}{repo.repo}
          </button>
          <button type="button" className={source === 'upstream' ? 'active' : ''}
            title={upstreamLinked ? undefined : 'Link an upstream workspace to browse its history'}
            onClick={() => {
              if (upstreamLinked) setSource('upstream');
              else setLinkOpen(true);
            }}>
            <span aria-hidden>⑂</span>
            {relation?.upstreamWorkspaceName ?? 'Link upstream'}
            {upstreamLinked && <small>UPSTREAM</small>}
          </button>
        </span>
        {source === 'fork' ? (
          <CommitBranchPicker branch={branch} branches={branches}
            upstreamBranches={upstreamRefs?.branches ?? []}
            upstreamLabel={repo.fullName}
            currentBranch={repo.local.currentBranch ?? null}
            onPick={next => { setBranchPicked(true); setBranch(next); }} />
        ) : (
          <CommitBranchPicker branch={upstream?.revision ?? 'default'}
            branches={[]} upstreamBranches={upstreamWorkspaceBranches}
            upstreamLabel={relation?.upstreamRepoFullName}
            currentBranch={null} onPick={setUpstreamRevision} />
        )}
        {source === 'fork' && (
          <CommitAuthorPicker author={author} authors={authors}
            total={authors.reduce((sum, tally) => sum + tally.count, 0)}
            onPick={setAuthor} />
        )}
        <label className="wu-search">
          <SearchIcon />
          <input value={query} onChange={event => setQuery(event.target.value)}
            placeholder={source !== 'fork'
              ? 'Search commits…'
              : tab === 'uncommitted' ? 'Filter files by path…' : 'Filter by title or SHA…'} />
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
      {source === 'fork' && (
        <div className="wu-ce-tabs" role="tablist" aria-label="Commit history or working tree">
          <button type="button" role="tab" aria-selected={tab === 'commits'}
            className={tab === 'commits' ? 'is-on' : ''}
            onClick={() => setTab('commits')}>Commits</button>
          <button type="button" role="tab" aria-selected={tab === 'uncommitted'}
            className={tab === 'uncommitted' ? 'is-on' : ''}
            onClick={() => setTab('uncommitted')}>
            Uncommitted changes
            {uncommitted !== null && uncommitted > 0 && <i>{uncommitted}</i>}
          </button>
        </div>
      )}
      {error !== null && <div className="wu-inline-error">{error}</div>}
      {source === 'upstream' && (
        <div className="wu-upstream-sha-range">
          <label>from <input aria-label="Range start commit sha" placeholder="sha"
            value={fromSha} onChange={event => setFromSha(event.target.value)} /></label>
          <label>to <input aria-label="Range end commit sha" placeholder="sha"
            value={toSha} onChange={event => setToSha(event.target.value)} /></label>
          <button type="button"
            disabled={fromSha.trim().length === 0 || toSha.trim().length === 0}
            onClick={() => setShaRangeOpen(true)}>
            Cherry-pick range…
          </button>
          <small>inclusive · resolved against the latest 5,000 upstream commits, not just the loaded page</small>
        </div>
      )}
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
        tab === 'uncommitted' ? (
          <WorkspaceWorkingTree workspaceId={workspaceId} query={query}
            onCountChange={setUncommitted} />
        ) : (
          <WorkspaceCommitEditor workspaceId={workspaceId} branch={branch}
            query={query} author={author} onAuthorsChange={setAuthors}
            cherryPickTargets={cherryPickTargets}
            repoContext={{ owner: repo.owner, repo: repo.repo }}
            onOpenIssue={onOpenIssue}
            onClearQuery={() => setQuery('')} onClearAuthor={() => setAuthor('all')} />
        )
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
      {linkOpen && (
        <LinkUpstreamDialog workspaceId={workspaceId} onClose={() => setLinkOpen(false)}
          onLinked={next => {
            setRelation(next);
            setLinkOpen(false);
            setSource('upstream');
          }} />
      )}
      {shaRangeOpen && upstream !== null && (
        <UpstreamCherryPicker
          workspaceId={workspaceId}
          repo={repo}
          snapshot={upstream}
          commits={[]}
          fromSha={fromSha.trim()}
          toSha={toSha.trim()}
          onClose={() => setShaRangeOpen(false)}
          onOpenHarness={onOpenHarness}
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
