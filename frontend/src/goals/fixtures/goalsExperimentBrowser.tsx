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

import { createRoot } from 'react-dom/client';
import { flushSync } from 'react-dom';
import { setCached } from '../../dataCache';
import { assert, settle } from '../../fixtures/harness';
import type { GoalDto } from '../contracts';
import { useGoals, usePinnedGoals, type GoalBridge } from '../useGoals';
import { GOALS_CHANGED } from '../../workitems/changes';

export async function run(): Promise<boolean> {
  assert(process.env.NODE_ENV === 'production', 'The fixture must use a production build');
  const saved: GoalDto = { id: 'goal', workspaceId: 'workspace', title: 'Saved experiment', description: '', pinned: true,
    paused: false, source: null, definition: null, revision: 0, course: null, items: [], recommendations: [], topics: [], createdAt: '', updatedAt: '' };
  setCached('goals:workspace', [saved]);
  const calls: string[] = [];
  const bridge = new Proxy({}, {
    get: (_target, name) => async () => { calls.push(String(name)); return name === 'listGoals' ? [saved] : []; },
  }) as GoalBridge;
  const getBridge = () => bridge;
  function Capture() {
    const goals = useGoals('workspace', getBridge);
    const pinned = usePinnedGoals(['workspace'], getBridge);
    assert(goals.goals.length === 0 && goals.pinned === null && pinned.size === 0, 'Saved experimental goals must remain hidden');
    return <div>Work items: {goals.workItems.length}</div>;
  }
  const container = document.createElement('div');
  document.body.append(container);
  const root = createRoot(container);
  try {
    flushSync(() => root.render(<Capture />));
    await settle();
    window.dispatchEvent(new CustomEvent(GOALS_CHANGED, { detail: 'workspace' }));
    await settle();
    assert(calls.length >= 2, 'Work items must load initially and after changes');
    assert(calls.every(call => call === 'listWorkItems'), `Production requested experimental data: ${calls.join(', ')}`);
    return true;
  }
  finally { root.unmount(); container.remove(); }
}
