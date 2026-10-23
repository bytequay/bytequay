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

  it('draws a mermaid fence as a diagram instead of printing its source as code', () => {
    const out = html('The call flow is:\n\n```mermaid\nflowchart TD\n    A --> B\n```');
    expect(out).toContain('<div class="md-mermaid">');
    expect(out).not.toContain('agt-code');
  });

  it('unwraps angle-delimited destinations with spaces and parentheses', () => {
    const out = html('See [Main.java:12](</Users/me/Application Support/worktree (review)/Main.java:12>) and [docs](<https://example.com/api(value)>).');
    expect(out).toContain('<code title="/Users/me/Application Support/worktree (review)/Main.java:12">Main.java:12</code>');
    expect(out).toContain('<a href="https://example.com/api(value)" target="_blank" rel="noreferrer">docs</a>');
  });

  it('links a GitHub pull request or issue as an in-app reference rather than a web page', () => {
    const out = html('Created draft [PR #31101](https://github.com/owner/repository/pull/31101) targeting `master` for [the flaky test](https://github.com/owner/repository/issues/7).');
    expect(out).toContain('<a class="md-reference is-pull" href="https://github.com/owner/repository/pull/31101"><svg');
    expect(out).toContain('</svg>PR #31101</a> targeting <code>master</code> for <a class="md-reference is-issue" href="https://github.com/owner/repository/issues/7"><svg');
    expect(out).toContain('</svg>the flaky test</a>.');
    expect(out).not.toContain('target="_blank"');
  });

  it('links a bare URL, as an in-app reference when it names a pull request or issue', () => {
    const out = html('https://github.com/owner/repository/issues/31072 (opened 2026-09-10) and https://example.com/docs.');
    expect(out).toContain('<a class="md-reference is-issue" href="https://github.com/owner/repository/issues/31072"><svg');
    expect(out).toContain('</svg>owner/repository <small>#31072</small></a> (opened 2026-09-10) and ');
    expect(out).toContain('<a href="https://example.com/docs" target="_blank" rel="noreferrer">https://example.com/docs</a>.');
  });

  it('links #number in the task repository the same way GitHub text does', () => {
    const out = renderToStaticMarkup(<>{renderMarkdown('Fixed in #1234, see **#7** and [#8](https://example.com).', 'owner/repository')}</>);
    expect(out).toMatch(/issues\/1234"><svg[^>]*>.*?<\/svg>#1234<\/a>/);
    expect(out).toMatch(/issues\/7"><svg[^>]*>.*?<\/svg>#7<\/a>/);
    expect(out.match(/md-reference/g)).toHaveLength(2);
    expect(html('Fixed in #1234')).not.toContain('md-reference');
  });

  it('leaves plain prose alone, including asterisks that mark nothing', () => {
    expect(html('2 * 3 = 6 and a_b_c stays')).toBe('<div class="agt-md"><p>2 * 3 = 6 and a_b_c stays</p></div>');
  });

  it('preserves saved instruction line breaks when requested without changing ordinary answers', () => {
    const text = 'Read the diff.\nKeep the existing question.\n\n## Report\n\n- Use `report_development_result`.\n- Keep it private.\n\n```\nfirst line\nsecond line\n```';
    const out = renderToStaticMarkup(<>{renderMarkdown(text, null, true)}</>);
    expect(out).toContain('<p>Read the diff.\nKeep the existing question.</p>');
    expect(out).toContain('<ul><li>Use <code>report_development_result</code>.</li><li>Keep it private.</li></ul>');
    expect(out).toContain('<pre class="agt-code">first line\nsecond line</pre>');
    expect(html(text)).toContain('<p>Read the diff. Keep the existing question.</p>');
  });

  it('renders a table as a table rather than one run-on paragraph', () => {
    const out = html('Commit order\n\n| Commit | Role |\n|---|:---:|\n| `60354c3a554` | Reattach the page source |\n| `07582ac4416` | Drop trivial overrides |\n\nDone.');
    expect(out).toContain('<th style="text-align:left">Commit</th><th style="text-align:center">Role</th>');
    expect(out).toContain('<td style="text-align:left"><code>60354c3a554</code></td><td style="text-align:center">Reattach the page source</td>');
    expect(out).toContain('<td style="text-align:center">Drop trivial overrides</td>');
    expect(out).toContain('<p>Done.</p>');
    expect(out).not.toContain('|');
  });

  it('renders commentary checklists as read-only boxes without creating a structured plan', () => {
    const out = html('- [x] Inspect **planner**\n- [ ] Implement fix\n- Ordinary note\n\n```\n- [ ] example\n```');
    expect(out).toContain('type="checkbox" disabled="" aria-label="Inspect **planner**" checked=""');
    expect(out).toContain('type="checkbox" disabled="" aria-label="Implement fix"');
    expect(out).toContain('<strong>planner</strong>');
    expect(out).toContain('<li>Ordinary note</li>');
    expect(out).toContain('<pre class="agt-code">- [ ] example</pre>');
    expect(out).not.toContain('agt-plan');
  });
});
