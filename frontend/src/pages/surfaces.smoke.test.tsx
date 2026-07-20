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
import { cleanup, render } from '@testing-library/react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { TrunkPage } from './TrunkPage';
import { TaskBrainRoute } from './TaskBrainRoute';
import { StageDetailRoute } from './StageDetailRoute';
import { ReviewThreadPage } from './ReviewThreadPage';
import { ChangesPage } from './ChangesPage';
import { CIStatusPage } from './CIStatusPage';
import { CILogView } from '../ui/fullpage';

// jsdom lacks scrollIntoView; the shared conversation may call it.
beforeAll(() => { Element.prototype.scrollIntoView = vi.fn(); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); Reflect.deleteProperty(window, 'bridge'); });
beforeEach(() => {
  (window as unknown as { bridge: unknown }).bridge = {
    // Trunk pane data + the two route hooks; pending promises keep fixtures/defaults.
    listBacklog: vi.fn().mockResolvedValue([]),
    listThreadSignals: vi.fn().mockResolvedValue([]),
    getBrainView: vi.fn(() => new Promise(() => {})),
    sendBrainMessage: vi.fn().mockResolvedValue({}),
    getStageDetail: vi.fn(() => new Promise(() => {})),
    steerStage: vi.fn().mockResolvedValue({ turnId: 'x' }),
  };
});

const composer = { value: '', onChange: () => {}, onSubmit: () => {} };

/** Every V3 surface mounts on the shared shell without throwing — the
 *  automated end-to-end smoke across the redesigned task surfaces. */
describe('V3 surfaces smoke', () => {
  it('TrunkPage mounts', () => {
    const { container } = render(
      <TrunkPage
        threadId="t1"
        thread={{ title: 'Thread' }}
        sidebar={<aside />}
        conversation={<div>conv</div>}
        composer={composer}
        tasks={{ active: [], closed: [] }}
      />,
    );
    expect(container.querySelector('.shell')).toBeTruthy();
  });

  it('TaskBrainRoute mounts', () => {
    const { container } = render(
      <TaskBrainRoute threadId="t1" taskId="task-1" onOpenStage={() => {}} onClosed={() => {}} />,
    );
    expect(container.querySelector('.shell')).toBeTruthy();
  });

  it('StageDetailRoute mounts', () => {
    const { container } = render(
      <StageDetailRoute threadId="t1" taskId="task-1" stageId="s1" />,
    );
    expect(container.querySelector('.shell')).toBeTruthy();
  });

  it('ReviewThreadPage mounts', () => {
    const { container } = render(
      <ReviewThreadPage
        thread={{ title: 'Review' }}
        sidebar={<aside />}
        conversation={<div>conv</div>}
        prTab={<div>pr</div>}
        composer={composer}
        onSubmitReview={() => {}}
      />,
    );
    expect(container.querySelector('.shell')).toBeTruthy();
  });

  it('ChangesPage mounts', () => {
    const { container } = render(
      <ChangesPage
        sidebar={<aside />}
        conversation={<div>conv</div>}
        composer={composer}
        fileTree={<div>tree</div>}
        diff={<div>diff</div>}
      />,
    );
    expect(container.querySelector('.shell.sidebar-collapsed')).toBeTruthy();
  });

  it('CIStatusPage mounts', () => {
    const { container } = render(
      <CIStatusPage
        sidebar={<aside />}
        conversation={<div>conv</div>}
        composer={composer}
        current={{ title: 'CI', checks: [] }}
        iterationGroups={[]}
        log={<CILogView text="log" />}
      />,
    );
    expect(container.querySelector('.shell.sidebar-collapsed')).toBeTruthy();
  });
});
