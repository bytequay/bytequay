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
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { RunEpisode } from './RunEpisode';
import type { AgentRunDto } from '../../types/brainView';

afterEach(cleanup);

function run(over: Partial<AgentRunDto> = {}): AgentRunDto {
  return {
    id: 'run-1', taskId: 't', kind: 'ci_fix', source: 'remote', parentStageId: null,
    reviewRoundId: null, stageId: 'stage-1', status: 'running', iterations: 2, budget: null,
    headline: null, startedAt: '2026-01-01T00:00:00Z', finishedAt: null, ...over,
  };
}

describe('RunEpisode', () => {
  it('shows the headline when present, else an iteration count', () => {
    render(<RunEpisode run={run({ headline: 'fixing linter warning' })} />);
    expect(screen.getByText('fixing linter warning')).toBeTruthy();

    cleanup();
    render(<RunEpisode run={run({ headline: null, iterations: 3 })} />);
    expect(screen.getByText('iter 3')).toBeTruthy();
  });

  it('labels an awaiting_gate run as "awaiting you"', () => {
    render(<RunEpisode run={run({ status: 'awaiting_gate' })} />);
    expect(screen.getByText('awaiting you')).toBeTruthy();
  });

  it('labels a finished run "done"', () => {
    render(<RunEpisode run={run({ status: 'succeeded' })} />);
    expect(screen.getByText('done')).toBeTruthy();
  });

  it('opens the run on click', () => {
    const onOpen = vi.fn();
    render(<RunEpisode run={run()} onOpen={onOpen} />);
    fireEvent.click(screen.getByText('CI fix run · remote'));
    expect(onOpen).toHaveBeenCalledOnce();
  });
});
