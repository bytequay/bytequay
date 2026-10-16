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
import { describe, expect, it } from 'vitest';
import type { AgendaCalendarDto, AgendaDto, AgendaItemDto, AgendaTargetDto } from '../types';
import { AgentHomeView } from '../agents/AgentHome';
import { inQuietHours } from '../settings/AgendaScheduleCard';
import TodayList from './TodayList';
import { allDone, attentionKey, calendarFact, doneRatio, doneText, hiddenCount, recapLabel, showsToday, todayKeys, todayRows, whereText } from './today';
import type { TodayAgenda } from './useTodayAgenda';

const pull = (repo: string, number: number): AgendaTargetDto =>
  ({ kind: 'PULL', workspaceId: 'ws1', repo, number, id: null, connectionId: null, cloudId: null });

function item(ref: string, status: AgendaItemDto['status'], extra: Partial<AgendaItemDto> = {}): AgendaItemDto {
  return {
    ref, action: 'review', title: `Review ${ref}`, label: ref, why: 'Asked 3 days ago.', at: '2026-10-13T08:00:00Z', workspaceId: 'ws1',
    target: pull('trinodb/trino', 31377), where: null, status, byYou: false, doneText: null, doneAt: null, ...extra,
  };
}

function agenda(extra: Partial<AgendaDto> = {}): AgendaDto {
  return {
    day: '2026-10-16', state: 'READY', enabled: true, at: 540, generatedAt: '2026-10-16T09:02:00Z', model: 'Sonnet',
    summary: 'Your conflict-retry fix merged last night.', items: [], recap: null, calendar: null, failure: null, ...extra,
  };
}

function render(value: AgendaDto, scope: 'global' | 'workspace' = 'global', error: string | null = null): string {
  const today: TodayAgenda = { agenda: value, error, makeAgain: () => {}, mark: () => {}, showHidden: () => {} };
  return renderToStaticMarkup(<TodayList today={today} scope={scope} />);
}

describe('the Today model', () => {
  const items = [item('a', 'done', { doneText: 'Merged', doneAt: '2026-10-16T00:14:00Z' }), item('b', 'open'), item('c', 'dismissed'), item('d', 'open')];

  it('lists open rows in the agent order, then done rows, and hides what was set aside', () => {
    expect(todayRows(items).map(row => row.ref)).toEqual(['b', 'd', 'a']);
    expect(doneRatio(items)).toEqual({ done: 1, total: 3 });
    expect(hiddenCount(items)).toBe(1);
    expect(allDone(items)).toBe(false);
    expect(allDone([item('a', 'done'), item('c', 'dismissed')])).toBe(true);
    expect(allDone([item('c', 'dismissed')])).toBe(false);
  });

  it('names where an item lives, dropping the repository on its own workspace Home', () => {
    expect(whereText(pull('trinodb/trino', 31402), 'global')).toBe('trinodb/trino #31402');
    expect(whereText(pull('trinodb/trino', 31402), 'workspace')).toBe('#31402');
    expect(whereText({ ...pull('x/y', 1), kind: 'WORK', id: 'w1' }, 'global')).toBe('Work item');
    expect(whereText({ ...pull('x/y', 1), kind: 'WORK', id: 'w1' }, 'global', 'Iceberg write path · 3/6')).toBe('Iceberg write path · 3/6');
    expect(whereText({ ...pull('x/y', 1), kind: 'TASK', id: 't1' }, 'global', 'trinodb/trino #31402')).toBe('trinodb/trino #31402');
    expect(whereText({ ...pull('x/y', 1), kind: 'TASK', id: 't1' }, 'workspace', 'trinodb/trino #31402')).toBe('#31402');
  });

  it('matches today items to Needs attention rows by their keys', () => {
    expect(attentionKey(pull('TrinoDB/Trino', 7))).toBe('trinodb/trino#7');
    expect(attentionKey({ ...pull('x/y', 1), kind: 'TASK', id: 't1' })).toBe('task:t1');
    expect(attentionKey({ ...pull('x/y', 1), kind: 'JIRA', id: '10042', cloudId: 'cloud' })).toBe('jira:cloud:10042');
    expect(attentionKey({ ...pull('x/y', 1), kind: 'WORK', id: 'w1' })).toBeNull();
    expect([...todayKeys(agenda({ items }))]).toEqual(['trinodb/trino#31377']);
  });

  it('says what closed a done row and when', () => {
    expect(doneText(item('a', 'done', { doneText: 'Checks passed' }))).toBe('Checks passed');
    expect(doneText(item('a', 'done', { doneText: 'Merged', doneAt: '2026-10-16T00:14:00Z' }))).toMatch(/^Merged \d{1,2}:\d{2}$/);
    expect(doneText(item('a', 'done', { byYou: true }))).toBe('Done');
  });

  it('names recap chips by number alone when they share a repository', () => {
    const own = [{ ref: 'pr:a', target: pull('trinodb/trino', 1) }, { ref: 'pr:b', target: pull('trinodb/trino', 2) }];
    expect(recapLabel(own[0], own)).toBe('#1');
    const mixed = [...own, { ref: 'pr:c', target: pull('trinodb/trino-gateway', 605) }];
    expect(recapLabel(mixed[0], mixed)).toBe('#1');
    expect(recapLabel(mixed[2], mixed)).toBe('trino-gateway #605');
  });

  it('draws nothing while the agenda is off, and a workspace Home only with items of its own', () => {
    expect(showsToday(null, 'global')).toBe(false);
    expect(showsToday(agenda({ enabled: false, state: 'NONE' }), 'global')).toBe(false);
    expect(showsToday(agenda({ state: 'NONE' }), 'global')).toBe(true);
    expect(showsToday(agenda({ state: 'NONE' }), 'workspace')).toBe(false);
    expect(showsToday(agenda({ items }), 'workspace')).toBe(true);
  });

  it('shows booked hours with the free time on hover, a dimmed clock when unread, and nothing when off', () => {
    const used: AgendaCalendarDto = { state: 'used', busyHours: 5.5, allDay: false, busy: [], free: [{ from: '12:30', to: '15:00' }] };
    expect(calendarFact(used)).toEqual({ text: '5.5 h', title: 'Free 12:30–15:00', dim: false });
    expect(calendarFact({ ...used, free: [], allDay: true })?.title).toBe('All-day event today · No free time between 9:00 and 18:00');
    expect(calendarFact({ ...used, state: 'unavailable' })).toEqual({ text: '', title: 'Calendar not checked', dim: true });
    expect(calendarFact({ ...used, state: 'off' })).toBeNull();
    expect(calendarFact(null)).toBeNull();
  });

  it('knows when the set time falls inside quiet hours, also past midnight', () => {
    expect(inQuietHours(540, { enabled: true, startMinute: 60, endMinute: 450 })).toBe(false);
    expect(inQuietHours(120, { enabled: true, startMinute: 60, endMinute: 450 })).toBe(true);
    expect(inQuietHours(30, { enabled: true, startMinute: 1380, endMinute: 420 })).toBe(true);
    expect(inQuietHours(120, { enabled: false, startMinute: 60, endMinute: 450 })).toBe(false);
  });
});

describe('the Today section', () => {
  it('waits for its time with only its header and a way to make it now', () => {
    const html = render(agenda({ state: 'NONE', generatedAt: null, model: null }));
    expect(html).toContain('at 9:00');
    expect(html).toContain('aria-label="Make now"');
    expect(html).not.toContain('today-list');
  });

  it('says it is making the agenda, and shows a failure inside the section', () => {
    expect(render(agenda({ state: 'RUNNING' }))).toContain('Making today&#x27;s agenda…');
    const failed = render(agenda({ state: 'FAILED', failure: 'Claude is signed out' }));
    expect(failed).toContain('Claude is signed out');
    expect(failed).toContain('Make again</button>');
  });

  it('shows the summary and rows on global Home, but neither summary nor recap on a workspace Home', () => {
    const value = agenda({
      items: [item('pr:trinodb/trino#31377', 'open'), item('pr:trinodb/trino#31390', 'done', { doneText: 'Checks passed' })],
      recap: { text: 'Merged 3 PRs.', merged: [{ ref: 'pr:x', target: pull('trinodb/trino', 31390) }], slipped: [] },
    });
    const global = render(value);
    expect(global).toContain('Your conflict-retry fix merged last night.');
    expect(global).toContain('1/2');
    expect(global).toContain('trinodb/trino #31377');
    expect(global).toContain('Asked 3 days ago.');
    expect(global).toContain('Checks passed');
    expect(global).toContain('This week');
    expect(global).not.toContain('<b>');
    const workspace = render(value, 'workspace');
    expect(workspace).not.toContain('Your conflict-retry fix merged last night.');
    expect(workspace).not.toContain('This week');
    expect(workspace).toContain('#31377');
  });

  it('folds to its header once every item is done, and says so on a quiet day', () => {
    const folded = render(agenda({ items: [item('a', 'done'), item('b', 'done')] }));
    expect(folded).toContain('2/2');
    expect(folded).toContain('aria-expanded="false"');
    expect(folded).not.toContain('today-list');
    expect(render(agenda({ items: [] }))).toContain('Nothing pressing today');
  });
});

describe('the workspace composer', () => {
  it('offers today\'s open items for this repository first among its starting points', () => {
    const value = agenda({ items: [
      item('work:w22', 'open', { action: 'start', label: 'Expose snapshot id', why: 'It is next.' }),
      item('pr:trinodb/trino#31390', 'done', { label: 'maven-checks' }),
    ] });
    const today: TodayAgenda = { agenda: value, error: null, makeAgain: () => {}, mark: () => {}, showHidden: () => {} };
    const html = renderToStaticMarkup(
      <AgentHomeView repository="trinodb/trino" workspaceId="ws1" laneId="codex" providers={[]} tasks={[]} models={[]} selection={null} draft=""
        blocker={null} onDraftChange={() => {}} onSelectProvider={() => {}} onSelectModel={() => {}} onCreate={() => {}} onOpenTask={() => {}}
        onOpenReview={() => {}} onShowAll={() => {}} today={today} />,
    );
    expect(html).toContain('class="today-start-label"');
    expect(html).toContain('title="It is next."');
    expect(html).toContain('Expose snapshot id');
    expect(html).not.toContain('>maven-checks<');
    expect(html.indexOf('Expose snapshot id')).toBeLessThan(html.indexOf('today-start-divider'));
  });
});
