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
import { renderChatMarkdown } from './markdown';

describe('renderChatMarkdown diff blocks', () => {
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

    const html = renderChatMarkdown(md);

    expect(html).toContain('<pre class="bq-diff">');
    expect(html).toContain('class="bq-diff-line bq-diff-meta">--- a/Foo.java');
    expect(html).toContain('class="bq-diff-line bq-diff-hunk">@@ -1,3 +1,3 @@');
    expect(html).toContain('class="bq-diff-line bq-diff-del">-  removed');
    expect(html).toContain('class="bq-diff-line bq-diff-add">+  added');
    expect(html).toContain('class="bq-diff-line bq-diff-ctx"> context line');
  });

  it('honours an explicit ```diff fence', () => {
    const html = renderChatMarkdown('```diff\n-gone\n+here\n```');
    expect(html).toContain('bq-diff-del');
    expect(html).toContain('bq-diff-add');
  });

  it('escapes html inside diff lines', () => {
    const html = renderChatMarkdown('```diff\n+<script>x</script>\n@@ -1 +1 @@\n```');
    expect(html).toContain('+&lt;script&gt;x&lt;/script&gt;');
    expect(html).not.toContain('<script>');
  });

  it('syntax-highlights a non-diff code block', () => {
    const html = renderChatMarkdown('```js\nconst a = 1;\n```');
    expect(html).not.toContain('bq-diff');
    expect(html).toContain('<pre>');
    // highlight.js tokenises the code: same text, wrapped in hljs spans.
    expect(html).toContain('hljs');
    expect(html).toContain('<span class="hljs-keyword">const</span>');
    expect(html).toContain('language-js');
  });
});
