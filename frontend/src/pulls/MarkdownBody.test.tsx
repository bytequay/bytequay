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
import MarkdownBody, { CodeReferenceCard, markdownBlocks, parseGitHubCodePermalink, toggleMarkdownTask } from './MarkdownBody';

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

  it('keeps tasks read-only unless a task handler is supplied', () => {
    const body = '- [ ] First\n\n<details><summary>More</summary>\n- [x] Nested\n</details>';
    const readOnly = renderToStaticMarkup(<MarkdownBody body={body} />);
    const interactive = renderToStaticMarkup(<MarkdownBody body={body} onTaskToggle={() => {}} />);
    const disabled = renderToStaticMarkup(<MarkdownBody body={body} onTaskToggle={() => {}} tasksDisabled />);

    expect(readOnly.match(/disabled=""/g)).toHaveLength(2);
    expect(readOnly.match(/readOnly=""/g)).toHaveLength(2);
    expect(interactive.match(/data-markdown-task=""/g)).toHaveLength(2);
    expect(interactive).not.toContain('disabled=""');
    expect(interactive).not.toContain('readOnly=""');
    expect(disabled.match(/disabled=""/g)).toHaveLength(2);
  });

  it('toggles tasks by rendered ordinal while preserving source formatting', () => {
    const body = '- [ ] Duplicate\r\n'
      + '```markdown\r\n- [ ] Fenced fake\r\n```\r\n'
      + '<!--\r\n- [ ] Comment fake\r\n-->\r\n'
      + '<details>\r\n<summary>More</summary>\r\n(*) Duplicate\r\n</details>\r\n'
      + '+ [X] Last\r\n';

    expect(toggleMarkdownTask(body, 1, false)).toBe(
      '- [ ] Duplicate\r\n'
      + '```markdown\r\n- [ ] Fenced fake\r\n```\r\n'
      + '<!--\r\n- [ ] Comment fake\r\n-->\r\n'
      + '<details>\r\n<summary>More</summary>\r\n( ) Duplicate\r\n</details>\r\n'
      + '+ [X] Last\r\n');
    expect(toggleMarkdownTask(body, 2, false)).toContain('+ [ ] Last\r\n');
    expect(toggleMarkdownTask(body, 0, true)).toContain('- [x] Duplicate\r\n');
  });

  it('preserves task marker families and ignores non-task source', () => {
    expect(toggleMarkdownTask('( ) Release note', 0, true)).toBe('(*) Release note');
    expect(toggleMarkdownTask('(&#x2713;) Release note', 0, false)).toBe('( ) Release note');
    expect(toggleMarkdownTask('<!-- keep --> - [ ] Ship', 0, true)).toBe('<!-- keep --> - [x] Ship');
    expect(toggleMarkdownTask('* [X] Ship', 0, true)).toBe('* [X] Ship');
    expect(toggleMarkdownTask('~~~markdown\n- [ ] Fake\n~~~\n- [ ] Real', 0, true))
      .toBe('~~~markdown\n- [ ] Fake\n~~~\n- [x] Real');
    expect(toggleMarkdownTask('- [ ] Untouched', 4, true)).toBe('- [ ] Untouched');
    expect(toggleMarkdownTask('- [ ] Untouched', -1, true)).toBe('- [ ] Untouched');
  });

  it('does not count an empty task marker that the renderer omits', () => {
    const body = '- [ ] \n- [ ] Visible';

    expect(toggleMarkdownTask(body, 0, true)).toBe('- [ ] \n- [x] Visible');
  });

  it('keeps quoted tasks read-only and outside interactive task ordinals', () => {
    const body = '> - [ ] Quoted\n\n- [ ] Visible';
    const html = renderToStaticMarkup(<MarkdownBody body={body} onTaskToggle={() => {}} />);

    expect(html.match(/data-markdown-task=""/g)).toHaveLength(1);
    expect(html.match(/disabled=""/g)).toHaveLength(1);
    expect(toggleMarkdownTask(body, 0, true)).toBe('> - [ ] Quoted\n\n- [x] Visible');
  });

  it('preserves an inline HTML comment when toggling a task', () => {
    expect(toggleMarkdownTask('- [ ] Ship <!-- keep -->', 0, true))
      .toBe('- [x] Ship <!-- keep -->');
  });

  it('does not let HTML-like fenced code hide a later task', () => {
    const body = '```html\n<!-- unfinished\n```\n- [ ] Visible';

    expect(toggleMarkdownTask(body, 0, true)).toBe('```html\n<!-- unfinished\n```\n- [x] Visible');
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

  it('preserves nested Markdown inside undefined references', () => {
    const html = renderToStaticMarkup(<MarkdownBody body={'Keep [**important**], [`code`], and [**full**][unknown].'} />);

    expect(html).toContain('[<strong>important</strong>]');
    expect(html).toContain('[<code>code</code>]');
    expect(html).toContain('[<strong>full</strong>][unknown]');
  });

  it('renders escaped backticks as literal delimiters', () => {
    const html = renderToStaticMarkup(<MarkdownBody body={'Use \\`ClickHouseClient.toColumnMapping()\\`.'} />);

    expect(html).toBe('<div class="markdown-body"><p>Use `ClickHouseClient.toColumnMapping()`.</p></div>');
  });

  it('preserves generic types inside inline and fenced code', () => {
    const releaseNote = '## SPI\n* {{breaking}} Change return type of `ConnectorMetadata.finishMerge` method '
      + 'to `Optional<ConnectorOutputMetadata>`. ({issue}`30934`)';

    const inline = renderToStaticMarkup(<MarkdownBody body={releaseNote} />);
    const fenced = renderToStaticMarkup(<MarkdownBody body={`\`\`\`markdown\n${releaseNote}\n\`\`\``} />);

    expect(inline).toContain('<code>Optional&lt;ConnectorOutputMetadata&gt;</code>');
    expect(fenced).toContain('Optional&lt;ConnectorOutputMetadata&gt;');
  });

  it.each(['sql', 'unknown', ''])('highlights fenced code with language %j', language => {
    const html = renderToStaticMarkup(<MarkdownBody body={`\`\`\`${language}\nSELECT * FROM users WHERE id = 1;\n\`\`\``} />);

    expect(html).toContain('<span class="hljs-keyword">SELECT</span>');
  });

  it('escapes HTML-looking source while highlighting it', () => {
    const html = renderToStaticMarkup(<MarkdownBody body={"```sql\nSELECT '<script>' AS value;\n```"} />);

    expect(html).toContain('&lt;script&gt;');
    expect(html).not.toContain('<script>');
  });

  it('links bare URLs in comments', () => {
    const url = 'https://github.com/apache/avro/blob/release-1.12.2/lang/java/avro/src/main/java/org/apache/avro/util/ClassSecurityValidator.java';

    const html = renderToStaticMarkup(<MarkdownBody body={`See ${url}. This validator matters.`} />);

    expect(html).toContain(`<a href="${url}">${url}</a>. This validator matters.`);
    expect(html).not.toContain(`href="${url}."`);
  });

  it('hides HTTP link definitions and resolves their references across details', () => {
    const definition = '[HBR-1234]: https://example.atlassian.net/browse/HBR-1234?atlOrigin=abc';
    const body = 'See [HBR-1234], [ticket][HBR-1234], and [HBR-1234][].\n\n'
      + '<details><summary>More</summary>[hbr-1234]</details>\n\n'
      + definition;

    const html = renderToStaticMarkup(<MarkdownBody body={body} />);

    expect(html.match(/href="https:\/\/example\.atlassian\.net\/browse\/HBR-1234\?atlOrigin=abc"/g)).toHaveLength(4);
    expect(html).toContain('>ticket</a>');
    expect(html).toContain('<details class="md-details"><summary>More</summary>');
    expect(html).not.toContain('[HBR-1234]:');
    expect(renderToStaticMarkup(<MarkdownBody body={`Review summary.\n${definition}`} />)).toContain('[HBR-1234]:');
    expect(renderToStaticMarkup(<MarkdownBody body={`\`\`\`markdown\n${definition}\n\`\`\``} />)).toContain(definition);
  });

  it('renders the reported inline same-repository code permalink as a code card', () => {
    const url = 'https://github.com/trinodb/trino/blob/4bfd15e6e1cd4b4a5c838c6065d22151d7d044aa/plugin/trino-bigquery/src/main/java/io/trino/plugin/bigquery/BigQueryMetadata.java#L796';
    const body = `the place ${url} has similar logic, we could include in this pr`;

    const html = renderToStaticMarkup(<MarkdownBody body={body} repository="trinodb/trino" />);

    expect(html).toContain('<p>the place</p><div class="md-code-reference">');
    expect(html).toContain('trino/plugin/trino-bigquery/src/main/java/io/trino/plugin/bigquery/BigQueryMetadata.java');
    expect(html).toContain('Line 796');
    expect(html).toContain('4bfd15e');
    expect(html).toContain('Loading code…');
    expect(html).toContain('</div><p>has similar logic, we could include in this pr</p>');
  });

  it('renders resolved source lines and restores the ordinary link on failure', () => {
    const url = 'https://github.com/trinodb/trino/blob/4bfd15e6e1cd4b4a5c838c6065d22151d7d044aa/src/Main.java#L10-L12';
    const reference = parseGitHubCodePermalink(url, 'trinodb/trino');
    expect(reference).not.toBeNull();
    if (!reference) throw new Error('expected a code permalink');

    const loaded = renderToStaticMarkup(<CodeReferenceCard reference={reference} snippet={{ lines: [
      { number: 10, content: '    try (JsonStreamWriter writer = open("https://example.com")) { // retry' },
      { number: 11, content: '        return writer;' },
      { number: 12, content: '    }' },
    ] }} />);
    const failed = renderToStaticMarkup(<CodeReferenceCard reference={reference} snippet={null} />);

    expect(loaded).toContain('<span class="md-code-keyword">try</span>');
    expect(loaded).toContain('<span class="md-code-type">JsonStreamWriter</span>');
    expect(loaded).toContain('<span class="md-code-string">&quot;https://example.com&quot;</span>');
    expect(loaded).toContain('<span class="md-code-comment">// retry</span>');
    expect(loaded).toContain('<span>11</span><code>    <span class="md-code-keyword">return</span> writer;</code>');
    expect(loaded).toContain('<span>12</span><code>}</code>');
    expect(loaded).not.toContain('Loading code…');
    expect(failed).toBe(`<p><a href="${url}">${url}</a></p>`);
  });

  it('parses a bounded code line range', () => {
    const url = 'https://github.com/trinodb/trino/blob/4bfd15e6e1cd4b4a5c838c6065d22151d7d044aa/src/Main.java#L10-L12';

    expect(markdownBlocks(url, 'TRINODB/TRINO')).toEqual([{
      kind: 'code-reference',
      reference: {
        href: url,
        owner: 'trinodb',
        repo: 'trino',
        commit: '4bfd15e6e1cd4b4a5c838c6065d22151d7d044aa',
        path: 'src/Main.java',
        startLine: 10,
        endLine: 12,
      },
    }]);
  });

  it.each([
    ['foreign repository', 'https://github.com/other/trino/blob/4bfd15e6e1cd4b4a5c838c6065d22151d7d044aa/src/Main.java#L10'],
    ['branch reference', 'https://github.com/trinodb/trino/blob/master/src/Main.java#L10'],
    ['missing line anchor', 'https://github.com/trinodb/trino/blob/4bfd15e6e1cd4b4a5c838c6065d22151d7d044aa/src/Main.java'],
  ])('keeps a %s as an ordinary link', (_label, url) => {
    const html = renderToStaticMarkup(<MarkdownBody body={url} repository="trinodb/trino" />);

    expect(html).toContain(`<a href="${url}">${url}</a>`);
    expect(html).not.toContain('md-code-reference');
  });

  it('does not promote labeled, backticked, fenced, or punctuation-adjacent code permalinks', () => {
    const url = 'https://github.com/trinodb/trino/blob/4bfd15e6e1cd4b4a5c838c6065d22151d7d044aa/src/Main.java#L10';
    const bodies = [
      `[source](${url})`,
      `\`${url}\``,
      `\`\`\`text\n${url}\n\`\`\``,
      `See ${url}.`,
      `See (${url}) here.`,
    ];

    for (const body of bodies) {
      const html = renderToStaticMarkup(<MarkdownBody body={body} repository="trinodb/trino" />);
      expect(html).not.toContain('md-code-reference');
    }
  });

  it('does not emit empty paragraphs when an inline code permalink starts or ends the prose', () => {
    const url = 'https://github.com/trinodb/trino/blob/4bfd15e6e1cd4b4a5c838c6065d22151d7d044aa/src/Main.java#L10';

    expect(markdownBlocks(`${url} after`, 'trinodb/trino').map(block => block.kind))
      .toEqual(['code-reference', 'paragraph']);
    expect(markdownBlocks(`before ${url}`, 'trinodb/trino').map(block => block.kind))
      .toEqual(['paragraph', 'code-reference']);
  });

  it('preserves repository context inside quoted Markdown', () => {
    const url = 'https://github.com/trinodb/trino/blob/4bfd15e6e1cd4b4a5c838c6065d22151d7d044aa/src/Main.java#L10';

    const html = renderToStaticMarkup(<MarkdownBody body={`> ${url}`} repository="trinodb/trino" />);

    expect(html).toContain('<blockquote><div class="md-code-reference">');
    expect(html).toContain('Line 10');
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
