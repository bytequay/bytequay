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
import type { ReactNode } from 'react';
import type { StageConversationRow } from '../types/brainView';
import { EventRow, ToolBlock, UserMsg } from '../ui/conv';

/**
 * Renders one stage-transcript row into a V3 conversation element. Shared
 * by the stage detail page and the code-diff page's conversation column so
 * both show an identical transcript.
 */
export function stageRow(r: StageConversationRow): ReactNode {
  switch (r.kind) {
    case 'user':
      return <UserMsg key={r.id} text={r.text ?? ''} />;
    case 'agent':
      return <EventRow key={r.id} kind="agent" who="Agent" markdown={r.text ?? ''} />;
    case 'iteration_marker':
      return <EventRow key={r.id} kind="system" who={`Iteration ${r.iterationNumber ?? ''}`} />;
    case 'tool_call':
      return (
        <EventRow key={r.id} kind="agent" who="Agent">
          <ToolBlock tag={r.toolTag ?? 'tool'} desc={r.toolLabel ?? r.toolDetail ?? ''}>
            {r.toolResult ?? r.toolDiff ?? undefined}
          </ToolBlock>
        </EventRow>
      );
    default:
      return null;
  }
}
