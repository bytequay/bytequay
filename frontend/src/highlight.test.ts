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
import { highlightToHtml } from './highlight';

describe('highlightToHtml type colouring', () => {
  it('tags bare class/collection references as hljs-type', () => {
    const html = highlightToHtml('List<String> names = ImmutableSet.builder();', 'java');
    expect(html).toContain('<span class="hljs-type">List</span>');
    expect(html).toContain('<span class="hljs-type">ImmutableSet</span>');
    expect(html).toContain('<span class="hljs-type">String</span>');
  });

  it('never recolours inside an existing token (string bodies stay put)', () => {
    const html = highlightToHtml('selectedColumns.add("_change_type");', 'java');
    // The string body is inside an hljs-string span, so no stray type span
    // wraps a capital inside it.
    expect(html).not.toContain('<span class="hljs-type">_');
    // A double-wrapped span would look like class="hljs-type"><span.
    expect(html).not.toMatch(/hljs-type">\s*<span/);
  });
});
