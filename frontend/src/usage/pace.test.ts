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
import { createElement } from 'react';
import type { ProviderUsageDto, ProviderUsageWindowDto } from '../types';
import { parseProviderUsage } from '../agents/contracts';
import { UsageRow } from './UsageMeter';
import { resetLabel, usagePace } from './pace';

const now = Date.parse('2026-10-08T02:00:00Z');
const window: ProviderUsageWindowDto = { id: 'session', label: '5-hour', usedPercent: 18,
  startsAt: '2026-10-08T00:00:00Z', resetsAt: '2026-10-08T05:00:00Z', amount: null };

describe('usage pacing', () => {
  it('colors only the difference from elapsed time, including zero and overage', () => {
    expect(usagePace(window, now)).toMatchObject({ gray: 18, green: 22, red: 0, expected: 40 });
    expect(usagePace({ ...window, usedPercent: 62 }, now)).toMatchObject({ gray: 40, green: 0, red: 22 });
    expect(usagePace({ ...window, usedPercent: 40 }, now)).toMatchObject({ gray: 40, green: 0, red: 0, guidance: 'On pace' });
    expect(usagePace({ ...window, usedPercent: 0 }, now)).toMatchObject({ gray: 0, green: 40, red: 0 });
    expect(usagePace({ ...window, usedPercent: 120 }, now)).toMatchObject({ gray: 40, green: 0, red: 60, guidance: 'Limit reached' });
  });
  it('removes pace guidance for unknown, future, stale and expired periods', () => {
    for (const value of [{ ...window, startsAt: null }, { ...window, startsAt: '2026-10-09T00:00:00Z' }]) {
      expect(usagePace(value, now)).toMatchObject({ expected: null, green: 0, red: 0 });
    }
    expect(usagePace(window, now, true)).toMatchObject({ gray: 18, green: 0, red: 0, guidance: 'Update needed' });
    expect(usagePace(window, Date.parse('2026-10-08T05:00:00Z'))).toMatchObject({ gray: 0, green: 0, red: 0, expired: true });
    expect(resetLabel(window.resetsAt, now)).toBe('Resets in 3h 0m');
  });
  it('uses actual calendar boundaries for monthly windows', () => {
    expect(usagePace({ ...window, startsAt: '2026-02-01T00:00:00Z', resetsAt: '2026-03-01T00:00:00Z' }, Date.parse('2026-02-15T00:00:00Z')).expected).toBe(50);
  });
  it('labels the bar for nonvisual reading and displays reported money amounts', () => {
    const html = renderToStaticMarkup(createElement(UsageRow, { window: { ...window, amount: { used: 120, total: 500, currency: 'USD' } }, now }));
    expect(html).toContain('Expected usage 40%');
    expect(html).toContain('$120.00 / $500.00');
    expect(html).not.toContain('Time passed');
  });
  it('rejects invalid bridge data and retains only quota fields', () => {
    const response: ProviderUsageDto & { token: string } = { accountId: 'codex', plan: 'pro', checkedAt: new Date(now).toISOString(), message: null, windows: [window], token: 'secret' };
    expect(parseProviderUsage(response)).not.toHaveProperty('token');
    expect(() => parseProviderUsage({ ...response, windows: [{ ...window, usedPercent: NaN }] })).toThrow();
    expect(() => parseProviderUsage({ ...response, checkedAt: null })).toThrow();
  });
});
