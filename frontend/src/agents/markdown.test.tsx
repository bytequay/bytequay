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
import { renderMarkdown } from './markdown';

const html = (text: string): string => renderToStaticMarkup(<>{renderMarkdown(text)}</>);

describe('agent markdown', () => {
  it('renders the marks an answer uses instead of printing them', () => {
    const out = html('The project has **109 Maven modules**, defined in the root [pom.xml](/Users/me/worktrees/x/pom.xml:30).\n\nBreakdown: 3 client, `8 core`.');
    expect(out).toContain('<strong>109 Maven modules</strong>');
    expect(out).not.toContain('**');
    // A link to a file is its name, not a clickable path into the machine.
    expect(out).toContain('<code title="/Users/me/worktrees/x/pom.xml:30">pom.xml</code>');
    expect(out).not.toContain('<a');
    expect(out).toContain('<code>8 core</code>');
    expect(out.match(/<p>/g)).toHaveLength(2);
  });

  it('renders lists, headings, quotes, fenced code and web links', () => {
    const out = html('## Result\n- The scan now checks *only* the selection.\n- Added a __test__.\n\n1. first\n2. second\n\n> careful\n\n```\nmvn verify\n```\nSee [the docs](https://example.com/x).');
    expect(out).toContain('<p><strong>Result</strong></p>');
    expect(out).toContain('<ul><li>The scan now checks <em>only</em> the selection.</li><li>Added a <strong>test</strong>.</li></ul>');
    expect(out).toContain('<ol><li>first</li><li>second</li></ol>');
    expect(out).toContain('<blockquote>careful</blockquote>');
    expect(out).toContain('<pre class="agt-code">mvn verify</pre>');
    expect(out).toContain('<a href="https://example.com/x" target="_blank" rel="noreferrer">the docs</a>');
  });

  it('leaves plain prose alone, including asterisks that mark nothing', () => {
    expect(html('2 * 3 = 6 and a_b_c stays')).toBe('<div class="agt-md"><p>2 * 3 = 6 and a_b_c stays</p></div>');
  });
});
