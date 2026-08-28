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
import {
  calendarCells,
  clockTime,
  dayHeading,
  highlightParts,
  mayLoad,
  rangeLabel,
  rangeWindow,
  reasonTone,
  roleTone,
  rowMarkdown,
  shiftDay,
  splitRepo,
  streamErrorMessage,
} from './model';
import type { RecentRowDto } from '../types';

const TODAY = '2026-08-28';

describe('day headings', () => {
  it('names the two most recent days and dates the rest', () => {
    expect(dayHeading(TODAY, TODAY)).toBe('TODAY');
    expect(dayHeading('2026-08-27', TODAY)).toBe('YESTERDAY');
    expect(dayHeading('2026-08-24', TODAY)).toBe('MONDAY, AUG 24');
    expect(dayHeading('2026-08-15', TODAY)).toBe('AUG 15, 2026');
  });
});

describe('range windows', () => {
  it('resolves each option to an inclusive day span', () => {
    expect(rangeWindow('today', TODAY)).toEqual({ from: TODAY, to: TODAY });
    expect(rangeWindow('yesterday', TODAY)).toEqual({ from: '2026-08-27', to: '2026-08-27' });
    expect(rangeWindow('last7', TODAY)).toEqual({ from: '2026-08-22', to: TODAY });
    expect(rangeWindow('last14', TODAY)).toEqual({ from: '2026-08-15', to: TODAY });
  });

  it('labels a span the way the header reads it', () => {
    expect(rangeLabel('2026-08-22', TODAY)).toBe('Aug 22 – Aug 28');
    expect(rangeLabel(TODAY, TODAY)).toBe('Aug 28');
  });

  it('crosses month boundaries', () => {
    expect(shiftDay('2026-09-02', -5)).toBe('2026-08-28');
  });
});

describe('badges and glyph colour', () => {
  it('tones the reason badge by what the touch was', () => {
    expect(reasonTone('APPROVED')).toBe('green');
    expect(reasonTone('MERGED')).toBe('purple');
    expect(reasonTone('PROMPTED')).toBe('amber');
    expect(reasonTone('READ')).toBe('neutral');
  });

  it('colours the glyph by role', () => {
    expect(roleTone({ type: 'PULL_REQUEST', mine: true })).toBe('mine');
    expect(roleTone({ type: 'PULL_REQUEST', mine: false })).toBe('theirs');
    expect(roleTone({ type: 'COMMIT', mine: true })).toBe('own');
  });
});

describe('word-mode highlighting', () => {
  it('splits on the offsets the backend returned', () => {
    expect(highlightParts('the cache races here', [{ start: 10, end: 15 }])).toEqual([
      { text: 'the cache ', match: false },
      { text: 'races', match: true },
      { text: ' here', match: false },
    ]);
  });

  it('leaves a sentence whole when nothing matched', () => {
    expect(highlightParts('nothing here', [])).toEqual([{ text: 'nothing here', match: false }]);
  });

  it('ignores ranges that run past the sentence', () => {
    expect(highlightParts('short', [{ start: 3, end: 99 }])).toEqual([
      { text: 'sho', match: false },
      { text: 'rt', match: true },
    ]);
  });
});

describe('copy forms', () => {
  it('keeps the number in the Markdown form', () => {
    const row = {
      title: 'Support sorted by nested fields',
      number: 30304,
      htmlUrl: 'https://github.com/trinodb/trino/pull/30304',
    } as RecentRowDto;
    expect(rowMarkdown(row)).toBe('[Support sorted by nested fields #30304](https://github.com/trinodb/trino/pull/30304)');
  });
});

describe('jump-to-a-date calendar', () => {
  it('pads to the first weekday and marks the kept window', () => {
    const cells = calendarCells('2026-08-01', ['2026-08-26'], '2026-08-15', TODAY);
    expect(cells.slice(0, 6)).toEqual([null, null, null, null, null, null]);
    expect(cells[6]).toMatchObject({ day: 1, inWindow: false, active: false });
    expect(cells.find(cell => cell?.day === 26)).toMatchObject({ inWindow: true, active: true });
    expect(cells.find(cell => cell?.day === 29)).toMatchObject({ inWindow: false });
  });
});

describe('clock', () => {
  it('formats a wall clock with a meridiem', () => {
    expect(clockTime(new Date(2026, 7, 28, 14, 14).toISOString())).toBe('2:14 PM');
    expect(clockTime(new Date(2026, 7, 28, 0, 5).toISOString())).toBe('12:05 AM');
  });
});

describe('stream fetch gating', () => {
  it('never drops a filter change, however busy the stream is', () => {
    // Switching from PRs to Issues inside one request window used to leave the
    // new chip lit with the old chip's rows still on screen.
    expect(mayLoad(true, true)).toBe(true);
    expect(mayLoad(true, false)).toBe(true);
  });

  it('still refuses to stack scroll pages', () => {
    expect(mayLoad(false, true)).toBe(false);
    expect(mayLoad(false, false)).toBe(true);
  });
});

describe('stream error text', () => {
  it('explains an unreachable backend instead of leaking the IPC wrapper', () => {
    const message = streamErrorMessage(
      new Error("Error invoking remote method 'recent:list': TypeError: fetch failed"),
    );
    expect(message).toContain('backend is not answering');
    expect(message).toContain('stored on this Mac');
    expect(message).not.toContain('remote method');
  });

  it('unwraps anything else the backend actually said', () => {
    expect(streamErrorMessage(new Error("Error invoking remote method 'recent:list': unsupported action: BOGUS")))
      .toBe('unsupported action: BOGUS');
  });

  it('never renders an empty box', () => {
    expect(streamErrorMessage(new Error(''))).toBe('Recent could not be loaded.');
  });
});

describe('repository split', () => {
  it('splits an owner/name pair', () => {
    expect(splitRepo('trinodb/trino')).toEqual({ owner: 'trinodb', name: 'trino' });
  });

  it('returns null for anything that is not a pair', () => {
    expect(splitRepo(null)).toBeNull();
    expect(splitRepo(undefined)).toBeNull();
    expect(splitRepo('trino')).toBeNull();
    expect(splitRepo('/trino')).toBeNull();
  });
});
