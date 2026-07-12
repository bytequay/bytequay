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
  /** Draft local (never-auto-posted) comments — everywhere except terminal states. */
  draftLocalComments: boolean;
  /** "Submit review" — batch local drafts into one GitHub review. External PRs only. */
  publishReview: boolean;
  /** Push the local branch + open a Draft PR. Task-origin, local-open only. */
  push: boolean;
  /** Merge a pushed PR — any origin, any remote-* status (task-origin once
   *  pushed, or an external PR from the dashboard sync). Merging a
   *  still-draft PR marks it ready for review first (backend flip). */
  merge: boolean;
  /** The composer belongs to the owning stage — task/stage surfaces only. */
  chatAgent: boolean;
  /** Post a comment straight to GitHub (vs. drafting locally) — any remote-* status. */
  postRemoteComment: boolean;
}

const TERMINAL_STATUSES = new Set(['merged', 'closed']);
const REMOTE_STATUSES = new Set(['remote-drafted', 'remote-open']);

/** The single source of truth for what a PR surface may do — derived purely
 *  from `(pr.origin, pr.status, surface)`, never re-tested inline by a
 *  component (unified-pr-view.md U7/U8). */
export function derivePRCapabilities(pr: LocalPR, surface: PRSurface): PRCapabilities {
  return {
    draftLocalComments: !TERMINAL_STATUSES.has(pr.status),
    publishReview: pr.origin === 'external',
    push: pr.origin === 'task' && pr.status === 'local-open',
    merge: REMOTE_STATUSES.has(pr.status),
    chatAgent: surface === 'task',
    postRemoteComment: REMOTE_STATUSES.has(pr.status),
  };
}
