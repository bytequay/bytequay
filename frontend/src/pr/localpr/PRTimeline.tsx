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
import { Fragment, type ReactNode } from 'react';
import type { ActivityItemDto, ChangedFileDto, PullRequestDetailDto, ReviewThreadDto } from '../../types';
import type { LocalPR, LocalPRComment, LocalPRCommit, LocalPRTimelineEvent } from '../../types/localPr';
import { MarkdownProse } from '../../threads/MarkdownProse';
import { groupTimelineEntries } from '../timelineGrouping';
import { buildRawTimelineEntries } from './githubActivityRows';
import { GitHubTimelineRow, type GitHubThreadActions } from './GitHubTimelineRow';
import { actorRole, agoLabel, displayName } from './prViewMeta';
import { BrainReviewCard } from './BrainReviewCard';
import { TimelineBubble } from './TimelineBubble';
import { TimelinePersonEvent } from './TimelinePersonEvent';
import { TimelineIconEvent } from './TimelineIconEvent';
import { TimelinePlanFinalized } from './TimelinePlanFinalized';
import { ReviewThreadCard } from './ReviewThreadCard';
import { PRCommentThreadBubble } from './PRCommentThreadBubble';
import { RailReviewThread } from './RailReviewThread';
import { buildAgentReviewTimelineEntries } from '../../review/AgentReviewTimeline';
import type { AgentReviewData } from '../../review/agentReviewTypes';

function str(payload: Record<string, unknown> | null, key: string): string | null {
  const v = payload?.[key];
  return typeof v === 'string' ? v : null;
}

function num(payload: Record<string, unknown> | null, key: string): number | null {
  const v = payload?.[key];
  return typeof v === 'number' ? v : null;
}

function stringList(payload: Record<string, unknown> | null, key: string): string[] {
  const v = payload?.[key];
  return Array.isArray(v) ? v.filter((item): item is string => typeof item === 'string') : [];
}

function isBrainReviewFinished(event: LocalPRTimelineEvent): boolean {
  if (event.eventType !== 'review' || event.actor !== 'brain' || str(event.payload, 'scope') === 'plan') return false;
  const activity = str(event.payload, 'reviewEvent');
  return activity === 'finished' || (activity === null && str(event.payload, 'verdict') !== null);
}

function isImportantLocalEvent(event: LocalPRTimelineEvent): boolean {
  const reviewActivity = str(event.payload, 'reviewEvent');
  return event.eventType === 'plan-finalized'
    || event.eventType === 'pull-request-progress'
    || event.eventType === 'pull-request-created'
    || (event.eventType === 'review'
      && (reviewActivity === 'started' || reviewActivity === 'finished' || reviewActivity === 'addressing-started')
      && (str(event.payload, 'scope') === 'dev' || str(event.payload, 'scope') === 'round'))
    || (event.eventType === 'review' && event.actor === 'brain' && reviewActivity === null)
    || (event.eventType === 'status' && str(event.payload, 'gate') === 'push');
}

function brainReviewIntent(scope: string | null, iteration: number | null): string | null {
  if (iteration === null || (scope !== 'dev' && scope !== 'round')) return null;
  const gate = scope === 'dev' ? 'Local Review' : 'the review-round approval gate';
  if (iteration > 1) {
    return `Pass ${iteration} · Verify fixes from pass ${iteration - 1} and check for regressions before ${gate}`;
  }
  return scope === 'dev'
    ? 'Pass 1 · Audit the completed implementation for bugs before Local Review'
    : 'Pass 1 · Audit the addressed reviewer changes before the review-round approval gate';
}

function assignBrainComments(
  events: LocalPRTimelineEvent[], comments: LocalPRComment[],
): Map<string, LocalPRComment[]> {
  const result = new Map<string, LocalPRComment[]>();
  const assignedRoots = new Set<string>();
  const roots = comments.filter(comment => comment.author === 'brain' && comment.parentCommentId === null);
  const finished = events.filter(isBrainReviewFinished).sort((a, b) => a.createdAt - b.createdAt);
  for (const event of finished) {
    const recordedIds = new Set(stringList(event.payload, 'commentIds'));
    const matchedRoots = recordedIds.size > 0
      ? roots.filter(comment => recordedIds.has(comment.id))
      : roots.filter(comment => !assignedRoots.has(comment.id) && comment.createdAt <= event.createdAt);
    for (const root of matchedRoots) assignedRoots.add(root.id);
    const rootIds = new Set(matchedRoots.map(comment => comment.id));
    const grouped = comments.filter(comment => rootIds.has(comment.id)
      || (comment.parentCommentId !== null && rootIds.has(comment.parentCommentId)));
    result.set(event.id, grouped);
  }
  return result;
}

type Row = { key: string; time: number; render: ReactNode };

function plural(count: number, singular: string): string {
  return `${count} ${singular}${count === 1 ? '' : 's'}`;
}

function actorFromLogin(login: string | null): string | null {
  if (login === null || login.trim().length === 0) return null;
  return login.startsWith('@') ? login : `@${login}`;
}

function descriptionActor(pr: LocalPR, githubFeedActive: boolean, currentUserLogin?: string | null): string {
  if (githubFeedActive) {
    if (pr.origin === 'task') {
      return actorFromLogin(currentUserLogin ?? null) ?? pr.author ?? 'you';
    }
    return pr.author ?? actorFromLogin(currentUserLogin ?? null) ?? 'you';
  }
  return pr.origin === 'external' && pr.author !== null ? pr.author : 'claude-code';
}

function componentOf(path: string): string {
  const parts = path.split('/').filter(Boolean);
  if (parts.length === 0) return path;
  if (parts[0] === 'frontend' && parts[1] === 'src' && parts[2] !== undefined) return `frontend/${parts[2]}`;
  if (parts[0] === 'backend') {
    const app = parts.indexOf('app');
    if (app >= 0 && parts[app + 1] !== undefined) return `backend/${parts[app + 1]}`;
    return 'backend';
  }
  if (parts[0] === 'docs' && parts[1] !== undefined) return `docs/${parts[1]}`;
  return parts.length > 1 ? `${parts[0]}/${parts[1]}` : parts[0];
}

function topComponents(files: string[]): string[] {
  const counts = new Map<string, number>();
  for (const file of files) {
    counts.set(componentOf(file), (counts.get(componentOf(file)) ?? 0) + 1);
  }
  return [...counts.entries()]
    .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
    .slice(0, 4)
    .map(([name]) => name);
}

function statusSummary(files: ChangedFileDto[]): string | null {
  if (files.length === 0) return null;
  const counts = new Map<string, number>();
  for (const file of files) counts.set(file.status, (counts.get(file.status) ?? 0) + 1);
  return [...counts.entries()]
    .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
    .map(([status, count]) => plural(count, status === 'removed' ? 'removed file' : `${status} file`))
    .join(' · ');
}

function LocalActivityFold({
  detail, commits, events, comments,
}: {
  detail?: PullRequestDetailDto | null;
  commits: LocalPRCommit[];
  events: LocalPRTimelineEvent[];
  comments: LocalPRComment[];
}) {
  const changedFiles = detail?.changedFiles ?? detail?.files.length ?? 0;
  const additions = detail?.additions ?? commits.reduce((sum, commit) => sum + commit.additions, 0);
  const deletions = detail?.deletions ?? commits.reduce((sum, commit) => sum + commit.deletions, 0);
  const files = detail?.files ?? [];
  const fallbackFiles = comments
    .map(comment => comment.filePath)
    .filter((path): path is string => path !== null);
  const componentNames = topComponents(files.length > 0 ? files.map(file => file.filename) : fallbackFiles);
  const localReviewCount = comments.filter(comment => comment.origin === 'local').length;
  const brainReviewCount = events.filter(isBrainReviewFinished).length;
  const localCheckCount = events.filter(event => event.eventType === 'ci' && str(event.payload, 'kind') === 'local').length;
  const fileStatus = statusSummary(files);

  return (
    <details className="pr-local-fold">
      <summary>
        <span className="tic">◆</span>
        <span className="pr-local-fold__body">
          <span className="pr-local-fold__title">Local work before push</span>
          <span className="pr-local-fold__meta">
            {plural(changedFiles, 'changed file')} · {plural(commits.length, 'commit')} · +{additions} -{deletions}
          </span>
          {componentNames.length > 0 && (
            <span className="pr-local-fold__components">
              {componentNames.map(name => <span key={name}>{name}</span>)}
            </span>
          )}
        </span>
      </summary>
      <div className="pr-local-fold__details">
        {fileStatus !== null && <div>{fileStatus}</div>}
        {(localReviewCount > 0 || brainReviewCount > 0 || localCheckCount > 0) && (
          <div>
            {brainReviewCount > 0 && <span>{plural(brainReviewCount, 'brain review')}</span>}
            {localReviewCount > 0 && <span>{plural(localReviewCount, 'local comment')}</span>}
            {localCheckCount > 0 && <span>{plural(localCheckCount, 'local check')}</span>}
          </div>
        )}
        {files.length > 0 && (
          <div className="pr-local-fold__files">
            {files.slice(0, 5).map(file => (
              <span key={file.filename}>{file.filename}</span>
            ))}
            {files.length > 5 && <span>+{files.length - 5} more</span>}
          </div>
        )}
      </div>
    </details>
  );
}

/** One conversation per root comment. Two findings can share an anchor and
 * still remain separate review threads, just as they do on GitHub. */
function groupLocalCommentThreads(comments: LocalPRComment[]): LocalPRComment[][] {
  const byId = new Map(comments.map(comment => [comment.id, comment]));
  const groups = new Map<string, LocalPRComment[]>();
  for (const comment of comments) {
    const rootId = comment.parentCommentId !== null && byId.has(comment.parentCommentId)
      ? comment.parentCommentId
      : comment.id;
    const group = groups.get(rootId);
    if (group === undefined) groups.set(rootId, [comment]);
    else group.push(comment);
  }
  return [...groups.values()]
    .map(group => group.sort((a, b) => a.createdAt - b.createdAt))
    .sort((a, b) => (a[0]?.createdAt ?? 0) - (b[0]?.createdAt ?? 0));
}

/**
 * The unified PR timeline (U13c/U15): a rail of speech-bubble comments,
 * person-events for reviews, one-line icon rows for compact events, and
 * review-thread cards for file-line comments — all in one time-ordered feed.
 * The description renders as the first bubble.
 */
export function PRTimeline({
  pr, events, comments, onReviewChanges, onResolveThread, onUnresolveThread, onDismissThread, onReplyThread, onReplyLineThread, onOpenStage,
  commits = [], activity, reviewThreads, remoteDetail, threadActions, currentUserLogin,
  reviewData, onOpenReviewRound, onAnswerFinding, onReviewRoundAction,
  onReplyFindingThread, onReplyFindingLineThread,
  onSetFindingResolved, onToggleFindingPromotion, canPromoteFindings = false, onOpenCommentLocation,
}: {
  pr: LocalPR;
  events: LocalPRTimelineEvent[];
  comments: LocalPRComment[];
  commits?: LocalPRCommit[];
  onReviewChanges?: () => void;
  onResolveThread?: (rootCommentId: string) => void;
  onUnresolveThread?: (rootCommentId: string) => void;
  onOpenCommentLocation?: (filePath: string, lineNumber: number | null, side: 'LEFT' | 'RIGHT') => void;
  onDismissThread?: (rootCommentId: string) => void;
  onReplyThread?: (rootCommentId: string, body: string) => void | Promise<void>;
  onReplyLineThread?: (
    rootCommentId: string, filePath: string, side: 'LEFT' | 'RIGHT', lineNumber: number,
    startLine: number | undefined, startSide: 'LEFT' | 'RIGHT' | undefined, body: string,
  ) => void | Promise<void>;
  /** Agent finding conversations remain local and interactive even after
   *  the remote PR reaches a terminal state. Ordinary drafts still use the
   *  capability-gated callbacks above. */
  onReplyFindingThread?: (rootCommentId: string, body: string) => void | Promise<void>;
  onReplyFindingLineThread?: (
    rootCommentId: string, filePath: string, side: 'LEFT' | 'RIGHT', lineNumber: number,
    startLine: number | undefined, startSide: 'LEFT' | 'RIGHT' | undefined, body: string,
  ) => void | Promise<void>;
  /** Jumps to a stage's detail view — wired to the "View the plan" link card
   *  on a `plan-finalized` row so it can jump back to the Plan node. */
  onOpenStage?: (stageId: string) => void;
  /** GitHub's own conversation feed (labels, review-requests, force-pushes,
   *  cross-references, comments/reviews + inline diff threads) — once the
   *  PR has a `remotePrNumber`, this becomes the source for everything it
   *  covers and the local `events`/`comments` below narrow to local-only
   *  rows (local checks, unpublished drafts) so nothing double-renders.
   *  Omitted (or `pr.remotePrNumber === null`) keeps today's all-local
   *  rendering for the pre-push phase. */
  activity?: ActivityItemDto[];
  reviewThreads?: ReviewThreadDto[];
  remoteDetail?: PullRequestDetailDto | null;
  threadActions?: GitHubThreadActions;
  currentUserLogin?: string | null;
  reviewData?: AgentReviewData;
  onOpenReviewRound?: (roundId: string) => void;
  onAnswerFinding?: (findingId: string, text: string) => void | Promise<unknown>;
  onReviewRoundAction?: (roundId: string) => void;
  onSetFindingResolved?: (findingId: string, resolved: boolean) => void | Promise<unknown>;
  onToggleFindingPromotion?: (findingId: string) => void | Promise<unknown>;
  canPromoteFindings?: boolean;
}) {
  const rows: Row[] = [];
  const githubFeedActive = pr.remotePrNumber !== null && threadActions !== undefined;
  const foldTaskLocalActivity = githubFeedActive && pr.origin === 'task';
  const brainCommentsByReview = assignBrainComments(events, comments);
  const commentsInBrainReviews = new Set(
    [...brainCommentsByReview.values()].flatMap(group => group.map(comment => comment.id)),
  );

  const descriptionActorName = descriptionActor(pr, githubFeedActive, currentUserLogin);
  rows.push({
    key: 'description',
    time: -Infinity, // always first, regardless of createdAt
    render: (
      <TimelineBubble
        key="description"
        actor={descriptionActorName}
        role={githubFeedActive ? 'author' : actorRole(descriptionActorName, pr)}
        action="drafted the description"
        time={pr.createdAt}
      >
        {pr.description.trim().length > 0
          ? <MarkdownProse text={pr.description} />
          : <span className="drafting-hint">No description yet.</span>}
      </TimelineBubble>
    ),
  });

  if (foldTaskLocalActivity) {
    rows.push({
      key: 'local-work-fold',
      time: Number.MIN_SAFE_INTEGER + 1,
      render: (
        <LocalActivityFold
          key="local-work-fold"
          detail={remoteDetail}
          commits={commits}
          events={events}
          comments={comments}
        />
      ),
    });
  }

  for (const event of events) {
    const importantLocalEvent = isImportantLocalEvent(event);
    if (foldTaskLocalActivity && !importantLocalEvent) continue;
    if (event.eventType === 'comment') continue; // rendered from `comments` instead
    if (reviewData !== undefined && !importantLocalEvent && event.eventType === 'review'
      && typeof event.payload?.reviewEvent === 'string') continue;
    // Once GitHub's own feed is active it's the source for commits/reviews/
    // status changes too (it already includes "committed"/"reviewed"/
    // merged-closed-reopened) — only LOCAL checks (a local `mvn test` run,
    // never synced remotely — see PRServiceImpl.recordSyncedCheck) have no
    // GitHub-native equivalent. A remote-kind `ci` event here is a stale row
    // written before that method stopped emitting one per synced check —
    // still worth filtering defensively rather than trusting the write side
    // alone, since a PR's rows can predate this fix.
    if (githubFeedActive && !importantLocalEvent
      && (event.eventType !== 'ci' || str(event.payload, 'kind') !== 'local')) continue;
    if (event.eventType === 'review') {
      const verdict = str(event.payload, 'verdict');
      const scope = str(event.payload, 'scope');
      const body = str(event.payload, 'body');
      const reviewActivity = str(event.payload, 'reviewEvent');
      if (reviewActivity === 'started' || reviewActivity === 'addressing-started') {
        const started = reviewActivity === 'started';
        const iteration = num(event.payload, 'iteration');
        const intent = started
          ? brainReviewIntent(scope, iteration)
          : iteration === null ? null : `Fix pass ${iteration} · Resolve findings before verification pass ${iteration + 1}`;
        rows.push({
          key: event.id,
          time: event.createdAt,
          render: (
            <div className="pr-tl-icon-row brain-review-activity" key={event.id}>
              <span className="tic">{started ? '◉' : '↻'}</span>
              <div className="tb">
                <span className="who">{displayName(event.actor)}</span>{' '}
                {started ? 'started an adversarial code review' : 'started addressing the adversarial review comments'}
                {iteration !== null && ` · pass ${iteration}`}
                <span className="lock-tag">🔒 local</span>
                {intent !== null && <span className="brain-review-intent">{intent}</span>}
              </div>
              <span className="ts">{agoLabel(event.createdAt)}</span>
            </div>
          ),
        });
        continue;
      }
      const reviewComments = brainCommentsByReview.get(event.id) ?? [];
      const findingCount = num(event.payload, 'findingCount') ?? reviewComments.filter(c => c.parentCommentId === null).length;
      const hasReviewContent = findingCount > 0 || reviewComments.length > 0 || (body !== null && body.trim().length > 0);
      rows.push({
        key: event.id,
        time: event.createdAt,
        render: (
          <Fragment key={event.id}>
            <TimelinePersonEvent
              actor={event.actor}
              verdict={verdict}
              scope={scope}
              intent={event.actor === 'brain' ? brainReviewIntent(scope, num(event.payload, 'iteration')) : null}
              time={event.createdAt}
              onViewChanges={onReviewChanges}
              hasReviewContent={event.actor !== 'brain' || hasReviewContent}
            />
            {event.actor === 'brain' && scope !== 'plan' && hasReviewContent ? (
              <BrainReviewCard
                pr={pr}
                verdict={verdict}
                comments={reviewComments}
                findingCount={findingCount}
                body={body}
              />
            ) : event.actor !== 'brain' && body !== null && body.trim().length > 0 && (
              <TimelineBubble actor={event.actor} role={actorRole(event.actor, pr)} action="left a review comment" time={event.createdAt}>
                {body}
              </TimelineBubble>
            )}
          </Fragment>
        ),
      });
      continue;
    }
    if (event.eventType === 'plan-finalized') {
      const planActor = event.actor === 'you'
        ? (currentUserLogin ?? pr.author ?? 'You').replace(/^@/, '')
        : event.actor;
      rows.push({
        key: event.id,
        time: event.createdAt,
        render: <TimelinePlanFinalized key={event.id} event={event} actor={planActor} onOpenStage={onOpenStage} />,
      });
      continue;
    }
    rows.push({ key: event.id, time: event.createdAt, render: <TimelineIconEvent key={event.id} event={event} /> });
  }

  // Remote-origin comments now come from the GitHub feed's `commented`
  // activity instead. Task-origin pushed PRs fold their local-only history
  // into the summary above; external PRs still render local draft comments —
  // but only while they're *unpublished* drafts. Once a draft is submitted it
  // becomes a real GitHub review comment and renders live via the feed below;
  // keeping the local copy too would show the same comment twice.
  const localCommentCandidates = (foldTaskLocalActivity
    ? comments.filter(comment => comment.author === 'brain')
    : githubFeedActive
      ? comments.filter(comment => comment.origin === 'local' && comment.publishedAt === null)
      : comments)
    .filter(comment => !commentsInBrainReviews.has(comment.id));
  const candidateIds = new Set(localCommentCandidates.map(comment => comment.id));
  const allCommentIds = new Set(comments.map(comment => comment.id));
  const localComments = localCommentCandidates.filter(comment => comment.parentCommentId === null
    || !allCommentIds.has(comment.parentCommentId)
    || candidateIds.has(comment.parentCommentId));

  for (const thread of groupLocalCommentThreads(localComments)) {
    const root = thread[0];
    if (root === undefined) continue;
    const findingId = root.findingId ?? null;
    // Brain review roots — even PR-level ones without a finding id — are
    // counted as open findings by the review loop, so they need the resolvable
    // ReviewThreadCard (Resolve / Discard), not the reply-only bubble. Only
    // plain PR-level discussion falls through to the bubble.
    if (root.scope === 'pr' && findingId === null && root.author !== 'brain') {
      const resolvedBrainRoot = root.author === 'brain'
        && (root.resolvedAt !== null || root.dismissedAt !== null);
      rows.push({
        key: root.id,
        time: root.createdAt,
        render: (
        <PRCommentThreadBubble
          key={root.id}
          pr={pr}
          comments={thread}
          reviewData={reviewData}
          onReply={resolvedBrainRoot ? undefined : onReplyThread}
        />
        ),
      });
      continue;
    }

    const resolved = root.resolvedAt !== null || root.dismissedAt !== null;
    const finding = findingId === null ? undefined : reviewData?.findings.find(row => row.id === findingId);
    const prReply = findingId === null ? onReplyThread : onReplyFindingThread ?? onReplyThread;
    const lineReply = findingId === null ? onReplyLineThread : onReplyFindingLineThread ?? onReplyLineThread;
    const reply = resolved && root.author === 'brain'
      ? undefined
      : root.scope === 'file-line' && root.filePath !== null && root.lineNumber !== null
      && lineReply !== undefined
      ? (rootCommentId: string, body: string) => lineReply(
          rootCommentId,
          root.filePath!,
          root.side,
          root.lineNumber!,
          root.startLine ?? undefined,
          root.startSide ?? undefined,
          body,
        )
      : prReply;
    rows.push({
      key: `thread-${root.id}`,
      time: root.createdAt,
      render: (
        <RailReviewThread key={`thread-${root.id}`}>
          <ReviewThreadCard
            pr={pr}
            comments={thread}
            resolved={resolved}
            currentUserLogin={currentUserLogin}
            onOpenLocation={onOpenCommentLocation}
            onSetResolved={findingId !== null && onSetFindingResolved !== undefined
              ? next => onSetFindingResolved(findingId, next)
              : (onResolveThread !== undefined || onUnresolveThread !== undefined)
                ? next => { if (next) onResolveThread?.(root.id); else onUnresolveThread?.(root.id); }
                : undefined}
            onDismiss={onDismissThread !== undefined ? () => onDismissThread(root.id) : undefined}
            onReply={reply}
            onAnswerFinding={findingId !== null ? onAnswerFinding : undefined}
            reviewData={reviewData}
            canPromote={findingId !== null && !resolved && canPromoteFindings}
            promoted={finding?.lifecycle_status === 'included'}
            onTogglePromotion={findingId !== null && onToggleFindingPromotion !== undefined
              ? () => onToggleFindingPromotion(findingId)
              : undefined}
          />
        </RailReviewThread>
      ),
    });
  }

  if (githubFeedActive && threadActions !== undefined) {
    const grouped = groupTimelineEntries(buildRawTimelineEntries(activity ?? [], reviewThreads ?? []));
    grouped.forEach((entry, index) => {
      const time = entry.kind === 'event-group' ? new Date(entry.lastItem.timestamp ?? 0).getTime()
        : entry.kind === 'activity' ? new Date(entry.item.timestamp ?? 0).getTime()
        : entry.kind === 'thread' ? new Date(entry.thread.messages[0]?.createdAt ?? 0).getTime()
        : 0;
      rows.push({
        key: `gh-${index}`,
        time,
        render: <GitHubTimelineRow
          key={`gh-${index}`}
          entry={entry}
          pr={pr}
          detail={remoteDetail}
          threadActions={threadActions}
        />,
      });
    });
  }

  if (reviewData !== undefined) {
    rows.push(...buildAgentReviewTimelineEntries(reviewData, {
      onOpenRound: onOpenReviewRound,
      onRoundAction: onReviewRoundAction,
    }));
  }

  rows.sort((a, b) => a.time - b.time);

  return <div className="pr-tl-rail">{rows.map(r => <Fragment key={r.key}>{r.render}</Fragment>)}</div>;
}
