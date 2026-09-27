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

import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { AttentionItem } from '../home/attention';
import type { JiraIssue } from '../jira/contracts';
import type { AgentTaskDto } from '../types';
import type { GoalDto, GoalItemDto } from './contracts';
import { parseGoal } from './contracts';
import { GoalPanel, type GoalPanelProps } from './GoalPanel';
import {
  addable, attentionWorkspace, goalHas, goalRef, itemsIn, needsYouKeys, placePill, recentActivity, refKey, summarize,
} from './model';
import { toggleGoalItem, useGoals, type GoalBridge, type Goals } from './useGoals';

afterEach(() => { vi.unstubAllGlobals(); });

function item(kind: GoalItemDto['kind'], key: string, label: string, state: GoalItemDto['state'], status: string, extra: Partial<GoalItemDto> = {}): GoalItemDto {
  return { kind, key, label, title: `${label} title`, state, status, blocked: false, url: null, activity: null, addedAt: '2026-09-20T00:00:00Z', ...extra };
}

const ITEMS: GoalItemDto[] = [
  item('PULL_REQUEST', 'trinodb/trino#30925', '#30925', 'WORKING', 'Open', { activity: { text: '#30925 updated', at: '2026-09-27T08:00:00Z' } }),
  item('PULL_REQUEST', 'trinodb/trino#30702', '#30702', 'FINISHED', 'Merged', { activity: { text: '#30702 merged', at: '2026-09-27T09:00:00Z' } }),
  item('ISSUE', 'trinodb/trino#31302', '#31302', 'IDLE', 'Open'),
  item('JIRA', 'connection:cloud:10412', 'TRINO-412', 'WORKING', 'Blocked', { blocked: true, activity: { text: 'TRINO-412 is Blocked', at: '2026-09-26T09:00:00Z' } }),
  item('TASK', 'task-1', 'Task', 'FINISHED', 'Completed'),
];
const GOAL: GoalDto = {
  id: 'goal-1', workspaceId: 'workspace-1', title: 'Iceberg & Delta Lake correctness for 482', description: 'Land the fixes before 482.', pinned: true,
  source: null, items: ITEMS, createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-27T00:00:00Z',
};

function attention(extra: Partial<AttentionItem>): AttentionItem {
  return {
    key: 'k', type: 'pr', kind: 'review', repo: 'TrinoDB/Trino', number: 30925, title: 't', author: null, authorAvatarUrl: null, createdAt: null,
    updatedAt: '2026-09-27T08:00:00Z', labels: [], pr: null, body: '', commentCount: 0, workspaceId: null, ...extra,
  };
}

describe('goal model', () => {
  it('names an attention card as the goal item it stands for', () => {
    expect(goalRef(attention({}))).toEqual({ kind: 'PULL_REQUEST', key: 'trinodb/trino#30925' });
    expect(goalRef(attention({ type: 'issue', number: 31302 }))).toEqual({ kind: 'ISSUE', key: 'trinodb/trino#31302' });
    const jira = { id: '10412', connectionId: 'connection', cloudId: 'cloud', workspace: { id: 'workspace-2', name: 'Lake' } } as unknown as JiraIssue;
    expect(goalRef(attention({ type: 'issue', jira, repo: '' }))).toEqual({ kind: 'JIRA', key: 'connection:cloud:10412' });
    const task = { id: 'task-1', workspaceId: 'workspace-3' } as AgentTaskDto;
    expect(goalRef(attention({ type: 'task', task: { dto: task, activity: null } }))).toEqual({ kind: 'TASK', key: 'task-1' });
    expect(goalRef(attention({ repo: '', number: 0 }))).toBeNull();

    const workspaces = [{ id: 'workspace-1', monitoredOwner: 'trinodb', monitoredRepo: 'trino' }];
    expect(attentionWorkspace(attention({}), workspaces)).toBe('workspace-1');
    expect(attentionWorkspace(attention({ jira, repo: '' }), workspaces)).toBe('workspace-2');
    expect(attentionWorkspace(attention({ task: { dto: task, activity: null } }), workspaces)).toBe('workspace-3');
    expect(attentionWorkspace(attention({ repo: 'other/repo' }), workspaces)).toBeNull();
  });

  it('counts progress, pull requests, what needs you and what is blocked', () => {
    const needsYou = needsYouKeys([attention({}), attention({ type: 'issue', number: 31302 }), attention({ number: 1 })]);
    expect(summarize(GOAL, needsYou)).toEqual({ total: 5, finished: 2, working: 2, idle: 1, prsMerged: 1, prsOpen: 1, needs: 2, blocked: 1 });
    expect(itemsIn(GOAL, 'needs', needsYou).map((entry) => entry.label)).toEqual(['#30925', '#31302']);
    expect(itemsIn(GOAL, 'blocked', needsYou).map((entry) => entry.label)).toEqual(['TRINO-412']);
    expect(recentActivity(GOAL).map((entry) => entry.text)).toEqual(['#30702 merged', '#30925 updated', 'TRINO-412 is Blocked']);
    expect(goalHas(GOAL, { kind: 'TASK', key: 'task-1' })).toBe(true);
    expect(goalHas(null, { kind: 'TASK', key: 'task-1' })).toBe(false);
    expect(addable([ITEMS[0], item('ISSUE', 'trinodb/trino#1', '#1', 'IDLE', 'Open')], GOAL).map(refKey)).toEqual(['ISSUE trinodb/trino#1']);
  });

  it('starts the pill just above the composer right edge and never leaves it over the composer', () => {
    const pill = { width: 280, height: 36 };
    const page = { width: 880, height: 952 };
    const composer = { left: 60, top: 746, right: 820, bottom: 900 };
    expect(placePill(null, pill, page, composer)).toEqual({ right: 60, bottom: 216 });
    // A spot saved on the composer is lifted above it; one beside it stays.
    expect(placePill({ right: 100, bottom: 40 }, pill, page, composer)).toEqual({ right: 100, bottom: 216 });
    expect(placePill({ right: 300, bottom: 400 }, pill, page, composer)).toEqual({ right: 300, bottom: 400 });
    // Kept inside the page, and in its corner where there is no composer.
    expect(placePill({ right: 5000, bottom: -20 }, pill, page, null)).toEqual({ right: 592, bottom: 8 });
    expect(placePill(null, pill, page, null)).toEqual({ right: 24, bottom: 24 });
  });

  it('reads a goal the backend sends and refuses a malformed one', () => {
    expect(parseGoal(JSON.parse(JSON.stringify(GOAL)))).toEqual(GOAL);
    expect(() => parseGoal({ ...GOAL, items: [{ ...ITEMS[0], state: 'DONE' }] })).toThrow('Malformed goal item state');
    expect(() => parseGoal({ ...GOAL, pinned: 'yes' })).toThrow('Malformed goal pinned');
  });
});

function panel(props: Partial<GoalPanelProps>): string {
  return renderToStaticMarkup(
    <GoalPanel mode="dock" repository="trinodb/trino" goals={[GOAL, { ...GOAL, id: 'goal-2', pinned: false }]} goal={GOAL}
      needsYou={new Set(['PULL_REQUEST trinodb/trino#30925'])} now={Date.parse('2026-09-27T12:00:00Z')}
      onClose={() => {}} onExpand={() => {}} onSwitch={() => {}} onCreate={async () => {}} onDelete={async () => {}}
      onAdd={async () => {}} onRemove={() => {}} onOpenItem={() => {}} search={async () => []} {...props} />);
}

describe('goal panel', () => {
  it('lists working and idle items by state with finished ones folded, and what changed lately', () => {
    const markup = panel({});
    expect(markup).toContain('GOAL</span><span class="goal-switch__pos">1 of 2</span>');
    expect(markup).toContain('>Pinned<');
    expect(markup).toContain('<span class="goal-progress__count">2</span><span class="goal-muted">of 5 finished</span>');
    expect(markup).toContain('title="Open full view to manage"');
    expect(markup).toMatch(/class="goal-group__label">WORKING<\/span><span class="goal-group__count">2</);
    expect(markup).toMatch(/class="goal-group__label">IDLE<\/span><span class="goal-group__count">1</);
    // Finished work starts folded.
    expect(markup).toContain('>Show 2</button>');
    expect(markup).not.toContain('#30702 title');
    expect(markup).toContain('class="goal-blocked">Blocked<');
    expect(markup.match(/class="goal-needs"/g)).toHaveLength(1);
    expect(markup).toContain('aria-label="Remove #30925 from the goal"');
    expect(markup).toContain('>RECENT ACTIVITY<');
    expect(markup).toContain('#30702 merged');
  });

  it('lays the board out as idle, working and finished columns', () => {
    const markup = panel({ mode: 'board' });
    expect(markup).toContain('placeholder="Add a PR, issue, Jira ticket or task to this goal…"');
    expect(markup).toMatch(/IDLE<span class="goal-group__count">1<\/span>.*WORKING<span class="goal-group__count">2<\/span>.*FINISHED<span class="goal-group__count">2</s);
    expect(markup).toContain('#30702 title');
    expect(markup).not.toContain('title="Open full view to manage"');
  });

  it('offers a first goal where the workspace has none', () => {
    const markup = panel({ goals: [], goal: null });
    expect(markup).toContain('No goal yet');
    expect(markup).toContain('aria-label="Goal name"');
  });
});

describe('goal changes', () => {
  /** Every bridge method a call touches, answering each goal method with the goal. */
  function spyBridge(): { bridge: GoalBridge; touched: string[] } {
    const touched: string[] = [];
    const bridge = new Proxy({}, {
      get: (_target, name) => {
        touched.push(String(name));
        return async () => (name === 'listGoals' ? [GOAL] : name === 'searchGoalCandidates' ? [] : name === 'deleteGoal' ? undefined : GOAL);
      },
    }) as GoalBridge;
    return { bridge, touched };
  }

  it('adds and removes items with goal calls alone, never a GitHub or Jira write', async () => {
    const events: string[] = [];
    vi.stubGlobal('window', { dispatchEvent: (event: CustomEvent<string>) => { events.push(event.detail); return true; } });
    const { bridge, touched } = spyBridge();
    let goals: Goals | undefined;
    function Capture(): null {
      goals = useGoals('workspace-1', () => bridge);
      return null;
    }
    renderToStaticMarkup(<Capture />);

    await goals?.add('goal-1', 'PULL_REQUEST', 'trinodb/trino#30925');
    await goals?.removeItem('goal-1', 'JIRA', 'connection:cloud:10412');
    await toggleGoalItem('workspace-1', 'goal-1', { kind: 'ISSUE', key: 'trinodb/trino#31302' }, false, bridge);
    await toggleGoalItem('workspace-1', 'goal-1', { kind: 'TASK', key: 'task-1' }, true, bridge);

    expect(touched).toEqual(['addGoalItem', 'removeGoalItem', 'addGoalItem', 'removeGoalItem']);
    // Every other view of the workspace's goals reads them again.
    expect(events).toEqual(['workspace-1', 'workspace-1', 'workspace-1', 'workspace-1']);
  });
});
