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
import type { ActivityItemDto } from '../../types';
import type { LocalPR } from '../../types/localPr';
import type { TimelineEntry } from '../timelineGrouping';
import Avatar from '../../Avatar';
import { ReviewThreadCard } from '../ReviewThreadCard';
import type { ReactionContent } from '../utils';
import { MarkdownProse } from '../../threads/MarkdownProse';
import {
  ClockIcon, CloseIcon, CommitIcon, EyeIcon, MergeBranchIcon,
} from '../../ui/TaskBrainDesignIcons';
import { actorRole, agoLabel, displayName } from './prViewMeta';
import { RailReviewThread } from './RailReviewThread';
import { TimelineBubble } from './TimelineBubble';
import { TimelinePersonEvent } from './TimelinePersonEvent';

function toMs(iso: string | null): number {
  return iso !== null ? new Date(iso).getTime() : 0;
}

function who(actor: string): ReactNode {
  return (
    <>
      <Avatar login={displayName(actor)} size={16} className="pr-tl-icon-row-avatar" />
      <span className="who">{displayName(actor)}</span>
    </>
  );
}

function sha(value: string | null): ReactNode {
  return value !== null ? <span className="sha">{value.slice(0, 7)}</span> : null;
}

/** "a", "a and b", "a, b and c" — github.com's own join style for a
 *  collapsed label burst (no Oxford comma). */
function joinWithAnd(nodes: ReactNode[]): ReactNode {
  return nodes.map((node, i) => (
    <Fragment key={i}>
      {i > 0 && (i === nodes.length - 1 ? ' and ' : ', ')}
      {node}
    </Fragment>
  ));
}

function labelPill(label: { name: string; color: string | null }, key: number): ReactNode {
  return (
    <span key={key} className="lbl" style={label.color ? { borderColor: `#${label.color}` } : undefined}>
      {label.name}
    </span>
  );
}

/** Body copy for a single GitHub-native activity item — the structural
 *  event types this feed adds beyond what {@link TimelineIconEvent} already
 *  covers for local events. Unhandled/legacy types fall through to a bare
 *  actor name, matching {@link TimelineIconEvent}'s own default case. */
function activityBody(item: ActivityItemDto): ReactNode {
  switch (item.eventType) {
    case 'labeled':
      return <>{who(item.actor)} added the <span className="lbl" style={item.labelColor ? { borderColor: `#${item.labelColor}` } : undefined}>{item.labelName}</span> label</>;
    case 'unlabeled':
      return <>{who(item.actor)} removed the <span className="lbl">{item.labelName}</span> label</>;
    case 'assigned':
      return <>{who(item.actor)} assigned {item.assigneeLogin}</>;
    case 'unassigned':
      return <>{who(item.actor)} unassigned {item.assigneeLogin}</>;
    case 'milestoned':
      return <>{who(item.actor)} added this to the {item.milestoneTitle} milestone</>;
    case 'demilestoned':
      return <>{who(item.actor)} removed this from the {item.milestoneTitle} milestone</>;
    case 'cross-referenced':
      return <>{who(item.actor)} referenced this {item.crossRefIsPullRequest ? 'pull request' : 'issue'}</>;
    case 'review_requested':
      return <>{who(item.actor)} requested a review from {item.requestedReviewer}</>;
    case 'head_ref_force_pushed':
      return <>{who(item.actor)} force-pushed the branch{item.afterSha !== null && <> to {sha(item.afterSha)}</>}</>;
    case 'committed':
      return <>{who(item.actor)} added a commit{item.afterSha !== null && <> {sha(item.afterSha)}</>}</>;
    case 'merged':
      return <>{who(item.actor)} merged this pull request</>;
    case 'closed':
      return <>{who(item.actor)} closed this pull request</>;
    case 'reopened':
      return <>{who(item.actor)} reopened this pull request</>;
    case 'added_to_merge_queue':
      return <>{who(item.actor)} added this pull request to the merge queue</>;
    case 'removed_from_merge_queue':
      return <>{who(item.actor)} removed this pull request from the merge queue</>;
    default:
      return who(item.actor);
  }
}

function groupBody(entry: Extract<TimelineEntry, { kind: 'event-group' }>): ReactNode {
  if (entry.eventType === 'review_requested' && entry.reviewers !== undefined) {
    return <>{who(entry.actor)} requested review from {entry.reviewers.join(', ')}</>;
  }
  if ((entry.eventType === 'labeled' || entry.eventType === 'unlabeled') && entry.labels !== undefined) {
    const verb = entry.eventType === 'labeled' ? 'added' : 'removed';
    return (
      <>
        {who(entry.actor)} {verb} the {joinWithAnd(entry.labels.map((l, idx) => labelPill(l, idx)))} labels
      </>
    );
  }
  if (entry.eventType === 'head_ref_force_pushed') {
    return (
      <>
        {who(entry.actor)} force-pushed {entry.count} times
        {entry.lastItem.afterSha !== null && <> · latest {sha(entry.lastItem.afterSha)}</>}
      </>
    );
  }
  // committed
  return (
    <>
      {who(entry.actor)} added {entry.count} commits
      {entry.lastItem.afterSha !== null && <> · latest {sha(entry.lastItem.afterSha)}</>}
    </>
  );
}

function iconForEvent(eventType: string): ReactNode {
  switch (eventType) {
    case 'committed':
    case 'head_ref_force_pushed':
      return <CommitIcon />;
    case 'review_requested':
      return <EyeIcon />;
    case 'merged':
    case 'added_to_merge_queue':
    case 'removed_from_merge_queue':
      return <MergeBranchIcon />;
    case 'closed':
      return <CloseIcon />;
    default:
      return <ClockIcon />;
  }
}

function toneForEvent(eventType: string): string {
  switch (eventType) {
    case 'merged':
      return 'green';
    case 'closed':
      return 'fail';
    case 'committed':
    case 'head_ref_force_pushed':
      return 'commit';
    default:
      return '';
  }
}

function IconRow({ children, time, eventType }: { children: ReactNode; time: number; eventType: string }) {
  return (
    <div className="pr-tl-icon-row">
      <span className={`tic ${toneForEvent(eventType)}`}>{iconForEvent(eventType)}</span>
      <div className="tb">{children}</div>
      <span className="ts">{agoLabel(time)}</span>
    </div>
  );
}

/** The referenced issue/PR's title, on its own line below the "referenced
 *  this X" row — matches github.com's own layout, which never inlines the
 *  title into the same line as the actor/verb text. */
function CrossRefCard({ item }: { item: ActivityItemDto }) {
  if (item.crossRefUrl === null) return null;
  return (
    <div className="pr-tl-crossref">
      <a href={item.crossRefUrl} target="_blank" rel="noreferrer">#{item.crossRefNumber} {item.crossRefTitle}</a>
    </div>
  );
}

/** Mutation callbacks + identity the inline {@link ReviewThreadCard} needs.
 *  `onSetResolved`/`onReact` are omitted deliberately for now — resolving a
 *  thread needs a legacy numeric PR id this unified-PR context doesn't have
 *  a reliable source for; reply/edit/delete only need (repo, number). */
export type GitHubThreadActions = {
  repo: string;
  prAuthor: string | null;
  prHtmlUrl: string;
  currentUserLogin?: string | null;
  onReply: (rootGithubId: number, body: string) => Promise<void>;
  onReact?: (commentGithubId: number, content: ReactionContent) => Promise<void>;
  onEditMessage?: (commentGithubId: number, newBody: string) => Promise<void>;
  onDeleteMessage?: (commentGithubId: number) => void | Promise<void>;
  canDeleteMessage?: (author: string | null, githubId: number) => boolean;
};

/** Renders one grouped GitHub-native timeline entry (labels, review
 *  requests, force-pushes, cross-references, comments, reviews + their
 *  inline diff threads) — the counterpart to {@link TimelineIconEvent} for
 *  data sourced live from GitHub rather than the local sync tables. */
export function GitHubTimelineRow({
  entry, pr, threadActions,
}: {
  entry: TimelineEntry;
  pr: LocalPR;
  threadActions: GitHubThreadActions;
}) {
  if (entry.kind === 'date-divider') {
    return null;
  }
  if (entry.kind === 'event-group') {
    return <IconRow time={toMs(entry.lastItem.timestamp)} eventType={entry.eventType}>{groupBody(entry)}</IconRow>;
  }
  if (entry.kind === 'thread') {
    return (
      <RailReviewThread>
        <ReviewThreadCard
          thread={entry.thread}
          prAuthor={threadActions.prAuthor}
          prHtmlUrl={threadActions.prHtmlUrl}
          currentUserLogin={threadActions.currentUserLogin}
          onReply={body => threadActions.onReply(entry.thread.rootGithubId, body)}
          onReact={threadActions.onReact}
          onEditMessage={threadActions.onEditMessage}
          onDeleteMessage={threadActions.onDeleteMessage}
          canDeleteMessage={threadActions.canDeleteMessage}
        />
      </RailReviewThread>
    );
  }
  const { item, attachedThreads } = entry;
  if (item.eventType === 'commented') {
    const role = actorRole(item.actor, pr);
    return (
      <TimelineBubble actor={item.actor} role={role} action="commented" time={toMs(item.timestamp)}>
        {item.body !== null && item.body.trim().length > 0
          ? <MarkdownProse text={item.body} />
          : null}
      </TimelineBubble>
    );
  }
  if (item.eventType === 'reviewed') {
    return (
      <Fragment>
        <TimelinePersonEvent actor={item.actor} verdict={item.state} time={toMs(item.timestamp)} />
        {item.body !== null && item.body.trim().length > 0 && (
          <TimelineBubble actor={item.actor} role={actorRole(item.actor, pr)} action="left a review comment" time={toMs(item.timestamp)}>
            <MarkdownProse text={item.body} />
          </TimelineBubble>
        )}
        {attachedThreads?.map(thread => (
          <RailReviewThread key={thread.rootGithubId}>
            <ReviewThreadCard
              thread={thread}
              prAuthor={threadActions.prAuthor}
              prHtmlUrl={threadActions.prHtmlUrl}
              currentUserLogin={threadActions.currentUserLogin}
              onReply={body => threadActions.onReply(thread.rootGithubId, body)}
              onReact={threadActions.onReact}
              onEditMessage={threadActions.onEditMessage}
              onDeleteMessage={threadActions.onDeleteMessage}
              canDeleteMessage={threadActions.canDeleteMessage}
            />
          </RailReviewThread>
        ))}
      </Fragment>
    );
  }
  if (item.eventType === 'cross-referenced') {
    return (
      <Fragment>
        <IconRow time={toMs(item.timestamp)} eventType={item.eventType}>{activityBody(item)}</IconRow>
        <CrossRefCard item={item} />
      </Fragment>
    );
  }
  return <IconRow time={toMs(item.timestamp)} eventType={item.eventType}>{activityBody(item)}</IconRow>;
}
