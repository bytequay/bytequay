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

import { describe, expect, it } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { jiraLocation, jiraRoute } from './contracts';
import { jiraAttentionItems } from './attention';
import { JiraIssueActions, supportedTransitionField, transitionPayload } from './JiraScreen';
import { JiraRequestCard } from './JiraSourceAttachment';
import { jiraIssue } from './fixtures/jira';
import { parseAgentTask } from '../agents/contracts';
import { createTaskCreationTracker } from '../agents/model';
import type { AgentTaskDefaultsDto } from '../types';
import fixture from '../agents/fixtures/agent-contracts-v1.json';

const source = { connectionId: jiraIssue.connectionId, cloudId: jiraIssue.cloudId, issueId: jiraIssue.id, key: jiraIssue.key,
  title: jiraIssue.title, body: jiraIssue.body, updatedAt: jiraIssue.updatedAt };
describe('Jira development', () => {
  it('preserves source identity across routes and separates sites in attention', () => {
    const location = { connectionId: 'with / spaces', cloudId: 'site?other', issueId: '1007' };
    expect(jiraLocation(jiraRoute(location))).toEqual(location);
    expect(jiraLocation('#/home')).toBeNull();
    expect(jiraRoute(source)).not.toContain('body=');
    expect(jiraRoute(source)).not.toContain('title=');
    const items = jiraAttentionItems([jiraIssue, { ...jiraIssue, cloudId: 'other' }, { ...jiraIssue, isGoal: true }, { ...jiraIssue, hasChildren: true }, { ...jiraIssue, task: { id: 'task', workspaceId: 'ws' } }]);
    expect(items.map(item => item.key)).toEqual(['jira:site:1007', 'jira:other:1007']);
    expect(items[0].reference).toBe('ENG-1007');
  });
  it('uses a selected Goal icon and cannot run parents', () => {
    const markup = renderToStaticMarkup(<JiraIssueActions issue={{ ...jiraIssue, isGoal: true }} onGoal={() => {}} onStart={() => {}} />);
    expect(markup).toContain('jira-goal-active');
    expect(markup).toContain('aria-pressed="true"');
    expect(markup).not.toContain('Use agent to develop');
    expect(renderToStaticMarkup(<JiraIssueActions issue={{ ...jiraIssue, status: { id: 'done', name: 'Done', category: 'done' } }} onGoal={() => {}} onStart={() => {}} />)).not.toContain('Use agent to develop');
    expect(renderToStaticMarkup(<JiraIssueActions issue={{ ...jiraIssue, hasChildren: true }} onGoal={() => {}} onStart={() => {}} />)).not.toContain('Use agent to develop');
  });
  it('strips unknown task source data and displays a compact request', () => {
    const taskSource = { ...source, url: jiraIssue.url, initialTurnId: 'turn', instructions: 'Implement the ticket', internalSecret: 'not-for-renderer' };
    const parsed = parseAgentTask({ ...fixture.task, jiraSource: taskSource });
    expect(parsed.jiraSource).not.toHaveProperty('internalSecret');
    const markup = renderToStaticMarkup(<JiraRequestCard source={{ ...source, url: jiraIssue.url, initialTurnId: 'turn', instructions: 'Implement the ticket' }} />);
    expect(markup).toContain('Start development'); expect(markup).toContain('ENG-1007'); expect(markup).not.toContain(jiraIssue.body);
  });
  it('submits supported transition values and refuses complex required editors', () => {
    const fields = [
      { id: 'estimate', name: 'Estimate', required: true, schemaType: 'number', allowedValues: [] },
      { id: 'resolution', name: 'Resolution', required: true, schemaType: 'resolution', allowedValues: [{ id: 'fixed', name: 'Fixed' }] },
      { id: 'approvers', name: 'Approvers', required: true, schemaType: 'array', allowedValues: [{ id: 'user', name: 'Person' }] },
      { id: 'description', name: 'Description', required: false, schemaType: 'string', allowedValues: [] },
    ];
    expect(fields.map(supportedTransitionField)).toEqual([true, true, false, false]);
    expect(transitionPayload(fields, { estimate: '2.5', resolution: 'fixed', approvers: 'user', description: 'not a rich-text document' })).toEqual({ estimate: 2.5, resolution: 'fixed' });
  });

  it('keeps an ambiguous create request stable and renews identity when the source changes', () => {
    let next = 0;
    const tracker = createTaskCreationTracker(() => `id-${++next}`);
    const defaults: AgentTaskDefaultsDto = { model: 'codex', effort: null, permissionPolicy: 'AUTO' };
    const first = tracker.requestFor('workspace', 'Implement this', 'codex', defaults, [], undefined, undefined, source);
    expect(tracker.requestFor('workspace', 'Implement this', 'codex', defaults, [], undefined, undefined, source)).toBe(first);
    expect(tracker.requestFor('workspace', 'Implement this', 'codex', defaults, [], undefined, undefined, { ...source, updatedAt: 'new' }).taskId).not.toBe(first.taskId);
  });
});
