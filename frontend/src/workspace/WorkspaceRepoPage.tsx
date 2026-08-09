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
import { useCallback, useEffect, useState, type ReactNode } from 'react';
import CurrentUserAvatar from '../CurrentUserAvatar';
import type {
  IssueDetailDto,
  IssueDto,
} from '../types';
import { renderMarkdown } from '../markdown';
import {
  workspaceApi,
  type BranchComparisonDto,
  type CherryPickResultDto,
  type WorkspaceBranchDto,
  type WorkspaceRepositoryDto,
  type WorkspaceTrunkDto,
} from './workspaceApi';
import WorkspacePullsScreen from '../pulls/WorkspacePullsScreen';
import { CreationOriginBadge } from '../ui/CreationOriginBadge';
import WorkspaceCommitsPage from './WorkspaceCommitsPage';
import { commitDate } from './CommitEditorUi';
import {
  BodyMessage,
  BranchCheckIcon,
  cherryResultTitle,
  BranchIcon,
  ChevronDownIcon,
  ExternalIcon,
  PageHeader,
  SearchIcon,
  message,
  relative,
  useDismissOnOutside,
} from './WorkspaceRepoUi';

export type WorkspaceRepoSection = 'pull-requests' | 'issues' | 'branches' | 'commits';

type Props = {
  workspaceId: string;
  section: WorkspaceRepoSection;
  onOpenPr: (number: number) => void;
  onOpenIssue: (number: number) => void;
  onOpenBranch?: (branchName: string) => void;
  onOpenTrunk?: (trunkId: string) => void;
  onOpenSync?: (jobId: string) => void;
  selectedNumber?: number;
  /** Stable PR id for an AgentReview whose PR may still be local-only. */
  selectedPrId?: string;
  /** Open the pull-requests surface with the agent-review column showing. */
  initialAgentView?: boolean;
  selectedBranch?: string;
  onBackToList: () => void;
};

/**
 * Repository pages backed exclusively by the workspace façade. The renderer
 * never carries owner/repository coordinates; the sidecar resolves the sole
 * verified clone from {@code workspaceId}.
 */
export default function WorkspaceRepoPage({
  workspaceId,
  section,
  onOpenPr,
  onOpenIssue,
  onOpenBranch,
  onOpenTrunk,
  onOpenSync,
  selectedNumber,
  selectedPrId,
  initialAgentView,
  selectedBranch,
  onBackToList,
}: Props) {
  const [repo, setRepo] = useState<WorkspaceRepositoryDto | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setError(null);
    void workspaceApi.repository(workspaceId)
      .then(value => { if (!cancelled) setRepo(value); })
      .catch(reason => { if (!cancelled) setError(message(reason)); });
    return () => { cancelled = true; };
  }, [workspaceId]);

  if (error !== null) return <PageError title={titleFor(section)} message={error} />;
  if (repo === null) return <PageLoading title={titleFor(section)} />;

  if (section === 'pull-requests') {
    return (
      <WorkspacePullsScreen
        workspaceId={workspaceId}
        initialPrNumber={selectedNumber}
        initialPrId={selectedPrId}
        initialAgentView={initialAgentView}
        onOpenPr={onOpenPr}
        onBackToList={onBackToList}
      />
    );
  }
  if (section === 'issues') {
    return selectedNumber === undefined
      ? <IssuesPage workspaceId={workspaceId} onOpen={onOpenIssue} />
      : (
        <IssueDetailPage
          workspaceId={workspaceId}
          repo={repo}
          number={selectedNumber}
          onBack={onBackToList}
          onOpenTrunk={onOpenTrunk}
        />
      );
  }
  if (section === 'branches') {
    return (
      <BranchesPage
        workspaceId={workspaceId}
        repo={repo}
        selectedBranch={selectedBranch}
        onOpenBranch={onOpenBranch}
        onOpenPr={onOpenPr}
        onOpenTrunk={onOpenTrunk}
        onBack={onBackToList}
      />
    );
  }
  return <WorkspaceCommitsPage workspaceId={workspaceId} repo={repo}
    onOpenSync={onOpenSync} onOpenIssue={onOpenIssue} />;
}

function IssuesPage({
  workspaceId,
  onOpen,
}: {
  workspaceId: string;
  onOpen: (number: number) => void;
}) {
  const [state, setState] = useState<'open' | 'closed'>('open');
  const [rows, setRows] = useState<IssueDto[]>([]);
  const [query, setQuery] = useState('');
  const [showAll, setShowAll] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    void workspaceApi.issues(workspaceId, state)
      .then(result => {
        if (!cancelled) {
          setRows(result);
          setError(null);
        }
      })
      .catch(reason => { if (!cancelled) setError(message(reason)); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [workspaceId, state]);
  const shown = rows.filter(issue => issue.title.toLowerCase().includes(query.trim().toLowerCase()));
  const visible = showAll ? shown : shown.slice(0, 6);
  const hiddenCount = Math.max(0, shown.length - visible.length);

  return (
    <section className="wu-page wu-issues">
      <PageHeader title="Issues">
        <Segmented<'open' | 'closed'>
          options={[['open', `Open · ${state === 'open' ? rows.length : ''}`], ['closed', 'Closed']] as const}
          value={state}
          onChange={setState}
        />
        <label className="wu-search">
          <SearchIcon />
          <span className={`wu-search__display${query ? ' has-value' : ''}`}>
            {query || 'Search issues…'}
          </span>
          <input
            value={query}
            onChange={event => {
              setQuery(event.target.value);
              setShowAll(false);
            }}
            aria-label="Search issues"
          />
        </label>
        <button type="button" className="wu-icon-button">Recent activity <ChevronDownIcon /></button>
      </PageHeader>
      {error !== null ? <BodyMessage>{error}</BodyMessage> : (
        <div className="wu-table wu-issue-table">
          {loading ? <BodyMessage>Loading issues…</BodyMessage> : visible.map(issue => (
            <div
              className={`wu-table-row${issue.linkedTrunkId ? ' linked' : ''}`}
              key={issue.id}
              role="button"
              tabIndex={0}
              onClick={() => onOpen(issue.number)}
              onKeyDown={event => {
                if (event.key === 'Enter' || event.key === ' ') onOpen(issue.number);
              }}
            >
              <IssueIcon state={issue.state} />
              <span className="wu-table-row__main">
                <strong>
                  {issue.title}
                  <CreationOriginBadge origin={issue.origin} />
                  {issue.labels.map(label => (
                    <i className={`wu-inline-label ${issueLabelTone(label)}`} key={label}>{label}</i>
                  ))}
                </strong>
                <small>#{issue.number} · opened by @{issue.author ?? 'unknown'} · active {issueActivityRelative(issue.updatedAt)}</small>
              </span>
              {issue.linkedTrunkId ? (
                <>
                  <span className="wu-issue-linked-chip"><TrunkIcon /> thread active</span>
                  <button type="button" className="wu-issue-open-action" onClick={event => {
                    event.stopPropagation();
                    onOpen(issue.number);
                  }}>Open →</button>
                </>
              ) : (
                <span className="wu-comment-count"><CommentIcon /> {issue.commentCount ?? 0}</span>
              )}
            </div>
          ))}
          {!loading && shown.length === 0 && <BodyMessage>No issues match this view.</BodyMessage>}
        </div>
      )}
      {!loading && shown.length > 0 && (
        <div className="wu-issues__more">
          <span>
            {hiddenCount > 0 && <>{hiddenCount} more · </>}
            <button type="button" className="wu-show-all" onClick={() => setShowAll(value => !value)}>
              {showAll ? 'Show less' : 'Show all'}
            </button>
          </span>
        </div>
      )}
    </section>
  );
}

function IssueDetailPage({
  workspaceId,
  repo,
  number,
  onBack,
  onOpenTrunk,
}: {
  workspaceId: string;
  repo: WorkspaceRepositoryDto;
  number: number;
  onBack: () => void;
  onOpenTrunk?: (trunkId: string) => void;
}) {
  const [detail, setDetail] = useState<IssueDetailDto | null>(null);
  const [trunks, setTrunks] = useState<WorkspaceTrunkDto[]>([]);
  const [linked, setLinked] = useState<string[]>([]);
  const [picker, setPicker] = useState<'start' | 'backlog' | null>(null);
  const [comment, setComment] = useState('');
  const [showAllComments, setShowAllComments] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const [nextDetail, nextTrunks, nextLinked] = await Promise.all([
        workspaceApi.issue(workspaceId, number),
        workspaceApi.trunks(workspaceId),
        workspaceApi.issueTrunks(workspaceId, number),
      ]);
      setDetail(nextDetail);
      setTrunks(nextTrunks.filter(trunk => trunk.kind === 'dev' && trunk.endedAt === null));
      setLinked(nextLinked);
      setError(null);
    }
    catch (reason) {
      setError(message(reason));
    }
  }, [workspaceId, number]);
  useEffect(() => { void load(); }, [load]);

  if (error !== null && detail === null) return <PageError title={`Issue #${number}`} message={error} />;
  if (detail === null) return <PageLoading title={`Issue #${number}`} />;

  const postComment = async () => {
    if (comment.trim().length === 0) return;
    setBusy(true);
    try {
      const posted = await workspaceApi.commentOnIssue(workspaceId, number, comment.trim());
      setDetail(current => current === null
        ? current
        : { ...current, comments: [...current.comments, posted] });
      setComment('');
    }
    catch (reason) {
      setError(message(reason));
    }
    finally {
      setBusy(false);
    }
  };

  const actOnTrunk = async (trunkId?: string) => {
    if (picker === null) return;
    setBusy(true);
    try {
      if (picker === 'start') {
        const result = await workspaceApi.startIssue(workspaceId, number, trunkId);
        setLinked(current => current.includes(result.trunkId) ? current : [...current, result.trunkId]);
        setPicker(null);
        onOpenTrunk?.(result.trunkId);
      }
      else {
        const item = await workspaceApi.addIssueToBacklog(workspaceId, number, trunkId);
        setLinked(current => current.includes(item.threadId) ? current : [...current, item.threadId]);
        setPicker(null);
      }
    }
    catch (reason) {
      setError(message(reason));
    }
    finally {
      setBusy(false);
    }
  };

  return (
    <section className="wu-page wu-issue-detail">
      <header className="wu-detail-nav">
        <span
          className="wu-issue-back"
          role="button"
          tabIndex={0}
          onClick={onBack}
          onKeyDown={event => {
            if (event.key === 'Enter' || event.key === ' ') onBack();
          }}
        >
          <BackIcon /> Issues
        </span>
        <span className="wu-detail-nav__slash">/</span>
        <code>#{detail.number}</code>
        <button
          type="button"
          className="wu-icon-button"
          onClick={() => { void window.bridge.openInAppBrowser(detail.htmlUrl); }}
        >
          Open on GitHub <ExternalIcon />
        </button>
      </header>
      <div className="wu-issue-detail__grid">
        <main>
          <div className="wu-issue-detail__heading">
            <div className="wu-label-line">
              <span className={`wu-open-pill ${detail.state}`}>
                <IssueIcon state={detail.state} /> {detail.state.toUpperCase()}
              </span>
              <CreationOriginBadge origin={detail.origin} />
              {detail.labels.map(label => (
                <i className={`wu-inline-label ${issueLabelTone(label.name)}`} key={label.name}>{label.name}</i>
              ))}
            </div>
            <h1><IssueHeadingTitle title={detail.title} /></h1>
            <p>opened {calendarRelative(detail.createdAt)} by <b>@{detail.author ?? 'unknown'}</b> · {detail.comments.length} comments</p>
          </div>
          <article className="wu-issue-body-card"><MarkdownBody body={detail.body} plainReferences /></article>
          <div className="wu-issue-comments">
            {(showAllComments ? detail.comments : detail.comments.slice(0, 2)).map(value => (
              <article className="wu-issue-comment" key={value.id}>
                <AvatarLetters name={value.author ?? '?'} />
                <div>
                  <span className="wu-issue-comment__meta">
                    <b>@{value.author ?? 'unknown'}</b> · {calendarRelative(value.createdAt)}
                  </span>
                  <MarkdownBody body={value.body} plainReferences />
                </div>
              </article>
            ))}
            {!showAllComments && detail.comments.length > 2 && (
              <span className="wu-issue-comments__more">
                {detail.comments.length - 2} more comments ·{' '}
                <button type="button" onClick={() => setShowAllComments(true)}>Show all</button>
              </span>
            )}
          </div>
          <div className="wu-issue-composer">
            <CurrentUserAvatar size={28} className="wu-current-user-avatar" />
            <div className="wu-issue-composer__body">
              <div className="wu-issue-comment-input">
                <span className={comment ? 'has-value' : ''}>{comment || 'Leave a comment…'}</span>
                <textarea
                  value={comment}
                  onChange={event => setComment(event.target.value)}
                  aria-label="Issue comment"
                />
              </div>
              <div>
                <small>Posts to GitHub as you</small>
                <button
                  type="button"
                  className="wu-primary-button"
                  disabled={busy || comment.trim().length === 0}
                  onClick={() => { void postComment(); }}
                >
                  Comment
                </button>
              </div>
            </div>
          </div>
        </main>
        <aside>
          <section className="wu-work-card">
            <h2>Work on this</h2>
            <button type="button" className="wu-primary-button" onClick={() => setPicker('start')}>
              <TrunkIcon /> Start trunk from issue
            </button>
            <button type="button" className="wu-icon-button" onClick={() => setPicker('backlog')}>
              Add to backlog
            </button>
            <p>Seeds a thread with this issue as context. The agent runs in this workspace&apos;s clone of <code>{repo.fullName}</code>.</p>
          </section>
          <RailCard title="Linked work">
            {detail.linkedWork?.map(item => (
              <div
                role="button"
                tabIndex={0}
                className={`wu-issue-linked-work ${item.kind}`}
                key={`${item.kind}-${item.id}`}
                onClick={() => {
                  if (item.kind === 'trunk') onOpenTrunk?.(item.id);
                }}
                onKeyDown={event => {
                  if (item.kind === 'trunk' && (event.key === 'Enter' || event.key === ' ')) {
                    event.preventDefault();
                    onOpenTrunk?.(item.id);
                  }
                }}
              >
                <span className="wu-issue-linked-work__icon">
                  {item.kind === 'trunk' ? <TrunkIcon /> : <PullIcon tone="open" />}
                </span>
                <span className="wu-issue-linked-work__title">{item.title}</span>
                {item.status === 'running'
                  ? <span className="wu-issue-linked-work__running" />
                  : <span className="wu-issue-linked-work__status">{item.status}</span>}
              </div>
            ))}
            {(detail.linkedWork === undefined || detail.linkedWork.length === 0) && linked.map(id => {
              const trunk = trunks.find(value => value.id === id);
              return (
                <div
                  role="button"
                  tabIndex={0}
                  className="wu-issue-linked-work trunk"
                  key={id}
                  onClick={() => onOpenTrunk?.(id)}
                  onKeyDown={event => {
                    if (event.key === 'Enter' || event.key === ' ') {
                      event.preventDefault();
                      onOpenTrunk?.(id);
                    }
                  }}
                >
                  <span className="wu-issue-linked-work__icon"><TrunkIcon /></span>
                  <span className="wu-issue-linked-work__title">{trunk?.title ?? 'Linked thread'}</span>
                  <span className="wu-issue-linked-work__running" />
                </div>
              );
            })}
            {(detail.linkedWork === undefined || detail.linkedWork.length === 0) && linked.length === 0 && <span>No linked work yet</span>}
          </RailCard>
          <section className="wu-rail-card wu-issue-details">
            <h2>Details</h2>
            <div>
              <span><em>Assignees</em><b>{detail.assignees.map(value => `@${value.login}`).join(', ') || '—'}</b></span>
              <span><em>Milestone</em><b>{detail.milestone?.title ?? '—'}</b></span>
              <span>
                <em>Participants</em>
                <span className="wu-issue-participants">
                  {(detail.participants ?? unique([
                    detail.author,
                    ...detail.comments.map(value => value.author),
                  ])).slice(0, 3).map((participant, index) => (
                    <i className={`tone-${index}`} key={participant} title={participant} />
                  ))}
                </span>
              </span>
            </div>
          </section>
        </aside>
      </div>
      {error !== null && <div className="wu-inline-error">{error}</div>}
      {picker !== null && (
        <TrunkPicker
          issueNumber={number}
          mode={picker}
          trunks={trunks}
          busy={busy}
          onSelect={id => { void actOnTrunk(id); }}
          onCreate={() => { void actOnTrunk(); }}
          onClose={() => setPicker(null)}
        />
      )}
    </section>
  );
}

function TrunkPicker({
  issueNumber,
  mode,
  trunks,
  busy,
  onSelect,
  onCreate,
  onClose,
}: {
  issueNumber: number;
  mode: 'start' | 'backlog';
  trunks: WorkspaceTrunkDto[];
  busy: boolean;
  onSelect: (trunkId: string) => void;
  onCreate: () => void;
  onClose: () => void;
}) {
  const [selectedId, setSelectedId] = useState<string | null>(null);
  return (
    <div className="wu-modal-backdrop" role="presentation" onMouseDown={onClose}>
      <section className="wu-trunk-picker" role="dialog" aria-modal="true" onMouseDown={event => event.stopPropagation()}>
        <div className="wu-trunk-picker__head">
          <h2>{mode === 'start' ? 'Work on this' : 'Add to backlog'}</h2>
          <button type="button" className="wu-trunk-picker__action">
            <TrunkIcon />
            <strong>{mode === 'start' ? 'Start work under a trunk' : 'Park under a trunk'}</strong>
            <ChevronDownIcon />
          </button>
        </div>
        <div className="wu-issue-trunk-picker__menu">
          <span className="wu-issue-trunk-picker__heading">Existing threads</span>
          <div className="wu-trunk-picker__rows">
            {trunks.map(trunk => (
              <div
                role="button"
                tabIndex={busy ? -1 : 0}
                aria-disabled={busy}
                className={selectedId === trunk.id ? 'selected' : ''}
                key={trunk.id}
                onMouseEnter={() => setSelectedId(trunk.id)}
                onFocus={() => setSelectedId(trunk.id)}
                onClick={() => {
                  if (!busy) onSelect(trunk.id);
                }}
                onKeyDown={event => {
                  if (!busy && (event.key === 'Enter' || event.key === ' ')) {
                    event.preventDefault();
                    onSelect(trunk.id);
                  }
                }}
              >
                <i className={trunkStatusClass(trunk.status)} />
                <span className="wu-trunk-picker__title" title={trunk.description ?? undefined}>
                  {trunk.title}
                </span>
                <span className="wu-trunk-picker__status">{trunkPickerStatus(trunk)}</span>
                {selectedId === trunk.id && <span className="wu-trunk-picker__select">Select</span>}
              </div>
            ))}
            {trunks.length === 0 && <p>No existing threads</p>}
          </div>
          <div className="wu-trunk-picker__divider" />
          <div
            className="wu-trunk-picker__new"
            role="button"
            tabIndex={busy ? -1 : 0}
            aria-disabled={busy}
            onClick={() => {
              if (!busy) onCreate();
            }}
            onKeyDown={event => {
              if (!busy && (event.key === 'Enter' || event.key === ' ')) {
                event.preventDefault();
                onCreate();
              }
            }}
          >
            <PlusIcon />
            <span className="wu-trunk-picker__new-title">New trunk from this issue</span>
            <span className="wu-trunk-picker__new-meta">named after #{issueNumber}</span>
          </div>
        </div>
        <p className="wu-trunk-picker__note">
          {mode === 'start'
            ? <>Posts &quot;<code>Work on issue #{issueNumber}</code>&quot; into the chosen thread. The agent fetches body + comments itself via the issue tool — no stale pasted context.</>
            : 'Creates the backlog item and links the issue without starting an agent session.'}
        </p>
      </section>
    </div>
  );
}

function BranchesPage({
  workspaceId,
  repo,
  selectedBranch,
  onOpenBranch,
  onOpenPr,
  onOpenTrunk,
  onBack,
}: {
  workspaceId: string;
  repo: WorkspaceRepositoryDto;
  selectedBranch?: string;
  onOpenBranch?: (branchName: string) => void;
  onOpenPr: (number: number) => void;
  onOpenTrunk?: (trunkId: string) => void;
  onBack: () => void;
}) {
  const [rows, setRows] = useState<WorkspaceBranchDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      await workspaceApi.refreshRepository(workspaceId);
      setRows(await workspaceApi.branches(workspaceId));
      setError(null);
    }
    catch (reason) {
      setError(message(reason));
    }
    finally {
      setLoading(false);
    }
  }, [workspaceId]);
  useEffect(() => { void refresh(); }, [refresh]);

  const selected = selectedBranch === undefined
    ? null
    : rows.find(branch => branch.name === selectedBranch) ?? null;
  if (selectedBranch !== undefined) {
    return (
      <BranchDetailPage
        workspaceId={workspaceId}
        repo={repo}
        branch={selected}
        branches={rows}
        loading={loading}
        error={error}
        onRefresh={refresh}
        onBack={onBack}
        onOpenPr={onOpenPr}
        onOpenTrunk={onOpenTrunk}
      />
    );
  }
  const defaultBranch = repo.local.defaultBranch ?? repo.defaultBaseBranch ?? 'main';
  const activeCount = rows.filter(branch => branch.cleanupReason === null).length;

  return (
    <section className="wu-page wu-branches wu-branch-list">
      <PageHeader title="Branches" detail={`${activeCount} active`}>
        <span className="wu-code-chip">default: {defaultBranch.replace(/^origin\//, '')}</span>
      </PageHeader>
      {error !== null ? <BodyMessage>{error}</BodyMessage> : (
        <div className="wu-table wu-branch-list__table">
          {loading ? <BodyMessage>Refreshing the local clone…</BodyMessage> : rows.map(branch => (
            <div className={`wu-table-row${branch.cleanupReason === null ? '' : ' cleanup'}`}
              key={branch.name} role="button" tabIndex={0}
              onClick={() => onOpenBranch?.(branch.name)}
              onKeyDown={event => {
                if (event.key === 'Enter' || event.key === ' ') {
                  event.preventDefault();
                  onOpenBranch?.(branch.name);
                }
              }}>
              <BranchIcon />
              <span className="wu-table-row__main">
                <strong>{branch.name}</strong>
              </span>
              {branch.name === defaultBranch || branch.name === defaultBranch.replace(/^origin\//, '')
                ? <span className="wu-neutral-pill">default</span>
                : branch.trunkId !== null
                  ? (
                    <button type="button" className="wu-owner-pill"
                      onClick={event => {
                        event.stopPropagation();
                        onOpenTrunk?.(branch.trunkId as string);
                      }}>
                      <TrunkIcon />{branch.trunkTitle ?? branch.taskTitle ?? branch.taskId}
                    </button>
                  )
                  : branch.cleanupReason !== null && (
                    <span className="wu-merged-pill"><PullIcon tone="merged" />merged</span>
                  )}
              {branch.commitCount !== null && branch.commitCount > 0 && branch.cleanupReason === null && (
                <span className="wu-branch-delta">+{branch.commitCount} −{branch.behind ?? 0}</span>
              )}
              <span className="wu-row-spacer" />
              {branch.linkedPrNumber !== null && (
                <button type="button" className="wu-pr-link"
                  onClick={event => {
                    event.stopPropagation();
                    onOpenPr(branch.linkedPrNumber as number);
                  }}>#{branch.linkedPrNumber}</button>
              )}
              <time>
                {branch.lastCommitAt === null
                  ? ''
                  : branch.name === defaultBranch || branch.name === defaultBranch.replace(/^origin\//, '')
                    ? `last push ${relative(branch.lastCommitAt)} ago`
                    : relative(branch.lastCommitAt)}
              </time>
              {branch.cleanupReason === null ? (
                <span className={`wu-branch-state ${branch.rebasePreview === 'CONFLICTS' ? 'conflicts' : 'healthy'}`}>
                  {branch.name === defaultBranch || branch.name === defaultBranch.replace(/^origin\//, '')
                    ? <BranchCheckIcon />
                    : <i />}
                  {branch.rebasePreview === 'CONFLICTS' ? 'conflicts' : branch.isCurrent ? 'CI green' : 'healthy'}
                </span>
              ) : (
                <button type="button" className="wu-cleanup-button"
                  onClick={event => {
                    event.stopPropagation();
                    if (!window.confirm(`Delete local branch ${branch.name}?`)) return;
                    void workspaceApi.deleteBranches(workspaceId, [branch.name])
                      .then(() => refresh())
                      .catch(reason => setError(message(reason)));
                  }}>Clean up</button>
              )}
            </div>
          ))}
          {!loading && rows.length === 0 && <BodyMessage>No local branches found.</BodyMessage>}
        </div>
      )}
    </section>
  );
}

function BranchDetailPage({
  workspaceId,
  repo,
  branch,
  branches,
  loading,
  error,
  onRefresh,
  onBack,
  onOpenPr,
  onOpenTrunk,
}: {
  workspaceId: string;
  repo: WorkspaceRepositoryDto;
  branch: WorkspaceBranchDto | null;
  branches: WorkspaceBranchDto[];
  loading: boolean;
  error: string | null;
  onRefresh: () => Promise<void>;
  onBack: () => void;
  onOpenPr: (number: number) => void;
  onOpenTrunk?: (trunkId: string) => void;
}) {
  const [comparison, setComparison] = useState<BranchComparisonDto | null>(null);
  const [selected, setSelected] = useState<[number, number] | null>(null);
  const [compareMenu, setCompareMenu] = useState(false);
  const compareShell = useDismissOnOutside<HTMLSpanElement>(
    compareMenu, () => setCompareMenu(false));
  const [target, setTarget] = useState('');
  const [picking, setPicking] = useState(false);
  const [aborting, setAborting] = useState(false);
  const [result, setResult] = useState<CherryPickResultDto | null>(null);
  const [localError, setLocalError] = useState<string | null>(null);

  useEffect(() => {
    if (branch === null) return;
    let cancelled = false;
    setComparison(null);
    setSelected(null);
    void workspaceApi.compareBranch(workspaceId, branch.name)
      .then(value => {
        if (cancelled) return;
        setComparison(value);
        setSelected(value.commits.length === 0 ? null : [0, 0]);
      })
      .catch(reason => { if (!cancelled) setLocalError(message(reason)); });
    return () => { cancelled = true; };
  }, [branch, workspaceId]);

  useEffect(() => {
    if (branch === null) return;
    const fallback = repo.local.defaultBranch ?? repo.defaultBaseBranch;
    const candidates = branches.filter(candidate => !candidate.remoteOnly && candidate.name !== branch.name);
    setTarget((fallback !== null && fallback !== branch.name
      ? fallback.replace(/^origin\//, '')
      : candidates[0]?.name) ?? '');
  }, [branch, branches, repo]);

  if (loading) return <PageLoading title="Branches" />;
  if (error !== null) return <PageError title="Branches" message={error} />;
  if (branch === null) return <PageError title="Branches" message="This branch is no longer available locally." />;
  const commits = comparison?.commits ?? [];
  const selectedCommits = selected === null
    ? []
    : commits.slice(selected[0], selected[1] + 1);
  const additions = comparison?.files.reduce((sum, file) => sum + Math.max(0, file.additions), 0) ?? 0;
  const deletions = comparison?.files.reduce((sum, file) => sum + Math.max(0, file.deletions), 0) ?? 0;

  const toggleCommit = (index: number) => {
    setResult(null);
    setSelected(current => {
      if (current === null) return [index, index];
      const [start, end] = current;
      if (index < start) return [index, end];
      if (index > end) return [start, index];
      if (start === end) return null;
      if (index === start) return [start + 1, end];
      if (index === end) return [start, end - 1];
      return [start, index - 1];
    });
  };

  return (
    <section className="wu-page wu-branch-detail">
      <header className="wu-branch-detail__header">
        <button type="button" className="wu-back-link" onClick={onBack}><BackIcon />Branches</button>
        <span>/</span>
        <strong>{branch.name}</strong>
        <span className="wu-branch-state healthy"><i />healthy</span>
        <span className="wu-row-spacer" />
        <button type="button" className="wu-icon-button"
          onClick={() => { void onRefresh(); }}><BranchRefreshIcon /> Refresh · 2m</button>
        <span className="wu-compare-pick" ref={compareShell}>
          <button type="button" className="wu-icon-button"
            onClick={() => setCompareMenu(open => !open)}><CompareIcon />Compare ▾</button>
          {compareMenu && (
            <div className="wu-compare-menu">
              <span>Compare against</span>
              {branches.filter(candidate => candidate.name !== branch.name).map(candidate => (
                <button type="button" key={candidate.name}
                  onClick={() => {
                    setCompareMenu(false);
                    setLocalError(null);
                    void workspaceApi.compareBranch(workspaceId, branch.name, candidate.name)
                      .then(value => {
                        setComparison(value);
                        setSelected(value.commits.length === 0 ? null : [0, 0]);
                      })
                      .catch(reason => setLocalError(message(reason)));
                  }}>{candidate.name}</button>
              ))}
            </div>
          )}
        </span>
        {branch.linkedPrNumber !== null && (
          <button type="button" className="wu-dark-button"
            onClick={() => onOpenPr(branch.linkedPrNumber as number)}>
            Open PR #{branch.linkedPrNumber}
          </button>
        )}
      </header>
      {localError !== null && <div className="wu-inline-error">{localError}</div>}
      <main className="wu-branch-detail__body">
        <div className="wu-branch-summary">
          <code>{comparison?.base ?? repo.local.defaultBranch ?? 'main'}…{branch.name}</code>
          <span><strong>+{branch.commitCount ?? commits.length} ahead</strong> · {branch.behind ?? 0} behind</span>
          <span className="wu-branch-summary__delta">
            <b>+{additions}</b> <i>−{deletions}</i> · {comparison?.files.length ?? 0} files
          </span>
          {branch.trunkId !== null && (
            <button type="button" className="wu-owner-pill"
              onClick={() => onOpenTrunk?.(branch.trunkId as string)}>
              <TrunkIcon />{branch.trunkTitle ?? branch.taskTitle ?? branch.taskId}
            </button>
          )}
          <span className="wu-row-spacer" />
          <small>guard: fresh · mergeable · CI green</small>
        </div>
        <div className="wu-branch-commit-list">
          {commits.map((commit, index) => {
            const checked = selected !== null && index >= selected[0] && index <= selected[1];
            return (
              <label className={checked ? 'selected' : ''} key={commit.sha}>
                <input type="checkbox" checked={checked}
                  onChange={() => toggleCommit(index)} />
                <code>{commit.shortSha}</code>
                <strong>{commit.subject}</strong>
                <time>{commit.displayTime ?? (commitDate(commit) === null ? '' : relative(commitDate(commit)!))}</time>
              </label>
            );
          })}
          {commits.length === 0 && <BodyMessage>No commits ahead of the base branch.</BodyMessage>}
        </div>
        {selectedCommits.length > 0 && (
          <div className="wu-cherry-bar">
            <strong>{selectedCommits.length} commit{selectedCommits.length === 1 ? '' : 's'} selected</strong>
            <span>contiguous range {selectedCommits[0]?.shortSha}…{selectedCommits.at(-1)?.shortSha}</span>
            <span className="wu-row-spacer" />
            <span className="wu-cherry-label">Cherry-pick to</span>
            <span className="wu-cherry-target">
              <select aria-label="Cherry-pick target" value={target} onChange={event => setTarget(event.target.value)}>
                {branches.filter(candidate => !candidate.remoteOnly && candidate.name !== branch.name)
                  .map(candidate => <option value={candidate.name} key={candidate.name}>{candidate.name}</option>)}
              </select>
              <span>{target}</span>
              <ChevronDownIcon />
            </span>
            <button type="button" disabled={target.length === 0 || picking}
              onClick={() => {
                setPicking(true);
                setLocalError(null);
                void workspaceApi.cherryPick(
                  workspaceId,
                  branch.name,
                  target,
                  selectedCommits.map(commit => commit.sha),
                ).then(setResult)
                  .catch(reason => setLocalError(message(reason)))
                  .finally(() => setPicking(false));
              }}>{picking ? 'Cherry-picking…' : 'Cherry-pick'}</button>
            <button type="button" onClick={() => setSelected(null)}>Clear</button>
          </div>
        )}
        {result !== null && (
          <div className={`wu-cherry-result ${result.status}`}>
            <strong>{cherryResultTitle(result)}</strong>
            <span>{result.status === 'done'
              ? 'No remote was changed or pushed.'
              : (result.message ?? 'Resolve it in the retained worktree, or abort to undo it.')}</span>
            {result.trunkId !== null && (
              <button type="button" onClick={() => onOpenTrunk?.(result.trunkId as string)}>
                Open fix session
              </button>
            )}
            {result.status === 'conflicted' && (
              <button type="button" disabled={aborting}
                onClick={() => {
                  setAborting(true);
                  setLocalError(null);
                  void workspaceApi.abortCherryPick(workspaceId, result.operationId)
                    .then(setResult)
                    .catch(reason => setLocalError(message(reason)))
                    .finally(() => setAborting(false));
                }}>{aborting ? 'Aborting…' : 'Abort'}</button>
            )}
          </div>
        )}
        <p className="wu-branch-detail__note">
          Cherry-pick runs on the local clone; a conflict retains an isolated worktree for manual resolution.
        </p>
      </main>
    </section>
  );
}

function Segmented<T extends string>({
  options,
  value,
  onChange,
}: {
  options: readonly (readonly [T, string])[];
  value: T;
  onChange: (value: T) => void;
}) {
  return (
    <div className="wu-segmented">
      {options.map(([key, label]) => (
        <button
          type="button"
          key={key}
          className={value === key ? 'active' : ''}
          onClick={() => onChange(key)}
        >
          {label}
        </button>
      ))}
    </div>
  );
}

function PageLoading({ title }: { title: string }) {
  return <section className="wu-page"><PageHeader title={title} /><BodyMessage>Loading repository…</BodyMessage></section>;
}

function PageError({ title, message: error }: { title: string; message: string }) {
  return <section className="wu-page"><PageHeader title={title} /><BodyMessage>{error}</BodyMessage></section>;
}

function RailCard({ title, children }: { title: string; children: ReactNode }) {
  return <section className="wu-rail-card"><h2>{title}</h2><div>{children}</div></section>;
}

function MarkdownBody({
  body,
  plainReferences = false,
}: {
  body: string | null;
  plainReferences?: boolean;
}) {
  const rendered = renderMarkdown(body?.trim() || '_No description provided._');
  return (
    <div
      className="wu-markdown"
      dangerouslySetInnerHTML={{
        __html: plainReferences
          ? rendered.replace(/<span class="md-ref-(?:issue|mention)"[^>]*>([^<]*)<\/span>/g, '$1')
          : rendered,
      }}
    />
  );
}

function AvatarLetters({ name }: { name: string }) {
  return (
    <span className={`wu-author-avatar tone-${issueAvatarTone(name)}`} aria-hidden>
      {issueAvatarInitials(name)}
    </span>
  );
}

function IssueHeadingTitle({ title }: { title: string }) {
  const token = '$partitions';
  const index = title.indexOf(token);
  if (index < 0) return title;
  return (
    <>
      {title.slice(0, index)}
      <span>{token}</span>
      {title.slice(index + token.length)}
    </>
  );
}

function issueLabelTone(label: string): string {
  return label.toLowerCase().includes('blocker') ? 'danger' : '';
}

function issueAvatarTone(name: string): number {
  const normalized = name.toLowerCase();
  if (normalized === 'mderoy') return 0;
  if (normalized === 'guyco33') return 1;
  if (normalized === 'cj') return 2;
  return 3;
}

function issueAvatarInitials(name: string): string {
  const known: Record<string, string> = {
    cj: 'CJ',
    guyco33: 'GC',
    mderoy: 'MD',
  };
  return known[name.toLowerCase()] ?? initials(name);
}

function trunkStatusClass(status: string): string {
  const normalized = status.toLowerCase();
  if (normalized === 'running') return 'running';
  if (normalized === 'needs_attention' || normalized === 'paused') return 'needs-you';
  return 'idle';
}

function trunkPickerStatus(trunk: WorkspaceTrunkDto): string {
  const normalized = trunk.status.toLowerCase();
  if (normalized === 'running') return 'agent running';
  if (normalized === 'needs_attention' || normalized === 'paused') return 'needs you';
  if (normalized === 'idle') {
    return trunk.taskCount
      ? `idle · ${trunk.taskCount} task${trunk.taskCount === 1 ? '' : 's'} shipped`
      : 'idle';
  }
  return normalized.replaceAll('_', ' ');
}

function titleFor(section: WorkspaceRepoSection): string {
  return section === 'pull-requests' ? 'Pull requests'
    : section[0].toUpperCase() + section.slice(1);
}

function calendarRelative(iso: string | null): string {
  if (iso === null) return 'recently';
  const date = new Date(iso);
  const delta = Date.now() - date.getTime();
  if (delta < 86_400_000) return relative(iso);
  if (delta < 7 * 86_400_000) {
    return new Intl.DateTimeFormat('en-US', { weekday: 'long' }).format(date);
  }
  return new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric', year: 'numeric' }).format(date);
}

function issueActivityRelative(iso: string): string {
  const date = new Date(iso);
  const delta = Date.now() - date.getTime();
  if (!Number.isFinite(delta) || delta < 60_000) return 'now';
  if (delta < 60 * 60_000) return `${Math.floor(delta / 60_000)}m ago`;
  if (delta < 24 * 60 * 60_000) return `${Math.floor(delta / (60 * 60_000))}h ago`;
  if (delta < 7 * 24 * 60 * 60_000) {
    return new Intl.DateTimeFormat('en-US', { weekday: 'long' }).format(date);
  }
  return new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric' }).format(date);
}

function initials(name: string): string {
  return name.split(/\s+/).map(part => part[0]).join('').slice(0, 2).toUpperCase();
}

function unique(values: Array<string | null | undefined>): string[] {
  return [...new Set(values.filter((value): value is string => value !== null && value !== undefined))];
}

function BackIcon() {
  return (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="m15 18-6-6 6-6" />
    </svg>
  );
}

function PlusIcon() {
  return (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" aria-hidden>
      <path d="M12 5v14M5 12h14" />
    </svg>
  );
}

function BranchRefreshIcon() {
  return (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M21 12a9 9 0 1 1-2.6-6.3" />
      <path d="M21 3v6h-6" />
    </svg>
  );
}

function CompareIcon() {
  return (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="m18 16 4-4-4-4" />
      <path d="m6 8-4 4 4 4" />
      <path d="m14.5 4-5 16" />
    </svg>
  );
}

function IssueIcon({ state = 'open' }: { state?: string }) {
  return <svg className={`wu-issue-icon ${state}`} width="16" height="16" viewBox="0 0 24 24" fill="none"
    stroke="currentColor" strokeWidth="1.8"><circle cx="12" cy="12" r="8.5" />
    <circle cx="12" cy="12" r="2.6" fill="currentColor" stroke="none" /></svg>;
}

function PullIcon({ tone = 'open' }: { tone?: 'open' | 'merged' | 'closed' }) {
  const path = tone === 'merged' ? 'M6 21V9a9 9 0 0 0 9 9' : 'M13 6h3a2 2 0 0 1 2 2v7M6 9v12';
  return <svg className={`wu-pull-icon ${tone}`} width="16" height="16" viewBox="0 0 24 24" fill="none"
    stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
    <circle cx="18" cy="18" r="2.6" /><circle cx="6" cy="6" r="2.6" /><path d={path} />
  </svg>;
}

function CommentIcon() {
  return <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
    strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
    <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
  </svg>;
}

function TrunkIcon() {
  return <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
    strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><circle cx="6" cy="6" r="2.4" />
    <circle cx="6" cy="18" r="2.4" /><circle cx="18" cy="12" r="2.4" />
    <path d="M8.3 7.2 15.7 11M8.3 16.8 15.7 13" /></svg>;
}
