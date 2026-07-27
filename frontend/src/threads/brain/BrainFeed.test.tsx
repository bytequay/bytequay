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
    managedSkills: [],
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
    expect(container.querySelector('.sp-node--green .sp-node__nm')?.textContent).toBe('Development');
    expect(container.querySelector('.sp-node__st')?.textContent).toContain('done · 7m');
    expect(container.querySelector('.sp-node__meta')?.textContent).toContain('Routed 7 sites');
  });

  it('folds a closed stage in Focused but keeps the user turn visible', () => {
    render(<BrainFeed feed={FEED} stages={[DEV]} density="focused" />);
    // The user's intervention stays visible even folded.
    expect(screen.getByText('run the gate')).toBeTruthy();
    // The final answer to that intervention stays visible too.
    expect(screen.getByText('gate green')).toBeTruthy();
    // The autonomous headline is folded away.
    expect(screen.queryByText('All sites routed')).toBeNull();
    // Expanding the boundary (the only button while folded) reveals the chatter.
    fireEvent.click(screen.getByRole('button'));
    expect(screen.getByText('All sites routed')).toBeTruthy();
  });

  it('keeps the remote pull request milestone visible in a folded Development stage', () => {
    const pullRequestCreated: BrainFeedRow = {
      ...row('pr-created', 'PUSHED_PR_CREATED'),
      stageId: 'dev',
      pullRequest: {
        branch: 'feature/timeline', baseBranch: 'main', number: 145,
        additions: 12, deletions: 3,
      },
    };
    render(<BrainFeed feed={[
      { ...row('o', 'STAGE_OPENED'), stageId: 'dev' },
      pullRequestCreated,
      { ...row('c', 'STAGE_CLOSED'), stageId: 'dev' },
    ]} stages={[DEV]} density="focused" />);

    expect(screen.getByText('PR pushed successfully')).toBeTruthy();
    expect(screen.getByText('#145')).toBeTruthy();
    expect(screen.getByText('feature/timeline')).toBeTruthy();
    expect(screen.getByText('main')).toBeTruthy();
    expect(screen.getByText('+12')).toBeTruthy();
    expect(screen.getByText('-3')).toBeTruthy();
    expect(screen.queryByText('R1')).toBeNull();
  });

  it('keeps pull request preparation visible in a folded Development stage', () => {
    const creatingDraft: BrainFeedRow = {
      ...row('pr-draft', 'PULL_REQUEST_PROGRESS'),
      stageId: 'dev',
      pullRequest: {
        phase: 'creating-draft', branch: 'feature/timeline', baseBranch: 'main',
      },
    };
    render(<BrainFeed feed={[
      { ...row('o', 'STAGE_OPENED'), stageId: 'dev' },
      creatingDraft,
      { ...row('later', 'ITERATION_SUMMARY', 'later agent work'), stageId: 'dev' },
      { ...row('c', 'STAGE_CLOSED'), stageId: 'dev' },
    ]} stages={[DEV]} density="focused" />);

    expect(screen.getByText('Creating draft')).toBeTruthy();
    expect(screen.getByText('feature/timeline')).toBeTruthy();
    expect(screen.queryByText('later agent work')).toBeNull();
    expect(screen.queryByText('R1')).toBeNull();
  });

  it('keeps a terminal push failure visible with its reason folded', () => {
    const pushFailed: BrainFeedRow = {
      ...row('pr-failed', 'PULL_REQUEST_PROGRESS'),
      stageId: 'dev',
      pullRequest: {
        phase: 'failed', branch: 'feature/timeline', baseBranch: 'main',
        failedStep: 'ensure_pull_request', reason: 'GitHub returned 403 Forbidden',
      },
    };
    const { container } = render(<BrainFeed feed={[
      { ...row('o', 'STAGE_OPENED'), stageId: 'dev' },
      pushFailed,
      { ...row('c', 'STAGE_CLOSED'), stageId: 'dev' },
    ]} stages={[DEV]} density="focused" />);

    const failure = container.querySelector<HTMLDetailsElement>('.pr-created-event--failed');
    expect(failure?.open).toBe(false);
    expect(screen.getByText('PR push failed')).toBeTruthy();
    expect(screen.getByText('GitHub returned 403 Forbidden')).toBeTruthy();

    fireEvent.click(screen.getByText('PR push failed'));
    expect(failure?.open).toBe(true);
  });

  it('keeps completed-stage summaries visible in the locked focused feed', () => {
    const { container } = render(
      <BrainFeed feed={FEED} stages={[DEV]} density="focused" foldClosedStages={false} />,
    );
    expect(screen.getByText('All sites routed')).toBeTruthy();
    expect(container.querySelector('.sp-work.open')).toBeNull();
    expect(screen.queryByText('intermediate work step')).toBeNull();
  });

  it('Full density shows the work fold contents inline', () => {
    const { container } = render(<BrainFeed feed={FEED} stages={[DEV]} density="full" />);
    // The intermediate work row is visible (fold force-open) and the headline shows.
    expect(screen.getByText('intermediate work step')).toBeTruthy();
    expect(screen.getByText('All sites routed')).toBeTruthy();
    expect(container.querySelector('.sp-work.open')).toBeTruthy();
  });

  it('shows runtime-managed skills on user turns', () => {
    render(<BrainFeed
      feed={[{ ...row('u', 'USER_MESSAGE', 'implement'), managedSkills: ['ponytail'] }]}
      stages={[]}
      density="full"
    />);

    fireEvent.click(screen.getByText('runtime'));
    expect(screen.getByText('Managed skills: ponytail')).toBeTruthy();
  });

  it('keeps the current plan self-review checkpoint visible as the headline', () => {
    const started = row('plan-review-started', 'PLAN_SELF_REVIEW_STARTED', 'Brain started mandatory plan self-review');
    const reviewed = row('plan-reviewed', 'PLAN_SELF_REVIEWED', 'Brain approved the plan');
    const { rerender } = render(<BrainFeed feed={[started]} stages={[]} density="focused" />);

    expect(screen.getByText('Brain started mandatory plan self-review')).toBeTruthy();

    rerender(<BrainFeed feed={[started, reviewed]} stages={[]} density="focused" />);
    expect(screen.getByText('Brain approved the plan')).toBeTruthy();
  });

  it('promotes a remote CI failure into the locked red quote card', () => {
    const failure = {
      ...row('ci', 'ITERATION_SUMMARY', 'Detected red CI in `TypecheckTest` — iteration 3 failed'),
      stageType: 'CI_FIXING_STAGE' as const,
    };
    const { container } = render(<BrainFeed feed={[failure]} stages={[]} density="full" />);
    expect(container.querySelector('.workspace-task-ci-failure__milestone')).toBeTruthy();
    expect(screen.getByText('REMOTE CI')).toBeTruthy();
    expect(container.querySelector('.workspace-task-ci-failure__quote')).toBeTruthy();
  });
});
