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
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { StageConversationRow } from '../types/brainView';
import { stageRow } from './stageConversationRow';

afterEach(cleanup);

function row(over: Partial<StageConversationRow>): StageConversationRow {
  return {
    id: 'r1', kind: 'tool_call', text: null,
    toolTag: 'Run', toolLabel: 'Bash', toolDetail: null,
    toolResult: null, toolError: null, toolDiff: null, iterationNumber: null,
    ...over,
  } as StageConversationRow;
}

describe('stageRow tool_call', () => {
  it('prints the command after the tool name', () => {
    const { container } = render(<>{stageRow(row({ toolDetail: 'mvnd airstyle:format' }))}</>);
    expect(screen.getByText('mvnd airstyle:format')).toBeTruthy();
    expect(container.querySelector('.tool-block')).toBeTruthy();
    // The command is the literal arg span, not the tool label.
    expect(container.querySelector('.tool-arg')?.textContent).toBe('mvnd airstyle:format');
  });

  it('renders the tool block bare — no redundant Agent who-row', () => {
    const { container } = render(<>{stageRow(row({ toolDetail: 'ls -la' }))}</>);
    // No EventRow wrapper (which would add the "Agent" who-row).
    expect(container.querySelector('.ev')).toBeNull();
    expect(screen.queryByText('Agent')).toBeNull();
  });

  it('falls back to the tool name when there is no command', () => {
    render(<>{stageRow(row({ toolLabel: 'Read', toolDetail: null }))}</>);
    expect(screen.getByText('Read')).toBeTruthy();
  });

  it('never renders a blank line — blank tag/label fall back to Tool / Tool call', () => {
    const { container } = render(
      <>{stageRow(row({ toolTag: '', toolLabel: '', toolDetail: '' }))}</>,
    );
    expect(container.querySelector('.tool-block')).toBeTruthy();
    expect(screen.getByText('Tool')).toBeTruthy();
    expect(screen.getByText('Tool call')).toBeTruthy();
  });
});
