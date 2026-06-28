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
import { buildLivePlan } from './livePlanModel';
import type { StageDto, StageState, StageType, TaskPhase } from '../../types/brainView';

function stage(type: StageType, state: StageState, over: Partial<StageDto> = {}): StageDto {
  return {
    id: `${type}-id`, taskId: 't', type, state,
    openedAt: '2026-01-01T00:00:00Z', closedAt: null, callerStageId: null,
    summary: '', loopIteration: 0, ...over,
  };
}

const node = (nodes: ReturnType<typeof buildLivePlan>, key: string) =>
  nodes.find(n => n.key === key)!;

describe('buildLivePlan', () => {
  it('renders the full eight-node lifecycle in order', () => {
    const nodes = buildLivePlan({
      stages: [], subStages: [],
      task: { prNumber: null, currentPhase: 'IMPLEMENTING' as TaskPhase, terminal: false },
    });
    expect(nodes.map(n => n.key)).toEqual(
      ['plan', 'dev', 'review', 'push', 'ci-fix', 'comments', 'merge', 'cleanup']);
  });

  it('maps stage state to node status (closed→done, active→running, open→sleep, absent→future)', () => {
    const nodes = buildLivePlan({
      stages: [
        stage('PLAN_STAGE', 'CLOSED'),
        stage('DEVELOPMENT_STAGE', 'ACTIVE', { loopIteration: 3 }),
        stage('CI_FIXING_STAGE', 'OPEN'),
      ],
      subStages: [],
      task: { prNumber: 145, currentPhase: 'CI_FIXING' as TaskPhase, terminal: false },
    });
    expect(node(nodes, 'plan').status).toBe('done');
    expect(node(nodes, 'dev').status).toBe('running');
    expect(node(nodes, 'dev').meta).toBe('iter 3');
    expect(node(nodes, 'ci-fix').status).toBe('sleep');
    expect(node(nodes, 'comments').status).toBe('future');
    expect(node(nodes, 'cleanup').status).toBe('future');
  });

  it('uses the planning variant for an active Plan stage', () => {
    const nodes = buildLivePlan({
      stages: [stage('PLAN_STAGE', 'ACTIVE')], subStages: [],
      task: { prNumber: null, currentPhase: 'IMPLEMENTING' as TaskPhase, terminal: false },
    });
    expect(node(nodes, 'plan').status).toBe('planning');
    expect(node(nodes, 'plan').glyph).toBe('●');
  });

  it('marks Push done with the PR number once a PR exists', () => {
    const nodes = buildLivePlan({
      stages: [], subStages: [],
      task: { prNumber: 145, currentPhase: 'PUSHED_AWAITING_CI' as TaskPhase, terminal: false },
    });
    expect(node(nodes, 'push').status).toBe('done');
    expect(node(nodes, 'push').meta).toBe('PR #145');
  });

  it('nests Push under Development and routes its click to the dev conversation', () => {
    const dev = stage('DEVELOPMENT_STAGE', 'CLOSED');
    const nodes = buildLivePlan({
      stages: [dev], subStages: [],
      task: { prNumber: 145, currentPhase: 'PUSHED_AWAITING_CI' as TaskPhase, terminal: false },
    });
    expect(node(nodes, 'push').placement).toBe('sub');
    expect(node(nodes, 'push').nav).toEqual({ kind: 'stage', stageId: dev.id });

    // No Development stage yet → nowhere to navigate, so the node is inert.
    const early = buildLivePlan({
      stages: [], subStages: [],
      task: { prNumber: null, currentPhase: 'IMPLEMENTING' as TaskPhase, terminal: false },
    });
    expect(node(early, 'push').nav).toEqual({ kind: 'none' });
  });

  it('drives the Merge node from PR/merge state', () => {
    const queued = buildLivePlan({
      stages: [], subStages: [],
      task: { prNumber: 145, currentPhase: 'AWAITING_REMOTE_REVIEW' as TaskPhase, terminal: false },
      prStatus: 'queued',
    });
    expect(node(queued, 'merge').status).toBe('running');
    expect(node(queued, 'merge').meta).toBe('queued');

    const merged = buildLivePlan({
      stages: [], subStages: [],
      task: { prNumber: 145, currentPhase: 'COMPLETED' as TaskPhase, terminal: true },
      prStatus: 'merged',
    });
    expect(node(merged, 'merge').status).toBe('done');
  });

  it('shows Review (callable) as not-invoked until a ReviewStage exists', () => {
    const none = buildLivePlan({
      stages: [], subStages: [],
      task: { prNumber: null, currentPhase: 'IMPLEMENTING' as TaskPhase, terminal: false },
    });
    expect(node(none, 'review').status).toBe('future');
    expect(node(none, 'review').meta).toBe('not invoked');
    expect(node(none, 'review').placement).toBe('sub');

    const invoked = buildLivePlan({
      stages: [], subStages: [stage('REVIEW_STAGE', 'ACTIVE', { callerStageId: 'dev-id' })],
      task: { prNumber: null, currentPhase: 'INTERNAL_REVIEW' as TaskPhase, terminal: false },
    });
    expect(node(invoked, 'review').status).toBe('running');
  });

  it('flags the currently-viewed stage as the active view', () => {
    const dev = stage('DEVELOPMENT_STAGE', 'ACTIVE');
    const nodes = buildLivePlan({
      stages: [dev], subStages: [],
      task: { prNumber: null, currentPhase: 'IMPLEMENTING' as TaskPhase, terminal: false },
      viewedStageId: dev.id,
    });
    expect(node(nodes, 'dev').activeView).toBe(true);
    expect(node(nodes, 'plan').activeView).toBe(false);
  });

  it('places CI Fix / Comments on the parallel split', () => {
    const nodes = buildLivePlan({
      stages: [], subStages: [],
      task: { prNumber: 1, currentPhase: 'CI_FIXING' as TaskPhase, terminal: false },
    });
    expect(node(nodes, 'ci-fix').placement).toBe('split-left');
    expect(node(nodes, 'comments').placement).toBe('split-right');
  });
});
