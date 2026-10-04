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

import { workspaceRoute } from '../workspaces/model';

export type JiraSite = { id: string; name: string; url: string };
export type JiraConnection = { id: string; authType: 'oauth' | 'api_token'; tokenEmail: string | null; state: 'pending' | 'connected' | 'error'; account: { accountId: string; displayName: string; email: string | null } | null; sites: JiraSite[]; authorizationUrl: string | null; pairingCode: string | null; error: string | null; duplicateOf: string | null };
export type JiraTokenRequest = { siteUrl: string; email: string; token: string };
export type JiraBoard = { id: number; name: string; type: string };
export type JiraSprint = { id: number; name: string; state: string; startDate: string | null; endDate: string | null };
export type JiraUser = { accountId: string; displayName: string; avatarUrl: string | null };
export type JiraStatus = { id: string; name: string; category: string };
/** The last change ByteQuay saw on a cached ticket; a status or field change names no actor. */
export type JiraActivity = {
  kind: 'STATUS_CHANGED' | 'COMMENTED' | 'FIELDS_CHANGED';
  at: string;
  actor: JiraUser | null;
  from: JiraStatus | null;
  to: JiraStatus | null;
  /** A comment's opening text: the comment itself, or one that came with a status change. */
  note: string | null;
  fields: string[];
};
export type JiraIssue = {
  id: string; key: string; url: string; connectionId: string; cloudId: string; title: string; body: string;
  type: string; status: JiraStatus; assignee: JiraUser | null; labels: string[];
  priority: { id: string; name: string } | null; parent: { id: string; key: string; title: string; type: string | null } | null;
  hasChildren: boolean; isGoal: boolean; updatedAt: string; completedAt: string | null; dueDate: string | null;
  task: { id: string; workspaceId: string } | null;
  workspace: { id: string; name: string } | null;
  /** Sent with Needs attention tickets only. */
  latestActivity?: JiraActivity | null;
};
export type JiraField = { id: string; name: string; required: boolean; schemaType: string; allowedValues: { id: string; name: string }[] };
/** `editableText` is set only on your own comments that plain text can rewrite without losing formatting. */
export type JiraComment = { id: string; parentId: string | null; author: JiraUser; body: string; created: string; updated: string; mine: boolean; editableText: string | null };
export type JiraRelatedIssue = { id: string; key: string; title: string; type: string; status: JiraStatus; priority: { id: string; name: string } | null; assignee: JiraUser | null };
/** `relation` reads from the ticket holding the link, for example "is blocked by". */
export type JiraIssueLink = { id: string; relation: string; issue: JiraRelatedIssue };
export type JiraLinkType = { id: string; name: string; inward: string; outward: string };
export type JiraIssueDetail = {
  issue: JiraIssue;
  comments: JiraComment[];
  transitions: { id: string; name: string; to: JiraStatus; fields: JiraField[] }[];
  editableFields: JiraField[]; assignees: JiraUser[];
  fetchedAt: string | null; error: string | null; commentsTruncated: boolean;
  /** Jira's level: subtasks are -1, standard tickets 0 and epics 1. */
  hierarchyLevel: number; children: JiraRelatedIssue[]; childrenTruncated: boolean; links: JiraIssueLink[];
  /** Site-defined multi-line text fields with content, such as "Additional Context", as Markdown. */
  richTextFields: { id: string; name: string; body: string }[];
  project: string | null; reporter: JiraUser | null;
  /** Site-defined user fields with someone in them, such as "Co-Assignee(s)". */
  peopleFields: { id: string; name: string; people: JiraUser[] }[];
  teamFields: { id: string; name: string; team: string }[];
};
export type JiraIssuePage = { issues: JiraIssue[]; fetchedAt: string | null; nextPageToken: string | null; error: string | null };
export type JiraSourceRequest = {
  connectionId: string; cloudId: string; issueId: string; key: string; title: string; body: string; updatedAt: string;
  /** The ticket's ended task a new attempt replaces; without it, the ticket's current task opens. */
  replaces?: string;
};
export type JiraTaskSource = JiraSourceRequest & { url: string; initialTurnId: string | null; instructions: string };
export type JiraLocation = { connectionId: string; cloudId: string; issueId: string };
export type JiraQuery = { connectionId: string; cloudId: string; boardId: number; sprintId: number | null; mine: boolean; completed?: boolean; nextPageToken?: string };
export type JiraBridge = {
  getJiraConfig: () => Promise<{ configured: boolean }>;
  listJiraConnections: () => Promise<JiraConnection[]>;
  connectJira: () => Promise<JiraConnection>;
  connectJiraToken: (request: JiraTokenRequest) => Promise<JiraConnection>;
  replaceJiraToken: (id: string, request: JiraTokenRequest) => Promise<JiraConnection>;
  reconnectJira: (id: string) => Promise<JiraConnection>;
  refreshJiraConnection: (id: string) => Promise<JiraConnection>;
  disconnectJira: (id: string) => Promise<void>;
  listJiraBoards: (connectionId: string, cloudId: string, refresh?: boolean) => Promise<JiraBoard[]>;
  listJiraSprints: (connectionId: string, cloudId: string, boardId: number, refresh?: boolean) => Promise<JiraSprint[]>;
  listJiraIssues: (query: JiraQuery, refresh?: boolean) => Promise<JiraIssuePage>;
  getJiraIssue: (location: JiraLocation, refresh?: boolean, automatic?: boolean) => Promise<JiraIssueDetail>;
  recordJiraRecentTouch: (location: JiraLocation, dwellSeconds: number) => Promise<void>;
  /** Looks up the ticket a link's key names now; everything after uses the returned issue id. */
  refreshJiraIssueByKey: (connectionId: string, cloudId: string, key: string) => Promise<JiraIssue>;
  updateJiraIssue: (location: JiraLocation, fields: Record<string, unknown>, expectedUpdatedAt: string) => Promise<JiraIssueDetail>;
  transitionJiraIssue: (location: JiraLocation, transitionId: string, fields: Record<string, unknown>, expectedUpdatedAt: string) => Promise<JiraIssueDetail>;
  moveJiraIssue: (location: JiraLocation, sprintId: number | null, boardId: number, expectedUpdatedAt: string) => Promise<JiraIssueDetail>;
  commentJiraIssue: (location: JiraLocation, body: string, operationId: string, parentId?: string) => Promise<JiraIssueDetail>;
  editJiraComment: (location: JiraLocation, commentId: string, body: string, expectedUpdated: string) => Promise<JiraIssueDetail>;
  setJiraGoal: (location: JiraLocation, isGoal: boolean) => Promise<JiraIssueDetail>;
  assignJiraWorkspace: (location: JiraLocation, workspaceId: string) => Promise<JiraIssueDetail>;
  listJiraAttention: () => Promise<JiraIssue[]>;
  /** Work types a new child of the ticket can have; read from Jira when the create form opens. */
  listJiraChildTypes: (location: JiraLocation) => Promise<{ id: string; name: string }[]>;
  createJiraChild: (location: JiraLocation, issueTypeId: string, summary: string) => Promise<JiraIssueDetail>;
  listJiraLinkTypes: (connectionId: string, cloudId: string) => Promise<JiraLinkType[]>;
  /** `outward` reads the ticket at `location` with the type's outward description, as in "blocks `key`". */
  linkJiraIssue: (location: JiraLocation, typeId: string, outward: boolean, key: string) => Promise<JiraIssueDetail>;
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
/** The site origin and key of a Jira Cloud ticket link such as `https://acme.atlassian.net/browse/ENG-7`. */
export function jiraTicketLink(href: string): { origin: string; key: string } | null {
  try {
    const url = new URL(href);
    const key = /^\/browse\/([a-z][a-z0-9_]*-[1-9][0-9]*)\/?$/i.exec(url.pathname)?.[1];
    return url.protocol === 'https:' && key ? { origin: url.origin, key: key.toUpperCase() } : null;
  }
  catch {
    return null;
  }
}
/** A ticket key typed or pasted as `ENG-7`, `eng-7` or a ticket link. */
export function jiraIssueKey(value: string): string | null {
  const text = value.trim();
  return jiraTicketLink(text)?.key ?? (/^[a-z][a-z0-9_]*-[1-9][0-9]*$/i.test(text) ? text.toUpperCase() : null);
}
export function jiraIssueLocation(issue: JiraIssue): JiraLocation {
  return { connectionId: issue.connectionId, cloudId: issue.cloudId, issueId: issue.id };
}
export const JIRA_CHANGED = 'bytequay:jira-changed';
export function notifyJiraChanged(): void { window.dispatchEvent(new Event(JIRA_CHANGED)); }

/** Whether any Jira account is connected with a site to read, which is when Jira shows up at all. */
export function anyJiraConnected(connections: JiraConnection[]): boolean {
  return connections.some(connection => connection.state === 'connected' && connection.sites.length > 0);
}

export function jiraSiteDomain(site: JiraSite): string {
  try { return new URL(site.url).hostname; } catch { return site.name; }
}
export function jiraAccountLabel(connection: JiraConnection): string {
  return connection.account?.email ?? connection.tokenEmail ?? connection.account?.displayName ?? `Account ${connection.id.slice(0, 8)}`;
}
