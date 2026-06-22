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
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { BrainFeedRow } from '../../types/brainView';
import { BrainFeedColumn } from './BrainFeedColumn';

afterEach(cleanup);
beforeEach(() => window.localStorage.clear());

function row(over: Partial<BrainFeedRow> & Pick<BrainFeedRow, 'id' | 'type'>): BrainFeedRow {
  return {
    stageId: 's1', stageType: 'CI_FIXING_STAGE', ts: '2026-06-21T10:00:00Z',
    body: over.body ?? over.id, referencedStageId: null, ...over,
  };
}

// A closed CI-fixing stage: opened + closed are boundary events; the panel
// review inside it is foldable chatter.
const FEED: BrainFeedRow[] = [
  row({ id: 'r1', type: 'STAGE_OPENED', body: 'opened' }),
  row({ id: 'r2', type: 'PANEL_REVIEW_COMPLETED', body: 'panel ran inside the stage' }),
  row({ id: 'r3', type: 'STAGE_CLOSED', body: 'closed' }),
];

function renderColumn(feed: BrainFeedRow[] = FEED) {
  return render(
    <BrainFeedColumn
      feed={feed}
      scrubbers={{ stageEvents: [], userMessages: [] }}
      stageLabels={new Map([['s1', 'CiFixingStage']])}
      activeStageIds={new Set()}
      nowMs={Date.parse('2026-06-21T10:05:00Z')}
      taskNumber={2}
      taskBranch="dev/foo"
      onOpenStage={() => {}}
      onSubmitMessage={() => {}}
    />,
  );
}

// A previous task's trunk recap (stage-less) carried in ahead of this
// task's first user message.
const CARRIED: BrainFeedRow[] = [
  row({ id: 'p1', type: 'TRUNK_MESSAGE', stageId: null, stageType: null, body: 'previous task recap' }),
  row({ id: 'u1', type: 'USER_MESSAGE', stageId: null, stageType: null,
    ts: '2026-06-21T10:01:00Z', body: 'new task request' }),
  row({ id: 't1', type: 'TRUNK_MESSAGE', stageId: null, stageType: null,
    ts: '2026-06-21T10:01:30Z', body: 'planning this task' }),
];

describe('BrainFeedColumn carried-over context', () => {
  it('folds the previous-task recap behind a header and marks the task start', () => {
    renderColumn(CARRIED);
    // The recap is hidden by default behind the carried-over header.
    expect(screen.queryByText('previous task recap')).toBeNull();
    expect(screen.getByText(/carried-over context from the previous task/)).toBeTruthy();
    // The task-start marker delineates this task's own conversation.
    expect(screen.getByText(/Task 2 started · dev\/foo/)).toBeTruthy();
    expect(screen.getByText('new task request')).toBeTruthy();
    expect(screen.getByText('planning this task')).toBeTruthy();
  });

  it('expands the recap on click', () => {
    renderColumn(CARRIED);
    fireEvent.click(screen.getByText(/carried-over context from the previous task/));
    expect(screen.getByText('previous task recap')).toBeTruthy();
  });
});

describe('BrainFeedColumn closed-stage fold', () => {
  it('folds a closed stage chatter behind a bar, keeping boundary events visible', () => {
    renderColumn();
    // Boundary events stay; the within-stage panel row is hidden behind a fold.
    expect(screen.getByText('opened')).toBeTruthy();
    expect(screen.getByText('closed')).toBeTruthy();
    expect(screen.queryByText('panel ran inside the stage')).toBeNull();
    expect(screen.getByText(/Expand · 1 hidden in CiFixingStage/)).toBeTruthy();
  });

  it('expands on click and persists the choice to localStorage', () => {
    renderColumn();
    fireEvent.click(screen.getByText(/Expand · 1 hidden/));
    expect(screen.getByText('panel ran inside the stage')).toBeTruthy();
    expect(window.localStorage.getItem('bytequay.stageExpanded.s1')).toBe('1');
    // A collapse affordance now appears.
    expect(screen.getByText(/Collapse CiFixingStage/)).toBeTruthy();
  });

  it('seeds the expanded state from localStorage', () => {
    window.localStorage.setItem('bytequay.stageExpanded.s1', '1');
    renderColumn();
    expect(screen.getByText('panel ran inside the stage')).toBeTruthy();
  });
});
