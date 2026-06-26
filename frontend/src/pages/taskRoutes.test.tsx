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
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { TaskBrainRoute } from './TaskBrainRoute';
import { StageDetailRoute } from './StageDetailRoute';
import { buildMockBrainView } from '../threads/brain/brainViewFixture';
import type { PlanCardDto } from '../types/brainView';

// jsdom lacks scrollIntoView; the shared conversation may call it.
beforeAll(() => { Element.prototype.scrollIntoView = vi.fn(); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); Reflect.deleteProperty(window, 'bridge'); });

describe('TaskBrainRoute', () => {
  it('mounts the V3 brain page on the fixture data and steers the brain agent', async () => {
    const sendBrainMessage = vi.fn().mockResolvedValue({ messageId: 'm1' });
    // getBrainView never resolves → the hook keeps its initial fixture data.
    (window as unknown as { bridge: unknown }).bridge = {
      getBrainView: vi.fn(() => new Promise(() => {})),
      sendBrainMessage,
    };
    render(<TaskBrainRoute threadId="t1" taskId="task-1" onOpenStage={() => {}} onOpenCode={() => {}} onClosed={() => {}} />);

    // The V3 shell + a TASK pill render from the fixture.
    expect(document.querySelector('.shell')).toBeTruthy();
    expect(document.querySelector('.v3-pill--task')).toBeTruthy();

    const box = screen.getByRole('textbox');
    fireEvent.change(box, { target: { value: 'what next?' } });
    fireEvent.keyDown(box, { key: 'Enter' });
    await waitFor(() => expect(sendBrainMessage).toHaveBeenCalledWith('task-1', 'what next?'));
    // The working indicator appears while awaiting the brain's reply.
    expect(await screen.findByText('Brain is thinking…')).toBeTruthy();
  });

  it('shows the Plan tab with Approve when the plan awaits the user', async () => {
    const approvePlan = vi.fn().mockResolvedValue({ devStageId: 'dev-9', redirectUrl: '' });
    const base = buildMockBrainView(0);
    const plan: PlanCardDto = {
      planStageId: 'plan-1', state: 'awaiting', status: 'finalized', source: 'brain',
      understandingSummary: 'Add a cost meter to the rail', intentSummary: 'wire it',
      steps: [{ ordinal: 1, action: 'Build the meter' }], validationStrategy: 'tests',
      pushStrategy: 'await_approval',
      signals: { riskLevel: 'low', estimatedComplexity: 'small', componentsCount: 2, expectedGain: 'x' },
      revisionCount: 0, followups: [],
    };
    const view = { ...base, rightRail: { ...base.rightRail, plan } };
    (window as unknown as { bridge: unknown }).bridge = {
      getBrainView: vi.fn().mockResolvedValue(view),
      sendBrainMessage: vi.fn().mockResolvedValue({}),
      approvePlan,
    };
    const onOpenStage = vi.fn();
    render(<TaskBrainRoute threadId="t1" taskId="task-1" onOpenStage={onOpenStage} onOpenCode={() => {}} onClosed={() => {}} />);

    // Plan is the default tab → its intent step + Approve action show.
    expect(await screen.findByText('Build the meter')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Approve plan' }));
    await waitFor(() => expect(approvePlan).toHaveBeenCalledWith('plan-1'));
    await waitFor(() => expect(onOpenStage).toHaveBeenCalledWith('dev-9'));
  });
});

describe('StageDetailRoute', () => {
  it('mounts the V3 stage page and steers the stage agent', async () => {
    const steerStage = vi.fn().mockResolvedValue({ turnId: 'x' });
    // getStageDetail never resolves → renders the loading defaults.
    (window as unknown as { bridge: unknown }).bridge = {
      getStageDetail: vi.fn(() => new Promise(() => {})),
      steerStage,
    };
    render(<StageDetailRoute threadId="t1" taskId="task-1" stageId="stage-1" onOpenCode={() => {}} />);

    expect(document.querySelector('.shell')).toBeTruthy();
    expect(document.querySelector('.v3-pill--stage')).toBeTruthy();

    const box = screen.getByRole('textbox');
    fireEvent.change(box, { target: { value: 'fix the import' } });
    fireEvent.keyDown(box, { key: 'Enter' });
    await waitFor(() => expect(steerStage).toHaveBeenCalledWith('stage-1', 'fix the import'));
  });
});
