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
import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import CurrentUserAvatar from '../CurrentUserAvatar';
import type {
  ActivityItemDto,
  IssueDetailDto,
  IssueDto,
  LocalCommitDetailDto,
  LocalCommitDto,
  LocalCommitFileDto,
  PullRequestCommitDto,
  PullRequestDetailDto,
  PullRequestDto,
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
import PullRequestBoardList from './PullRequestBoardList';

export type WorkspaceRepoSection = 'pull-requests' | 'issues' | 'branches' | 'commits';

type Props = {
  workspaceId: string;
  section: WorkspaceRepoSection;
  onOpenPr: (number: number) => void;
  onOpenIssue: (number: number) => void;
  onOpenBranch?: (branchName: string) => void;
  onOpenTrunk?: (trunkId: string) => void;
  selectedNumber?: number;
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
  selectedNumber,
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
    return selectedNumber === undefined
      ? <PullRequestsPage workspaceId={workspaceId} onOpen={onOpenPr} />
      : (
        <PullRequestDetailPage
          workspaceId={workspaceId}
          number={selectedNumber}
          onBack={onBackToList}
          onOpenTrunk={onOpenTrunk}
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
  return <CommitsPage workspaceId={workspaceId} repo={repo} onOpenTrunk={onOpenTrunk} />;
}

function PullRequestsPage({
  workspaceId,
  onOpen,
}: {
  workspaceId: string;
  onOpen: (number: number) => void;
}) {
  const [rows, setRows] = useState<PullRequestDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      setRows(await workspaceApi.pullRequests(workspaceId));
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

  return (
    <PullRequestBoardList
      title="Pull requests"
      rows={rows}
      loading={loading}
      error={error}
      showRepository={false}
      onOpen={pr => onOpen(pr.number)}
      onRefresh={() => { void refresh(); }}
    />
  );
}

function PullRequestDetailPage({
  workspaceId,
  number,
  onBack,
  onOpenTrunk,
}: {
  workspaceId: string;
  number: number;
  onBack: () => void;
  onOpenTrunk?: (trunkId: string) => void;
}) {
  const [pr, setPr] = useState<PullRequestDto | null>(null);
  const [detail, setDetail] = useState<PullRequestDetailDto | null>(null);
  const [commits, setCommits] = useState<PullRequestCommitDto[] | null>(null);
  const [tab, setTab] = useState<'conversation' | 'commits' | 'checks' | 'changes'>('conversation');
  const [error, setError] = useState<string | null>(null);
  const [reviewing, setReviewing] = useState(false);

  const load = useCallback(async () => {
    try {
      const [nextPr, nextDetail, nextCommits] = await Promise.all([
        workspaceApi.pullRequest(workspaceId, number),
        workspaceApi.pullRequestDetail(workspaceId, number),
        workspaceApi.pullRequestCommits(workspaceId, number),
      ]);
      setPr(nextPr);
      setDetail(nextDetail);
      setCommits(nextCommits);
      setError(null);
    }
    catch (reason) {
      setError(message(reason));
    }
  }, [workspaceId, number]);
  useEffect(() => { void load(); }, [load]);

  if (error !== null) return <PageError title={`Pull request #${number}`} message={error} />;
  if (pr === null || detail === null) return <PageLoading title={`Pull request #${number}`} />;

  const startReview = async () => {
    setReviewing(true);
    try {
      const started = await workspaceApi.reviewPullRequest(workspaceId, number);
      if (started.trunkId !== null) onOpenTrunk?.(started.trunkId);
    }
    catch (reason) {
      setError(message(reason));
    }
    finally {
      setReviewing(false);
    }
  };

  const workspaceLinks: NonNullable<PullRequestDetailDto['workspaceLinks']> =
    detail.workspaceLinks ?? detail.linkedIssues.map(issue => ({
    kind: 'task' as const,
    title: `#${issue.number} ${issue.title}`,
    detail: 'linked issue',
  }));
  const reviewers = detail.reviewers ?? deriveReviewerRows(pr, detail);
  const developmentLinks = detail.developmentLinks
    ?? detail.linkedIssues.map(issue => ({ number: issue.number, title: issue.title, closes: true }));
  const participants = detail.participants ?? unique([
    pr.author,
    ...reviewers.map(reviewer => reviewer.login),
  ]);
  const commitCount = commits?.length ?? 0;

  return (
    <section className="wu-page wu-pr-detail">
      <header className="wu-pr-detail__header">
        <div className="wu-pr-detail__topline">
          <span
            className="wu-pr-detail__back"
            role="button"
            tabIndex={0}
            onClick={onBack}
            onKeyDown={event => {
              if (event.key === 'Enter' || event.key === ' ') onBack();
            }}
          >
            <PrDetailIcon kind="back" /> Pull requests
          </span>
          <span className="wu-sync-ok"><PrDetailIcon kind="check" /> synced {detail.syncedLabel ?? 'just now'}</span>
          <button
            type="button"
            className="wu-primary-button"
            disabled={reviewing}
            onClick={() => { void startReview(); }}
          >
            <PrDetailIcon kind="agent" /> {reviewing ? 'Starting review…' : 'Review with agent'}
          </button>
        </div>
        <h1>{pr.title} <span>#{pr.number}</span></h1>
        <div className="wu-pr-detail__meta">
          <span className={`wu-open-pill ${pr.state ?? 'open'}`}>
            <PullIcon tone={pr.state === 'merged' ? 'merged' : pr.state === 'closed' ? 'closed' : 'open'} />
            {pr.state === 'merged' ? 'Merged' : pr.state === 'closed' ? 'Closed' : 'Open'}
          </span>
          <span>
            <b>@{pr.author ?? 'unknown'}</b>
            {' wants to merge '}
            {commitCount} {commitCount === 1 ? 'commit' : 'commits'} into <span className="wu-pr-ref">{detail.baseRef ?? 'main'}</span>
            {' from '}<span className="wu-pr-ref">{detail.headRef ?? pr.headRef ?? 'branch'}</span>
          </span>
          <span className="wu-pr-detail__delta">
            <b>+{detail.additions}</b>{' '}<b className="deletions">−{detail.deletions}</b>
          </span>
        </div>
        <nav className="wu-detail-tabs">
          <TabButton active={tab === 'conversation'} onClick={() => setTab('conversation')}>
            <PrDetailIcon kind="conversation" /> Conversation <i>{detail.conversationCount ?? detail.recentActivity.length}</i>
          </TabButton>
          <TabButton active={tab === 'commits'} onClick={() => setTab('commits')}>
            <PrDetailIcon kind="commit" /> Commits <i>{commitCount}</i>
          </TabButton>
          <TabButton active={tab === 'checks'} onClick={() => setTab('checks')}>
            <PrDetailIcon kind="check" /> Checks <i>{detail.checkCount ?? detail.checkRuns.length}</i>
          </TabButton>
          <TabButton active={tab === 'changes'} onClick={() => setTab('changes')}>
            <PrDetailIcon kind="changes" /> Changes
          </TabButton>
        </nav>
      </header>
      <div className="wu-pr-detail__body">
        <main>
          {tab === 'conversation' && (
            <div className="wu-pr-conversation">
              <div className="wu-pr-feed-card">
                <PrAvatar name={pr.author ?? '?'} />
                <article className="wu-conversation-card wu-pr-description-card">
                  <header>
                    <span>
                      <strong>{pr.author ?? 'unknown'}</strong>
                      {' drafted the description · '}
                      {pr.createdAt === null ? 'recently' : prTimelineDate(pr.createdAt, true)}
                    </span>
                    <i>AUTHOR</i>
                  </header>
                  <MarkdownBody body={detail.body} />
                </article>
              </div>
              {detail.recentActivity.map((activity, index) => (
                <PrActivity
                  key={`${activity.githubId ?? activity.timestamp}-${index}`}
                  activity={activity}
                  author={pr.author}
                  resolvedThreads={detail.reviewThreads.filter(thread => thread.resolved === true)}
                />
              ))}
              {detail.recentActivity.length === 0 && <BodyMessage>No conversation activity yet.</BodyMessage>}
            </div>
          )}
          {tab === 'commits' && (
            <DetailList>
              {commits?.map(commit => (
                <div className="wu-detail-list-row wu-pr-commit-row" key={commit.sha}>
                  <AvatarLetters name={commit.authorLogin ?? commit.authorName ?? '?'} />
                  <span>
                    <strong>{firstLine(commit.message) || 'Untitled commit'}</strong>
                    <small>
                      <b>{commit.authorLogin ?? commit.authorName ?? 'Unknown author'}</b>
                      {' committed '}
                      {commit.authoredAt === null ? 'at an unknown time' : relative(commit.authoredAt)}
                    </small>
                  </span>
                  <code>{commit.sha.slice(0, 7)}</code>
                </div>
              ))}
              {commits?.length === 0 && <BodyMessage>No commits are attached to this pull request.</BodyMessage>}
            </DetailList>
          )}
          {tab === 'checks' && (
            <DetailList>
              {detail.checkRuns.map((check, index) => (
                <div className="wu-detail-list-row" key={check.githubId ?? `${check.name}-${index}`}>
                  <span className={`wu-check-state ${check.conclusion ?? check.status ?? ''}`}>
                    {check.conclusion === 'success' ? '✓' : check.status === 'in_progress' ? '●' : '×'}
                  </span>
                  <span>
                    <strong>{check.name ?? 'Check'}</strong>
                    <small>{check.outputTitle ?? check.conclusion ?? check.status ?? 'unknown'}</small>
                  </span>
                  {check.htmlUrl !== null && <a href={check.htmlUrl}>Open ↗</a>}
                </div>
              ))}
            </DetailList>
          )}
          {tab === 'changes' && (
            <DetailList>
              {detail.files.map(file => (
                <div className="wu-detail-list-row" key={file.filename}>
                  <span className="wu-file-status">{file.status.slice(0, 1).toUpperCase()}</span>
                  <span><strong>{file.filename}</strong></span>
                  <b className="wu-delta">+{file.additions} <em>−{file.deletions}</em></b>
                </div>
              ))}
            </DetailList>
          )}
        </main>
        <aside className="wu-pr-detail__rail">
          <section className="wu-pr-meta-section wu-pr-linked-work">
            <h2>Linked work</h2>
            {workspaceLinks.map((link, index) => (
              <div
                role="button"
                tabIndex={0}
                key={`${link.kind}-${link.title}-${index}`}
                onClick={() => {
                  const trunkId = link.trunkId;
                  if (trunkId !== undefined && trunkId !== null) onOpenTrunk?.(trunkId);
                }}
                onKeyDown={event => {
                  const trunkId = link.trunkId;
                  if ((event.key === 'Enter' || event.key === ' ')
                    && trunkId !== undefined && trunkId !== null) onOpenTrunk?.(trunkId);
                }}
              >
                <span className={`wu-pr-link-icon ${link.kind}`}>
                  <PrDetailIcon kind={link.kind === 'trunk' ? 'trunk' : link.kind === 'task' ? 'merged' : 'agent'} />
                </span>
                <span>{link.title} <i>{link.detail}</i></span>
              </div>
            ))}
            {workspaceLinks.length === 0 && <p>No linked work</p>}
          </section>
          <section className="wu-pr-meta-section wu-pr-reviewers">
            <h2>Reviewers <PrDetailIcon kind="settings" /></h2>
            {reviewers.map(reviewer => (
              <div key={reviewer.login}>
                <PrAvatar name={reviewer.login} compact agent={reviewer.login.includes('agent')} />
                <span>{reviewer.login}</span>
                <ReviewerState state={reviewer.state} />
              </div>
            ))}
            {reviewers.length === 0 && <p>No reviewers</p>}
          </section>
          <section className="wu-pr-meta-section">
            <h2>Assignees</h2>
            {detail.assignees !== undefined && detail.assignees.length > 0
              ? detail.assignees.map(login => <span key={login}>@{login}</span>)
              : <p>No one — <a href={pr.htmlUrl}>assign yourself</a></p>}
          </section>
          <section className="wu-pr-meta-section">
            <h2>Labels</h2>
            <div className="wu-pr-labels">
              {detail.labels.map((label, index) => <i key={label} className={index % 2 === 0 ? 'blue' : 'purple'}>{label}</i>)}
            </div>
          </section>
          <section className="wu-pr-meta-section">
            <h2>Milestone</h2>
            {detail.milestone === null || detail.milestone === undefined
              ? <p>No milestone</p>
              : (
                <>
                  <div className="wu-pr-milestone">
                    <span style={{ width: `${Math.max(0, Math.min(100, detail.milestone.progressPercent))}%` }} />
                  </div>
                  <strong>{detail.milestone.title}</strong>
                </>
              )}
          </section>
          <section className="wu-pr-meta-section">
            <h2>Development</h2>
            {developmentLinks.map(link => (
              <p key={link.number}>{link.closes ? 'Merging may close ' : 'Linked to '}<a href={pr.htmlUrl}>#{link.number}</a></p>
            ))}
            {developmentLinks.length === 0 && <p>No linked issues</p>}
          </section>
          <section className="wu-pr-meta-section wu-pr-participants">
            <h2>{participants.length} participants</h2>
            <div>
              {participants.slice(0, 5).map(login => <PrAvatar key={login} name={login} compact blank />)}
              {participants.length > 5 && <span className="wu-pr-participant-more">+{participants.length - 5}</span>}
            </div>
            <button type="button" className="wu-pr-unsubscribe"><PrDetailIcon kind="bell-off" /> Unsubscribe</button>
            <p>{detail.subscriptionReason ?? "You're notified because your review was requested."}</p>
          </section>
        </aside>
      </div>
    </section>
  );
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
  const [picker, setPicker] = useState<'start' | 'backlog' | null>(() =>
    document.documentElement.dataset.workspaceVisualFrame === '5b' ? 'start' : null);
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
          onClick={() => { void window.bridge.openExternal(detail.htmlUrl); }}
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
              <TrunkIcon /> Start thread from issue
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
  const visualSelection = document.documentElement.dataset.workspaceVisualFrame === '5b'
    ? 'trunk-clean-code'
    : null;
  const [selectedId, setSelectedId] = useState<string | null>(visualSelection);
  return (
    <div className="wu-modal-backdrop" role="presentation" onMouseDown={onClose}>
      <section className="wu-trunk-picker" role="dialog" aria-modal="true" onMouseDown={event => event.stopPropagation()}>
        <div className="wu-trunk-picker__head">
          <h2>{mode === 'start' ? 'Work on this' : 'Add to backlog'}</h2>
          <button type="button" className="wu-trunk-picker__action">
            <TrunkIcon />
            <strong>{mode === 'start' ? 'Start work under a thread' : 'Park under a thread'}</strong>
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
                <span className="wu-trunk-picker__title">{trunk.title}</span>
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
            <span className="wu-trunk-picker__new-title">New thread from this issue</span>
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
  const [target, setTarget] = useState('');
  const [picking, setPicking] = useState(false);
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
        setSelected(value.commits.length === 0
          ? null
          : document.documentElement.dataset.workspaceVisualFrame === '4d'
            ? [0, Math.min(1, value.commits.length - 1)]
            : [0, 0]);
      })
      .catch(reason => { if (!cancelled) setLocalError(message(reason)); });
    return () => { cancelled = true; };
  }, [branch, workspaceId]);

  useEffect(() => {
    if (branch === null) return;
    const fallback = repo.local.defaultBranch ?? repo.defaultBaseBranch;
    const candidates = branches.filter(candidate => !candidate.remoteOnly && candidate.name !== branch.name);
    const visualTarget = document.documentElement.dataset.workspaceVisualFrame === '4d'
      ? candidates.find(candidate => candidate.name === 'release/482')?.name
      : undefined;
    setTarget(visualTarget ?? (fallback !== null && fallback !== branch.name
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
        <button type="button" className="wu-icon-button"
          onClick={() => setCompareMenu(open => !open)}><CompareIcon />Compare ▾</button>
        {branch.linkedPrNumber !== null && (
          <button type="button" className="wu-dark-button"
            onClick={() => onOpenPr(branch.linkedPrNumber as number)}>
            Open PR #{branch.linkedPrNumber}
          </button>
        )}
      </header>
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
                <time>{commit.displayTime ?? (commit.authoredAt === null ? '' : relative(commit.authoredAt))}</time>
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
            <strong>{result.status === 'done'
              ? `Created local branch ${result.resultBranch}`
              : `Conflict in ${result.conflictPaths.length} file${result.conflictPaths.length === 1 ? '' : 's'}`}</strong>
            <span>{result.status === 'done'
              ? 'No remote was changed or pushed.'
              : 'The isolated worktree was retained and a CI-fix session was queued.'}</span>
            {result.trunkId !== null && (
              <button type="button" onClick={() => onOpenTrunk?.(result.trunkId as string)}>
                Open fix session
              </button>
            )}
          </div>
        )}
        <p className="wu-branch-detail__note">
          Cherry-pick runs on the local clone; a conflict opens a fix session in the owning thread.
        </p>
      </main>
    </section>
  );
}

function CommitsPage({
  workspaceId,
  repo,
  onOpenTrunk,
}: {
  workspaceId: string;
  repo: WorkspaceRepositoryDto;
  onOpenTrunk?: (trunkId: string) => void;
}) {
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

  useEffect(() => {
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
  }, [branch, workspaceId]);

  useEffect(() => {
    if (selected === null) {
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
  }, [workspaceId, selected]);

  const shown = rows.filter(row => `${row.subject} ${row.sha} ${row.authorName}`
    .toLowerCase().includes(query.trim().toLowerCase()));
  const groups = useMemo(() => groupCommits(shown), [shown]);

  return (
    <section className="wu-page wu-commits wu-commit-history">
      <PageHeader title="Commits">
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
        <label className="wu-search">
          <SearchIcon />
          <input value={query} onChange={event => setQuery(event.target.value)} placeholder="Search commits…" />
        </label>
      </PageHeader>
      {error !== null && <div className="wu-inline-error">{error}</div>}
      <div className="wu-commit-history__groups">
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
                            void window.bridge.openExternal(`https://github.com/${repo.fullName}/commit/${commit.sha}`);
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
      </div>
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

function PageHeader({
  title,
  detail,
  children,
}: {
  title: string;
  detail?: string;
  children?: ReactNode;
}) {
  return (
    <header className="wu-page-header">
      <div className="wu-page-heading"><h1>{title}</h1>{detail && <span>{detail}</span>}</div>
      <div className="wu-header-actions">{children}</div>
    </header>
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

function TabButton({
  active,
  onClick,
  children,
}: {
  active: boolean;
  onClick: () => void;
  children: ReactNode;
}) {
  return (
    <span
      role="button"
      tabIndex={0}
      className={active ? 'active' : ''}
      onClick={onClick}
      onKeyDown={event => {
        if (event.key === 'Enter' || event.key === ' ') onClick();
      }}
    >
      {children}
    </span>
  );
}

function PageLoading({ title }: { title: string }) {
  return <section className="wu-page"><PageHeader title={title} /><BodyMessage>Loading repository…</BodyMessage></section>;
}

function PageError({ title, message: error }: { title: string; message: string }) {
  return <section className="wu-page"><PageHeader title={title} /><BodyMessage>{error}</BodyMessage></section>;
}

function BodyMessage({ children }: { children: ReactNode }) {
  return <div className="wu-body-message">{children}</div>;
}

function DetailList({ children }: { children: ReactNode }) {
  return <div className="wu-detail-list">{children}</div>;
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

function PrActivity({
  activity,
  author,
  resolvedThreads,
}: {
  activity: ActivityItemDto;
  author: string | null;
  resolvedThreads: PullRequestDetailDto['reviewThreads'];
}) {
  if (activity.eventType === 'reviewed') {
    const path = resolvedThreads[0]?.filePath;
    return (
      <div className="wu-pr-feed-card wu-pr-review-card">
        <PrAvatar name={activity.actor} />
        <article className="wu-conversation-card">
          <header>
            <span><strong>{activity.actor}</strong> reviewed · {prTimelineDate(activity.timestamp)}</span>
            {resolvedThreads.length > 0 && <b>{resolvedThreads.length} resolved</b>}
          </header>
          {path !== null && path !== undefined && (
            <div className="wu-pr-resolved-summary">
              <code>{compactPath(path)}</code>
              <span>RESOLVED</span>
            </div>
          )}
          {activity.body !== null && <MarkdownBody body={activity.body} />}
        </article>
      </div>
    );
  }
  if (activity.body !== null) {
    return (
      <div className="wu-pr-feed-card wu-pr-comment-card">
        <PrAvatar name={activity.actor} />
        <article className="wu-conversation-card">
          <header>
            <span>
              <strong>{activity.actor}</strong> · {prTimelineDate(activity.timestamp)}
              {author === activity.actor && <i>AUTHOR</i>}
            </span>
          </header>
          <MarkdownBody body={activity.body} />
        </article>
      </div>
    );
  }
  return (
    <div className="wu-timeline-event">
      <span><PrDetailIcon kind={timelineIcon(activity.eventType)} /></span>
      <span>
        <b>{activity.actor}</b>{' '}
        {activity.eventType === 'labeled' ? (
          <>added the <i className="wu-pr-event-label">{activity.labelName ?? 'label'}</i> label</>
        ) : activity.eventType.includes('push') ? (
          <>force-pushed to <code>{activity.afterSha?.slice(0, 7) ?? 'the branch'}</code></>
        ) : activity.eventType === 'cross-referenced' ? (
          <>referenced <a href={activity.crossRefUrl ?? undefined}>
            #{activity.crossRefNumber} {activity.crossRefTitle}
          </a></>
        ) : activityLabel(activity)}
        {' · '}{prTimelineDate(activity.timestamp)}
      </span>
    </div>
  );
}

function PrAvatar({
  name,
  compact = false,
  agent = false,
  blank = false,
}: {
  name: string;
  compact?: boolean;
  agent?: boolean;
  blank?: boolean;
}) {
  return (
    <span className={`wu-pr-avatar tone-${avatarTone(name)}${compact ? ' compact' : ''}${agent ? ' agent' : ''}${blank ? ' blank' : ''}`} aria-hidden>
      {agent ? <PrDetailIcon kind="agent" /> : blank ? null : prInitials(name)}
    </span>
  );
}

function ReviewerState({ state }: { state: 'commented' | 'approved' | 'requested' }) {
  if (state === 'requested') return <a>Request</a>;
  return <PrDetailIcon kind={state === 'approved' ? 'check' : 'conversation'} />;
}

function AvatarLetters({ name }: { name: string }) {
  return (
    <span className={`wu-author-avatar tone-${issueAvatarTone(name)}`} aria-hidden>
      {issueAvatarInitials(name)}
    </span>
  );
}

function CommitAvatarLetters({ name }: { name: string }) {
  return (
    <span className="wu-author-avatar" aria-hidden>
      {prInitials(name)}
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

function message(value: unknown): string {
  return value instanceof Error ? value.message : String(value);
}

function isToday(iso: string | null): boolean {
  if (iso === null) return false;
  const date = new Date(iso);
  const now = new Date();
  return date.getFullYear() === now.getFullYear()
    && date.getMonth() === now.getMonth()
    && date.getDate() === now.getDate();
}

function relative(iso: string): string {
  const delta = Date.now() - Date.parse(iso);
  if (!Number.isFinite(delta) || delta < 60_000) return 'now';
  if (delta < 3_600_000) return `${Math.floor(delta / 60_000)}m`;
  if (delta < 86_400_000) return `${Math.floor(delta / 3_600_000)}h`;
  return `${Math.floor(delta / 86_400_000)}d`;
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

function initials(name: string): string {
  return name.split(/\s+/).map(part => part[0]).join('').slice(0, 2).toUpperCase();
}

function firstLine(value: string | null): string {
  return value?.split(/\r?\n/, 1)[0]?.trim() ?? '';
}

function statusBadge(pr: PullRequestDto): string {
  if (pr.state === 'merged') return 'merged';
  if (pr.state === 'closed') return 'closed';
  if (pr.draft) return 'draft';
  if (pr.handledAction === 'APPROVED') return 'approved';
  if (isToday(pr.reviewedAt)) return 'reviewed';
  return '';
}

function activityLabel(activity: ActivityItemDto): string {
  if (activity.eventType === 'labeled') return `added the ${activity.labelName ?? ''} label`;
  if (activity.eventType === 'review_requested') return `requested review from ${activity.requestedReviewer ?? ''}`;
  if (activity.eventType.includes('push')) {
    return `force-pushed to ${activity.afterSha?.slice(0, 7) ?? 'the branch'}`;
  }
  return activity.eventType.replaceAll('_', ' ');
}

function deriveReviewerRows(
  pr: PullRequestDto,
  detail: PullRequestDetailDto,
): NonNullable<PullRequestDetailDto['reviewers']> {
  const rows = new Map<string, 'commented' | 'approved' | 'requested'>();
  for (const [login, verdict] of Object.entries(pr.reviewerVerdicts ?? {})) {
    rows.set(login, verdict === 'APPROVED' ? 'approved' : 'commented');
  }
  for (const login of detail.requestedReviewers) {
    if (!rows.has(login)) rows.set(login, 'requested');
  }
  return [...rows].map(([login, state]) => ({ login, state }));
}

function unique(values: Array<string | null | undefined>): string[] {
  return [...new Set(values.filter((value): value is string => value !== null && value !== undefined))];
}

function prTimelineDate(iso: string | null, includeYear = false): string {
  if (iso === null) return 'recently';
  return new Intl.DateTimeFormat('en-US', {
    month: 'short',
    day: 'numeric',
    ...(includeYear ? { year: 'numeric' as const } : {}),
  }).format(new Date(iso));
}

function compactPath(path: string): string {
  const parts = path.split('/');
  return parts.length < 4 ? path : `${parts.slice(0, 2).join('/')}/…/${parts.at(-1)}`;
}

type PrDetailIconKind =
  | 'agent' | 'back' | 'bell-off' | 'check' | 'clock' | 'commit' | 'conversation'
  | 'changes' | 'link' | 'merged' | 'settings' | 'trunk';

function timelineIcon(eventType: string): PrDetailIconKind {
  if (eventType.includes('push')) return 'commit';
  if (eventType === 'cross-referenced') return 'link';
  return 'clock';
}

function avatarTone(name: string): number {
  const normalized = name.toLowerCase();
  if (normalized === 'skyglass' || normalized === 'math-ias') return 0;
  if (normalized === 'ebyhr') return 1;
  if (normalized === 'chenjian2664') return 2;
  if (normalized.includes('agent')) return 3;
  if (normalized === 'electrum') return 4;
  return [...name].reduce((sum, character) => sum + character.charCodeAt(0), 0) % 5;
}

function prInitials(name: string): string {
  const known: Record<string, string> = {
    chenjian2664: 'CJ',
    ebyhr: 'EB',
    skyglass: 'SG',
  };
  const knownValue = known[name.toLowerCase()];
  if (knownValue !== undefined) return knownValue;
  const parts = name.split(/[-_\s]+/).filter(Boolean);
  if (parts.length > 1) return parts.slice(0, 2).map(part => part[0]).join('').toUpperCase();
  return name.slice(0, 2).toUpperCase();
}

function PrDetailIcon({ kind }: { kind: PrDetailIconKind }) {
  const common = {
    width: 13,
    height: 13,
    viewBox: '0 0 24 24',
    fill: 'none',
    stroke: 'currentColor',
    strokeWidth: kind === 'check' ? 2 : 1.8,
    strokeLinecap: 'round' as const,
    strokeLinejoin: 'round' as const,
  };
  if (kind === 'back') return <svg {...common}><path d="m15 18-6-6 6-6" /></svg>;
  if (kind === 'check') return <svg {...common}><path d="M20 6 9 17l-5-5" /></svg>;
  if (kind === 'conversation') {
    return <svg {...common}><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" /></svg>;
  }
  if (kind === 'commit') {
    return <svg {...common} strokeWidth="1.7"><circle cx="12" cy="12" r="3" /><path d="M3 12h6M15 12h6" /></svg>;
  }
  if (kind === 'changes') {
    return <svg {...common}><path d="m16 3 5 5-5 5M21 8H9M8 21l-5-5 5-5M3 16h12" /></svg>;
  }
  if (kind === 'agent') {
    return (
      <svg {...common}>
        <rect x="5" y="9" width="14" height="10" rx="2" /><path d="M12 5v4" />
        <circle cx="12" cy="4" r="1" /><path d="M9 13.5h.01M15 13.5h.01" />
      </svg>
    );
  }
  if (kind === 'trunk') {
    return (
      <svg {...common}>
        <circle cx="6" cy="6" r="2.4" /><circle cx="6" cy="18" r="2.4" /><circle cx="18" cy="12" r="2.4" />
        <path d="M8.3 7.2 15.7 11M8.3 16.8 15.7 13" />
      </svg>
    );
  }
  if (kind === 'merged') {
    return <svg {...common}><circle cx="18" cy="18" r="2.6" /><circle cx="6" cy="6" r="2.6" /><path d="M6 21V9a9 9 0 0 0 9 9" /></svg>;
  }
  if (kind === 'settings') {
    return (
      <svg {...common} width="12" height="12">
        <circle cx="12" cy="12" r="3" />
        <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-2.82 1.17V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 8 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 3.6 15H3.5a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 5 8.6l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 5.6h.09A1.65 1.65 0 0 0 10 3.6V3.5a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 20.4 9h.1a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
      </svg>
    );
  }
  if (kind === 'bell-off') {
    return (
      <svg {...common} width="12" height="12" strokeWidth="1.7">
        <path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9M10.3 21a1.94 1.94 0 0 0 3.4 0M3 3l18 18" />
      </svg>
    );
  }
  if (kind === 'link') {
    return (
      <svg {...common}>
        <path d="M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7" />
      </svg>
    );
  }
  return <svg {...common} strokeWidth="1.7"><circle cx="12" cy="12" r="8.5" /><path d="M12 8v4l2.5 2" /></svg>;
}

function SearchIcon() {
  return <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
    strokeWidth="1.8" strokeLinecap="round"><circle cx="11" cy="11" r="7" /><path d="m20 20-3.5-3.5" /></svg>;
}

function BackIcon() {
  return (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="m15 18-6-6 6-6" />
    </svg>
  );
}

function ExternalIcon() {
  return (
    <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M7 17 17 7" />
      <path d="M8 7h9v9" />
    </svg>
  );
}

function ChevronDownIcon() {
  return (
    <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="m6 9 6 6 6-6" />
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

function RefreshIcon() {
  return <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
    strokeWidth="1.8" strokeLinecap="round"><path d="M20 6v5h-5" /><path d="M4 18v-5h5" />
    <path d="M6.1 9A7 7 0 0 1 18 6l2 5M18 15a7 7 0 0 1-12 3l-2-5" /></svg>;
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

function BranchIcon() {
  return <svg className="wu-branch-icon" width="14" height="14" viewBox="0 0 24 24" fill="none"
    stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
    <path d="M6 3v12" />
    <circle cx="18" cy="6" r="2.6" />
    <circle cx="6" cy="18" r="2.6" />
    <path d="M18 9a9 9 0 0 1-9 9" />
  </svg>;
}

function BranchCheckIcon() {
  return (
    <svg width="11" height="11" viewBox="0 0 24 24" fill="none"
      stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M20 6 9 17l-5-5" />
    </svg>
  );
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

function AgentIcon() {
  return <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
    strokeWidth="1.8" strokeLinecap="round"><rect x="5" y="7" width="14" height="12" rx="3" />
    <path d="M12 3v4M9 12h.01M15 12h.01M9 16h6" /></svg>;
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
