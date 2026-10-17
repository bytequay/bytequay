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
import type { AgendaTargetDto, AssistantItemDto, AssistantReceiptDto } from '../types';
import { workspaceAgentTaskRoute, workspaceIssueRoute, workspacePullRequestRoute, workspaceReviewTaskRoute } from '../workspaces/model';
import { entries, linkRefs, refFromHref, refLabel, screenRef } from './model';

const target: AgendaTargetDto = { kind: 'PULL', workspaceId: 'ws', repo: 'trinodb/trino', number: 31402, id: null, connectionId: null, cloudId: null };

describe('screenRef', () => {
  it('names the top layer before the page under it', () => {
    expect(screenRef({ kind: 'pull', repo: 'TrinoDB/Trino', number: 7 }, workspaceAgentTaskRoute('ws', 't1'), 'trinodb/trino')).toBe('pr:trinodb/trino#7');
    expect(screenRef({ kind: 'issue', repo: 'a/B', number: 3, workspaceId: 'ws' }, '#/home', null)).toBe('issue:a/b#3');
    expect(screenRef({ kind: 'jira', connectionId: 'c', cloudId: 'cloud', issueId: '10042' }, '#/home', null)).toBe('jira:cloud:10042');
    expect(screenRef({ kind: 'work', workspaceId: 'ws', itemId: 'w-22' }, '#/home', null)).toBe('work:w-22');
    expect(screenRef({ kind: 'task', workspaceId: 'ws', taskId: 't9' }, '#/home', null)).toBe('task:t9');
  });

  it('reads pull request, task and review routes, and nothing elsewhere', () => {
    expect(screenRef(undefined, '#/pulls/TrinoDB/trino/31402', null)).toBe('pr:trinodb/trino#31402');
    expect(screenRef(undefined, workspaceAgentTaskRoute('ws', 't1'), null)).toBe('task:t1');
    expect(screenRef(undefined, workspaceReviewTaskRoute('ws', 'r1'), null)).toBe('review:r1');
    expect(screenRef(undefined, workspacePullRequestRoute('ws', 12), 'TrinoDB/Trino')).toBe('pr:trinodb/trino#12');
    expect(screenRef(undefined, workspaceIssueRoute('ws', 5), 'trinodb/trino')).toBe('issue:trinodb/trino#5');
    expect(screenRef(undefined, workspacePullRequestRoute('ws', 12), null)).toBeNull();
    expect(screenRef(undefined, '#/home', 'trinodb/trino')).toBeNull();
  });
});

describe('refLabel', () => {
  it('shows numbers for pull requests and issues, and the kind otherwise', () => {
    expect(refLabel('pr:trinodb/trino#31402')).toBe('#31402');
    expect(refLabel('issue:trinodb/trino#9')).toBe('#9');
    expect(refLabel('work:w-22')).toBe('work item');
    expect(refLabel('jira:cloud:10042')).toBe('Jira ticket');
  });
});

describe('linkRefs', () => {
  const targets = { 'pr:trinodb/trino#31402': target };
  const link = (label: string) => `[${label}](https://bytequay.invalid/ref?r=${encodeURIComponent('pr:trinodb/trino#31402')})`;

  it('links refs with a target, written as links or bare', () => {
    expect(linkRefs('Review [#31402](pr:trinodb/trino#31402) first.', targets)).toBe(`Review ${link('#31402')} first.`);
    expect(linkRefs('Review pr:trinodb/trino#31402.', targets)).toBe(`Review ${link('#31402')}.`);
    expect(refFromHref(`https://bytequay.invalid/ref?r=${encodeURIComponent('pr:trinodb/trino#31402')}`)).toBe('pr:trinodb/trino#31402');
    expect(refFromHref('https://github.com/trinodb/trino/pull/31402')).toBeNull();
  });

  it('keeps only the label of a ref without a target, and leaves longer refs and code alone', () => {
    expect(linkRefs('See [the fix](pr:trinodb/trino#1).', targets)).toBe('See the fix.');
    expect(linkRefs('See pr:trinodb/trino#314021 and `pr:trinodb/trino#31402`.', targets)).toBe('See pr:trinodb/trino#314021 and `pr:trinodb/trino#31402`.');
    expect(linkRefs('Nothing to link.', {})).toBe('Nothing to link.');
  });
});

describe('entries', () => {
  const item = (id: string, kind: AssistantItemDto['kind'], outcome?: AssistantReceiptDto['outcome']): AssistantItemDto => ({
    id, day: '2026-10-17', at: '2026-10-17T07:00:00Z', kind, text: id, targets: {}, notReady: false,
    receipt: outcome === undefined ? null : { operation: 'ANSWER_APPROVAL', outcome, detail: null, target: null, turnId: null, fix: null, words: null },
  });

  it('groups a run of done receipts and leaves refusals and the rest on their own', () => {
    const drawn = entries([item('u1', 'USER'), item('d1', 'RECEIPT', 'DONE'), item('d2', 'RECEIPT', 'DONE'), item('x1', 'RECEIPT', 'REFUSED'),
      item('d3', 'RECEIPT', 'DONE'), item('r1', 'REPLY')]);
    expect(drawn.map(entry => Array.isArray(entry) ? entry.map(one => one.id) : entry.id)).toEqual(['u1', ['d1', 'd2'], 'x1', ['d3'], 'r1']);
  });
});
