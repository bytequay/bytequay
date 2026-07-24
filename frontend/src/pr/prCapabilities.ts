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
import type { LocalPR } from '../types/localPr';

/** Where the PR is being rendered — the only other input `derivePRCapabilities`
 *  takes besides the PR itself (unified-pr-view.md U7). `task` covers both the
 *  task and stage panes (agent chat lives there); `details` is the standalone
 *  PR details page. */
export type PRSurface = 'task' | 'details';

/** What the user may do with a PR on a given surface (U8, locked). Replaces
 *  the old `mode` / `allowLocalComments` props — `<PRView>` and
 *  `<CodeDiffView>` take this object instead of testing `pr.origin` /
 *  `pr.status` themselves. */
export interface PRCapabilities {
  /** Draft local (never-auto-posted) comments. Task PRs may draft only in
   *  Local Review; external PRs may draft until they become terminal. */
  draftLocalComments: boolean;
  /** "Submit review" — batch local drafts into one GitHub review. External PRs,
   *  and task PRs once they've reached the remote stage. */
  publishReview: boolean;
  /** Push the local branch + open a Draft PR. Task-origin, local-open only. */
  push: boolean;
  /** Direct merge is for a ready external PR only. Task-origin PRs merge via
   *  the authoritative ready-to-merge notification gate. */
  merge: boolean;
  /** The composer belongs to the owning stage — task/stage surfaces only. */
  chatAgent: boolean;
  /** Post a comment straight to GitHub (vs. drafting locally) — any remote-* status or merged PR. */
  postRemoteComment: boolean;
}

const TERMINAL_STATUSES = new Set(['merged', 'closed']);
const REMOTE_COMMENT_STATUSES = new Set(['remote-drafted', 'remote-open', 'merged']);
/** Once a task PR reaches GitHub it is reviewed like any remote PR: the user
 *  may draft inline comments and publish them as one GitHub review. GitHub
 *  itself rejects Approve/Request-changes on a PR you authored — the UI
 *  offers all three verdicts and lets that rejection surface as an error.
 *  Before push it stays private. */
const REMOTE_TASK_REVIEW_STATUSES = new Set(['remote-drafted', 'remote-open']);

/** The single source of truth for what a PR surface may do — derived purely
 *  from `(pr.origin, pr.status, surface)`, never re-tested inline by a
 *  component (unified-pr-view.md U7/U8). */
export function derivePRCapabilities(pr: LocalPR, surface: PRSurface): PRCapabilities {
  const remoteTaskReview = pr.origin === 'task' && REMOTE_TASK_REVIEW_STATUSES.has(pr.status);
  return {
    draftLocalComments: pr.origin === 'task'
      ? pr.status === 'local-open' || remoteTaskReview
      : !TERMINAL_STATUSES.has(pr.status),
    publishReview: pr.origin === 'external' || remoteTaskReview,
    push: pr.origin === 'task' && pr.status === 'local-open',
    merge: pr.origin === 'external' && pr.status === 'remote-open',
    chatAgent: surface === 'task',
    postRemoteComment: REMOTE_COMMENT_STATUSES.has(pr.status),
  };
}
