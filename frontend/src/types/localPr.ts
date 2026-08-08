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

/**
 * Frontend mirror of the backend local-PR data contract (see the LocalPR
 * domain records + their DTOs). A "local PR" is the full pull-request
 * artifact — description, commits, timeline, checks, comments — living in
 * ByteQuay before it is ever pushed to GitHub. The unified `<PRView mode>`
 * renders both the local phase (`mode="local"`) and the remote phase
 * (`mode="remote"`) from these shapes; `mode` switches the data source and a
 * few affordances, not the visual template.
 */

export type LocalPRStatus =
  | 'local-drafted' // agent writing commits + description
  | 'local-open' // dev done, awaiting user review
  | 'remote-drafted' // pushed as Draft on GitHub
  | 'remote-open' // ready-for-review on GitHub
  | 'merged'
  | 'closed';

/** `origin=task` — created by the dev agent, full local→remote lifecycle.
 *  `origin=external` — discovered via the dashboard sync (someone else's PR,
 *  or our own PR opened outside ByteQuay); never occupies the local-only
 *  statuses. See `derivePRCapabilities`. */
export type PROrigin = 'task' | 'external';

export interface LocalPR {
  id: string;
  taskId: string | null; // null for origin=external
  branchName: string;
  baseBranch: string;
  title: string;
  description: string; // markdown
  status: LocalPRStatus;
  createdAt: number; // epoch ms
  pushedAt: number | null;
  remotePrNumber: number | null;
  remotePrUrl: string | null;
  mergedAt: number | null;
  closedAt: number | null;
  origin: PROrigin;
  repo: string | null; // "owner/name", set for origin=external
  author: string | null; // set for origin=external
  syncedAt: number | null; // last successful GitHub sync, null pre-first-sync
  /** GitHub's own PR-level diff totals (origin=external only) — GitHub's
   *  commit-list API has no per-commit stats, so summing `LocalPRCommit`s
   *  the way a task-origin PR does always reads 0. Null pre-first-sync. */
  syncedAdditions: number | null;
  syncedDeletions: number | null;
  /** GitHub's mergeable/mergeableState for the base branch (origin=external
   *  only) — null until GitHub has computed it (or pre-first-sync). Powers
   *  the merge-box's "No conflicts with base branch" line. */
  syncedMergeable: boolean | null;
  syncedMergeableState: string | null;
  /** True when the PR's base branch has a merge queue configured — GraphQL-
   *  sourced (REST doesn't expose this). Drives the merge-box's "Merge when
   *  ready" button mode instead of a direct method-dropdown merge. */
  syncedMergeQueueEnabled: boolean;
  /** GitHub's per-PR merge-queue entry state ("QUEUED", etc.) once the PR
   *  has joined the queue; null otherwise. */
  syncedMergeQueueState: string | null;
  /** Epoch ms the app deleted the head branch after a merge; null until
   *  the user clicks "Delete branch" (or it's never been clicked). */
  branchDeletedAt: number | null;
}

export interface LocalPRCommit {
  id: string;
  localPrId: string;
  sha: string;
  message: string;
  additions: number;
  deletions: number;
  authoredAt: number;
  pushedAt: number | null;
}

export type LocalPRTimelineEventType =
  | 'commit'
  | 'ci'
  | 'amend'
  | 'branch'
  | 'status'
  | 'review'
  | 'comment'
  | 'follow-up'
  | 'plan-finalized'
  | 'pull-request-progress'
  | 'pull-request-created';

export interface LocalPRTimelineEvent {
  id: string;
  localPrId: string;
  eventType: LocalPRTimelineEventType;
  actor: string; // "claude-code" | "you" | "@<github-user>"
  isLocalOnly: boolean; // stripped on push
  strippedOnPushAt: number | null;
  createdAt: number;
  payload: Record<string, unknown> | null;
  remoteEventId?: number | null;
}

export type LocalPRCheckKind = 'local' | 'remote';
export type LocalPRCheckStatus = 'pending' | 'running' | 'passed' | 'failed' | 'neutral';

export interface LocalPRCheck {
  id: string;
  localPrId: string;
  kind: LocalPRCheckKind;
  name: string;
  status: LocalPRCheckStatus;
  durationMs: number | null;
  startedAt: number;
  finishedAt: number | null;
  runId: string | null;
}

export type LocalPRCommentOrigin = 'local' | 'remote';
export type LocalPRCommentScope = 'pr' | 'file-line';

export interface LocalPRComment {
  id: string;
  localPrId: string;
  origin: LocalPRCommentOrigin;
  scope: LocalPRCommentScope;
  filePath: string | null;
  lineNumber: number | null;
  /** 'LEFT' (removed) or 'RIGHT' (added/context) — defaults to 'RIGHT' for
   *  every comment that predates this concept. */
  side: 'LEFT' | 'RIGHT';
  /** First line of a multi-line range; null for a single-line comment. */
  startLine: number | null;
  /** Diff side of `startLine`; null for a single-line comment. */
  startSide: 'LEFT' | 'RIGHT' | null;
  author: string;
  body: string;
  createdAt: number;
  resolvedAt: number | null;
  dismissedAt: number | null;
  strippedOnPushAt: number | null;
  parentCommentId: string | null;
  /** Set once `publish-review` batches this draft into a GitHub review
   *  (origin=external only — task-origin drafts are stripped on push
   *  instead and never reach this state). */
  publishedAt: number | null;
  /** Investigation finding backing this comment. Null/absent for ordinary
   * human and draft comments. */
  findingId?: string | null;
  /** Actor who resolved the thread ('you' / dev agent id), for the
   *  "X marked this conversation as resolved" attribution. Null while open. */
  resolvedBy?: string | null;
}

/** Everything the `<PRView>` needs, as one bundle a bridge hook resolves. */
export interface LocalPRBundle {
  pr: LocalPR;
  commits: LocalPRCommit[];
  timeline: LocalPRTimelineEvent[];
  checks: LocalPRCheck[];
  comments: LocalPRComment[];
  /** Authoritative count of local-only events + local comments a push would
   *  strip (design #47) — the push dialog shows this verbatim. Optional so
   *  presentational fixtures can omit it. */
  pendingStripCount?: number;
  /** True while the backend is still refreshing this PR from git/GitHub in the
   *  background — the snapshot above is served immediately and may be a beat
   *  behind. `usePR` polls faster until it clears. */
  syncing?: boolean;
}
