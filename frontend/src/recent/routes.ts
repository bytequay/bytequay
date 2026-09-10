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
import { pullRequestRoute } from '../githubReference';
import type { RecentRowDto } from '../types';
import { workspaceAgentTaskRoute, workspaceIssueRoute, workspacePullRequestRoute, workspaceRoute } from '../workspaces/model';
import { splitRepo } from './model';

/** The stream and both search modes open the same item from its durable identity. */
export function recentItemRoute(item: Pick<RecentRowDto, 'type' | 'itemKey' | 'workspaceId'>): string | null {
  if (item.type === 'TASK') {
    const taskId = /^task:(.+)$/.exec(item.itemKey)?.[1];
    return taskId && item.workspaceId ? workspaceAgentTaskRoute(item.workspaceId, taskId) : null;
  }
  if (item.type === 'COMMIT') return item.workspaceId ? workspaceRoute(item.workspaceId, 'commits') : null;

  const reference = /^(pr|issue):(.+)#(\d+)$/.exec(item.itemKey);
  if (!reference) return null;
  const repo = splitRepo(reference[2]);
  const number = Number(reference[3]);
  if (!repo || number < 1) return null;
  if (item.type === 'PULL_REQUEST' && reference[1] === 'pr') {
    return item.workspaceId
      ? workspacePullRequestRoute(item.workspaceId, number)
      : pullRequestRoute({ owner: repo.owner, repo: repo.name, number });
  }
  return item.type === 'ISSUE' && reference[1] === 'issue' && item.workspaceId
    ? workspaceIssueRoute(item.workspaceId, number)
    : null;
}
