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
import type { TaskBrainViewData } from '../../types/brainView';
import { MOCK_BRAIN_VIEW } from './brainViewFixture';

/**
 * Mock implementation. Replace with a real fetch against the brain
 * endpoint (`GET /api/tasks/{taskId}/brain`, via `window.bridge`) in a
 * follow-up commit once the backend read endpoints land. The return
 * type is the shared, locked {@link TaskBrainViewData} so consumers
 * don't change when the implementation swaps — only this file does.
 *
 * The fixture is task-independent for now, so `taskId` is accepted to
 * keep the call site stable but isn't yet used to select data.
 */
export function useBrainViewData(_taskId: string): TaskBrainViewData {
  return MOCK_BRAIN_VIEW;
}
