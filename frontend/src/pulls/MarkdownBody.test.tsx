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
import { prDetail } from './fixtures/pullRequest';
import MarkdownBody, { changedSpan, CodeReferenceCard, linkIssueNumbers, markdownBlocks, parseGitHubCodePermalink, referenceCardItem, toggleMarkdownTask } from './MarkdownBody';

describe('suggested changes', () => {
  it('spans the differing middle between a shared prefix and a non-overlapping suffix', () => {
    const before = '        .collect(toImmutableSet())';
    const after = '        .distinct()';
    const span = changedSpan(before, after);

    expect(span).toEqual({ prefix: 9, suffix: 1 });
    expect(before.slice(9, before.length - 1)).toBe('collect(toImmutableSet()');
    expect(after.slice(9, after.length - 1)).toBe('distinct(');
    expect(changedSpan('aa', 'aaa')).toEqual({ prefix: 2, suffix: 0 });
    expect(changedSpan('same', 'same')).toBeNull();
    expect(changedSpan('abc', 'xyz')).toBeNull();
  });

  it('lists every original line as removed, then every suggested line as added, with changed parts marked', () => {
    const html = renderToStaticMarkup(<MarkdownBody
      body={'Prefer this:\n\n```suggestion\n        .distinct()\n        .toList();\n```'}
      suggestion={{ startLine: 12, lines: ['        .collect(toImmutableSet())', '        .toList();'] }}
    />);

    expect(html).toContain('<div class="md-suggestion"><div class="md-suggestion__header">Suggested change</div>'
      + '<table class="md-suggestion__rows" aria-label="Suggested change">');
    expect(html).toContain('<tbody>'
      + '<tr class="md-suggestion__row is-del"><td class="md-suggestion__number">12</td><td class="md-suggestion__code"><span>−</span>        .<mark>collect(toImmutableSet()</mark>)</td></tr>'
      + '<tr class="md-suggestion__row is-del"><td class="md-suggestion__number">13</td><td class="md-suggestion__code"><span>−</span>        .toList();</td></tr>'
      + '<tr class="md-suggestion__row is-add"><td class="md-suggestion__number">12</td><td class="md-suggestion__code"><span>+</span>        .<mark>distinct(</mark>)</td></tr>'
      + '<tr class="md-suggestion__row is-add"><td class="md-suggestion__number">13</td><td class="md-suggestion__code"><span>+</span>        .toList();</td></tr>'
      + '</tbody>');
    expect(html).not.toContain('md-code-block');
  });

  it('marks nothing when the line counts differ', () => {
    const html = renderToStaticMarkup(<MarkdownBody body={'```suggestion\nint a = 2;\n```'}
      suggestion={{ startLine: 3, lines: ['int a = 1;', 'int b = 1;'] }} />);

    expect(html.match(/is-del/g)).toHaveLength(2);
    expect(html.match(/is-add/g)).toHaveLength(1);
    expect(html).not.toContain('<mark>');
  });

  it('renders an empty suggestion as a deletion and unresolved original lines as additions only', () => {
    const deletion = renderToStaticMarkup(<MarkdownBody body={'```suggestion\n```'}
      suggestion={{ startLine: 7, lines: ['remove me'] }} />);
    const unresolved = renderToStaticMarkup(<MarkdownBody body={'```Suggestion\nfirst\nsecond\n```'}
      suggestion={{ startLine: 7, lines: null }} />);

    expect(deletion).toContain('<td class="md-suggestion__number">7</td><td class="md-suggestion__code"><span>−</span>remove me</td>');
    expect(deletion).not.toContain('is-add');
    expect(unresolved).not.toContain('is-del');
    expect(unresolved).toContain('<td class="md-suggestion__number">7</td><td class="md-suggestion__code"><span>+</span>first</td>');
    expect(unresolved).toContain('<td class="md-suggestion__number">8</td><td class="md-suggestion__code"><span>+</span>second</td>');
  });

  it('keeps a suggestion block as plain code without a base and inside quotes, but not inside details', () => {
    const body = '```suggestion\nreturn value;\n```';
    const base = { startLine: 1, lines: ['return null;'] };

    expect(renderToStaticMarkup(<MarkdownBody body={body} />)).toContain('<div class="md-code-block"><span>suggestion</span>');
    expect(renderToStaticMarkup(<MarkdownBody body={body} />)).not.toContain('md-suggestion');
    expect(renderToStaticMarkup(<MarkdownBody body={`> ${body.replaceAll('\n', '\n> ')}`} suggestion={base} />)).not.toContain('md-suggestion');
    expect(renderToStaticMarkup(<MarkdownBody body={`<details><summary>Fix</summary>\n\n${body}\n</details>`} suggestion={base} />))
      .toContain('Suggested change');
  });
});

const pullRequestDetail = prDetail({
  repo: 'owner/repository', number: 30611, title: 'Handle IPv6 zone ids in Web UI task URIs', htmlUrl: null,
  mergeable: null, mergeableState: null, author: 'wren-d', baseRef: 'master', headRef: 'ipv6',
  createdAt: '2026-08-27T10:00:00Z', updatedAt: '2026-09-05T12:00:00Z',
  body: 'Fixes the zone id.', additions: 2, deletions: 1,
});

describe('MarkdownBody', () => {
  it.each(['open', 'closed'])('maps an %s issue reference onto the shared card', state => {
    const item = referenceCardItem(
      { kind: 'issue', owner: 'owner', repo: 'repository', number: 30611, href: 'https://github.com/owner/repository/issues/30611' },
      {
        kind: 'issue',
        workspaceId: null,
        issue: {
          title: 'Kerberos Hive container startup times out',
          body: 'The **container** never starts.',
          author: 'octocat',
          labels: ['bug'],
          updatedAt: '2026-09-05T12:00:00Z',
          state,
        },
      },
    );

    expect(item).toMatchObject({
      type: 'issue',
      repo: 'owner/repository',
      number: 30611,
      title: 'Kerberos Hive container startup times out',
      author: 'octocat',
      labels: ['bug'],
    });
    expect(item.chip?.label).toBe(state === 'open' ? 'Open' : 'Closed');
  });

  it('reads a pull request reference from the detail, merged state included', () => {
    const reference = { kind: 'pull' as const, owner: 'owner', repo: 'repository', number: 30611, href: 'https://github.com/owner/repository/pull/30611' };
    const detail = { ...pullRequestDetail, state: 'closed', mergedAt: '2026-09-06T09:00:00Z', labels: [{ name: 'iceberg', color: null as string | null }] };

    expect(referenceCardItem(reference, { kind: 'pull', detail })).toMatchObject({
      type: 'pr',
      title: 'Handle IPv6 zone ids in Web UI task URIs',
      author: 'wren-d',
      labels: ['iceberg'],
    });
    expect(referenceCardItem(reference, { kind: 'pull', detail }).chip?.label).toBe('Merged');
    expect(referenceCardItem(reference, { kind: 'pull', detail: { ...pullRequestDetail, draft: true } }).chip?.label).toBe('Draft');
    expect(referenceCardItem(reference, undefined).title).toBe('owner/repository #30611');
  });

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

  it('continues list items and tasks across wrapped lines until a blank line', () => {
    const body = '* Add `$spec_id` hidden column with the partition specification id of each\r\n'
      + "  row's file, and allow `optimize`\r\n"
      + 'with a lazy line.\r\n'
      + '- [ ] Wrapped\r\n'
      + '  task\r\n'
      + '\r\n'
      + 'After the list\r\n'
      + 'stays a paragraph';

    expect(markdownBlocks(body)).toEqual([
      { kind: 'list', ordered: false, items: ["Add `$spec_id` hidden column with the partition specification id of each\nrow's file, and allow `optimize`\nwith a lazy line."] },
      { kind: 'task', checked: false, text: 'Wrapped\ntask' },
      { kind: 'paragraph', text: 'After the list\nstays a paragraph' },
    ]);
  });

  it('renders the reported description with indented code and GitHub line breaks', () => {
    const body = '## Description\r\n\r\n'
      + 'With `optimize_metadata_queries=true`, filtering on a hidden column fails to plan\r\n'
      + 'on any identity-partitioned table:\r\n\r\n'
      + '    SET SESSION optimize_metadata_queries = true;\r\n'
      + '    SELECT DISTINCT b, c FROM t WHERE "$path" IS NOT NULL;\r\n'
      + '    -- IllegalArgumentException: Constraint on an unexpected column 2147483646:$path:varchar\r\n\r\n'
      + '`getTableProperties` passes the raw enforced predicate to `toIcebergExpression`\r\n'
      + 'when building the `DiscretePredicates` scan.\r\n\r\n'
      + '## Iceberg\r\n'
      + '* Fix query failure when `optimize_metadata_queries` is enabled and the query\r\n'
      + '  filters on `$path`.';

    const html = renderToStaticMarkup(<MarkdownBody body={body} />);

    expect(html).toContain('<p>With <code>optimize_metadata_queries=true</code>, filtering on a hidden column fails to plan<br/>on any identity-partitioned table:</p>'
      + '<div class="md-code-block"><span>Plain text</span>');
    expect(html.match(/<pre>[^]*<\/pre>/)?.[0].replace(/<[^>]+>/g, '')).toBe(renderToStaticMarkup(<>{
      'SET SESSION optimize_metadata_queries = true;\n'
      + 'SELECT DISTINCT b, c FROM t WHERE "$path" IS NOT NULL;\n'
      + '-- IllegalArgumentException: Constraint on an unexpected column 2147483646:$path:varchar'
    }</>));
    expect(html).toContain('</div><p><code>getTableProperties</code> passes the raw enforced predicate to <code>toIcebergExpression</code><br/>when building');
    expect(html).toContain('<li>Fix query failure when <code>optimize_metadata_queries</code> is enabled and the query<br/>filters on <code>$path</code>.</li>');
    expect(html).not.toContain('<em>');
  });

  it('renders indented code inside a quote', () => {
    const html = renderToStaticMarkup(<MarkdownBody body={'> It collapses them:\n> \n>     SELECT (SELECT 1),\n>            (SELECT 1)\n> \n> returns two equal counts.'} />);

    expect(html).toContain('<blockquote><p>It collapses them:</p><div class="md-code-block">');
    expect(html.match(/<pre>[^]*<\/pre>/)?.[0].replace(/<[^>]+>/g, '')).toBe('SELECT (SELECT 1),\n       (SELECT 1)');
    expect(html).toContain('</div><p>returns two equal counts.</p></blockquote>');
  });

  it('keeps indented lines inside list items and fences out of indented code', () => {
    const fenced = '```java\nclass A {\n\n    void run() {}\n}\n```';
    const listed = renderToStaticMarkup(<MarkdownBody body={'- Item\n\n    More about the item'} />);

    expect(listed).not.toContain('md-code-block');
    expect(listed).toContain('<p>More about the item</p>');
    expect(renderToStaticMarkup(<MarkdownBody body={fenced} />).match(/md-code-block/g)).toHaveLength(1);
  });

  it('does not count a task inside indented code', () => {
    const body = 'Template:\n\n    - [ ] Example\n\n- [ ] Real';

    expect(renderToStaticMarkup(<MarkdownBody body={body} onTaskToggle={() => {}} />).match(/data-markdown-task=""/g)).toHaveLength(1);
    expect(toggleMarkdownTask(body, 0, true)).toBe('Template:\n\n    - [ ] Example\n\n- [x] Real');
  });

  it('keeps underscores inside words or beside spaces literal, like GitHub', () => {
    const html = renderToStaticMarkup(<MarkdownBody body={'nit: why not ICEBERG_FILESYSTEM_ERROR?\n\n'
      + 'Set _only_ this, (_quoted_), not snake_case_name, 1_000_000, a _ spaced _ pair, or 中文_强调_中文.\n\n'
      + '_private_field_ and **bold**_after_ and `code`_after_'} />);

    expect(html).toContain('<p>nit: why not ICEBERG_FILESYSTEM_ERROR?</p>');
    expect(html).toContain('<p>Set <em>only</em> this, (<em>quoted</em>), not snake_case_name, 1_000_000, a _ spaced _ pair, or 中文_强调_中文.</p>');
    expect(html).toContain('<p><em>private_field</em> and <strong>bold</strong><em>after</em> and <code>code</code><em>after</em></p>');
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

  it('keeps truncated bot code spans from exposing later HTML metadata and formatting', () => {
    const body = '<details><summary>Pre-merge checks</summary>\n\n'
      + '| Check | Explanation |\n| --- | --- |\n| Linked issues | Uses `DeltaLakeTab… |\n\n</details>\n\n'
      + '<!-- pre_merge_checks_walkthrough_end -->\n\n'
      + '<!-- hidden template\n```html\n<strong>hidden</strong>\n```\n-->\n\n'
      + '- [ ] <!-- {"checkboxId":"fix"} --> Fix all pre-merge checks with AI\n'
      + '<!-- autopilot:start -->\n'
      + '- [ ] <!-- {"checkboxId":"autopilot"} --> <strong title="Keep fixing findings">Autopilot</strong> · Keep fixing findings\n'
      + '<!-- autopilot:end -->\n<!-- tips_start -->\n\n---\n\n'
      + '<sub>Comment `@coderabbitai help` to get the list of available commands.</sub>\n\n<!-- tips_end -->';
    const html = renderToStaticMarkup(<MarkdownBody body={body} onTaskToggle={() => {}} />);

    expect(html).not.toContain('&lt;');
    expect(html).not.toMatch(/checkboxId|autopilot:start|tips_start|pre_merge_checks_walkthrough_end|hidden/);
    expect(html.match(/data-markdown-task=""/g)).toHaveLength(2);
    expect(html).toContain('<strong>Autopilot</strong> · Keep fixing findings');
    expect(html).toContain('<sub>Comment <code>@coderabbitai help</code> to get the list of available commands.</sub>');
    expect(toggleMarkdownTask(body, 1, true)).toBe(body.replace('- [ ] <!-- {"checkboxId":"autopilot"}', '- [x] <!-- {"checkboxId":"autopilot"}'));
  });

  it.each(['```', '~~~~'])('preserves HTML-looking source and blank lines inside %s code fences', fence => {
    const source = '<!-- visible in code -->\n\n<strong>Literal</strong> <sub>source</sub>';
    const html = renderToStaticMarkup(<MarkdownBody body={`${fence}text\n${source}\n${fence}\n\n<!-- hidden -->\nAfter`} />);

    expect(html.match(/<pre>[^]*<\/pre>/)?.[0].replace(/<[^>]+>/g, '')).toBe(renderToStaticMarkup(<>{source}</>));
    expect(html).not.toContain('hidden');
    expect(html).toContain('<p>After</p>');
  });

  it('renders only allowed HTML formatting without copying attributes or interpreting code as tags', () => {
    const html = renderToStaticMarkup(<MarkdownBody body={'<STRONG onclick="alert(1)">Bold <sub style="color:red">small</sub></STRONG>\n\n'
      + '`<strong>literal</strong>` and `multiple\nlines`'} />);

    expect(html).toContain('<strong>Bold <sub>small</sub></strong>');
    expect(html).not.toMatch(/onclick|style=/);
    expect(html).toContain('<code>&lt;strong&gt;literal&lt;/strong&gt;</code> and <code>multiple lines</code>');
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

  it.each([
    ['/worktree/src/Main.java:12', '/worktree/src/Main.java:12'],
    ['</Users/me/Library/Application Support/worktree (review)/Main.java:12>', '/Users/me/Library/Application Support/worktree (review)/Main.java:12'],
    ['<file:///worktree/Main.java:12>', 'file:///worktree/Main.java:12'],
  ])('renders the local file location %s without a broken browser link', (destination, path) => {
    const html = renderToStaticMarkup(<MarkdownBody body={`See [Main.java:12](${destination}).`} />);
    expect(html).toBe(`<div class="markdown-body"><p>See <code title="${path}">Main.java:12</code>.</p></div>`);
  });

  it('preserves angle-delimited web destinations and literal code while still removing HTML', () => {
    const body = 'See [the **docs**](<https://example.com/api(value)?a=1&b=2>) and [pull](/owner/repo/pull/7).\n\n'
      + '`[source](</worktree/Main.java:12>)`\n\n<script>hidden tag</script>';
    const html = renderToStaticMarkup(<MarkdownBody body={body} />);
    expect(html).toContain('<a href="https://example.com/api(value)?a=1&amp;b=2">the <strong>docs</strong></a>');
    expect(html).toContain('href="https://github.com/owner/repo/pull/7"');
    expect(html).toContain('<code>[source](&lt;/worktree/Main.java:12&gt;)</code>');
    expect(html).not.toContain('<script>');
  });

  it('keeps angle-delimited image and linked-image destinations working', () => {
    const html = renderToStaticMarkup(<MarkdownBody body={'![Diagram](<https://example.com/image(1).png>) '
      + '[![Preview](<https://example.com/preview.png>)](<https://example.com/full(1)>)'} />);
    expect(html).toContain('<img class="md-image" src="https://example.com/image(1).png" alt="Diagram"/>');
    expect(html).toContain('<a class="md-image-link" href="https://example.com/full(1)"><img src="https://example.com/preview.png" alt="Preview"/></a>');
  });

  it('renders escaped backticks as literal delimiters', () => {
    const html = renderToStaticMarkup(<MarkdownBody body={'Use \\`ClickHouseClient.toColumnMapping()\\`.'} />);

    expect(html).toBe('<div class="markdown-body"><p>Use `ClickHouseClient.toColumnMapping()`.</p></div>');
  });

  it('renders Jira text escapes without turning literal punctuation or tags into formatting', () => {
    const body = '\\# Plain heading\n\n\\*\\*literal\\*\\* \\_literal\\_ GENERIC\\_INTERNAL\\_ERROR '
      + '\\[label\\]\\(value\\) &lt;details&gt;&lt;summary&gt;literal&lt;/summary&gt;&lt;/details&gt; &amp;lt;tag&amp;gt;\n\n'
      + '&gt; Plain quote\n\n**bold with \\* punctuation** and _italic with \\_ punctuation_';
    const html = renderToStaticMarkup(<MarkdownBody body={body} />);

    expect(html).toContain('<p># Plain heading</p>');
    expect(html).toContain('**literal** _literal_ GENERIC_INTERNAL_ERROR [label](value) &lt;details&gt;&lt;summary&gt;literal&lt;/summary&gt;&lt;/details&gt;');
    expect(html).toContain('&amp;lt;tag&amp;gt;');
    expect(html).toContain('<p>&gt; Plain quote</p>');
    expect(html).toContain('<strong>bold with * punctuation</strong> and <em>italic with _ punctuation</em>');
    expect(html).not.toMatch(/<(?:details|summary|blockquote|h1|a)\b/);
  });

  it('preserves inline code containing backticks, padding and HTML-looking text', () => {
    const html = renderToStaticMarkup(<MarkdownBody body={'Use `` `value` `` and ``a`b<T>`` with `\\_literal\\_`, `&lt;tag&gt;`, and **`<details>literal</details>`**.'} />);

    expect(html).toContain('<code>`value`</code>');
    expect(html).toContain('<code>a`b&lt;T&gt;</code>');
    expect(html).toContain('<code>\\_literal\\_</code>');
    expect(html).toContain('<code>&amp;lt;tag&amp;gt;</code>');
    expect(html).toContain('<strong><code>&lt;details&gt;literal&lt;/details&gt;</code></strong>');
    expect(html).not.toContain('<details');
  });

  it('preserves escaped link labels and ordered list start numbers from Jira', () => {
    const html = renderToStaticMarkup(<MarkdownBody body={'3. [a\\]b](https://example.com)\n4. Continue'} />);

    expect(html).toContain('<ol start="3"><li><a href="https://example.com">a]b</a></li><li>Continue</li></ol>');
    const card = renderToStaticMarkup(<MarkdownBody body={'[https://example\\.atlassian\\.net/browse/ENG\\-7](https://example.atlassian.net/browse/ENG-7)'} />);
    expect(card.match(/<a /g)).toHaveLength(1);
    expect(card).toContain('<a href="https://example.atlassian.net/browse/ENG-7">https://example.atlassian.net/browse/ENG-7</a>');
  });

  it('renders full Jira mention names as one profile link without parsing the label as Markdown', () => {
    const body = 'Hello [@Riley Park](https://example.atlassian.net/jira/people/account%3A123), cc '
      + '[@Anu Sudarsan](https://example.atlassian.net/jira/people/anu) and '
      + '[@张 三 \\(QA\\) \\[team\\]](https://example.atlassian.net/jira/people/qa).';
    const html = renderToStaticMarkup(<MarkdownBody body={body} />);

    expect(html).toContain('<a class="md-mention" href="https://example.atlassian.net/jira/people/account%3A123">@Riley Park</a>');
    expect(html).toContain('<a class="md-mention" href="https://example.atlassian.net/jira/people/anu">@Anu Sudarsan</a>');
    expect(html).toContain('>@张 三 (QA) [team]</a>');
    expect(html).not.toContain('class="md-link"');
    expect(html.match(/<a /g)).toHaveLength(3);
    expect(renderToStaticMarkup(<MarkdownBody body={'[@Riley Park](https://example.com/jira/people/riley) and `@Riley Park`'} />)).not.toContain('md-mention');
  });

  it('keeps escaped pipes inside table text and code cells', () => {
    const body = '| Metric | Value |\n| --- | --- |\n| **left\\|right** | `left\\|right` |\n| path\\\\ | final |';
    const html = renderToStaticMarkup(<MarkdownBody body={body} />);

    expect(html.match(/<td\b/g)).toHaveLength(4);
    expect(html).toContain('<strong>left|right</strong>');
    expect(html).toContain('<code>left|right</code>');
    expect(html).toContain('>path\\</td>');
  });

  it.each([1, 2, 3])('preserves %s code backslashes before an escaped table pipe', count => {
    const source = 'left' + '\\'.repeat(count) + '|right';
    const body = '| Code |\n| --- |\n| `' + source.replace('|', '\\|') + '` |';

    expect(renderToStaticMarkup(<MarkdownBody body={body} />)).toContain(`<code>${source}</code>`);
  });

  it.each(['md', 'markdown'])('preserves source inside inline code and %s fences without a header', language => {
    const releaseNote = '## SPI\n* {{breaking}} Change return type of `ConnectorMetadata.finishMerge` method '
      + 'to `Optional<ConnectorOutputMetadata>`. ({issue}`30934`)';

    const inline = renderToStaticMarkup(<MarkdownBody body={releaseNote} />);
    const fenced = renderToStaticMarkup(<MarkdownBody body={`\`\`\`${language}\n${releaseNote}\n\`\`\``} />);

    expect(inline).toContain('<code>Optional&lt;ConnectorOutputMetadata&gt;</code>');
    expect(fenced.replace(/<[^>]+>/g, '')).toBe(renderToStaticMarkup(<>{releaseNote}</>));
    expect(fenced).toContain('<div class="md-code-block"><pre>');
    expect(fenced).toContain('<span class="hljs-section">## SPI</span>');
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
    expect(renderToStaticMarkup(<MarkdownBody body={`\`\`\`markdown\n${definition}\n\`\`\``} />).replace(/<[^>]+>/g, '')).toBe(definition);
  });

  it('renders the reported inline same-repository code permalink as a code card', () => {
    const url = 'https://github.com/owner/repository/blob/4bfd15e6e1cd4b4a5c838c6065d22151d7d044aa/plugin/bigquery/src/main/java/com/example/bigquery/BigQueryMetadata.java#L796';
    const body = `the place ${url} has similar logic, we could include in this pr`;

    const html = renderToStaticMarkup(<MarkdownBody body={body} repository="owner/repository" />);

    expect(html).toContain('<p>the place</p><div class="md-code-reference">');
    expect(html).toContain('repository/plugin/bigquery/src/main/java/com/example/bigquery/BigQueryMetadata.java');
    expect(html).toContain('Line 796');
    expect(html).toContain('4bfd15e');
    expect(html).toContain('Loading code…');
    expect(html).toContain('</div><p>has similar logic, we could include in this pr</p>');
  });

  it('renders resolved source lines and restores the ordinary link on failure', () => {
    const url = 'https://github.com/owner/repository/blob/4bfd15e6e1cd4b4a5c838c6065d22151d7d044aa/src/Main.java#L10-L12';
    const reference = parseGitHubCodePermalink(url, 'owner/repository');
    expect(reference).not.toBeNull();
    if (!reference) throw new Error('expected a code permalink');

    const loaded = renderToStaticMarkup(<CodeReferenceCard reference={reference} snippet={{ lines: [
      { number: 10, content: '    try (JsonStreamWriter writer = open("https://example.com/<script>")) { // retry' },
      { number: 11, content: '        return writer;' },
      { number: 12, content: '    }' },
    ] }} />);
    const failed = renderToStaticMarkup(<CodeReferenceCard reference={reference} snippet={null} />);

    expect(loaded).toContain('<span class="hljs-keyword">try</span>');
    expect(loaded).toContain('<span class="hljs-type">JsonStreamWriter</span>');
    expect(loaded).toContain('<span class="hljs-string">&quot;https://example.com/&lt;script&gt;&quot;</span>');
    expect(loaded).toContain('<span class="hljs-comment">// retry</span>');
    expect(loaded).toContain('<span>11</span><code class="hljs">    <span class="hljs-keyword">return</span> writer;</code>');
    expect(loaded).toContain('<span>12</span><code class="hljs">}</code>');
    expect(loaded).not.toContain('Loading code…');
    expect(failed).toBe(`<p><a href="${url}">${url}</a></p>`);
  });

  it('parses a bounded code line range', () => {
    const url = 'https://github.com/owner/repository/blob/4bfd15e6e1cd4b4a5c838c6065d22151d7d044aa/src/Main.java#L10-L12';

    expect(markdownBlocks(url, 'OWNER/REPOSITORY')).toEqual([{
      kind: 'code-reference',
      reference: {
        href: url,
        owner: 'owner',
        repo: 'repository',
        commit: '4bfd15e6e1cd4b4a5c838c6065d22151d7d044aa',
        path: 'src/Main.java',
        startLine: 10,
        endLine: 12,
      },
    }]);
  });

  it.each([
    ['foreign repository', 'https://github.com/other/repository/blob/4bfd15e6e1cd4b4a5c838c6065d22151d7d044aa/src/Main.java#L10'],
    ['branch reference', 'https://github.com/owner/repository/blob/master/src/Main.java#L10'],
    ['missing line anchor', 'https://github.com/owner/repository/blob/4bfd15e6e1cd4b4a5c838c6065d22151d7d044aa/src/Main.java'],
  ])('keeps a %s as an ordinary link', (_label, url) => {
    const html = renderToStaticMarkup(<MarkdownBody body={url} repository="owner/repository" />);

    expect(html).toContain(`<a href="${url}">${url}</a>`);
    expect(html).not.toContain('md-code-reference');
  });

  it('does not promote labeled, backticked, fenced, or punctuation-adjacent code permalinks', () => {
    const url = 'https://github.com/owner/repository/blob/4bfd15e6e1cd4b4a5c838c6065d22151d7d044aa/src/Main.java#L10';
    const bodies = [
      `[source](${url})`,
      `\`${url}\``,
      `\`\`\`text\n${url}\n\`\`\``,
      `See ${url}.`,
      `See (${url}) here.`,
    ];

    for (const body of bodies) {
      const html = renderToStaticMarkup(<MarkdownBody body={body} repository="owner/repository" />);
      expect(html).not.toContain('md-code-reference');
    }
  });

  it('does not emit empty paragraphs when an inline code permalink starts or ends the prose', () => {
    const url = 'https://github.com/owner/repository/blob/4bfd15e6e1cd4b4a5c838c6065d22151d7d044aa/src/Main.java#L10';

    expect(markdownBlocks(`${url} after`, 'owner/repository').map(block => block.kind))
      .toEqual(['code-reference', 'paragraph']);
    expect(markdownBlocks(`before ${url}`, 'owner/repository').map(block => block.kind))
      .toEqual(['paragraph', 'code-reference']);
  });

  it('preserves repository context inside quoted Markdown', () => {
    const url = 'https://github.com/owner/repository/blob/4bfd15e6e1cd4b4a5c838c6065d22151d7d044aa/src/Main.java#L10';

    const html = renderToStaticMarkup(<MarkdownBody body={`> ${url}`} repository="owner/repository" />);

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
      + '@contributor I moved the work to workers.';

    const html = renderToStaticMarkup(<MarkdownBody body={body} />);

    expect(html.match(/<blockquote>/g)).toHaveLength(2);
    expect(html).toContain('<blockquote><p>I read the execution flow:</p><blockquote><p>The coordinator aggregates deletion vectors —<br/>this is necessary because multiple workers may process the same file.<br/>CopyOnWriteFileRewriter writes the surviving rows.</p></blockquote><p>I suspect the rewriting is happening on the coordinator.</p></blockquote>');
    expect(html).not.toContain('&gt; The coordinator');
  });
});

describe('short issue references', () => {
  it('uses an explicit repository and rejects invalid numbers and URL fragments', () => {
    const html = renderToStaticMarkup(<>{linkIssueNumbers('Compare #42 with other/project.name#42; #0 #01 #9007199254740992 a#9 #9x /#8 https://example.com/#7', 'owner/repo', true)}</>);
    expect(html).toContain('href="https://github.com/owner/repo/issues/42">#42</a>');
    expect(html).toContain('href="https://github.com/other/project.name/issues/42">other/project.name#42</a>');
    expect(html.match(/md-reference/g)).toHaveLength(2);
  });

  it('links #number to the body repository and leaves look-alikes as text', () => {
    const html = renderToStaticMarkup(<MarkdownBody body={'Fixes #1234 and **#7**, not a#5, &#39;, `#8` or #9x'} repository="owner/repository" />);

    // The icon leads from the start; the title replaces the number's text once the preview loads.
    expect(html).toMatch(/<a class="md-reference is-issue" href="https:\/\/github.com\/owner\/repository\/issues\/1234"><svg[^>]*>.*?<\/svg>#1234<\/a>/);
    expect(html).toMatch(/issues\/7"><svg[^>]*>.*?<\/svg>#7<\/a>/);
    expect(html.match(/md-reference/g)).toHaveLength(2);
  });

  it('keeps a compact #number, for a title, to the bare number', () => {
    expect(renderToStaticMarkup(<h1>{linkIssueNumbers('Fix it (#7)', 'owner/repository', true)}</h1>))
      .toBe('<h1>Fix it (<a class="md-reference is-issue" href="https://github.com/owner/repository/issues/7">#7</a>)</h1>');
  });

  it('keeps #number plain without a repository or inside another link', () => {
    expect(renderToStaticMarkup(<MarkdownBody body="Fixes #1234" />)).toBe('<div class="markdown-body"><p>Fixes #1234</p></div>');
    const html = renderToStaticMarkup(<MarkdownBody body="See [#5 notes](https://example.com)" repository="owner/repository" />);
    expect(html).not.toContain('md-reference');
    expect(html).toContain('>#5 notes</a>');
  });
});
