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
import { readPullRequestViewer, usePullRequestBridge, useHostingPresentation } from './PullRequestWorkspace';
import { shortSha } from '@bytequay/ui/displayText';
import { Copy, Ellipsis, ExternalLink, Eye, GitCommitHorizontal, Link, LoaderCircle, Pencil, Quote, Tag } from 'lucide-react';
import { errorMessage } from '../errorMessage';
import { useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import type { TaskDiscussionDto } from '../agents/discussions/contracts';
import CopyLinkButton from '@bytequay/ui/CopyLinkButton';
import { parseUnifiedDiff } from '../diffParse';
import { openWebLink } from '../githubReference';
import { pullRequestAttentionKey } from '../home/attention';
import { recordAttentionAction } from '../home/useAttentionVisit';
import LearnFromPullButton from '../memory/LearnFromPullButton';
import ReplayReviewButton from '../memory/ReplayReviewButton';
import { relativeTime } from '@bytequay/ui/relativeTime';
import RepoAvatar from '../RepoAvatar';
import type {
  Bridge,
  PullCommentTarget,
  PullRequestDetailDto,
  PullRequestDto,
  PullTimelineCommentDto,
  PullTimelinePayloadDto,
  PullRequestViewer,
} from '../types';
import { usePopoverDismiss } from '@bytequay/ui/usePopoverDismiss';
import UserAvatar from '../UserAvatar';
import BetterWordsButton from './BetterWordsButton';
import CodeExcerpt, { type ReviewCommentLine } from './CodeExcerpt';
import LocalReviewThreadCard from './local-review/LocalReviewThreadCard';
import type { LocalReviewThreadDto, LocalReviewThreads } from './local-review/types';
import MergeQueueRemoval from './MergeQueueRemoval';
import MarkdownBody, { GitHubReferenceLink, linkIssueNumbers, toggleMarkdownTask, type SuggestionBase } from './MarkdownBody';
import MarkdownEditor from './MarkdownEditor';
import { displayActor } from './model';
import PullChecks from './PullChecks';
import PullMetadata, { LabelBadge } from './PullMetadata';
import { ReactionPicker, Reactions, useReactionAction, type ReactionAction } from './PullReactions';
import { mergePullRequestWriteDetail, updatePullReactionDetail, type PullDetailUpdater } from './pullDetail';
import { PrivateThreadControls, PrivateThreadForward, privateThreadKey, PrivateThreadsToggle, PrivateThreadTab, withMention, type PrivateThreads } from './PrivateThread';
import { PullRequestEpicCard, PullRequestEpicLink, usePullRequestEpic } from './PullRequestEpic';
import { PullRequestIcon } from './PullRowItem';
import ReviewCommentIndex, { commentIndexTarget } from './ReviewCommentIndex';
import { PendingReviewComments, useReviewDraft } from './ReviewDrafts';
import { ReviewThreadCard, ReviewThreadMessage } from './ReviewThreadCard';
import { mentionCandidates, useMentions, type MentionCandidate } from './useMentions';
import { usePrCommentCommand } from './usePrCommentCommand';

type Activity = PullRequestDetailDto['recentActivity'][number];
const LABEL_EVENT_TYPES = new Set(['labeled', 'unlabeled']);
const TIMELINE_EVENT_GROUP_WINDOW_MS = 5 * 60 * 1000;
const HIDDEN_TIMELINE_EVENTS = new Set([
  'convert_to_draft',
  'description_reactions',
  'mentioned',
  'ready_for_review',
  'review_request_removed',
  'subscribed',
  'unsubscribed',
]);

/** The signed-in account, for review composers, local pending comments, and editing your own comments. */
export function useViewer(enabled: boolean): PullRequestViewer | null {
  const bridge = usePullRequestBridge();
  const { workspaceId, accountId, repository } = useHostingPresentation();
  const [viewer, setViewer] = useState<PullRequestViewer | null>(null);
  useEffect(() => {
    if (!enabled) return;
    let live = true;
    readPullRequestViewer(bridge, { workspaceId, accountId, repository })
      .then(profile => { if (live) setViewer(profile); })
      .catch(() => { /* the composer just falls back to an initial */ });
    return () => { live = false; };
  }, [accountId, bridge, enabled, repository, workspaceId]);
  return viewer;
}

export function isViewerComment(actor: string | null | undefined, viewerLogin: string | null | undefined) {
  return Boolean(actor && viewerLogin && actor.toLowerCase() === viewerLogin.toLowerCase());
}

export default function PullOverview({ pr, detail, contributorsWorkspaceId, workspaceId, localReview, privateThreads, taskActions, onRefresh, onDetailUpdate = () => {},
  onDetailChange = () => { void onRefresh(); } }: {
  pr: PullRequestDto;
  detail: PullRequestDetailDto;
  contributorsWorkspaceId?: string;
  /** The workspace watching the pull request's repository, whose work may name it. */
  workspaceId?: string;
  taskActions?: ReactNode;
  localReview?: LocalReviewThreads;
  /** The host task's private threads on this pull request's comments, when available. */
  privateThreads?: PrivateThreads;
  onRefresh: () => Promise<void>;
  onDetailUpdate?: PullDetailUpdater;
  onDetailChange?: (detail: PullRequestDetailDto) => void;
}) {
  const bridge = usePullRequestBridge();
  const [quotedReply, setQuotedReply] = useState<QuoteRequest>();
  const viewerLogin = useViewer(true)?.login;
  // Only the user's own pull requests come from their development tasks, so only those can deliver an Epic's work.
  const authored = pr.origin === 'AUTHORED' || (viewerLogin !== undefined && detail.author?.toLowerCase() === viewerLogin.toLowerCase());
  const epic = usePullRequestEpic(authored ? workspaceId : undefined, detail.repo, detail.number);
  const [repoOwner, repoName = ''] = detail.repo.split('/');
  const state = detail.state ?? pr.state;
  const displayState = state === 'merged'
    ? 'Merged'
    : state === 'closed'
      ? 'Closed'
      : detail.draft
        ? 'Draft'
        : isQueued(detail.recentActivity)
          ? 'Queued'
          : 'Open';

  const timeline: ({ kind: 'remote'; activities: Activity[]; key: string; at: string }
    | { kind: 'local'; thread: LocalReviewThreadDto; key: string; at: string })[] = [
    ...groupTimelineActivities(detail.recentActivity).map((activities, index) => ({
      kind: 'remote' as const, activities, key: `remote-${activities[0].remoteId ?? `${activities[0].eventType}-${index}`}`,
      at: activities[0].timestamp ?? '',
    })),
    ...(localReview?.threads ?? []).filter(thread => thread.repo.toLowerCase() === pr.repo.toLowerCase() && thread.number === pr.number)
      .map(thread => ({ kind: 'local' as const, thread, key: `local-${thread.id}`, at: thread.createdAt })),
  ];
  timeline.sort((left, right) => left.at.localeCompare(right.at));

  return (
    <ReviewCommentIndex className="pr-overview pull-request-overview">
      <section className="pr-overview__summary">
        <div className="pr-overview__title">
          <PullTitle key={`${detail.repo.toLowerCase()}#${detail.number}`} pr={pr} detail={detail} onDetailUpdate={onDetailUpdate} />
          <div className="pr-overview__number">
            <span>#{detail.number}</span>
            <CopyLinkButton value={detail.htmlUrl ?? pr.htmlUrl} label="pull request link" />
          </div>
        </div>
        <div className="pr-overview__branches">
          <span className={`pr-state is-${displayState.toLowerCase()}`}>
            <PullRequestIcon merged={displayState === 'Merged'} /> {displayState}
          </span>
          <span className="pr-overview__base-branch">
            <RepoAvatar owner={repoOwner} repo={repoName} size={24} />
            <span>{detail.repo}</span>
            <code>{detail.baseRef ?? 'base unavailable'}</code>
          </span>
          <span aria-hidden="true">←</span>
          <span className="pr-overview__head-branch">
            <code title={detail.headRef ?? pr.headRef ?? undefined}><bdi>{detail.headRef ?? pr.headRef ?? 'branch unavailable'}</bdi></code>
            <CopyLinkButton value={detail.headRef ?? pr.headRef ?? ''} label="head branch" />
          </span>
          <button type="button" aria-label="Open in browser" title="Open in browser"
            onClick={() => { void bridge.openInAppBrowser(detail.htmlUrl ?? pr.htmlUrl); }}>
            <ExternalLink size={18} />
          </button>
          {privateThreads !== undefined && <PrivateThreadsToggle threads={privateThreads} />}
          {epic !== null && <PullRequestEpicLink value={epic} />}
          {contributorsWorkspaceId !== undefined && (displayState === 'Merged' || displayState === 'Closed')
            && <LearnFromPullButton workspaceId={contributorsWorkspaceId} repo={detail.repo} number={detail.number} />}
          {contributorsWorkspaceId !== undefined && displayState === 'Merged'
            && <ReplayReviewButton workspaceId={contributorsWorkspaceId} repo={detail.repo} number={detail.number} />}
          {taskActions}
        </div>
      </section>

      <div className="pr-overview__rule" />
      <PullMetadata detail={detail} contributorsWorkspaceId={contributorsWorkspaceId} onDetailUpdate={onDetailUpdate} />
      {detail.unavailableSections.length > 0 && (
        <div className="pr-unavailable" role="status">
          Some sections are temporarily unavailable: {detail.unavailableSections.join(', ')}.
        </div>
      )}

      <section className="pr-timeline" aria-label="Pull request activity">
        {epic !== null && <PullRequestEpicCard value={epic} />}
        <DescriptionCard pr={pr} detail={detail} fallbackAuthor={pr.author} fallbackUrl={pr.htmlUrl}
          onDetailUpdate={onDetailUpdate}
          onQuote={body => setQuotedReply(current => ({ id: (current?.id ?? 0) + 1, body }))} />
        {timeline.map(item => item.kind === 'local' && localReview !== undefined ? (
          <div className="pr-timeline__slot" key={item.key}>
            <LocalReviewThreadCard {...localReview} thread={item.thread} />
          </div>
        ) : item.kind === 'remote' ? (
          <div className="pr-timeline__slot" key={item.key}
            id={item.activities[0].remoteId === null ? undefined : `pr-activity-${item.activities[0].remoteId}`}>
            <TimelineItem activities={item.activities} viewerLogin={viewerLogin} privateThreads={privateThreads}
              pr={pr} headRef={detail.headRef ?? pr.headRef} workspaceId={contributorsWorkspaceId} onRefresh={onRefresh} onDetailUpdate={onDetailUpdate}
              onQuote={body => setQuotedReply(current => ({ id: (current?.id ?? 0) + 1, body }))} />
          </div>
        ) : null)}
        <PullChecks pr={pr} detail={detail} onRefresh={onRefresh} />
      </section>
      <DiscussionComposer
        betterWords
        workspaceId={contributorsWorkspaceId}
        candidates={mentionCandidates(detail)}
        repository={pr.repo}
        pullRequestNumber={pr.number}
        quotedReply={quotedReply}
        onDraftChange={body => { void saveDraft(pr.repo, pr.number, body); }}
        onClose={state?.toLowerCase() === 'open' && (detail.viewerCanWrite || pr.origin === 'AUTHORED')
          ? async () => {
              await bridge.closePr(pr.repo, pr.number);
              recordAttentionAction(pullRequestAttentionKey(pr), 'update');
              await onRefresh();
            }
          : undefined}
        onSubmit={async body => {
          await submitPullRequestComment(bridge, pr.repo, pr.number, body, onDetailChange);
        }}
      />
    </ReviewCommentIndex>
  );
}

function PullTitle({ pr, detail, onDetailUpdate }: {
  pr: PullRequestDto;
  detail: PullRequestDetailDto;
  onDetailUpdate: PullDetailUpdater;
}) {
  const bridge = usePullRequestBridge();
  const title = detail.title ?? pr.title;
  const canEdit = detail.viewerCanWrite || pr.origin === 'AUTHORED';
  const [edit, setEdit] = useState<{ base: string; draft: string } | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const writeInFlight = useRef(false);
  const nextTitle = edit?.draft.trim() ?? '';

  const cancel = () => {
    if (writeInFlight.current) return;
    setEdit(null);
    setError(null);
  };
  const save = async () => {
    if (!canEdit || edit === null || !nextTitle || nextTitle === title || writeInFlight.current) return;
    if (title !== edit.base) {
      setError('The title changed while you were editing. Reopen the editor to use the latest version.');
      return;
    }
    writeInFlight.current = true;
    setSaving(true);
    setError(null);
    try {
      await bridge.updatePrTitle(detail.repo, detail.number, nextTitle);
      onDetailUpdate(detail.repo, detail.number, current => ({ ...current, title: nextTitle }));
      recordAttentionAction(pullRequestAttentionKey(detail), 'update');
      setEdit(null);
    }
    catch (failure) {
      setError(`Could not update title. ${errorMessage(failure)}`);
    }
    finally {
      writeInFlight.current = false;
      setSaving(false);
    }
  };

  return <div className="pr-title">
    {edit === null ? <h1>
      <span className="pr-title__text">{linkIssueNumbers(title, detail.repo, true)}</span>
      {canEdit && <button className="pr-title__edit" type="button" aria-label="Edit pull request title" title="Edit title" onClick={() => {
        setEdit({ base: title, draft: title });
        setError(null);
      }}><Pencil size={15} /></button>}
    </h1> : <form className="pr-title-editor" onSubmit={event => { event.preventDefault(); void save(); }}>
      <textarea aria-label="Pull request title" value={edit.draft} disabled={saving} autoFocus required rows={3}
        onChange={event => setEdit({ ...edit, draft: event.target.value.replace(/[\r\n]+/g, ' ') })}
        onKeyDown={event => {
          if (event.key === 'Escape') { event.preventDefault(); cancel(); }
          if (event.key === 'Enter' && !event.nativeEvent.isComposing) { event.preventDefault(); void save(); }
        }} />
      <div className="pr-description-editor__actions">
        <button type="button" disabled={saving} onClick={cancel}>Cancel</button>
        <button className="is-primary" type="submit" disabled={saving || !canEdit || !nextTitle || nextTitle === title}>
          {saving ? 'Saving…' : 'Save'}
        </button>
      </div>
      {error && <div className="pr-title-editor__error" role="alert">{error}</div>}
    </form>}
  </div>;
}

function DescriptionCard({ pr, detail, fallbackAuthor, fallbackUrl, onDetailUpdate, onQuote }: {
  pr: PullRequestDto;
  detail: PullRequestDetailDto;
  fallbackAuthor: string | null;
  fallbackUrl: string;
  onDetailUpdate: PullDetailUpdater;
  onQuote: (body: string) => void;
}) {
  const bridge = usePullRequestBridge();
  const actor = detail.author ?? fallbackAuthor ?? 'Unknown author';
  const key = `${detail.repo.toLowerCase()}#${detail.number}`;
  const serverBody = detail.body ?? '';
  const [localBody, setLocalBody] = useState<{ key: string; value: string } | null>(null);
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState('');
  const [editBase, setEditBase] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const writeInFlight = useRef(false);
  const body = localBody?.key === key ? localBody.value : serverBody;
  const displayBody = body || 'No description provided.';
  const serverReactions = detail.recentActivity
    .find(activity => activity.eventType === 'description_reactions')?.payload.reactions ?? [];
  const reaction = useReactionAction(
    detail.repo,
    detail.number,
    'pull_request',
    null,
    serverReactions,
    true,
    (reactions, onlyIf) => onDetailUpdate(detail.repo, detail.number, current =>
      updatePullReactionDetail(current, 'pull_request', null, reactions, onlyIf)),
  );
  const canEdit = detail.viewerCanWrite || pr.origin === 'AUTHORED';

  useEffect(() => {
    if (!saving && localBody?.key === key && localBody.value === serverBody) setLocalBody(null);
  }, [key, localBody, saving, serverBody]);

  useEffect(() => {
    setEditing(false);
    setDraft('');
    setEditBase('');
    setError(null);
    writeInFlight.current = false;
    setSaving(false);
  }, [key]);

  const persistBody = async (nextBody: string) => {
    if (writeInFlight.current || nextBody === body) return false;
    const previousLocal = localBody?.key === key ? localBody : null;
    const previousBody = previousLocal?.value ?? detail.body;
    writeInFlight.current = true;
    setSaving(true);
    setError(null);
    setLocalBody({ key, value: nextBody });
    onDetailUpdate(detail.repo, detail.number, current => ({ ...current, body: nextBody }));
    try {
      await bridge.updatePrDescription(detail.repo, detail.number, nextBody);
      recordAttentionAction(pullRequestAttentionKey(detail), 'update');
      onDetailUpdate(detail.repo, detail.number, current => ({ ...current, body: nextBody }));
      return true;
    }
    catch (failure) {
      onDetailUpdate(detail.repo, detail.number, current => current.body === nextBody
        ? { ...current, body: previousBody }
        : current);
      setLocalBody(current => current?.key === key && current.value === nextBody
        ? previousLocal
        : current);
      setError(`Could not update description. ${errorMessage(failure)}`);
      return false;
    }
    finally {
      writeInFlight.current = false;
      setSaving(false);
    }
  };

  const saveEdit = async () => {
    if (body !== editBase) {
      setError('The description changed while you were editing. Reopen the editor to use the latest version.');
      return;
    }
    setEditing(false);
    if (!await persistBody(draft)) setEditing(true);
  };

  return (
    <article className="pr-card pr-description">
      <CardHeader actor={actor} avatarUrl={detail.authorAvatarUrl} timestamp={detail.createdAt}
        reaction={reaction}
        actions={{
          link: detail.htmlUrl ?? fallbackUrl,
          markdown: body,
          onQuote: () => onQuote(body),
          editLabel: 'Edit description',
          onEdit: canEdit ? () => {
            setDraft(body);
            setEditBase(body);
            setError(null);
            setEditing(true);
          } : undefined,
        }} />
      {editing ? (
        <div className="pr-description-editor">
          <textarea aria-label="Pull request description" value={draft} rows={10} autoFocus
            onChange={event => setDraft(event.target.value)}
            onKeyDown={event => {
              if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
                event.preventDefault();
                void saveEdit();
              }
            }} />
          <div className="pr-description-editor__actions">
            <button type="button" disabled={saving} onClick={() => {
              setEditing(false);
              setError(null);
            }}>Cancel</button>
            <button className="is-primary" type="button" disabled={saving || draft === body}
              onClick={() => { void saveEdit(); }}>Save</button>
          </div>
        </div>
      ) : (
        <MarkdownBody body={displayBody} repository={detail.repo} tasksDisabled={saving}
          onTaskToggle={canEdit ? (taskIndex, checked) => {
            const nextBody = toggleMarkdownTask(body, taskIndex, checked);
            if (nextBody !== body) void persistBody(nextBody);
          } : undefined} />
      )}
      {error && <div className="pr-description__error" role="alert">{error}</div>}
      <Reactions reactions={reaction?.reactions ?? serverReactions} reaction={reaction} alwaysShowPicker />
    </article>
  );
}

function TimelineItem({ activities, viewerLogin, privateThreads, pr, headRef, workspaceId, onRefresh, onDetailUpdate, onQuote }: {
  activities: Activity[];
  viewerLogin?: string;
  privateThreads?: PrivateThreads;
  pr: PullRequestDto;
  headRef: string | null;
  workspaceId?: string;
  onRefresh: () => Promise<void>;
  onDetailUpdate: PullDetailUpdater;
  onQuote: (body: string) => void;
}) {
  const activity = activities[0];
  if (activity.eventType === 'reviewed' && activities.some(item => item.eventType === 'review_thread')) {
    return <ReviewGroup activities={activities} viewerLogin={viewerLogin} privateThreads={privateThreads} pr={pr} workspaceId={workspaceId}
      onRefresh={onRefresh} onDetailUpdate={onDetailUpdate} onQuote={onQuote} />;
  }
  if (activity.eventType === 'review_thread') {
    return <ReviewThread activity={activity} viewerLogin={viewerLogin} privateThreads={privateThreads} pr={pr} workspaceId={workspaceId}
      onRefresh={onRefresh} onDetailUpdate={onDetailUpdate} />;
  }
  if (activity.eventType === 'commented' || activity.eventType === 'reviewed') {
    return <CommentCard activity={activity} viewerLogin={viewerLogin} privateThreads={privateThreads} pr={pr}
      onDetailUpdate={onDetailUpdate} onQuote={onQuote} />;
  }
  if (activity.eventType === 'committed') return <CommitEvent activity={activity} />;
  return <CompactEvent activities={activities} headRef={headRef} pr={pr} />;
}

export function groupTimelineActivities(activities: Activity[]) {
  const candidates = activities.filter(activity => !HIDDEN_TIMELINE_EVENTS.has(activity.eventType));
  const reviewIds = new Set(candidates
    .filter(activity => activity.eventType === 'reviewed' && activity.remoteId !== null)
    .map(activity => activity.remoteId));
  const threadsByReview = new Map<number, Activity[]>();
  candidates.forEach((activity) => {
    const reviewId = reviewIdForThread(activity);
    if (reviewId === null || !reviewIds.has(reviewId)) return;
    const threads = threadsByReview.get(reviewId) ?? [];
    threads.push(activity);
    threadsByReview.set(reviewId, threads);
  });

  return candidates.filter(activity => isVisibleTimelineActivity(activity, threadsByReview)).reduce<Activity[][]>((groups, activity) => {
    const reviewId = reviewIdForThread(activity);
    if (reviewId !== null && threadsByReview.has(reviewId)) return groups;
    if (activity.eventType === 'reviewed' && activity.remoteId !== null) {
      groups.push([activity, ...(threadsByReview.get(activity.remoteId) ?? [])]);
      return groups;
    }
    const previous = groups.at(-1);
    const previousActivity = previous?.at(-1);
    if (previous && previousActivity && canGroupTimelineEvents(previousActivity, activity)) {
      previous.push(activity);
    }
    else {
      groups.push([activity]);
    }
    return groups;
  }, []);
}

function reviewIdForThread(activity: Activity) {
  if (activity.eventType !== 'review_thread') return null;
  return activity.payload.comments.find(comment => comment.inReplyToId === null)?.reviewId
    ?? activity.payload.comments[0]?.reviewId
    ?? null;
}

function canGroupTimelineEvents(previous: Activity, activity: Activity) {
  if (activity.eventType !== previous.eventType
      || activity.actor !== previous.actor) {
    return false;
  }
  if (activity.eventType === 'head_ref_force_pushed') return true;
  if (activity.eventType !== 'review_requested' && !LABEL_EVENT_TYPES.has(activity.eventType)) return false;
  const previousTime = Date.parse(previous.timestamp ?? '');
  const activityTime = Date.parse(activity.timestamp ?? '');
  return Number.isFinite(previousTime)
    && Number.isFinite(activityTime)
    && activityTime - previousTime >= 0
    && activityTime - previousTime <= TIMELINE_EVENT_GROUP_WINDOW_MS;
}

function isVisibleTimelineActivity(activity: Activity, threadsByReview: Map<number, Activity[]>) {
  return !(activity.eventType === 'reviewed'
    && activity.payload.state?.toUpperCase() === 'COMMENTED'
    && !activity.body?.trim()
    && (activity.remoteId === null || !threadsByReview.has(activity.remoteId)));
}

function CommitEvent({ activity }: { activity: Activity }) {
  const { payload } = activity;
  return (
    <div className="pr-commit-event">
      <span aria-hidden="true"><GitCommitHorizontal size={14} aria-hidden="true" /></span>
      <div>
        {payload.commitMessage?.split('\n')[0] ?? 'Committed'}
        {payload.commitSha && <code>{shortSha(payload.commitSha)}</code>}
        <time> · {relativeTime(activity.timestamp)}</time>
      </div>
    </div>
  );
}

function CommentCard({ activity, viewerLogin, privateThreads, pr, onDetailUpdate, onQuote }: {
  activity: Activity;
  viewerLogin?: string;
  privateThreads?: PrivateThreads;
  pr: PullRequestDto;
  onDetailUpdate: PullDetailUpdater;
  onQuote: (body: string) => void;
}) {
  const state = activity.payload.state?.toUpperCase();
  const actor = displayActor(activity.actor || 'github');
  const body = activity.body?.trim() ? activity.body : null;
  const [editDraft, setEditDraft] = useState<string | null>(null);
  const commentId = activity.remoteId;
  const canEdit = body !== null && commentId !== null && isViewerComment(activity.actor, viewerLogin);
  const reaction = useReactionAction(
    pr.repo,
    pr.number,
    'issue_comment',
    activity.remoteId,
    activity.payload.reactions,
    activity.eventType === 'commented' && activity.remoteId !== null,
    (reactions, onlyIf) => onDetailUpdate(pr.repo, pr.number, current =>
      updatePullReactionDetail(current, 'issue_comment', activity.remoteId, reactions, onlyIf)),
  );
  // The backend resolves review: keys from the same reviewed event this card renders.
  const threadKey = privateThreads === undefined || body === null || commentId === null ? null
    : privateThreadKey(activity.eventType === 'reviewed' ? 'review' : 'issue_comment', commentId);
  const badge = reviewBadge(state);
  if (!body) {
    return <article className="pr-card pr-review-event">
      <CardHeader actor={actor} avatarUrl={activity.payload.actorAvatarUrl} bot={activity.payload.actorBot}
        timestamp={activity.timestamp} badge={badge} reaction={reaction} />
    </article>;
  }
  if (privateThreads !== undefined && threadKey !== null && privateThreads.forward === threadKey) {
    return (
      <div className="pr-card pr-private-card" {...commentIndexTarget(activity.payload.actorBot ? 'agent' : 'human', actor, body)}>
        <PrivateThreadForward threads={privateThreads} threadKey={threadKey} author={actor} avatarUrl={activity.payload.actorAvatarUrl}
          timestamp={activity.timestamp} body={body} repository={pr.repo} viewerLogin={viewerLogin} />
      </div>
    );
  }
  return (
    <ReviewThreadCard className="pr-comment-card" resolved={false} controls={null}
      attributes={commentIndexTarget(activity.payload.actorBot ? 'agent' : 'human', actor, body)}>
      <ReviewThreadMessage timestamp={activity.timestamp}
        author={<UserAvatar login={actor} avatarUrl={activity.payload.actorAvatarUrl} size={22} showLogin />}
        tag={<>{activity.payload.actorBot && <span className="pr-bot">Bot</span>}
          {badge && <span className={`pr-review-badge ${badge.className}`}>{badge.text}</span>}</>}
        menu={<>{reaction && <ReactionPicker action={reaction} />}
          {privateThreads !== undefined && threadKey !== null && <PrivateThreadControls threads={privateThreads} threadKey={threadKey}
            own={isViewerComment(activity.actor, viewerLogin)} comment={{ author: actor, body, url: activity.payload.htmlUrl ?? pr.htmlUrl, location: null }} />}
          <CardActionsMenu actions={{ link: activity.payload.htmlUrl ?? pr.htmlUrl, markdown: body,
            onQuote: () => onQuote(body), onEdit: canEdit ? () => setEditDraft(body) : undefined }} />
        </>}
        reactions={<Reactions reactions={reaction?.reactions ?? activity.payload.reactions} reaction={reaction} />}>
        {editDraft !== null && commentId !== null
          ? <CommentEditor pr={pr} target={activity.eventType === 'reviewed' ? 'review' : 'issue_comment'} commentId={commentId}
            body={body} draft={editDraft} onDraftChange={setEditDraft} onDetailUpdate={onDetailUpdate} onClose={() => setEditDraft(null)} />
          : <MarkdownBody body={body} repository={pr.repo} />}
        {privateThreads !== undefined && threadKey !== null && <PrivateThreadTab threads={privateThreads} threadKey={threadKey} />}
      </ReviewThreadMessage>
    </ReviewThreadCard>
  );
}

function ReviewGroup({ activities, viewerLogin, privateThreads, pr, workspaceId, onRefresh, onDetailUpdate, onQuote }: {
  activities: Activity[];
  viewerLogin?: string;
  privateThreads?: PrivateThreads;
  pr: PullRequestDto;
  workspaceId?: string;
  onRefresh: () => Promise<void>;
  onDetailUpdate: PullDetailUpdater;
  onQuote: (body: string) => void;
}) {
  const review = activities[0];
  const actor = displayActor(review.actor || 'github');
  const badge = reviewBadge(review.payload.state?.toUpperCase());
  return (
    <section className="pr-review-group">
      <div className="pr-review-group__summary">
        <span className="pr-review-group__marker" aria-hidden="true"><ReviewIcon /></span>
        <UserAvatar login={actor} avatarUrl={review.payload.actorAvatarUrl} size={24} showLogin />
        {review.payload.actorBot && <span className="pr-bot-badge">Bot</span>}
        <span>reviewed</span>
        <time>{relativeTime(review.timestamp)}</time>
        {badge && <span className={`pr-review-badge ${badge.className}`}>{badge.text}</span>}
      </div>
      {review.body?.trim() && (
        <CommentCard activity={review} viewerLogin={viewerLogin} privateThreads={privateThreads} pr={pr} onDetailUpdate={onDetailUpdate} onQuote={onQuote} />
      )}
      {activities.slice(1).map(thread => (
        <ReviewThread key={thread.remoteId ?? `${thread.timestamp}-${thread.payload.path}`} activity={thread} privateThreads={privateThreads}
          viewerLogin={viewerLogin} pr={pr} workspaceId={workspaceId} onRefresh={onRefresh} onDetailUpdate={onDetailUpdate} />
      ))}
    </section>
  );
}

export function updateReviewThreadResolution(
  detail: PullRequestDetailDto,
  activityId: number | null,
  rootCommentId: number,
  resolved: boolean,
  onlyIfResolved?: boolean,
) {
  return {
    ...detail,
    recentActivity: detail.recentActivity.map(candidate => {
      const matches = candidate.eventType === 'review_thread'
        && (activityId !== null
          ? candidate.remoteId === activityId
          : candidate.payload.comments.some(comment => comment.remoteId === rootCommentId));
      if (!matches || (onlyIfResolved !== undefined && candidate.payload.resolved !== onlyIfResolved)) {
        return candidate;
      }
      return { ...candidate, payload: { ...candidate.payload, resolved } };
    }),
  };
}

export function ReviewThread({ activity, viewerLogin, privateThreads, pr, workspaceId, onRefresh, onDetailUpdate, showCode = true, pendingReview }: {
  activity: Activity;
  viewerLogin?: string;
  privateThreads?: PrivateThreads;
  pr: PullRequestDto;
  workspaceId?: string;
  onRefresh: () => Promise<void>;
  onDetailUpdate: PullDetailUpdater;
  showCode?: boolean;
  pendingReview?: React.ContextType<typeof PendingReviewComments>;
}) {
  const bridge = usePullRequestBridge();
  const { payload } = activity;
  const hostPending = useContext(PendingReviewComments);
  const pending = pendingReview ?? hostPending;
  const [replying, setReplying] = useReviewDraft(`remote:${activity.remoteId}:replying`, false);
  const [body, setBody] = useReviewDraft(`remote:${activity.remoteId}:body`, '');
  const [preview, setPreview] = useState(false);
  const [posting, setPosting] = useState(false);
  const [changingResolution, setChangingResolution] = useState(false);
  const [optimisticResolved, setOptimisticResolved] = useState<boolean | null>(null);
  const [edit, setEdit] = useReviewDraft<{ commentId: number; draft: string } | null>(`remote:${activity.remoteId}:edit`, null);
  const [error, setError] = useState('');
  const [drafting, setDrafting] = useState(false);
  // The editor holds the agent's draft until the user chooses to post it or add it to a review.
  const [drafted, setDrafted] = useState(false);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const resolutionInFlight = useRef(false);
  const targetLine = payload.line ?? payload.originalLine;
  const commentedLines = showCode ? threadCommentedLines(payload) : [];
  const suggestion = reviewThreadSuggestion(payload);
  const rootCommentId = payload.comments.find(comment => comment.inReplyToId === null)?.remoteId
    ?? payload.comments[0]?.remoteId;
  const pendingReplies = pending?.comments.filter(comment => comment.replyToCommentId === rootCommentId) ?? [];
  const resolved = optimisticResolved ?? payload.resolved;
  const startLine = payload.line === null ? payload.originalStartLine : payload.startLine;
  const sidePrefix = payload.side === 'LEFT' ? 'L' : payload.side === 'RIGHT' ? 'R' : '';
  const isRange = startLine !== null && startLine !== targetLine;
  const location = showCode
    ? <bdi dir="ltr"><code>{threadLocation(payload.path, resolved || commentedLines.length === 0 ? targetLine : null, payload.side, startLine)}</code></bdi>
    : targetLine === null
      ? <span>Comment on file</span>
      : <span>Comment on {isRange ? 'lines' : 'line'} <strong>{isRange ? `${sidePrefix}${startLine}–` : ''}{sidePrefix}{targetLine}</strong></span>;
  const submit = async () => {
    if (!rootCommentId || !body.trim()) return;
    setPosting(true);
    setError('');
    try {
      await bridge.replyToReviewComment(pr.repo, pr.number, rootCommentId, body.trim());
      recordAttentionAction(pullRequestAttentionKey(pr), 'comment');
    }
    catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not post reply.');
      setPosting(false);
      return;
    }
    setBody('');
    setPreview(false);
    setReplying(false);
    setPosting(false);
    setDrafted(false);
    await onRefresh();
  };
  const changeResolution = async () => {
    if (!rootCommentId || resolutionInFlight.current) return;
    const previous = resolved;
    const desired = !previous;
    resolutionInFlight.current = true;
    setChangingResolution(true);
    setOptimisticResolved(desired);
    setError('');
    onDetailUpdate(pr.repo, pr.number, current => updateReviewThreadResolution(
      current, activity.remoteId, rootCommentId, desired));
    try {
      await bridge.setReviewThreadResolved(pr.repo, pr.number, rootCommentId, desired);
      recordAttentionAction(pullRequestAttentionKey(pr), 'update');
      onDetailUpdate(pr.repo, pr.number, current => updateReviewThreadResolution(
        current, activity.remoteId, rootCommentId, desired));
    }
    catch (cause) {
      onDetailUpdate(pr.repo, pr.number, current => updateReviewThreadResolution(
        current, activity.remoteId, rootCommentId, previous, desired));
      setError(cause instanceof Error ? cause.message : `Could not ${previous ? 'unresolve' : 'resolve'} thread.`);
    }
    setOptimisticResolved(null);
    resolutionInFlight.current = false;
    setChangingResolution(false);
  };
  const focusReply = () => requestAnimationFrame(() => {
    textareaRef.current?.focus();
    textareaRef.current?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  });
  const quoteReply = (quotedBody: string) => {
    setBody(current => appendQuotedReply(current, quotedBody));
    setPreview(false);
    setError('');
    setReplying(true);
    focusReply();
  };
  const draftWithAgent = async (thread: TaskDiscussionDto, author: string) => {
    if (privateThreads === undefined || drafting) return;
    privateThreads.setForward(null);
    setDrafting(true);
    setError('');
    try {
      const text = thread.replyDraft ?? await privateThreads.draftReply(thread);
      // The draft can take many seconds; if the user started typing elsewhere meanwhile, the caret stays there.
      const active = document.activeElement;
      const typingElsewhere = active !== textareaRef.current && active instanceof HTMLElement
        && (active.isContentEditable || active instanceof HTMLTextAreaElement || active instanceof HTMLInputElement);
      setBody(current => withMention(current, text, author));
      setReplying(true);
      setPreview(false);
      setDrafted(true);
      if (!typingElsewhere) focusReply();
    }
    catch (failure) {
      setError(errorMessage(failure));
    }
    finally {
      setDrafting(false);
    }
  };
  const addToReview = () => {
    if (!pending || !rootCommentId || !body.trim()) return;
    pending.onChange([...pending.comments, {
      replyToCommentId: rootCommentId, body: body.trim(), path: payload.path ?? '',
      line: targetLine ?? 1, side: payload.side === 'LEFT' ? 'LEFT' : 'RIGHT',
      startLine: null, startSide: null, commitId: null,
    }]);
    discard();
  };
  const discard = () => { setReplying(false); setBody(''); setPreview(false); setError(''); setDrafted(false); };
  const controls = drafting ? (
    <footer className="lrc__foot pr-thread-draft" role="status">
      <span className="bq-spinner is-spinning"><LoaderCircle className="pr-task-spinner" size={14} aria-hidden="true" /></span> Agent is drafting a reply from your private thread…
    </footer>
  ) : replying ? (
    <footer className="lrc__foot is-composing">
      <MarkdownEditor value={body} onChange={setBody} repository={pr.repo} suggestion={suggestion}
        textareaRef={textareaRef} autoFocus={!drafted} preview={preview} onPreviewChange={setPreview}
        ariaLabel="Reply to review thread" placeholder="Reply to this thread…" busy={posting} error={error}
        // Agent drafts require an explicit button click, never the private composer's send key.
        onSubmit={drafted ? undefined : () => { void submit(); }}
        header={drafted && <div className="pr-thread-draft__head">
          <span className="pr-thread-draft__badge">Draft · not posted</span>
          <span>Written by the agent from your private thread. Edit it before you post.</span>
        </div>}
        note={drafted && <span className="pr-thread-draft__note">Posts as you</span>}>
        <BetterWordsButton text={body} textareaRef={textareaRef} repository={pr.repo} workspaceId={workspaceId}
          disabled={preview || posting}
          onError={failure => setError(failure === null ? '' : errorMessage(failure))} />
        <button type="button" disabled={posting} onClick={discard}>{drafted ? 'Discard' : 'Cancel'}</button>
        {pending && pr.state?.toLowerCase() === 'open' && <button type="button" disabled={posting || !body.trim()}
          onClick={addToReview}>Add to review</button>}
        <button className="is-primary" type="button" disabled={posting || !body.trim()}
          onClick={() => { void submit(); }}>{drafted ? posting ? 'Commenting…' : 'Comment' : posting ? 'Replying…' : 'Reply'}</button>
      </MarkdownEditor>
    </footer>
  ) : (
    <footer className="lrc__foot">
      <button className="lrc__ask" type="button" disabled={!rootCommentId}
        onClick={() => { setError(''); setPreview(false); setReplying(true); }}>Reply</button>
      {error && <span className="pr-thread-reply__error" role="alert">{error}</span>}
      {pendingReplies.map((comment, index) => <button key={index} className="lrc__action" type="button"
        title={comment.body} aria-label={`Remove pending reply${pendingReplies.length > 1 ? ` ${index + 1}` : ''}`}
        onClick={() => pending?.onChange(pending.comments.filter(value => value !== comment))}>
        {pendingReplies.length > 1 ? `Remove reply ${index + 1}` : 'Remove'}
      </button>)}
      <button className="lrc__action" type="button" disabled={!rootCommentId || changingResolution}
        onClick={() => { void changeResolution(); }}>
        {resolved ? 'Unresolve' : 'Resolve'}
      </button>
    </footer>
  );
  return (
    <ReviewThreadCard title={<span className={`lrc__title${showCode ? ' is-path' : ''}`} title={showCode ? payload.path ?? undefined : undefined}>{location}</span>}
      resolved={resolved} outdated={payload.outdated}
      badges={pendingReplies.length > 0 && <span className="pull-diff-pending__badge">Pending</span>}
      code={commentedLines.length > 0 && <CodeExcerpt path={payload.path} lines={commentedLines} />} controls={controls}>
      {payload.comments.map(comment => <ThreadComment comment={comment} fallbackUrl={payload.htmlUrl ?? pr.htmlUrl} suggestion={suggestion}
        location={threadLocation(payload.path, targetLine, payload.side, startLine)}
        indexLabel={`${threadLocation(payload.path, targetLine, payload.side, startLine)} · ${resolved ? 'Resolved' : 'Open'} · ${payload.outdated ? 'Outdated · ' : ''}`}
        viewerLogin={viewerLogin} pr={pr} onDetailUpdate={onDetailUpdate} onQuote={quoteReply} edit={edit} onEditChange={setEdit}
        privateThreads={privateThreads}
        onDraftReply={rootCommentId === undefined || rootCommentId === null ? undefined
          : thread => { void draftWithAgent(thread, displayActor(comment.actor ?? '')); }}
        key={comment.remoteId ?? comment.timestamp} />)}
      {pendingReplies.map((comment, index) => (
        <ReviewThreadMessage key={index} timestamp={null}
          author={viewerLogin ? <UserAvatar login={viewerLogin} size={22} showLogin /> : <strong>You</strong>}>
          <MarkdownBody body={comment.body} repository={pr.repo} />
        </ReviewThreadMessage>
      ))}
    </ReviewThreadCard>
  );
}

function ThreadComment({ comment, fallbackUrl, suggestion, viewerLogin, pr, onDetailUpdate, onQuote, edit, onEditChange, privateThreads, onDraftReply, location, indexLabel }: {
  comment: PullTimelineCommentDto;
  location: string;
  privateThreads?: PrivateThreads;
  onDraftReply?: (thread: TaskDiscussionDto) => void;
  fallbackUrl: string;
  suggestion?: SuggestionBase;
  indexLabel: string;
  viewerLogin?: string;
  pr: PullRequestDto;
  onDetailUpdate: PullDetailUpdater;
  onQuote: (body: string) => void;
  edit: { commentId: number; draft: string } | null;
  onEditChange: (edit: { commentId: number; draft: string } | null) => void;
}) {
  const commentId = comment.remoteId;
  const canEdit = Boolean(comment.body) && commentId !== null && isViewerComment(comment.actor, viewerLogin);
  const editDraft = commentId !== null && edit?.commentId === commentId ? edit.draft : null;
  const reaction = useReactionAction(
    pr.repo,
    pr.number,
    'review_comment',
    comment.remoteId,
    comment.reactions,
    comment.remoteId !== null,
    (reactions, onlyIf) => onDetailUpdate(pr.repo, pr.number, current =>
      updatePullReactionDetail(current, 'review_comment', comment.remoteId, reactions, onlyIf)),
  );
  const threadKey = privateThreads === undefined || commentId === null || !comment.body ? null : privateThreadKey('review_comment', commentId);
  if (privateThreads !== undefined && threadKey !== null && privateThreads.forward === threadKey) {
    return <div {...commentIndexTarget(comment.actorBot ? 'agent' : 'human', `${comment.actor ?? 'github'} · ${indexLabel}`, comment.body ?? '')}>
      <PrivateThreadForward threads={privateThreads} threadKey={threadKey} author={displayActor(comment.actor ?? 'github')}
      avatarUrl={comment.actorAvatarUrl} timestamp={comment.timestamp} body={comment.body ?? ''} repository={pr.repo}
      viewerLogin={viewerLogin} onDraftReply={onDraftReply} /></div>;
  }
  return (
    <ReviewThreadMessage timestamp={comment.timestamp}
      attributes={commentIndexTarget(comment.actorBot ? 'agent' : 'human', `${comment.actor ?? 'github'} · ${indexLabel}`, comment.body ?? '')} author={<>
        <UserAvatar login={comment.actor ?? 'github'} avatarUrl={comment.actorAvatarUrl} size={22} showLogin />
        {comment.actorBot && <span className="pr-bot">Bot</span>}
      </>}
      menu={<>{reaction && <ReactionPicker action={reaction} />}
        {privateThreads !== undefined && threadKey !== null && <PrivateThreadControls threads={privateThreads} threadKey={threadKey}
          own={isViewerComment(comment.actor, viewerLogin)}
          comment={{ author: displayActor(comment.actor ?? 'github'), body: comment.body ?? '', url: comment.htmlUrl ?? fallbackUrl, location }} />}
        {comment.body && <CardActionsMenu actions={{
          link: comment.htmlUrl ?? fallbackUrl,
          markdown: comment.body,
          onQuote: () => onQuote(comment.body ?? ''),
          onEdit: canEdit && commentId !== null ? () => onEditChange({ commentId, draft: comment.body ?? '' }) : undefined,
        }} />}</>}
      reactions={<Reactions reactions={reaction?.reactions ?? comment.reactions} reaction={reaction} />}>
      {comment.body && (editDraft !== null && commentId !== null
        ? <CommentEditor pr={pr} target="review_comment" commentId={commentId} body={comment.body} suggestion={suggestion}
          draft={editDraft} onDraftChange={draft => onEditChange({ commentId, draft })}
          onDetailUpdate={onDetailUpdate} onClose={() => onEditChange(null)} />
        : <MarkdownBody body={comment.body} repository={pr.repo} suggestion={suggestion} />)}
      {privateThreads !== undefined && threadKey !== null && <PrivateThreadTab threads={privateThreads} threadKey={threadKey} />}
    </ReviewThreadMessage>
  );
}

/** GitHub stores web-typed comments with CRLF, but a textarea only ever yields LF. */
export function canUpdateComment(draft: string, body: string) {
  return draft.trim() !== '' && draft.replaceAll('\r\n', '\n') !== body.replaceAll('\r\n', '\n');
}

/**
 * Edits a posted comment in place. The owner seeds `draft` from the body once, when Edit is chosen, so a
 * refresh that changes the body never clobbers it.
 */
export function CommentEditor({ pr, target, commentId, body, draft, suggestion, onDraftChange, onDetailUpdate, onClose }: {
  pr: PullRequestDto;
  target: PullCommentTarget;
  commentId: number;
  body: string;
  draft: string;
  suggestion?: SuggestionBase;
  onDraftChange: (draft: string) => void;
  onDetailUpdate: PullDetailUpdater;
  onClose: () => void;
}) {
  const bridge = usePullRequestBridge();
  const [preview, setPreview] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const canSave = !saving && canUpdateComment(draft, body);
  const save = async () => {
    if (!canSave) return;
    setSaving(true);
    setError('');
    try {
      const response = await bridge.editPrComment(pr.repo, pr.number, target, commentId, draft);
      recordAttentionAction(pullRequestAttentionKey(pr), 'update');
      onDetailUpdate(pr.repo, pr.number, current => mergePullRequestWriteDetail(current, response));
      onClose();
    }
    catch (failure) {
      setError(`Could not update comment. ${errorMessage(failure)}`);
      setSaving(false);
    }
  };
  return (
    <MarkdownEditor value={draft} onChange={onDraftChange} repository={pr.repo} suggestion={suggestion}
      textareaRef={textareaRef} autoFocus ariaLabel="Edit comment" preview={preview} onPreviewChange={setPreview}
      busy={saving} error={error} onSubmit={() => { void save(); }}>
      <button type="button" disabled={saving} onClick={onClose}>Cancel</button>
      <button className="is-primary" type="button" disabled={!canSave}
        onClick={() => { void save(); }}>{saving ? 'Updating…' : 'Update comment'}</button>
    </MarkdownEditor>
  );
}

/** The commented lines of a range, read from a thread's diff_hunk or from a whole-file patch of several hunks. */
export function commentedCodeLines(patch: string | null, side: string | null, startLine: number | null, endLine: number | null): ReviewCommentLine[] {
  if (endLine === null) return [];
  const left = side?.toUpperCase() === 'LEFT';
  const first = startLine ?? endLine;
  return parseUnifiedDiff(patch).flatMap(hunk => hunk.rows)
    .map((row): ReviewCommentLine => ({
      lineNumber: left ? row.oldLine : row.newLine,
      prefix: row.kind === 'add' ? '+' : row.kind === 'del' ? '-' : ' ',
      text: row.content,
    }))
    .filter(line => line.lineNumber !== null && line.lineNumber >= first && line.lineNumber <= endLine);
}

/** A thread's range in its own diff_hunk, which GitHub records as the diff at the comment's original commit. */
function threadHunkRange(payload: PullTimelinePayloadDto): { start: number; end: number } | null {
  const end = payload.originalLine ?? payload.line;
  if (end === null) return null;
  return { start: (payload.originalLine !== null ? payload.originalStartLine : payload.startLine) ?? end, end };
}

/** The code a review thread comments on, as its diff_hunk recorded it. */
export function threadCommentedLines(payload: PullTimelinePayloadDto): ReviewCommentLine[] {
  const range = threadHunkRange(payload);
  return range === null ? [] : commentedCodeLines(payload.diffHunk, payload.side, range.start, range.end);
}

/** The head-side lines a RIGHT review thread's suggestion replaces. */
export function reviewThreadSuggestion(payload: PullTimelinePayloadDto): SuggestionBase | undefined {
  const range = threadHunkRange(payload);
  if (payload.side?.toUpperCase() !== 'RIGHT' || !payload.diffHunk || range === null) return undefined;
  const lines = threadCommentedLines(payload);
  return {
    startLine: payload.line !== null ? payload.startLine ?? payload.line : range.start,
    lines: lines.length > 0 && lines.length === range.end - range.start + 1 ? lines.map(line => line.text) : null,
  };
}

function CompactEvent({ activities, headRef, pr }: { activities: Activity[]; headRef: string | null; pr: PullRequestDto }) {
  const { server } = useHostingPresentation();
  const webBaseUrl = server?.webBaseUrl ?? 'https://github.com';
  const activity = activities[0];
  const { payload } = activity;
  const actor = displayActor(activity.actor || 'github');
  if (activity.eventType === 'review_requested') {
    return (
      <div className="pr-review-group__summary">
        <span className="pr-review-group__marker" aria-hidden="true"><ReviewIcon /></span>
        <UserAvatar login={actor} avatarUrl={payload.actorAvatarUrl} size={24} showLogin />
        {payload.actorBot && <span className="pr-bot">Bot</span>}
        <span>{eventText(activity, activities, headRef, webBaseUrl)}</span>
        <time dateTime={activity.timestamp ?? undefined}>{relativeTime(activity.timestamp, { style: 'long' })}</time>
      </div>
    );
  }
  if (LABEL_EVENT_TYPES.has(activity.eventType)) {
    return (
      <div className="pr-event pr-label-event">
        <span className="pr-label-event__marker" aria-hidden="true"><Tag size={15} strokeWidth={1.4} aria-hidden="true" /></span>
        <div className="pr-label-event__content">
          <span className="pr-label-event__actor">
            <UserAvatar login={actor} avatarUrl={payload.actorAvatarUrl} size={24} showLogin />
            {payload.actorBot && <span className="pr-bot">Bot</span>}
          </span>
          <span>{activity.eventType === 'labeled' ? 'added' : 'removed'}</span>
          <span className="pr-label-event__labels">
            {activities.map(labelActivity => (
              <LabelBadge name={labelActivity.payload.label ?? 'label'} color={labelActivity.payload.labelColor}
                key={labelActivity.remoteId ?? labelActivity.payload.label} />
            ))}
          </span>
          {activities.length > 1 && <span>labels</span>}
          <time>· {relativeTime(activity.timestamp)}</time>
        </div>
      </div>
    );
  }
  return (
    <div className="pr-event pr-actor-event">
      <UserAvatar login={actor} avatarUrl={payload.actorAvatarUrl} size={24} showLogin />
      <div>
        {payload.actorBot && <span className="pr-bot">Bot</span>}{' '}
        <span>{eventText(activity, activities, headRef, webBaseUrl)}</span>
        <time> · {relativeTime(activity.timestamp)}</time>
        {activity.eventType === 'removed_from_merge_queue' && <MergeQueueRemoval activity={activity} repo={pr.repo} number={pr.number} />}
      </div>
    </div>
  );
}

function eventText(activity: Activity, activities: Activity[], headRef: string | null, webBaseUrl: string) {
  const p = activity.payload;
  switch (activity.eventType) {
    case 'labeled': return <>added <LabelBadge name={p.label ?? 'label'} color={p.labelColor} /></>;
    case 'unlabeled': return <>removed <LabelBadge name={p.label ?? 'label'} color={p.labelColor} /></>;
    case 'renamed': return <>changed the title from <del>{p.renameFrom}</del> to <strong>{p.renameTo}</strong></>;
    case 'cross-referenced': return <>mentioned this in <GitHubReferenceLink href={p.sourceUrl}>{p.sourceTitle ?? `#${p.sourceNumber ?? ''}`}</GitHubReferenceLink></>;
    case 'review_requested': {
      const reviewers = [...new Set(activities
        .map(item => item.payload.requestedReviewer)
        .filter((reviewer): reviewer is string => typeof reviewer === 'string' && reviewer.length > 0))]
        .sort((left, right) => left.localeCompare(right));
      if (reviewers.length === 0) return <>requested a review</>;
      return <>requested a review from {reviewers.slice(0, 3).map((reviewer, index) => {
        const [owner, team] = reviewer.split('/');
        const href = team ? `${webBaseUrl}/orgs/${encodeURIComponent(owner)}/teams/${encodeURIComponent(team)}`
          : `${webBaseUrl}/${encodeURIComponent(reviewer)}`;
        return <span key={reviewer}>{index === 0 ? '' : index === reviewers.length - 1 ? ' and ' : ', '}
          <a href={href} onClick={event => { event.preventDefault(); void openWebLink(href); }}><strong>{reviewer}</strong></a>
        </span>;
      })}{reviewers.length > 3 && ` and ${reviewers.length - 3} other ${reviewers.length === 4 ? 'reviewer' : 'reviewers'}`}</>;
    }
    case 'review_request_removed': return <>removed a review request</>;
    case 'assigned': return <>assigned{p.assignee ? <> <strong>{p.assignee}</strong></> : null}</>;
    case 'unassigned': return <>unassigned{p.assignee ? <> <strong>{p.assignee}</strong></> : null}</>;
    case 'milestoned': return <>added this to the <strong>{p.milestoneTitle}</strong> milestone</>;
    case 'demilestoned': return <>removed this from the <strong>{p.milestoneTitle}</strong> milestone</>;
    case 'added_to_merge_queue': return <>added this to the merge queue</>;
    case 'removed_from_merge_queue': return <>removed this from the merge queue</>;
    case 'auto_merge_enabled':
    case 'auto_rebase_enabled':
    case 'auto_squash_enabled': return <>enabled merge when ready</>;
    case 'auto_merge_disabled': return <>cancelled merge when ready</>;
    case 'head_ref_force_pushed': return <>force-pushed the head branch{activities.length > 1 ? ` ${activities.length} times` : ''}</>;
    case 'head_ref_deleted': return <>deleted the {headRef ? <code>{headRef}</code> : 'head'} branch</>;
    case 'convert_to_draft': return <>converted this pull request to draft</>;
    case 'ready_for_review': return <>marked this pull request ready for review</>;
    case 'closed': return <>closed this</>;
    case 'merged': return <>merged</>;
    case 'reopened': return <>reopened this</>;
    case 'system_note':
    case 'ci_autofix': return <>{activity.body ?? 'updated this pull request'}</>;
    default: return <>{activity.eventType.replaceAll('_', ' ')}</>;
  }
}

type CardActions = {
  link: string;
  markdown: string;
  onQuote: () => void;
  editLabel?: string;
  onEdit?: () => void;
};

export function CardHeader({ actor, avatarUrl, bot = false, timestamp, badge, reaction, actions }: {
  actor: string;
  avatarUrl?: string | null;
  bot?: boolean;
  timestamp: string | null;
  badge?: { text: string; className: string } | null;
  reaction?: ReactionAction;
  actions?: CardActions;
}) {
  return (
    <header className="pr-card__header">
      <UserAvatar login={actor} avatarUrl={avatarUrl} size={22} showLogin />
      {bot && <span className="pr-bot">Bot</span>}
      {timestamp && <><span>·</span><time>{relativeTime(timestamp)}</time></>}
      {badge && <span className={`pr-review-badge ${badge.className}`}>{badge.text}</span>}
      {(reaction || actions) && <div className="pr-card__menu">
        {reaction && <ReactionPicker action={reaction} />}
        {actions && <CardActionsMenu actions={actions} />}
      </div>}
    </header>
  );
}

function CardActionsMenu({ actions }: { actions: CardActions }) {
  const [open, setOpen] = useState(false);

  const root = usePopoverDismiss(() => setOpen(false), false, open);

  const copy = async (value: string) => {
    await navigator.clipboard.writeText(value);
    setOpen(false);
  };

  return (
    <div ref={root} className={`pr-card-actions${open ? ' is-open' : ''}`}>
      <button type="button" className="pr-card-actions__trigger" aria-label="More actions"
        aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen(value => !value)}><Ellipsis size={18} aria-hidden="true" /></button>
      <div className="pr-card-actions__menu" role="menu" aria-label="Comment actions">
        {actions.onEdit && <button type="button" role="menuitem" onClick={() => {
          actions.onEdit?.();
          setOpen(false);
        }}><Pencil size={16} strokeWidth={1.4} aria-hidden="true" />{actions.editLabel ?? 'Edit'}</button>}
        <button type="button" role="menuitem" onClick={() => { void copy(actions.link); }}><Link size={16} strokeWidth={1.4} aria-hidden="true" />Copy link</button>
        <button type="button" role="menuitem" onClick={() => { void copy(actions.markdown); }}><CopyIcon />Copy Markdown</button>
        <button type="button" role="menuitem" onClick={() => { actions.onQuote(); setOpen(false); }}><Quote size={16} strokeWidth={1.4} aria-hidden="true" />Quote reply</button>
      </div>
    </div>
  );
}

// ponytail: one pending request at a time; asking to comment on another pull request first replaces it.
let commentRequest: string | null = null;

/** Has the next comment box drawn for this pull request take focus, as the Assistant's Comment opens it to write in. */
export function requestPullComment(repo: string, number: number): void {
  commentRequest = `${repo.toLowerCase()}#${number}`;
}
export type QuoteRequest = { id: number; body: string };

export function DiscussionComposer({
  betterWords = false,
  workspaceId,
  candidates,
  repository,
  pullRequestNumber,
  quotedReply,
  closeLabel = 'Close pull request',
  onClose,
  onDraftChange,
  onSubmit,
}: {
  betterWords?: boolean;
  workspaceId?: string;
  candidates: MentionCandidate[];
  repository: string;
  pullRequestNumber?: number;
  quotedReply?: QuoteRequest;
  closeLabel?: string;
  onClose?: () => Promise<void>;
  /** Called as the composer settles, so a reply never posted stays findable. */
  onDraftChange?: (body: string) => void;
  onSubmit: (body: string) => Promise<void>;
}) {
  const [body, setBody] = useState('');
  const [preview, setPreview] = useState(false);
  const [posting, setPosting] = useState(false);
  const [closing, setClosing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [draftRevision, setDraftRevision] = useState(0);
  const hadDraft = useRef(false);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const command = usePrCommentCommand({ repository, number: pullRequestNumber, value: body, onChange: setBody, textareaRef });
  const mentions = useMentions({ repository, workspaceId, participants: candidates, value: body, onChange: setBody, textareaRef });
  const dismissMention = mentions.dismiss;
  useEffect(() => {
    if (pullRequestNumber === undefined || commentRequest !== `${repository.toLowerCase()}#${pullRequestNumber}`) return;
    commentRequest = null;
    requestAnimationFrame(() => {
      textareaRef.current?.focus();
      textareaRef.current?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    });
  }, [repository, pullRequestNumber]);
  useEffect(() => {
    if (!quotedReply) return;
    setBody(current => appendQuotedReply(current, quotedReply.body));
    setPreview(false);
    dismissMention();
    setError(null);
    requestAnimationFrame(() => {
      textareaRef.current?.focus();
      textareaRef.current?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    });
  }, [quotedReply, dismissMention]);
  useEffect(() => {
    if (!onDraftChange || (!body && !hadDraft.current)) return;
    // An untouched composer must not clear the saved text from an earlier visit.
    hadDraft.current = true;
    const timer = window.setTimeout(() => onDraftChange(body), 1200);
    return () => window.clearTimeout(timer);
  }, [body, onDraftChange]);
  const submit = async () => {
    if (!body.trim() || command.loading) return;
    command.dismiss();
    setPosting(true);
    setError(null);
    try {
      await onSubmit(body.trim());
      setBody('');
      setDraftRevision(revision => revision + 1);
    }
    finally { setPosting(false); }
  };
  const post = () => { void submit().catch(failure => setError(errorMessage(failure))); };
  const close = async () => {
    if (!onClose) return;
    setClosing(true);
    setError(null);
    // A draft goes out first, as GitHub's "Close with comment" does; a failed post leaves it open.
    try {
      await submit();
      await onClose();
    }
    catch (failure) { setError(errorMessage(failure)); }
    finally { setClosing(false); }
  };
  return (
    <section className="pr-composer">
      <header>
        <button className={!preview ? 'is-active' : ''} type="button" onClick={() => {
          setPreview(false);
          requestAnimationFrame(() => textareaRef.current?.focus());
        }}>Write</button>
        <button className={preview ? 'is-active' : ''} type="button" onClick={() => { setPreview(true); mentions.dismiss(); command.dismiss(); }}>Preview</button>
      </header>
      {preview && <MarkdownBody body={body || 'Nothing to preview.'} repository={repository} />}
      <div className="pr-composer__input" hidden={preview}>
          <textarea ref={textareaRef} value={body} placeholder="Add a comment" rows={7}
            disabled={posting || closing}
            {...command.inputProps}
            {...(mentions.open ? mentions.inputProps : {})}
            onBlur={() => { mentions.dismiss(); command.dismiss(); }}
            onChange={event => {
              command.change(event);
              setError(null);
              mentions.change(event.target);
            }}
            onKeyDown={event => {
              // Ahead of the mention list, whose plain Enter would otherwise take Cmd+Enter as a pick.
              if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
                event.preventDefault();
                post();
                return;
              }
              if (command.keyDown(event)) return;
              mentions.keyDown(event);
            }} />
          {command.suggestion}
          {mentions.list}
        </div>
      {error && <span className="pr-composer__error" role="alert">{error}</span>}
      <footer>
        {betterWords && <BetterWordsButton key={draftRevision} text={body} textareaRef={textareaRef}
          repository={repository} workspaceId={workspaceId}
          disabled={preview || posting || closing || command.loading}
          onError={failure => setError(failure === null ? null : errorMessage(failure))} />}
        {onClose && <button className="pr-composer__close" type="button" disabled={posting || closing || command.loading}
          onClick={() => { void close(); }}>{closing ? 'Closing…' : body.trim() ? 'Close with comment' : closeLabel}</button>}
        <button type="button" disabled={posting || closing || command.loading || !body.trim()}
          onClick={post}>
          {posting ? 'Posting…' : 'Comment'}
        </button>
      </footer>
    </section>
  );
}

export function appendQuotedReply(draft: string, body: string) {
  const normalized = body.replaceAll('\r\n', '\n').replace(/^\n+|\n+$/g, '');
  const quote = normalized.split('\n').map(line => `> ${line}`).join('\n');
  const existing = draft.trimEnd();
  return `${existing}${existing ? '\n\n' : ''}${quote}\n\n`;
}

/**
 * Keeps the composer's unsent text in Recent, tagged "Never posted" in Words
 * mode. Posting clears it, because the reply is no longer the one that got away.
 */
function saveDraft(repo: string, number: number, body: string) {
  return window.bridge.saveRecentDraft({ type: 'PULL_REQUEST', repo, number, body })
    .catch(() => {
      // Losing a draft record is never worth interrupting the composer.
    });
}

export async function submitPullRequestComment(
  bridge: Pick<Bridge, 'commentPr'>,
  repo: string,
  number: number,
  body: string,
  onDetailChange: (detail: PullRequestDetailDto) => void,
) {
  const detail = await bridge.commentPr(repo, number, body);
  onDetailChange(detail);
  recordAttentionAction(pullRequestAttentionKey(detail), 'comment');
}

export function isQueued(activity: Activity[]) {
  return activity.reduce((queued, item) => item.eventType === 'added_to_merge_queue'
    ? true
    : item.eventType === 'removed_from_merge_queue'
      ? false
      : queued, false);
}

export function isAutoMergeEnabled(activity: Activity[]) {
  return activity.reduce((enabled, item) => {
    if (['auto_merge_enabled', 'auto_rebase_enabled', 'auto_squash_enabled'].includes(item.eventType)) return true;
    if (['auto_merge_disabled', 'added_to_merge_queue', 'removed_from_merge_queue', 'closed', 'merged', 'convert_to_draft'].includes(item.eventType)) return false;
    return enabled;
  }, false);
}

function reviewBadge(state?: string | null) {
  if (state === 'APPROVED') return { text: 'Approved', className: 'is-approved' };
  if (state === 'CHANGES_REQUESTED') return { text: 'Changes requested', className: 'is-changes' };
  if (state === 'DISMISSED') return { text: 'Dismissed', className: 'is-dismissed' };
  return null;
}

function threadLocation(path: string | null, line: number | null, side: string | null, startLine: number | null) {
  const prefix = side === 'RIGHT' ? 'R' : 'L';
  const range = startLine !== null && startLine !== line ? `${prefix}${startLine}–` : '';
  return `${path ?? 'Changed file'}${line ? `:${range}${prefix}${line}` : ''}`;
}

function CopyIcon() {
  return <Copy size={16} strokeWidth={1.5} aria-hidden="true" />;
}

function ReviewIcon() {
  return <Eye size={15} strokeWidth={1.4} aria-hidden="true" />;
}
