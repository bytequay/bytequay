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

export type JiraSite = { id: string; name: string; url: string };
export type JiraConnection = { id: string; brokerUrl: string; state: string; sites: JiraSite[]; authorizationUrl: string | null; pairingCode: string | null; error: string | null };
export type JiraBoard = { id: number; name: string; type: string };
export type JiraSprint = { id: number; name: string; state: string; startDate: string | null; endDate: string | null };
export type JiraUser = { accountId: string; displayName: string; avatarUrl: string | null };
export type JiraStatus = { id: string; name: string; category: string };
export type JiraIssue = {
  id: string; key: string; url: string; connectionId: string; cloudId: string; title: string; body: string;
  type: string; status: JiraStatus; assignee: JiraUser | null; labels: string[];
  priority: { id: string; name: string } | null; parent: { id: string; key: string; title: string } | null;
  hasChildren: boolean; isGoal: boolean; updatedAt: string; completedAt: string | null; dueDate: string | null;
  task: { id: string; workspaceId: string } | null;
};
export type JiraField = { id: string; name: string; required: boolean; schemaType: string; allowedValues: { id: string; name: string }[] };
export type JiraIssueDetail = {
  issue: JiraIssue;
  comments: { id: string; author: JiraUser; body: string; created: string; updated: string }[];
  transitions: { id: string; name: string; to: JiraStatus; fields: JiraField[] }[];
  editableFields: JiraField[]; assignees: JiraUser[];
  fetchedAt: string | null; error: string | null; commentsTruncated: boolean;
};
export type JiraIssuePage = { issues: JiraIssue[]; fetchedAt: string | null; partial: boolean; error: string | null };
export type JiraSourceRequest = { connectionId: string; cloudId: string; issueId: string; key: string; title: string; body: string; updatedAt: string };
export type JiraTaskSource = JiraSourceRequest & { url: string; initialTurnId: string | null; instructions: string };
export type JiraLocation = { connectionId: string; cloudId: string; issueId: string };
export type JiraQuery = { connectionId: string; cloudId: string; boardId: number; sprintId: number | null; mine: boolean; completed?: boolean };
export type JiraBridge = {
  listJiraConnections: () => Promise<JiraConnection[]>;
  connectJira: (brokerUrl: string) => Promise<JiraConnection>;
  refreshJiraConnection: (id: string) => Promise<JiraConnection>;
  disconnectJira: (id: string) => Promise<void>;
  listJiraBoards: (connectionId: string, cloudId: string, refresh?: boolean) => Promise<JiraBoard[]>;
  listJiraSprints: (connectionId: string, cloudId: string, boardId: number, refresh?: boolean) => Promise<JiraSprint[]>;
  listJiraIssues: (query: JiraQuery, refresh?: boolean) => Promise<JiraIssuePage>;
  getJiraIssue: (location: JiraLocation, refresh?: boolean) => Promise<JiraIssueDetail>;
  updateJiraIssue: (location: JiraLocation, fields: Record<string, unknown>, expectedUpdatedAt: string) => Promise<JiraIssueDetail>;
  transitionJiraIssue: (location: JiraLocation, transitionId: string, fields: Record<string, unknown>, expectedUpdatedAt: string) => Promise<JiraIssueDetail>;
  moveJiraIssue: (location: JiraLocation, sprintId: number | null, boardId: number, expectedUpdatedAt: string) => Promise<JiraIssueDetail>;
  commentJiraIssue: (location: JiraLocation, body: string, operationId: string) => Promise<JiraIssueDetail>;
  setJiraGoal: (location: JiraLocation, isGoal: boolean) => Promise<JiraIssueDetail>;
  listJiraAttention: () => Promise<JiraIssue[]>;
};

export function jiraRoute(location?: JiraLocation): string {
  return location === undefined ? '#/jira' : `#/jira?${new URLSearchParams({ connectionId: location.connectionId, cloudId: location.cloudId, issueId: location.issueId })}`;
}
export function jiraLocation(hash: string): JiraLocation | null {
  if (!hash.startsWith('#/jira?')) return null;
  const query = new URLSearchParams(hash.slice(7));
  const connectionId = query.get('connectionId');
  const cloudId = query.get('cloudId');
  const issueId = query.get('issueId');
  return connectionId && cloudId && issueId ? { connectionId, cloudId, issueId } : null;
}
export function jiraIssueLocation(issue: JiraIssue): JiraLocation {
  return { connectionId: issue.connectionId, cloudId: issue.cloudId, issueId: issue.id };
}
export const JIRA_CHANGED = 'bytequay:jira-changed';
export function notifyJiraChanged(): void { window.dispatchEvent(new Event(JIRA_CHANGED)); }
