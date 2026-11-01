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
import type { PullReactionDto, PullReactionTarget, PullRequestDetailDto } from '../types';
import { EMPTY_TIMELINE_PAYLOAD } from './model';

export type PullDetailUpdater = (
  repo: string,
  number: number,
  update: (detail: PullRequestDetailDto) => PullRequestDetailDto,
) => void;

export function updatePullReactionDetail(
  detail: PullRequestDetailDto,
  target: PullReactionTarget,
  commentId: number | null,
  reactions: PullReactionDto[],
  onlyIf?: PullReactionDto[],
) {
  if (target === 'pull_request') {
    let found = false;
    const recentActivity = detail.recentActivity.map(activity => {
      if (activity.eventType !== 'description_reactions') return activity;
      found = true;
      if (onlyIf !== undefined && activity.payload.reactions !== onlyIf) return activity;
      return { ...activity, payload: { ...activity.payload, reactions } };
    });
    if (!found && onlyIf === undefined) {
      recentActivity.push({
        actor: '',
        eventType: 'description_reactions',
        timestamp: null,
        body: null,
        remoteId: null,
        payload: { ...EMPTY_TIMELINE_PAYLOAD, reactions },
      });
    }
    return { ...detail, recentActivity };
  }
  if (commentId === null) return detail;
  const recentActivity = detail.recentActivity.map(activity => {
    if (target === 'issue_comment') {
      if (activity.eventType !== 'commented' || activity.remoteId !== commentId
          || (onlyIf !== undefined && activity.payload.reactions !== onlyIf)) return activity;
      return { ...activity, payload: { ...activity.payload, reactions } };
    }
    const comments = activity.payload.comments.map(comment => {
      if (comment.remoteId !== commentId || (onlyIf !== undefined && comment.reactions !== onlyIf)) return comment;
      return { ...comment, reactions };
    });
    return comments.some((comment, index) => comment !== activity.payload.comments[index])
      ? { ...activity, payload: { ...activity.payload, comments } }
      : activity;
  });
  return { ...detail, recentActivity };
}

function reviewThreadKey(activity: PullRequestDetailDto['recentActivity'][number]) {
  const rootCommentId = activity.payload.comments.find(comment => comment.inReplyToId === null)?.remoteId
    ?? activity.payload.comments[0]?.remoteId;
  return activity.remoteId !== null ? `thread:${activity.remoteId}` : `comment:${rootCommentId ?? ''}`;
}

/** Applies a write response without discarding independent optimistic fields. */
export function mergePullRequestWriteDetail(
  current: PullRequestDetailDto,
  response: PullRequestDetailDto,
): PullRequestDetailDto {
  const issueReactions = new Map<number, PullReactionDto[]>();
  const reviewReactions = new Map<number, PullReactionDto[]>();
  const threadResolution = new Map<string, boolean>();
  let descriptionReactions: PullReactionDto[] | undefined;
  current.recentActivity.forEach(activity => {
    if (activity.eventType === 'description_reactions') descriptionReactions = activity.payload.reactions;
    if (activity.eventType === 'commented' && activity.remoteId !== null) {
      issueReactions.set(activity.remoteId, activity.payload.reactions);
    }
    if (activity.eventType === 'review_thread') {
      threadResolution.set(reviewThreadKey(activity), activity.payload.resolved);
    }
    activity.payload.comments.forEach(comment => {
      if (comment.remoteId !== null) reviewReactions.set(comment.remoteId, comment.reactions);
    });
  });
  let merged = {
    ...response,
    title: current.title,
    body: current.body,
    labels: current.labels,
    assignees: current.assignees,
    reviewers: current.reviewers,
    recentActivity: response.recentActivity.map(activity => ({
      ...activity,
      payload: {
        ...activity.payload,
        resolved: activity.eventType === 'review_thread'
          ? threadResolution.get(reviewThreadKey(activity)) ?? activity.payload.resolved
          : activity.payload.resolved,
        reactions: activity.eventType === 'commented' && activity.remoteId !== null
          ? issueReactions.get(activity.remoteId) ?? activity.payload.reactions
          : activity.payload.reactions,
        comments: activity.payload.comments.map(comment => ({
          ...comment,
          reactions: comment.remoteId === null
            ? comment.reactions
            : reviewReactions.get(comment.remoteId) ?? comment.reactions,
        })),
      },
    })),
  };
  if (descriptionReactions !== undefined) {
    merged = updatePullReactionDetail(merged, 'pull_request', null, descriptionReactions);
  }
  return merged;
}
