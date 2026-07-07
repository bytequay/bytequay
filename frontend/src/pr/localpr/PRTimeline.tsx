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
import type { ActivityItemDto, ReviewThreadDto } from '../../types';
import type { LocalPR, LocalPRComment, LocalPRTimelineEvent } from '../../types/localPr';
import { MarkdownProse } from '../../threads/MarkdownProse';
import { groupTimelineEntries } from '../timelineGrouping';
import { buildRawTimelineEntries } from './githubActivityRows';
import { GitHubTimelineRow, type GitHubThreadActions } from './GitHubTimelineRow';
import { actorRole } from './prViewMeta';
import { TimelineBubble } from './TimelineBubble';
import { TimelinePersonEvent } from './TimelinePersonEvent';
import { TimelineIconEvent } from './TimelineIconEvent';
import { ReviewThreadCard } from './ReviewThreadCard';

function str(payload: Record<string, unknown> | null, key: string): string | null {
  const v = payload?.[key];
  return typeof v === 'string' ? v : null;
}

type Row = { key: string; time: number; render: ReactNode };

/** Groups file-line comments into one thread per (filePath, lineNumber),
 *  root-first — this milestone doesn't track deeper threading than a single
 *  reply (design #47/#49), so grouping by anchor is equivalent to grouping
 *  by root comment. */
function groupThreads(comments: LocalPRComment[]): Map<string, LocalPRComment[]> {
  const threads = new Map<string, LocalPRComment[]>();
  for (const c of comments) {
    if (c.scope !== 'file-line' || c.filePath === null || c.lineNumber === null) continue;
    const key = `${c.filePath}:${c.lineNumber}`;
    const existing = threads.get(key);
    if (existing === undefined) threads.set(key, [c]);
    else existing.push(c);
  }
  for (const group of threads.values()) group.sort((a, b) => a.createdAt - b.createdAt);
  return threads;
}

/**
 * The unified PR timeline (U13c/U15): a rail of speech-bubble comments,
 * person-events for reviews, one-line icon rows for compact events, and
 * review-thread cards for file-line comments — all in one time-ordered feed.
 * The description renders as the first bubble.
 */
export function PRTimeline({
  pr, events, comments, onReviewChanges, onResolveThread, onDismissThread,
  activity, reviewThreads, threadActions,
}: {
  pr: LocalPR;
  events: LocalPRTimelineEvent[];
  comments: LocalPRComment[];
  onReviewChanges?: () => void;
  onResolveThread?: (rootCommentId: string) => void;
  onDismissThread?: (rootCommentId: string) => void;
  /** GitHub's own conversation feed (labels, review-requests, force-pushes,
   *  cross-references, comments/reviews + inline diff threads) — once the
   *  PR has a `remotePrNumber`, this becomes the source for everything it
   *  covers and the local `events`/`comments` below narrow to local-only
   *  rows (local checks, unpublished drafts) so nothing double-renders.
   *  Omitted (or `pr.remotePrNumber === null`) keeps today's all-local
   *  rendering for the pre-push phase. */
  activity?: ActivityItemDto[];
  reviewThreads?: ReviewThreadDto[];
  threadActions?: GitHubThreadActions;
}) {
  const rows: Row[] = [];
  const githubFeedActive = pr.remotePrNumber !== null && threadActions !== undefined;

  const descriptionActor = pr.origin === 'external' && pr.author !== null ? pr.author : 'claude-code';
  rows.push({
    key: 'description',
    time: -Infinity, // always first, regardless of createdAt
    render: (
      <TimelineBubble
        key="description"
        actor={descriptionActor}
        role={actorRole(descriptionActor, pr)}
        action="drafted the description"
        time={pr.createdAt}
      >
        {pr.description.trim().length > 0
          ? <MarkdownProse text={pr.description} />
          : <span className="drafting-hint">No description yet.</span>}
      </TimelineBubble>
    ),
  });

  for (const event of events) {
    if (event.eventType === 'comment') continue; // rendered from `comments` instead
    // Once GitHub's own feed is active it's the source for commits/reviews/
    // status changes too (it already includes "committed"/"reviewed"/
    // merged-closed-reopened) — only LOCAL checks (a local `mvn test` run,
    // never synced remotely — see PRServiceImpl.recordSyncedCheck) have no
    // GitHub-native equivalent. A remote-kind `ci` event here is a stale row
    // written before that method stopped emitting one per synced check —
    // still worth filtering defensively rather than trusting the write side
    // alone, since a PR's rows can predate this fix.
    if (githubFeedActive && (event.eventType !== 'ci' || str(event.payload, 'kind') !== 'local')) continue;
    if (event.eventType === 'review') {
      const verdict = str(event.payload, 'verdict');
      const body = str(event.payload, 'body');
      rows.push({
        key: event.id,
        time: event.createdAt,
        render: (
          <Fragment key={event.id}>
            <TimelinePersonEvent actor={event.actor} verdict={verdict} time={event.createdAt} onViewChanges={onReviewChanges} />
            {body !== null && body.trim().length > 0 && (
              <TimelineBubble actor={event.actor} role={actorRole(event.actor, pr)} action="left a review comment" time={event.createdAt}>
                {body}
              </TimelineBubble>
            )}
          </Fragment>
        ),
      });
      continue;
    }
    rows.push({ key: event.id, time: event.createdAt, render: <TimelineIconEvent key={event.id} event={event} /> });
  }

  // Remote-origin comments now come from the GitHub feed's `commented`
  // activity instead — only local-origin ones (drafts, PR-scope or
  // file-line) still render from the local table once that feed is active.
  const localComments = githubFeedActive ? comments.filter(c => c.origin === 'local') : comments;

  for (const comment of localComments) {
    if (comment.scope !== 'pr') continue;
    const role = actorRole(comment.author, pr);
    const pending = comment.origin === 'local' && comment.publishedAt === null;
    rows.push({
      key: comment.id,
      time: comment.createdAt,
      render: (
        <TimelineBubble key={comment.id} actor={comment.author} role={role} action="commented" time={comment.createdAt} pending={pending}>
          {comment.body}
        </TimelineBubble>
      ),
    });
  }

  for (const [key, thread] of groupThreads(localComments)) {
    const root = thread[0];
    const [filePath, lineNumberStr] = [root.filePath ?? '', root.lineNumber ?? 0];
    const resolved = root.resolvedAt !== null || root.dismissedAt !== null;
    rows.push({
      key: `thread-${key}`,
      time: root.createdAt,
      render: (
        <ReviewThreadCard
          key={`thread-${key}`}
          pr={pr}
          filePath={filePath}
          lineNumber={typeof lineNumberStr === 'number' ? lineNumberStr : 0}
          comments={thread}
          resolved={resolved}
          onResolve={onResolveThread !== undefined ? () => onResolveThread(root.id) : undefined}
          onDismiss={onDismissThread !== undefined ? () => onDismissThread(root.id) : undefined}
        />
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
        render: <GitHubTimelineRow key={`gh-${index}`} entry={entry} pr={pr} threadActions={threadActions} />,
      });
    });
  }

  rows.sort((a, b) => a.time - b.time);

  return <div className="pr-tl-rail">{rows.map(r => <Fragment key={r.key}>{r.render}</Fragment>)}</div>;
}
