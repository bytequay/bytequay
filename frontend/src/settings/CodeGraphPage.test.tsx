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
import { describe, expect, it, vi } from 'vitest';
import type { CodeGraphArmComparisonDto, CodeGraphRepositoryComparisonDto } from '../types';
import CodeGraphPage, { formatDelta, formatMetric } from './CodeGraphPage';

function arm(overrides: Partial<CodeGraphArmComparisonDto>): CodeGraphArmComparisonDto {
  return {
    arm: 'ON',
    taskCount: 6,
    turnCount: 9,
    turnsPerTask: 1.5,
    smallSample: false,
    inputTokens: 3_012_065,
    outputTokens: 16_172,
    toolCalls: 35,
    exploreActions: 4.5,
    minutes: 8.7,
    graphCalls: 2,
    graphSeconds: 3,
    ...overrides,
  };
}

describe('comparison formatting', () => {
  it('reads tokens at the scale they arrive in', () => {
    expect(formatMetric(3_012_065, 'tokens')).toBe('3.0M');
    expect(formatMetric(11_063, 'tokens')).toBe('11.1k');
    expect(formatMetric(940, 'tokens')).toBe('940');
    expect(formatMetric(2.75, 'number')).toBe('2.8');
    expect(formatMetric(null, 'tokens')).toBe('—');
  });

  it('marks a cheaper graph arm as the better one', () => {
    expect(formatDelta(80, 100)).toEqual({ text: '-20%', tone: 'is-better' });
    expect(formatDelta(120, 100)).toEqual({ text: '+20%', tone: 'is-worse' });
    expect(formatDelta(100, 100)).toEqual({ text: '0%', tone: 'is-none' });
    // Nothing to compare against is not a zero difference.
    expect(formatDelta(100, null)).toEqual({ text: '—', tone: 'is-none' });
    expect(formatDelta(100, 0)).toEqual({ text: '—', tone: 'is-none' });
  });
});

describe('code graph page', () => {
  const both: CodeGraphRepositoryComparisonDto = {
    repository: 'bytequay/sample',
    arms: [arm({}), arm({ arm: 'OFF', taskCount: 8, inputTokens: 3_488_120, graphCalls: null, graphSeconds: null })],
  };

  it('shows the arms, the sample line and the graph-versus-no-graph delta', () => {
    const markup = renderToStaticMarkup(
      <CodeGraphPage mode="COMPARE" baseline={{ repositories: 3, updatedAt: null }} comparison={[both]} error={null} onMode={vi.fn()} />,
    );

    expect(markup).toContain('graph 6 tasks · no graph 8 tasks');
    expect(markup).toContain('3.0M');
    expect(markup).toContain('3.5M');
    expect(markup).toContain('Turns per task');
    expect(markup).toContain('3 repos');
    expect(markup).not.toContain('low sample');
  });

  it('warns when either arm is too small to read', () => {
    const thin = { ...both, arms: [arm({ taskCount: 4 }), both.arms[1]] };
    const markup = renderToStaticMarkup(
      <CodeGraphPage mode="COMPARE" baseline={null} comparison={[thin]} error={null} onMode={vi.fn()} />,
    );

    expect(markup).toContain('low sample');
  });

  it('says there is nothing to compare rather than inventing a delta', () => {
    const onlyGraph = { repository: 'bytequay/sample', arms: [arm({})] };
    const markup = renderToStaticMarkup(
      <CodeGraphPage mode="COMPARE" baseline={null} comparison={[onlyGraph]} error={null} onMode={vi.fn()} />,
    );

    expect(markup).toContain('nothing to compare against');
    expect(markup).not.toContain('%');
  });

  it('explains what each mode does on its own row', () => {
    const off = renderToStaticMarkup(
      <CodeGraphPage mode="OFF" baseline={null} comparison={[]} error={null} onMode={vi.fn()} />,
    );

    expect(off).toContain('Tool hidden and indexing stopped');
    expect(off).toContain('No task has been stamped with an arm yet.');
  });
});
