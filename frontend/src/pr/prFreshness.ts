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
import type { ActivityItemDto, PullRequestDetailDto } from '../types';

/**
 * A compact fingerprint of the change-sensitive parts of a PR's detail
 * snapshot: the conversation timeline, the review threads, the review
 * tallies, the draft/mergeable state, and the diff shape. Two snapshots
 * with the same fingerprint carry the same context, so a comment composed
 * against one is equally valid against the other. We use this to detect
 * that GitHub moved under the user between opening a PR and posting —
 * label changes, reaction-only edits, and CI churn are deliberately left
 * out so they don't trip a false "review first" prompt.
 */
export function prDetailFingerprint(d: PullRequestDetailDto): string {
  return JSON.stringify({
    act: d.recentActivity.map(activityKey),
    rt: d.reviewThreads.map(t => [t.rootGithubId, t.messages.length, t.resolved]),
    ap: d.approvalCount,
    cr: d.changesRequestedCount,
    draft: d.draft,
    ms: d.mergeableState,
    add: d.additions,
    del: d.deletions,
    cf: d.changedFiles,
  });
}

/** True when two snapshots differ in any change-sensitive field. */
export function prDetailChanged(
  a: PullRequestDetailDto, b: PullRequestDetailDto,
): boolean {
  return prDetailFingerprint(a) !== prDetailFingerprint(b);
}

/**
 * A short, human description of what changed between the snapshot the
 * user was viewing ({@code shown}) and a freshly fetched one
 * ({@code fresh}) — the copy for the composer's "review first" notice.
 * Returns null when nothing change-sensitive moved. Best-effort: when the
 * fingerprints differ but no specific delta is recognised, it falls back
 * to a generic line so the caller always has something to show.
 */
export function describePrChange(
  shown: PullRequestDetailDto, fresh: PullRequestDetailDto,
): string | null {
  if (!prDetailChanged(shown, fresh)) {
    return null;
  }
  const parts: string[] = [];

  const shownKeys = new Set(shown.recentActivity.map(activityKey));
  const newActivity = fresh.recentActivity
    .filter(a => !shownKeys.has(activityKey(a)))
    .length;
  if (newActivity > 0) {
    parts.push(`${newActivity} new ${newActivity === 1 ? 'comment or event' : 'comments or events'}`);
  }

  const newReplies = totalReviewMessages(fresh) - totalReviewMessages(shown);
  if (newReplies > 0) {
    parts.push(`${newReplies} new review ${newReplies === 1 ? 'reply' : 'replies'}`);
  }

  if (fresh.changedFiles !== shown.changedFiles
      || fresh.additions !== shown.additions
      || fresh.deletions !== shown.deletions) {
    parts.push('new commits');
  }

  if (fresh.draft !== shown.draft) {
    parts.push(fresh.draft ? 'converted to draft' : 'marked ready for review');
  }

  const lead = 'This pull request changed on GitHub since you opened it';
  if (parts.length === 0) {
    return `${lead}.`;
  }
  return `${lead} — ${joinWithAnd(parts)}. Review above, then post again.`;
}

/** Stable-enough identity for a timeline row: the GitHub event id when
 *  present, else a composite of the fields that move when a row is added
 *  (legacy / id-less rows still differ by type + timestamp + verdict). */
function activityKey(a: ActivityItemDto): string {
  return `${a.githubId ?? 'x'}|${a.eventType}|${a.timestamp ?? ''}|${a.state ?? ''}`;
}

function totalReviewMessages(d: PullRequestDetailDto): number {
  return d.reviewThreads.reduce((sum, t) => sum + t.messages.length, 0);
}

function joinWithAnd(parts: string[]): string {
  if (parts.length === 1) {
    return parts[0];
  }
  return `${parts.slice(0, -1).join(', ')} and ${parts[parts.length - 1]}`;
}
