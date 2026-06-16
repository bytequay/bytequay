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
import { FlowStepper } from './FlowStepper';
import { PhaseChip } from './PhaseChip';

afterEach(cleanup);

describe('FlowStepper', () => {
  it('renders all 8 nodes and marks state by position', () => {
    const { container } = render(<FlowStepper currentPhase="AWAITING_PUSH" />);
    const nodes = container.querySelectorAll('li[data-state]');
    expect(nodes).toHaveLength(8);
    // AWAITING_PUSH is node 3: 0..2 done, 3 active, 4..7 pending.
    expect(nodes[2].getAttribute('data-state')).toBe('done');
    expect(nodes[3].getAttribute('data-state')).toBe('active');
    expect(nodes[4].getAttribute('data-state')).toBe('pending');
  });

  it('keeps the active node at CI for the CI_FIXING loop (no rewind)', () => {
    const { container } = render(<FlowStepper currentPhase="CI_FIXING" />);
    const nodes = container.querySelectorAll('li[data-state]');
    expect(nodes[4].getAttribute('data-state')).toBe('active');
  });

  it('marks the terminal Done node as done, not active, when completed', () => {
    const { container } = render(<FlowStepper currentPhase="COMPLETED" />);
    const nodes = container.querySelectorAll('li[data-state]');
    // Every node, including the final "Done", reads done (green) — a
    // finished task must not show its last node mid-progress (amber).
    expect(nodes[7].getAttribute('data-state')).toBe('done');
    expect(
      Array.from(nodes).every(n => n.getAttribute('data-state') === 'done'),
    ).toBe(true);
  });
});

describe('PhaseChip', () => {
  it('shows the humanised phase', () => {
    render(<PhaseChip phase="AWAITING_REMOTE_REVIEW" />);
    expect(screen.getByText('Awaiting remote review')).toBeTruthy();
  });
});
