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
import { afterEach, describe, expect, it } from 'vitest';
import type { BrainFeedRow, StageDto } from '../../types/brainView';
import { BrainFeed } from './BrainFeed';

afterEach(cleanup);

function row(id: string, type: BrainFeedRow['type'], body = id): BrainFeedRow {
  return {
    id,
    messageSeq: null,
    type,
    stageId: null,
    stageType: null,
    ts: '2026-01-01T00:00:00Z',
    body,
    referencedStageId: null,
    images: [],
  };
}

const DEV: StageDto = {
  id: 'dev', taskId: 't', type: 'DEVELOPMENT_STAGE', state: 'CLOSED',
  openedAt: '2026-01-01T00:00:00Z', closedAt: '2026-01-01T00:07:00Z',
  callerStageId: null, summary: 'Routed 7 sites through parse()', loopIteration: 1,
};

const FEED: BrainFeedRow[] = [
  { ...row('o', 'STAGE_OPENED'), stageId: 'dev' },
  row('w', 'ITERATION_SUMMARY', 'intermediate work step'),
  row('h', 'BRAIN_AGENT_RESPONSE', 'All sites routed'),
  row('u', 'USER_MESSAGE', 'run the gate'),
  row('a', 'BRAIN_AGENT_RESPONSE', 'gate green'),
  { ...row('c', 'STAGE_CLOSED'), stageId: 'dev' },
];

describe('BrainFeed', () => {
  it('renders a stage boundary node with name + done duration + outcome', () => {
    const { container } = render(<BrainFeed feed={FEED} stages={[DEV]} density="full" />);
    expect(container.querySelector('.sp-node--blue .sp-node__nm')?.textContent).toBe('Development');
    expect(container.querySelector('.sp-node__st')?.textContent).toContain('done · 7m');
    expect(container.querySelector('.sp-node__meta')?.textContent).toContain('Routed 7 sites');
  });

  it('folds a closed stage in Focused but keeps the user turn visible', () => {
    const { container } = render(<BrainFeed feed={FEED} stages={[DEV]} density="focused" />);
    // The user's intervention stays visible even folded.
    expect(screen.getByText('run the gate')).toBeTruthy();
    // The autonomous headline is folded away.
    expect(screen.queryByText('All sites routed')).toBeNull();
    // Expanding the boundary (the only button while folded) reveals the chatter.
    fireEvent.click(screen.getByRole('button'));
    expect(screen.getByText('All sites routed')).toBeTruthy();
  });

  it('Full density shows the work fold contents inline', () => {
    const { container } = render(<BrainFeed feed={FEED} stages={[DEV]} density="full" />);
    // The intermediate work row is visible (fold force-open) and the headline shows.
    expect(screen.getByText('intermediate work step')).toBeTruthy();
    expect(screen.getByText('All sites routed')).toBeTruthy();
    expect(container.querySelector('.sp-work.open')).toBeTruthy();
  });
});
