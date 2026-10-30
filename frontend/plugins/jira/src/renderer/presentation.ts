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

import type { JiraComment, JiraIssue, JiraIssueDetail, JiraUser } from '../contracts';

export const JIRA_WORKFLOW_GROUPS = ['To do', 'In progress', 'In review', 'Done'] as const;
export function jiraWorkflowGroup(issue: JiraIssue): string {
  if (issue.status.category === 'new') return 'To do';
  if (issue.status.category === 'done') return 'Done';
  // Review is a presentation group; Jira's actual status and transitions remain unchanged.
  if (issue.status.category === 'indeterminate') return /\breview\b/i.test(issue.status.name) ? 'In review' : 'In progress';
  return issue.status.name;
}

export type JiraPriorityLevel = 'urgent' | 'high' | 'medium' | 'low' | 'lowest' | 'neutral';
/** How urgent a priority name reads: P1 is urgent, P4 neither urgent nor important. A tenant's own names keep no rank. */
export function jiraPriorityLevel(name: string): JiraPriorityLevel {
  const value = name.trim().toLowerCase();
  if (/^(p1|highest|critical|blocker)$/.test(value)) return 'urgent';
  if (/^(p2|high)$/.test(value)) return 'high';
  if (/^(p3|medium)$/.test(value)) return 'medium';
  if (/^(p4|low)$/.test(value)) return 'low';
  return /^(p5|lowest)$/.test(value) ? 'lowest' : 'neutral';
}

export function completedGroups(issues: JiraIssue[], now = new Date()): { label: string; issues: JiraIssue[] }[] {
  const midnight = (offset: number) => new Date(now.getFullYear(), now.getMonth(), now.getDate() + offset).getTime();
  const today = midnight(0);
  const tomorrow = midnight(1);
  const yesterday = midnight(-1);
  const mondayOffset = -((now.getDay() + 6) % 7);
  const monday = midnight(mondayOffset);
  const lastMonday = midnight(mondayOffset - 7);
  const groups = ['Today', 'Yesterday', 'This week', 'Last week', 'Earlier', 'Future completion date', 'Completion date unavailable'].map(label => ({ label, issues: [] as JiraIssue[] }));
  const timestamp = (issue: JiraIssue) => issue.completedAt === null ? NaN : Date.parse(issue.completedAt);
  const sorted = issues.toSorted((a, b) => {
    const left = timestamp(a); const right = timestamp(b);
    if (!Number.isFinite(left)) return Number.isFinite(right) ? 1 : a.key.localeCompare(b.key);
    if (!Number.isFinite(right)) return -1;
    return right - left || a.key.localeCompare(b.key);
  });
  for (const issue of sorted) {
    const date = timestamp(issue);
    const index = !Number.isFinite(date) ? 6 : date >= tomorrow ? 5 : date >= today ? 0 : date >= yesterday ? 1 : date >= monday ? 2 : date >= lastMonday ? 3 : 4;
    groups[index].issues.push(issue);
  }
  return groups.filter(group => group.issues.length > 0);
}

type JiraCommentThread = { comment: JiraComment; replies: JiraComment[] };
/** Threads oldest-first comments as Jira shows them: newest thread first, replies oldest first under their root. */
export function jiraCommentThreads(comments: JiraComment[]): JiraCommentThread[] {
  const threads: JiraCommentThread[] = [];
  const threadOf = new Map<string, JiraCommentThread>();
  for (const comment of comments) {
    // A reply to a reply joins its root's thread; a reply whose parent is not loaded starts its own.
    const parent = comment.parentId === null ? undefined : threadOf.get(comment.parentId);
    const thread = parent ?? { comment, replies: [] };
    if (parent) parent.replies.push(comment);
    else threads.push(thread);
    threadOf.set(comment.id, thread);
  }
  return threads.reverse();
}

/** People a comment can mention: the ticket's own people first, then everyone Jira lets you assign it to. */
export function jiraMentionPeople(detail: JiraIssueDetail): JiraUser[] {
  const people = [detail.issue.assignee, detail.reporter, ...detail.comments.map(comment => comment.author).reverse(), ...detail.peopleFields.flatMap(field => field.people), ...detail.assignees];
  return [...new Map(people.filter(person => person !== null).map(person => [person.accountId, person])).values()];
}

/** The `@` query ending at the caret; it may hold spaces so a full name keeps matching. */
export function jiraMentionQuery(value: string, caret: number): { start: number; query: string } | null {
  const match = value.slice(0, caret).match(/(^|[\s(])@([^@\n]{0,40})$/);
  return match ? { start: caret - match[2].length - 1, query: match[2] } : null;
}

/** The first few people whose name, or a word of it, starts with the query. */
export function jiraMentionMatches(people: JiraUser[], query: string): JiraUser[] {
  const prefix = query.toLowerCase();
  return people.filter(person => {
    const name = person.displayName.toLowerCase();
    return name.startsWith(prefix) || name.split(/\s+/).some(word => word.startsWith(prefix));
  }).slice(0, 8);
}

/** `@Name` → account id for each known person the text names. */
export function jiraMentions(text: string, people: JiraUser[]): Record<string, string> {
  const ids = new Map<string, Set<string>>();
  for (const person of people) ids.set(`@${person.displayName}`, (ids.get(`@${person.displayName}`) ?? new Set()).add(person.accountId));
  // ponytail: a name two people share stays plain text; track the picked account per insertion if that bites.
  return Object.fromEntries([...ids].filter(([name, accounts]) => accounts.size === 1 && text.includes(name)).map(([name, accounts]) => [name, [...accounts][0]]));
}
