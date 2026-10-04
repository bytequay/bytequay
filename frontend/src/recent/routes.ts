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
import type { ZoomedReference } from '../githubReference';
import type { JiraConnection } from '../jira/contracts';
import type { RecentRowDto } from '../types';
import { workspaceAgentTaskRoute, workspaceRoute } from '../workspaces/model';
import { splitRepo } from './model';

/**
 * The stream and both search modes open the same item from its durable identity: a pull request or
 * issue zooms over the page, so reading it never loses your place in Recent; a task or commit is a
 * route to its page.
 */
export function recentItemTarget(item: Pick<RecentRowDto, 'type' | 'itemKey' | 'workspaceId'>, connections: JiraConnection[] = []): ZoomedReference | string | null {
  if (item.type === 'JIRA') {
    const reference = /^jira:([^:]+):([0-9]+)$/.exec(item.itemKey);
    if (!reference) return null;
    const connection = connections.find(candidate => candidate.state === 'connected' && candidate.sites.some(site => site.id === reference[1]));
    return connection ? { kind: 'jira', connectionId: connection.id, cloudId: reference[1], issueId: reference[2] } : null;
  }
  if (item.type === 'TASK') {
    const taskId = /^task:(.+)$/.exec(item.itemKey)?.[1];
    return taskId && item.workspaceId ? workspaceAgentTaskRoute(item.workspaceId, taskId) : null;
  }
  if (item.type === 'COMMIT') return item.workspaceId ? workspaceRoute(item.workspaceId, 'commits') : null;

  const reference = /^(pr|issue):(.+)#(\d+)$/.exec(item.itemKey);
  if (!reference) return null;
  const repo = reference[2];
  const number = Number(reference[3]);
  if (!splitRepo(repo) || number < 1) return null;
  if (item.type === 'PULL_REQUEST' && reference[1] === 'pr') {
    return { kind: 'pull', repo, number, workspaceId: item.workspaceId ?? undefined };
  }
  return item.type === 'ISSUE' && reference[1] === 'issue' && item.workspaceId
    ? { kind: 'issue', repo, number, workspaceId: item.workspaceId }
    : null;
}
