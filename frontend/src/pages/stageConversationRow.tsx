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

/** A blank-safe trim: empty/whitespace strings count as absent. */
function nonBlank(s: string | null): string | null {
  return s !== null && s.trim().length > 0 ? s.trim() : null;
}

/** The tool-block description: the tool name plus its command / target
 *  (the Bash command, the edited file, the search pattern…) so the row
 *  shows what actually ran, not just "Bash" — and never renders blank
 *  (which read as an empty line). */
function toolDesc(label: string | null, detail: string | null): ReactNode {
  const name = nonBlank(label);
  const arg = nonBlank(detail);
  if (name === null && arg === null) return 'Tool call';
  if (arg === null) return name;
  if (name === null) return <span className="tool-arg">{arg}</span>;
  return <>{name} <span className="tool-arg">{arg}</span></>;
}

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
      // No "Agent" who-row — tool calls render as bare blocks so a run of
      // them doesn't repeat the redundant agent header on every line. Tag
      // falls back to "Tool" so the block never collapses to a blank line.
      return (
        <ToolBlock key={r.id} tag={nonBlank(r.toolTag) ?? 'Tool'} desc={toolDesc(r.toolLabel, r.toolDetail)}>
          {r.toolResult ?? r.toolDiff ?? undefined}
        </ToolBlock>
      );
    default:
      return null;
  }
}
