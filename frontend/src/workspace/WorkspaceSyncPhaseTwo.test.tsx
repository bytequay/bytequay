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
import { SyncExcusedCheck, SyncFixupAttribution } from './WorkspaceSyncEvidence';
import WorkspaceSyncRounds, { SyncFeedRounds } from './WorkspaceSyncRounds';
import type {
  SyncCompileProofDto, SyncFixupDto, SyncRoundDto,
} from './workspaceApi';

afterEach(cleanup);

const rounds: SyncRoundDto[] = [
  {
    ordinal: 1, roundId: 'r1', remoteHead: '21ca3fc0000', state: 'SUPERSEDED',
    observedCount: 213, failingCount: 121, createdAt: '2026-08-09T10:00:00Z',
  },
  {
    ordinal: 2, roundId: 'r2', remoteHead: '8f05f980000', state: 'FINAL_RED',
    observedCount: 213, failingCount: 27, createdAt: '2026-08-09T12:00:00Z',
  },
  {
    ordinal: 3, roundId: 'r3', remoteHead: '7895bf50000',
    state: 'NEEDS_ATTENTION', observedCount: 213, failingCount: 6,
    createdAt: '2026-08-09T14:00:00Z',
  },
];

describe('the rounds rail', () => {
  it('reads each round against the next, and states the last on its own', () => {
    render(<WorkspaceSyncRounds rounds={rounds} />);

    expect(screen.getByText('121 → 27 failing')).toBeTruthy();
    expect(screen.getByText('27 → 6 failing')).toBeTruthy();
    // Nothing has measured what the last round leads to, so it says only what
    // it observed rather than implying a result.
    expect(screen.getByText('6 failing · parked')).toBeTruthy();
    expect(screen.getByText('21ca3fc')).toBeTruthy();
  });

  it('marks a round with nothing failing as green', () => {
    render(<WorkspaceSyncRounds rounds={[
      { ...rounds[2], failingCount: 0, state: 'GREEN' },
    ]} />);

    expect(screen.getByText('0 failing · green')).toBeTruthy();
    expect(document.querySelector('.st-round__mark.is-green')).toBeTruthy();
  });
});

describe('the rounds in the run conversation', () => {
  it('reads as one line each, opening on what the round observed', () => {
    render(<SyncFeedRounds rounds={rounds} />);

    expect(screen.getByText('pushed 21ca3fc · CI 121 → 27 failing')).toBeTruthy();
    fireEvent.click(screen.getAllByRole('button')[2]);

    // The frozen required selection, not the provider's whole board.
    expect(screen.getByText('213 required checks observed on 7895bf5')).toBeTruthy();
    expect(screen.getByText('6 failing · parked')).toBeTruthy();
  });
});

const fixups: SyncFixupDto[] = [
  {
    pickIndex: 1, upstreamSha: '9be22d1', targetSubject: 'Extract CoordinatorModule config',
    kind: 'ADJACENT_FIXUP', commitSha: '5d1ae7400', changedPaths: ['a.java', 'b.java'],
    amendCount: 0, origin: 'CONFLICT_REPAIR', at: '2026-08-09T14:07:00Z',
  },
  {
    pickIndex: 4, upstreamSha: 'b3d91e0', targetSubject: 'Remove legacy-timestamp flag',
    kind: 'ADJACENT_FIXUP', commitSha: 'aa11bb22c', changedPaths: ['pom.xml'],
    amendCount: 2, origin: 'CI_REPAIR', at: '2026-08-09T16:00:00Z',
  },
];

describe('the fixup attribution block', () => {
  it('names the pick each repair belongs behind, and where it came from', () => {
    render(<SyncFixupAttribution fixups={fixups} />);

    expect(screen.getByText('2 fixups · 1 while picking · 1 from CI')).toBeTruthy();
    fireEvent.click(screen.getByRole('button'));

    expect(screen.getByText('PICK 2')).toBeTruthy();
    expect(screen.getByText('PICK 5')).toBeTruthy();
    expect(screen.getByText('fixup! Extract CoordinatorModule config')).toBeTruthy();
    expect(screen.getByText('conflict repair')).toBeTruthy();
    expect(screen.getByText('CI repair')).toBeTruthy();
    // A second repair amends the first, so a pick still carries exactly one.
    expect(screen.getByText('amended ×2')).toBeTruthy();
  });

  it('says nothing at all when no repair was needed', () => {
    const { container } = render(<SyncFixupAttribution fixups={[]} />);
    expect(container.textContent).toBe('');
  });
});

const proof: SyncCompileProofDto = {
  proofId: 'proof-1',
  headSha: '7895bf5000000',
  provedAt: '2026-08-09T15:00:00Z',
  boundaries: [
    {
      ordinal: 0, commitSha: '41c9b02aaaa', kind: 'PLAIN',
      exitState: 'PASSED', evidenceRef: 'check-run:9001',
    },
    {
      ordinal: 1, commitSha: '5d1ae74bbbb', kind: 'FIXUP',
      exitState: 'PASSED', evidenceRef: 'check-run:9002',
    },
  ],
  compileSelectors: ['GITHUB_CHECK:7:check-commit'],
  compileSourceRef: '.github/workflows/ci.yml@8f05f98',
  excusedTargets: ['Extract CoordinatorModule config'],
};

describe('the excused-check card', () => {
  it('shows the boundary builds, not just the verdict', () => {
    render(<SyncExcusedCheck proof={proof} />);

    expect(screen.getByText('Per-commit compile red excused on 1 target')).toBeTruthy();
    expect(screen.getByText('Extract CoordinatorModule config')).toBeTruthy();
    // The citation matters as much as the selector: a guessed compile check
    // would excuse reds it has no business excusing.
    expect(screen.getByText('read from .github/workflows/ci.yml@8f05f98')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: /Boundary compile proof/ }));
    expect(screen.getByText('after the fixup')).toBeTruthy();
    expect(screen.getByText('target with no fixup')).toBeTruthy();
    expect(screen.getByText('check-run:9002')).toBeTruthy();
  });

  it('excuses nothing when a boundary build failed', () => {
    render(<SyncExcusedCheck proof={{
      ...proof,
      boundaries: [{ ...proof.boundaries[0], exitState: 'FAILED' }],
    }} />);

    expect(screen.getByText('Boundary compile failed — nothing is excused')).toBeTruthy();
  });

  it('excuses nothing when no compile check could be identified', () => {
    render(<SyncExcusedCheck proof={{
      ...proof, compileSelectors: [], excusedTargets: [],
    }} />);

    expect(screen.getByText(
      /No per-commit compile check was identified/)).toBeTruthy();
  });
});
