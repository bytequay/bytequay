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
import type { PullRequestDetailDto, ReactionsDto, ReviewMessageDto } from '../types';
import { REACTION_FIELD, type ReactionContent } from './utils';

const ZERO_REACTIONS: ReactionsDto = {
  plusOne: 0, minusOne: 0, laugh: 0, hooray: 0,
  confused: 0, heart: 0, rocket: 0, eyes: 0,
};

/** Returns a new ReactionsDto with one field bumped by {@code delta}.
 *  Counts clamp at zero so a roll-back from a never-incremented chip
 *  doesn't underflow into negatives. Pulled out so both subtree walks
 *  share the exact same arithmetic. */
function bumpField(base: ReactionsDto | null, content: ReactionContent, delta: number): ReactionsDto {
  const safe = base ?? ZERO_REACTIONS;
  const field = REACTION_FIELD[content];
  return { ...safe, [field]: Math.max(0, safe[field] + delta) };
}

/**
 * Optimistic count update for one comment's reaction. Looks up the
 * target by GitHub id across both subtrees of the cached detail —
 * top-level issue/PR comments live under {@code detail.recentActivity},
 * per-line review-thread messages live under
 * {@code detail.reviewThreads[].messages[]} — and bumps the matching
 * field by {@code delta} (default {@code +1}, pass {@code -1} to roll
 * back on failure).
 *
 * Returns a structurally-new detail object when something actually
 * changed (so React picks up the prop change); the original reference
 * when the id wasn't found in either subtree (avoids spurious
 * re-renders on a no-op).
 */
export function optimisticallyBumpReaction(
  detail: PullRequestDetailDto | null,
  commentGithubId: number,
  content: ReactionContent,
  delta = 1,
): PullRequestDetailDto | null {
  if (!detail) return detail;
  let changed = false;

  // Top-level issue / PR comments. One activity entry per comment;
  // match by githubId. Non-comment timeline entries (committed,
  // reviewed, etc.) carry githubId === null and never match.
  const nextActivity = detail.recentActivity.map(item => {
    if (item.githubId !== commentGithubId) return item;
    changed = true;
    return { ...item, reactions: bumpField(item.reactions, content, delta) };
  });

  // Per-line review-thread messages. Walk every thread; only clone
  // the threads whose message list actually changed so untouched
  // threads keep their reference and React can bail on them.
  const nextThreads = detail.reviewThreads.map(thread => {
    let threadChanged = false;
    const nextMsgs = thread.messages.map(msg => {
      if (msg.githubId !== commentGithubId) return msg;
      threadChanged = true;
      changed = true;
      return { ...msg, reactions: bumpField(msg.reactions, content, delta) };
    });
    return threadChanged ? { ...thread, messages: nextMsgs } : thread;
  });

  return changed
    ? { ...detail, recentActivity: nextActivity, reviewThreads: nextThreads }
    : detail;
}

/** Replace the body of one comment (top-level issue comment OR per-line
 *  review-thread message) after a successful edit so the rendered text
 *  updates without a full PR-detail refetch. Looks up the target by
 *  GitHub id across both subtrees, returns the original reference when
 *  the id isn't found (no spurious re-renders). */
export function optimisticallyUpdateCommentBody(
  detail: PullRequestDetailDto | null,
  commentGithubId: number,
  newBody: string,
): PullRequestDetailDto | null {
  if (!detail) return detail;
  let changed = false;
  const nextActivity = detail.recentActivity.map(item => {
    if (item.githubId !== commentGithubId) return item;
    changed = true;
    return { ...item, body: newBody };
  });
  const nextThreads = detail.reviewThreads.map(thread => {
    let threadChanged = false;
    const nextMsgs = thread.messages.map(msg => {
      if (msg.githubId !== commentGithubId) return msg;
      threadChanged = true;
      changed = true;
      return { ...msg, body: newBody };
    });
    return threadChanged ? { ...thread, messages: nextMsgs } : thread;
  });
  return changed
    ? { ...detail, recentActivity: nextActivity, reviewThreads: nextThreads }
    : detail;
}

/** Optimistic removal of a comment (top-level issue comment OR per-line
 *  review-thread message) after a successful delete, so it disappears
 *  without a full PR-detail refetch. Drops the matching timeline entry
 *  from {@code recentActivity} and the matching message from every
 *  review thread; a thread emptied of all messages is dropped too so no
 *  ghost header lingers. Returns the original reference when the id
 *  isn't found in either subtree. */
export function optimisticallyRemoveComment(
  detail: PullRequestDetailDto | null,
  commentGithubId: number,
): PullRequestDetailDto | null {
  if (!detail) return detail;
  let changed = false;
  const nextActivity = detail.recentActivity.filter(item => {
    if (item.githubId !== commentGithubId) return true;
    changed = true;
    return false;
  });
  const nextThreads = detail.reviewThreads
    .map(thread => {
      const nextMsgs = thread.messages.filter(msg => msg.githubId !== commentGithubId);
      if (nextMsgs.length === thread.messages.length) return thread;
      changed = true;
      return { ...thread, messages: nextMsgs };
    })
    .filter(thread => thread.messages.length > 0);
  return changed
    ? { ...detail, recentActivity: nextActivity, reviewThreads: nextThreads }
    : detail;
}

/** Optimistic append of a freshly-posted reply to a review thread. The
 *  GitHub POST returns void in our backend (the response body is
 *  discarded), so we synthesise a temporary message and slot it onto
 *  the matching thread immediately — the user sees their reply land
 *  right after the network call resolves, instead of after the slow
 *  full-detail refetch that was previously the only thing repainting
 *  the thread. The background refetch reconciles the temp id (negative
 *  to guarantee no collision) with the real GitHub id. */
export function optimisticallyAppendReply(
  detail: PullRequestDetailDto | null,
  rootGithubId: number,
  message: ReviewMessageDto,
): PullRequestDetailDto | null {
  if (!detail) return detail;
  let changed = false;
  const nextThreads = detail.reviewThreads.map(thread => {
    if (thread.rootGithubId !== rootGithubId) return thread;
    changed = true;
    return { ...thread, messages: [...thread.messages, message] };
  });
  return changed ? { ...detail, reviewThreads: nextThreads } : detail;
}

/** Optimistic toggle for a review thread's resolved flag — flips the
 *  matching thread's {@code resolved} field and returns a new detail
 *  object. */
export function optimisticallyToggleResolved(
  detail: PullRequestDetailDto | null,
  rootGithubId: number,
  resolved: boolean,
): PullRequestDetailDto | null {
  if (!detail) return detail;
  let changed = false;
  const nextThreads = detail.reviewThreads.map(thread => {
    if (thread.rootGithubId !== rootGithubId) return thread;
    if (thread.resolved === resolved) return thread;
    changed = true;
    return { ...thread, resolved };
  });
  return changed ? { ...detail, reviewThreads: nextThreads } : detail;
}
