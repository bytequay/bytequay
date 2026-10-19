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

import type { JiraIssue, JiraIssueDetail } from '../../contracts';
export const jiraIssue: JiraIssue = {
  connectionId: 'connection', cloudId: 'site', id: '1007', key: 'ENG-1007', url: 'https://example.atlassian.net/browse/ENG-1007',
  title: 'Preserve predicate pushdown for partition columns', body: 'Keep partition filters in the remote query.\n\n- Verify null values.\n- Preserve case-sensitive column names.',
  type: 'Story', status: { id: 'todo', name: 'To do', category: 'new' },
  assignee: { accountId: 'me', displayName: 'Alex Chen', avatarUrl: null }, labels: ['connectors'], priority: { id: '2', name: 'High' },
  parent: { id: '1000', key: 'ENG-1000', title: 'Connector reliability', type: 'Epic' }, hasChildren: false, isEpic: false,
  updatedAt: '2026-09-26T08:00:00Z', completedAt: null, dueDate: null, task: null, workspace: null,
};
export function jiraDetail(issue: JiraIssue = jiraIssue): JiraIssueDetail {
  return { issue, comments: [{ id: 'c1', parentId: null, author: { accountId: 'reviewer', displayName: 'Sam Rivera', avatarUrl: null }, body: 'Please preserve the existing null handling.', created: issue.updatedAt, updated: issue.updatedAt, mine: false, editableText: null }],
    transitions: [{ id: 'start', name: 'Start progress', to: { id: 'progress', name: 'In progress', category: 'indeterminate' }, fields: [] },
      { id: 'done', name: 'Mark done', to: { id: 'done', name: 'Done', category: 'done' }, fields: [{ id: 'resolution', name: 'Resolution', required: true, schemaType: 'resolution', allowedValues: [{ id: '1', name: 'Fixed' }] }] },
      { id: 'complex', name: 'Complete with approvals', to: { id: 'done', name: 'Done', category: 'done' }, fields: [{ id: 'approvers', name: 'Approvers', required: true, schemaType: 'array', allowedValues: [] }] }],
    editableFields: [{ id: 'summary', name: 'Summary', required: true, schemaType: 'string', allowedValues: [] }, { id: 'labels', name: 'Labels', required: false, schemaType: 'array', allowedValues: [] },
      { id: 'assignee', name: 'Assignee', required: false, schemaType: 'user', allowedValues: [] },
      { id: 'priority', name: 'Priority', required: false, schemaType: 'priority', allowedValues: [{ id: '2', name: 'High' }, { id: '3', name: 'Medium' }] },
      { id: 'duedate', name: 'Due date', required: false, schemaType: 'date', allowedValues: [] }],
    assignees: [{ accountId: 'me', displayName: 'Alex Chen', avatarUrl: null }], fetchedAt: issue.updatedAt, error: null, commentsTruncated: false,
    hierarchyLevel: 0, children: [], childrenTruncated: false, links: [], richTextFields: [],
    project: 'Engineering', reporter: { accountId: 'reviewer', displayName: 'Sam Rivera', avatarUrl: null }, peopleFields: [{ id: 'customfield_1', name: 'Co-Assignee(s)', people: [{ accountId: 'vlad', displayName: 'Vera Lind', avatarUrl: null }] }],
    teamFields: [{ id: 'customfield_2', name: 'Team', team: 'ENG-Connectors' }] };
}
