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
import { flushSync } from 'react-dom';
import { createFixtureRoot } from '../../fixtures/reactFixture';
import { assert, element, runScenarios, settle } from '../../fixtures/harness';
import { zoomReference } from '../../githubReference';
import { PREVIEW_CATALOG } from '../../agents/fixtures/previewBridge';
import QuickAsk, { type QuickAskBridge } from '../QuickAsk';
import type { AppNotice } from '../../notices/contracts';
import type { Bridge } from '../../types';
import '../../index.css';
import '../quick.css';

const FINISHED: AppNotice = {
  id: 'finished:turn-2', kind: 'TURN_FINISHED', taskId: 'task-2', workspaceId: 'ws1', turnId: 'turn-2', reviewId: null, title: 'Retry commit on conflict',
  pullRequest: null, pullRequestTitle: null, changes: null, message: 'Done', repository: 'owner/repository', laneId: 'claude', occurredAt: '2026-10-16T07:10:00Z',
};

function mount() {
  const calls = { closed: 0, heights: [] as number[], opened: [] as unknown[] };
  window.bridge = {
    getSetupPreferences: async () => ({ step: 'COMPLETE', assistantEnabled: true }),
    getModelSettings: async () => PREVIEW_CATALOG,
    readAppNotices: async () => ({ notices: [FINISHED], viewedIds: [], popupIds: [], active: false }),
    readAgentHistory: async () => ({ events: [], beforeCursor: null, afterCursor: null, latestSequence: 0 }),
    readAgentPendingApprovals: async () => [],
    onAppNotices: () => () => {},
    getVoiceSettings: async () => ({ enabled: true, spoken: null, written: null }),
    onVoiceChanged: () => () => {},
    onDictationEvent: () => () => {},
    listAssistantConversations: async () => [],
    getAssistantConversation: async (id: string) => ({ id, model: null, running: false, items: [], rows: {} }),
    getAgendaToday: async () => { throw new Error('No agenda in this fixture'); },
    listRunningTasks: async () => [],
  } as unknown as Bridge;
  const bridge: QuickAskBridge = {
    close: () => { calls.closed++; },
    resize: height => { calls.heights.push(height); },
    navigate: target => { calls.opened.push(target); },
  };
  const { host, root, dispose } = createFixtureRoot();
  flushSync(() => root.render(<QuickAsk bridge={bridge} />));
  return { host, calls, dispose };
}

async function showsTheAppsAssistant(): Promise<void> {
  const view = mount();
  try {
    await settle();
    await settle();
    const float = element('.quick-ask .assistant-float', view.host, 'The app’s Assistant panel shows');
    assert(float.querySelector('button[aria-label^="Dictate"]') !== null, 'Its composer has the mic');
    assert(float.querySelector('.assistant-pill[data-group="finished"]') !== null, 'Its pills hold what waits, read from the app’s notices');
    assert(view.host.querySelector('.assistant-backdrop')?.getBoundingClientRect().width === 0, 'No backdrop dims a page that is not there');
    const height = Math.ceil(view.host.querySelector('.assistant-panel')?.getBoundingClientRect().height ?? 0);
    assert(Math.abs((view.calls.heights.at(-1) ?? 0) - height) <= 1, `The window is the panel's height: ${view.calls.heights.at(-1)} vs ${height}`);
    element('.quick-ask textarea', view.host).dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
    assert(view.calls.closed === 1, 'Escape puts it away');
  }
  finally { view.dispose(); }
}

async function opensInTheMainWindow(): Promise<void> {
  const view = mount();
  try {
    await settle();
    window.location.hash = '#/workspaces/w1/tasks/t1';
    await new Promise(resolve => setTimeout(resolve, 0));
    zoomReference({ kind: 'pull', repo: 'owner/repository', number: 7 });
    assert(JSON.stringify(view.calls.opened) === JSON.stringify([{ hash: '#/workspaces/w1/tasks/t1' }, { zoom: { kind: 'pull', repo: 'owner/repository', number: 7 } }]),
      `A page and a reference open in the main window: ${JSON.stringify(view.calls.opened)}`);
    assert(window.location.hash === '', 'The floating page stays where it is');
  }
  finally { view.dispose(); }
}

export function run(): Promise<{ name: string; error?: string }[]> {
  return runScenarios([['shows the app’s Assistant', showsTheAppsAssistant], ['opens in the main window', opensInTheMainWindow]]);
}

/** Leaves the floating Assistant on screen for a screenshot. */
export function showPreview(): void {
  mount();
}
