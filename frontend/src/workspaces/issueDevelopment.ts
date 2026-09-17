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

import { setCached } from '../dataCache';
import type { RepositoryIssueSnapshotDto } from '../types';

export type DevelopmentIssue = RepositoryIssueSnapshotDto['issues'][number];
export const ISSUE_DEVELOPMENT_CHANGED = 'bytequay:issue-development-changed';

export function notifyIssueDevelopmentChanged(issueId?: number): void {
  // Clear before navigation so Home cannot seed an already-started suggestion.
  setCached('home:issues', []);
  window.dispatchEvent(new CustomEvent(ISSUE_DEVELOPMENT_CHANGED, { detail: { issueId } }));
}

export function issueDevelopmentAction(issue: DevelopmentIssue): 'view' | 'start' | null {
  if (issue.developmentTask != null) return 'view';
  return issue.isGoal ? null : 'start';
}
