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

import type { JiraBridge as IntegrationJiraBridge, JiraLocation } from '@bytequay/jira/contracts';
import type { WorkItemDto } from '../goals/contracts';
import { workspaceRoute } from '../workspaces/model';

export * from '@bytequay/jira/contracts';

/** Creating local work remains an application action beside the Jira provider bridge. */
export type JiraBridge = IntegrationJiraBridge & {
  createLocalJiraChild: (location: JiraLocation, id: string, title: string, description: string) => Promise<WorkItemDto>;
};

export function jiraRoute(location?: JiraLocation, workspaceId?: string): string {
  const route = workspaceId === undefined ? '#/jira' : workspaceRoute(workspaceId, 'jira');
  return location === undefined ? route : `${route}?${new URLSearchParams({ connectionId: location.connectionId, cloudId: location.cloudId, issueId: location.issueId })}`;
}
export function jiraLocation(hash: string): JiraLocation | null {
  const [path, search] = hash.split('?');
  if (path !== '#/jira' && !/^#\/workspaces\/[^/]+\/jira$/.test(path)) return null;
  const query = new URLSearchParams(search);
  const connectionId = query.get('connectionId');
  const cloudId = query.get('cloudId');
  const issueId = query.get('issueId');
  return connectionId && cloudId && issueId ? { connectionId, cloudId, issueId } : null;
}
