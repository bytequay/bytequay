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
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { AggregateMetricsStrip } from './AggregateMetricsStrip';
import type { TaskBrainViewData } from '../../types/brainView';

afterEach(cleanup);

const AGG: TaskBrainViewData['aggregate'] = {
  pushes: 4,
  activeTimeSec: 12 * 60,
  waitingUserTimeSec: 23 * 60,
  toolCalls: 89,
  turns: 31,
  messages: 412,
  panels: 2,
  costCents: 147,
  autoPushBudget: { used: 5, limit: 5 },
};

describe('AggregateMetricsStrip', () => {
  it('formats durations and cost into human strings', () => {
    const { container } = render(<AggregateMetricsStrip aggregate={AGG} liveLabel={null} />);
    const text = container.textContent ?? '';
    expect(text).toContain('12m');     // active time
    expect(text).toContain('23m');     // waiting time
    expect(text).toContain('$1.47');   // cost
    expect(text).toContain('5/5');     // auto-push budget
    expect(screen.getByText('89')).toBeTruthy();   // tool calls
  });

  it('omits the auto-push budget pill when no CiFixingStage exists', () => {
    const { container } = render(
      <AggregateMetricsStrip aggregate={{ ...AGG, autoPushBudget: null }} liveLabel={null} />,
    );
    expect(container.textContent).not.toContain('auto-push');
  });

  it('shows the live pill only when a live label is provided', () => {
    const { rerender, container } = render(
      <AggregateMetricsStrip aggregate={AGG} liveLabel={null} />,
    );
    expect(container.querySelector('.live-pill')).toBeNull();

    rerender(<AggregateMetricsStrip aggregate={AGG} liveLabel="CI FIX RUNNING" />);
    expect(container.querySelector('.live-pill')).not.toBeNull();
    expect(screen.getByText('CI FIX RUNNING')).toBeTruthy();
  });
});
