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
import { afterEach, expect, it, vi } from 'vitest';
import type { LabTask } from '@bytequay/lab/contracts';
import { listenForLabTasks } from './lab';
import { settingsRoute } from './settings/routes';
import { workspaceFixture } from './workspaces/fixtures/workspace';

const started = vi.hoisted(() => vi.fn());
vi.mock('./workspaces/epics', () => ({ startEpicWork: started }));

afterEach(() => { vi.unstubAllGlobals(); started.mockReset(); });

it('opens a task from the dashboard in the workspace that watches its repository, whatever the case', async () => {
  let deliver: (task: LabTask) => void = () => {};
  const stop = vi.fn();
  const location = { hash: '#/recent' };
  vi.stubGlobal('window', { location, bridge: {
    onLabTask: (callback: (task: LabTask) => void) => { deliver = callback; return stop; },
    listWorkspaces: async () => [workspaceFixture({ id: 'trino', monitoredOwner: 'trinodb', monitoredRepo: 'trino' }),
      workspaceFixture({ id: 'bytequay', monitoredOwner: 'ByteQuay', monitoredRepo: 'ByteQuay' })],
  } });
  const unsubscribe = listenForLabTasks();
  deliver({ repository: 'bytequay/bytequay', text: 'Shorten it' });
  await vi.waitFor(() => expect(started).toHaveBeenCalledWith({ workspaceId: 'bytequay', draft: 'Shorten it' }));
  deliver({ repository: 'apache/iceberg', text: 'Shorten it' });
  await vi.waitFor(() => expect(location.hash).toBe(settingsRoute('lab')));
  expect(started).toHaveBeenCalledOnce();
  unsubscribe();
  expect(stop).toHaveBeenCalledOnce();
});
