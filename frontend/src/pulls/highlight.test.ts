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
import hljs from './highlight';

const java = (code: string) => hljs.highlight(code, { language: 'java', ignoreIllegals: true }).value;

describe('Java highlighting', () => {
  it('colors method calls and constants as GitHub does', () => {
    const html = java('assertThat(factory.create(ID, Map.of(PREFIX_KEY + "a")));');
    for (const call of ['assertThat', 'create', 'of']) expect(html).toContain(`<span class="hljs-title function_ invoke__">${call}</span>`);
    expect(html).toContain('<span class="hljs-variable constant_">PREFIX_KEY</span>');
    expect(html).toContain('<span class="hljs-variable constant_">ID</span>');
    expect(html).toContain('<span class="hljs-string">&quot;a&quot;</span>');
  });

  it('keeps keywords, declarations and constructors in their own colors', () => {
    const html = java('if (x) { for (int i = 0; i < n; i++) try (var r = open()) { this(1); return (y); } catch (E e) { throw new Failure(e); } }');
    for (const keyword of ['if', 'for', 'try', 'return', 'catch', 'throw', 'new']) expect(html).toContain(`<span class="hljs-keyword">${keyword}</span>`);
    expect(html).toContain('<span class="hljs-built_in">this</span>');
    expect(html).toContain('<span class="hljs-title class_">Failure</span>');
    expect(java('public void run(String a) {}')).toContain('<span class="hljs-title function_">run</span>');
  });
});
