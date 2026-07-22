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
import type { BrainFeedRow, StageDto } from '../../types/brainView';
import { buildBrainTimeline, headlineOf, isQnA, workOf } from './brainTimeline';

function row(id: string, type: BrainFeedRow['type'], body = id, stageId: string | null = null): BrainFeedRow {
  return {
    id,
    messageSeq: null,
    type,
    stageId,
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
  openedAt: '2026-01-01T00:00:00Z', closedAt: '2026-01-01T00:10:00Z',
  callerStageId: null, summary: 'built it', loopIteration: 1,
};

describe('buildBrainTimeline', () => {
  it('segments by stage boundary and groups rounds by user message', () => {
    const feed: BrainFeedRow[] = [
      row('o', 'STAGE_OPENED', 'open', 'dev'),
      row('r1a', 'ITERATION_SUMMARY', 'autonomous work'),
      row('r1b', 'BRAIN_AGENT_RESPONSE', 'done first pass'),
      row('u1', 'USER_MESSAGE', 'is it tested?'),
      row('a1', 'BRAIN_AGENT_RESPONSE', 'yes'),
      row('c', 'STAGE_CLOSED', 'close', 'dev'),
    ];
    const segs = buildBrainTimeline(feed, [DEV]);
    expect(segs).toHaveLength(1);
    expect(segs[0].stage?.id).toBe('dev');
    expect(segs[0].closed).toBe(true);
    // Round 1 autonomous (2 rows), round 2 opened by the user (1 reply).
    expect(segs[0].rounds).toHaveLength(2);
    expect(segs[0].rounds[0].userTurn).toBeNull();
    expect(segs[0].rounds[0].rows).toHaveLength(2);
    expect(segs[0].rounds[1].userTurn?.id).toBe('u1');
    expect(segs[0].rounds[1].rows).toHaveLength(1);
  });

  it('derives headline (last row) and folds the rest as work', () => {
    const segs = buildBrainTimeline([
      row('o', 'STAGE_OPENED', 'open', 'dev'),
      row('w1', 'ITERATION_SUMMARY', 'step one'),
      row('w2', 'ITERATION_SUMMARY', 'step two'),
      row('h', 'BRAIN_AGENT_RESPONSE', 'conclusion'),
    ], [DEV]);
    const round = segs[0].rounds[0];
    expect(headlineOf(round)?.id).toBe('h');
    expect(workOf(round).map(r => r.id)).toEqual(['w1', 'w2']);
  });

  it('gives a pull request creation milestone its own autonomous round', () => {
    const segs = buildBrainTimeline([
      row('o', 'STAGE_OPENED', 'open', 'dev'),
      row('work', 'ITERATION_SUMMARY', 'finished work'),
      row('pr', 'PUSHED_PR_CREATED', 'Pull request created', 'dev'),
      row('c', 'STAGE_CLOSED', 'close', 'dev'),
    ], [DEV]);

    expect(segs[0].rounds).toHaveLength(2);
    expect(segs[0].rounds[0].rows.map(item => item.id)).toEqual(['work']);
    expect(segs[0].rounds[1].userTurn).toBeNull();
    expect(segs[0].rounds[1].rows.map(item => item.id)).toEqual(['pr']);
  });

  it('gives each pull request preparation milestone its own autonomous round', () => {
    const segs = buildBrainTimeline([
      row('o', 'STAGE_OPENED', 'open', 'dev'),
      row('work', 'ITERATION_SUMMARY', 'finished work'),
      row('start', 'PULL_REQUEST_PROGRESS', 'Starting pull request', 'dev'),
      row('draft', 'PULL_REQUEST_PROGRESS', 'Creating draft', 'dev'),
      row('later', 'ITERATION_SUMMARY', 'later work', 'dev'),
      row('c', 'STAGE_CLOSED', 'close', 'dev'),
    ], [DEV]);

    expect(segs[0].rounds.map(round => round.rows.map(item => item.id)))
      .toEqual([['work'], ['start'], ['draft'], ['later']]);
  });

  it('flags a clean Q&A round (user + single reply)', () => {
    const segs = buildBrainTimeline([
      row('u', 'USER_MESSAGE', 'why?'),
      row('a', 'BRAIN_AGENT_RESPONSE', 'because'),
    ], []);
    expect(isQnA(segs[0].rounds[0])).toBe(true);
  });

  it('puts stage-less leading rows in a null-stage segment', () => {
    const segs = buildBrainTimeline([row('u', 'USER_MESSAGE', 'hi')], []);
    expect(segs[0].stage).toBeNull();
    expect(segs[0].rounds[0].userTurn?.id).toBe('u');
  });
});
