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

import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { AGENT_MARK_FAMILY, AGENT_MARK_PALETTE, AgentMark, agentMark } from './AgentMark';

describe('agent mark', () => {
  it('picks the same shape and colour the design does for the fixture agents', () => {
    // Pinned from the design's own hash, so a mark here matches the mark in the prototype.
    expect(agentMark('Preview backend')).toEqual({ shape: 'flower', hue: 0 });
    expect(agentMark('Renderer range')).toEqual({ shape: 'feather', hue: 2 });
    expect(agentMark('History tests')).toEqual({ shape: 'acorn', hue: 1 });
    expect(agentMark('Range probe')).toEqual({ shape: 'sparkle', hue: 4 });
    expect(agentMark('count_dirs')).toEqual({ shape: 'butterfly', hue: 7 });
  });

  it('is deterministic and spreads names across the family', () => {
    const names = Array.from({ length: 40 }, (_, index) => `agent-${index}`);
    const marks = names.map((name) => `${agentMark(name).shape}/${agentMark(name).hue}`);
    expect(marks).toEqual(names.map((name) => `${agentMark(name).shape}/${agentMark(name).hue}`));
    expect(new Set(marks).size).toBeGreaterThan(20);
    for (const name of names) {
      expect(AGENT_MARK_FAMILY).toContain(agentMark(name).shape);
      expect(agentMark(name).hue).toBeLessThan(AGENT_MARK_PALETTE.length);
    }
  });

  it('renders an inline glyph in its colour, grey when the agent has expired', () => {
    const live = renderToStaticMarkup(<AgentMark name="Preview backend" />);
    expect(live).toContain('data-shape="flower"');
    expect(live).toContain('color:#0969da');
    expect(live).toContain('aria-label="Preview backend — flower mark"');
    expect(live).toContain('width="15"');
    expect(renderToStaticMarkup(<AgentMark name="Preview backend" muted size={16} />))
      .toContain('color:#8c959f');
  });
});
