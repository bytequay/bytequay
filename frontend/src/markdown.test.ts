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
import { renderMarkdown } from './markdown';

describe('renderMarkdown diff blocks', () => {
  it('paints add / del / hunk / meta rows for a plain fenced diff', () => {
    const md = [
      '```',
      '--- a/Foo.java',
      '+++ b/Foo.java',
      '@@ -1,3 +1,3 @@',
      ' context line',
      '-  removed',
      '+  added',
      '```',
    ].join('\n');

    const html = renderMarkdown(md);

    expect(html).toContain('<pre class="bq-diff">');
    expect(html).toContain('class="bq-diff-line bq-diff-meta">--- a/Foo.java');
    expect(html).toContain('class="bq-diff-line bq-diff-hunk">@@ -1,3 +1,3 @@');
    expect(html).toContain('class="bq-diff-line bq-diff-del">-  removed');
    expect(html).toContain('class="bq-diff-line bq-diff-add">+  added');
    expect(html).toContain('class="bq-diff-line bq-diff-ctx"> context line');
  });

  it('honours an explicit ```diff fence', () => {
    const html = renderMarkdown('```diff\n-gone\n+here\n```');
    expect(html).toContain('bq-diff-del');
    expect(html).toContain('bq-diff-add');
  });

  it('escapes html inside diff lines', () => {
    const html = renderMarkdown('```diff\n+<script>x</script>\n@@ -1 +1 @@\n```');
    expect(html).toContain('+&lt;script&gt;x&lt;/script&gt;');
    expect(html).not.toContain('<script>');
  });

  it('syntax-highlights a non-diff code block', () => {
    const html = renderMarkdown('```js\nconst a = 1;\n```');
    expect(html).not.toContain('bq-diff');
    expect(html).toContain('<pre>');
    // highlight.js tokenises the code: same text, wrapped in hljs spans.
    expect(html).toContain('hljs');
    expect(html).toContain('<span class="hljs-keyword">const</span>');
    expect(html).toContain('language-js');
  });
});

describe('emoji shortcodes', () => {
  it('renders GitHub custom :shipit: as an image', () => {
    const html = renderMarkdown('Ship it :shipit:');
    expect(html).toContain('<img');
    expect(html).toContain('shipit.png');
    expect(html).toContain('class="md-emoji"');
    expect(html).toContain('alt=":shipit:"');
  });

  it('converts common Unicode shortcodes to their glyph', () => {
    expect(renderMarkdown(':tada: :+1: :rocket:')).toContain('🎉');
    expect(renderMarkdown(':+1:')).toContain('👍');
  });

  it('leaves unknown shortcodes as literal text', () => {
    const html = renderMarkdown('not an emoji :definitely_not_a_real_code:');
    expect(html).toContain(':definitely_not_a_real_code:');
    expect(html).not.toContain('<img');
  });

  it('does not emojify inside code spans or fences', () => {
    expect(renderMarkdown('`:shipit:`')).not.toContain('<img');
    expect(renderMarkdown('```\n:shipit:\n```')).not.toContain('<img');
  });

  it('does not mistake a time like 12:00:00 for a shortcode', () => {
    const html = renderMarkdown('meet at 12:00:00 today');
    expect(html).toContain('12:00:00');
    expect(html).not.toContain('<img');
  });
});

describe('GitHub PR links', () => {
  it('rewrites a bare PR URL into an internal reference chip', () => {
    const html = renderMarkdown('see https://github.com/trinodb/trino/pull/29952 please');
    expect(html).toContain('class="md-ref-pr"');
    expect(html).toContain('data-pr-owner="trinodb"');
    expect(html).toContain('data-pr-repo="trino"');
    expect(html).toContain('data-pr-number="29952"');
    // Bare URL collapses to the cross-repo reference label.
    expect(html).toContain('>trinodb/trino#29952<');
  });

  it('shortens to #N when the link targets the same repo as the comment', () => {
    const html = renderMarkdown('https://github.com/trinodb/trino/pull/29952', { owner: 'trinodb', repo: 'trino' });
    expect(html).toContain('data-pr-number="29952"');
    // Visible chip text is the short same-repo form.
    expect(html).toContain('>#29952<');
  });

  it('keeps an explicit link label but still marks it for internal nav', () => {
    const html = renderMarkdown('[the fix](https://github.com/trinodb/trino/pull/29952)');
    expect(html).toContain('data-pr-number="29952"');
    expect(html).toContain('>the fix<');
    // No chip styling — only bare URLs become chips.
    expect(html).not.toContain('class="md-ref-pr"');
  });

  it('tolerates trailing path / query on the PR URL', () => {
    const html = renderMarkdown('https://github.com/trinodb/trino/pull/29952/files');
    expect(html).toContain('data-pr-number="29952"');
  });

  it('leaves non-PR github URLs alone', () => {
    const html = renderMarkdown('https://github.com/trinodb/trino/issues/12');
    expect(html).not.toContain('data-pr-number');
    expect(html).not.toContain('class="md-ref-pr"');
  });
});
