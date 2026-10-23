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
import CodeExcerpt, { codeLanguage } from './CodeExcerpt';

describe('CodeExcerpt', () => {
  it('names the grammar from the file extension and leaves unknown files plain', () => {
    expect(codeLanguage('src/main/java/Example.java')).toBe('java');
    expect(codeLanguage('frontend/src/App.TSX')).toBe('tsx');
    expect(codeLanguage('scripts/build.sh')).toBe('sh');
    expect(codeLanguage('Makefile')).toBeNull();
    expect(codeLanguage('notes.unknown')).toBeNull();
    expect(codeLanguage(null)).toBeNull();
  });

  it('draws lines as diff rows, with keyword colors and escaped source', () => {
    const html = renderToStaticMarkup(<CodeExcerpt path="src/Example.java" caption="Selected code · head abc12345" lines={[
      { lineNumber: 7, prefix: '+', text: 'return "<b>";' },
      { lineNumber: 8, prefix: '-', text: '' },
      { lineNumber: null, prefix: ' ', text: 'x' },
    ]} />);
    expect(html).toContain('<tr class="is-hunk"><td colSpan="2">Selected code · head abc12345</td></tr>');
    expect(html).toContain('<tr class="is-add"><td class="is-line">7</td><td class="is-source"><span>+</span><code class="hljs"><span class="hljs-keyword">return</span> <span class="hljs-string">&quot;&lt;b&gt;&quot;</span>;</code></td></tr>');
    expect(html).toContain('<tr class="is-del"><td class="is-line">8</td><td class="is-source"><span>−</span>');
    expect(html).toContain('<tr class="is-context"><td class="is-line"></td>');
    expect(html).not.toContain('<b>');
  });

  it('keeps a file without a known grammar as plain text', () => {
    const html = renderToStaticMarkup(<CodeExcerpt path="LICENSE" lines={[{ lineNumber: 1, prefix: ' ', text: 'return <b>' }]} />);
    expect(html).toContain('<code>return &lt;b&gt;</code>');
  });
});
