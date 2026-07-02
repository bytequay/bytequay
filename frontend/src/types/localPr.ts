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

export interface LocalPR {
  id: string;
  taskId: string;
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
  | 'follow-up';

export interface LocalPRTimelineEvent {
  id: string;
  localPrId: string;
  eventType: LocalPRTimelineEventType;
  actor: string; // "claude-code" | "you" | "@<github-user>"
  isLocalOnly: boolean; // stripped on push
  strippedOnPushAt: number | null;
  createdAt: number;
  payload: Record<string, unknown> | null;
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
  author: string;
  body: string;
  createdAt: number;
  resolvedAt: number | null;
  strippedOnPushAt: number | null;
  parentCommentId: string | null;
}

/** Everything the `<PRView>` needs, as one bundle a bridge hook resolves. */
export interface LocalPRBundle {
  pr: LocalPR;
  commits: LocalPRCommit[];
  timeline: LocalPRTimelineEvent[];
  checks: LocalPRCheck[];
  comments: LocalPRComment[];
}

/** `mode="local"` renders the local phase, `mode="remote"` the pushed phase. */
export type PRViewMode = 'local' | 'remote';

/** True while the PR is still in a local-only state (badge + hints differ). */
export function isLocalStatus(status: LocalPRStatus): boolean {
  return status === 'local-drafted' || status === 'local-open';
}
