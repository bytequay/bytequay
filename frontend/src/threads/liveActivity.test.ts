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
import { updateLiveActivities } from './liveActivity';

describe('updateLiveActivities', () => {
  it('shows a shell command immediately and clears it once the turn ends', () => {
    const started = updateLiveActivities([], {
      name: 'ToolCallStarted',
      data: {
        callId: 'call-1', toolName: 'command_execution',
        inputJson: '{"command":"git log --oneline -8"}', timestamp: '2026-07-15T12:00:00Z',
      },
    });
    expect(started).toEqual([expect.objectContaining({
      label: 'Running command', detail: 'git log --oneline -8', done: false,
    })]);

    const completed = updateLiveActivities(started, {
      name: 'ToolCallDone', data: { callId: 'call-1', isError: false },
    });
    expect(completed[0]).toEqual(expect.objectContaining({ done: true, failed: false }));
    expect(updateLiveActivities(completed, { name: 'TurnDone', data: {} })).toEqual([]);
  });

  it('shows a search by its pattern, not the directory it was scoped to', () => {
    const rows = updateLiveActivities([], {
      name: 'ToolCallStarted',
      data: {
        callId: 'call-2', toolName: 'Grep',
        inputJson: '{"pattern":"CodeGraphService","path":"backend/src"}',
        timestamp: '2026-07-15T12:00:00Z',
      },
    });
    expect(rows).toEqual([expect.objectContaining({
      label: 'Searching', detail: 'CodeGraphService',
    })]);
  });

  it('reports a read by its file argument', () => {
    const rows = updateLiveActivities([], {
      name: 'ToolCallStarted',
      data: {
        callId: 'call-3', toolName: 'Read',
        inputJson: '{"file_path":"backend/src/main/java/Foo.java"}',
        timestamp: '2026-07-15T12:00:00Z',
      },
    });
    expect(rows).toEqual([expect.objectContaining({
      label: 'Reading', detail: 'backend/src/main/java/Foo.java',
    })]);
  });
});
