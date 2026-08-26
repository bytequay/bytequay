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
import MarkdownBody, { markdownBlocks } from './MarkdownBody';

describe('MarkdownBody', () => {
  it('renders checkbox and unordered-list markers', () => {
    const body = '( ) Parenthesized unchecked\n'
      + '(*) Parenthesized asterisk checked\n'
      + '[x] Square checked\n'
      + '- [x] Dash checked\n'
      + '* [ ] Asterisk unchecked\n\n'
      + '- Dash item\n'
      + '* Asterisk item';

    expect(markdownBlocks(body)).toEqual([
      { kind: 'task', checked: false, text: 'Parenthesized unchecked' },
      { kind: 'task', checked: true, text: 'Parenthesized asterisk checked' },
      { kind: 'task', checked: true, text: 'Square checked' },
      { kind: 'task', checked: true, text: 'Dash checked' },
      { kind: 'task', checked: false, text: 'Asterisk unchecked' },
      { kind: 'list', ordered: false, items: ['Dash item', 'Asterisk item'] },
    ]);

    const html = renderToStaticMarkup(<MarkdownBody body={body} />);
    expect(html.match(/type="checkbox"/g)).toHaveLength(5);
    expect(html.match(/checked=""/g)).toHaveLength(3);
    expect(html).toContain('<ul><li>Dash item</li><li>Asterisk item</li></ul>');
  });

  it('renders an encoded checkmark as a checked task', () => {
    const html = renderToStaticMarkup(<MarkdownBody body="(&#x2713;) Release notes are required." />);

    expect(html.match(/type="checkbox"/g)).toHaveLength(1);
    expect(html.match(/checked=""/g)).toHaveLength(1);
    expect(html).not.toContain('x2713');
  });

  it('renders code and links nested inside bold text', () => {
    const body = '- **`RedirectionAwareViewHandle`** — record for view redirection\n'
      + '- **[`MetadataManager.getRedirectedViewName()`](cci:1://file:///src/MetadataManager.java:2061:4-2102:5)** — redirect resolution';

    const html = renderToStaticMarkup(<MarkdownBody body={body} />);
    expect(html).toContain('<strong><code>RedirectionAwareViewHandle</code></strong>');
    expect(html).toContain('<strong><a href="cci:1://file:///src/MetadataManager.java:2061:4-2102:5"><code>MetadataManager.getRedirectedViewName()</code></a></strong>');
    expect(html).not.toContain('`RedirectionAwareViewHandle`');
    expect(html).not.toContain('](cci:1://');
  });

  it('links bare URLs in comments', () => {
    const url = 'https://github.com/apache/avro/blob/release-1.12.2/lang/java/avro/src/main/java/org/apache/avro/util/ClassSecurityValidator.java';

    const html = renderToStaticMarkup(<MarkdownBody body={`See ${url}. This validator matters.`} />);

    expect(html).toContain(`<a href="${url}">${url}</a>. This validator matters.`);
    expect(html).not.toContain(`href="${url}."`);
  });

  it('renders quoted replies as continuous nested blockquotes', () => {
    const body = '> I read the execution flow:\n'
      + '> \n'
      + '> > The coordinator aggregates deletion vectors —\n'
      + '> this is necessary because multiple workers may process the same file.\n'
      + '> CopyOnWriteFileRewriter writes the surviving rows.\n'
      + '> \n'
      + '> I suspect the rewriting is happening on the coordinator.\n\n'
      + '@chenjian2664 I moved the work to workers.';

    const html = renderToStaticMarkup(<MarkdownBody body={body} />);

    expect(html.match(/<blockquote>/g)).toHaveLength(2);
    expect(html).toContain('<blockquote><p>I read the execution flow:</p><blockquote><p>The coordinator aggregates deletion vectors — this is necessary because multiple workers may process the same file. CopyOnWriteFileRewriter writes the surviving rows.</p></blockquote><p>I suspect the rewriting is happening on the coordinator.</p></blockquote>');
    expect(html).not.toContain('&gt; The coordinator');
  });
});
