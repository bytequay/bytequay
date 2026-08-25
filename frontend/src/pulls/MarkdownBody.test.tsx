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
      + '[x] Square checked\n'
      + '- [x] Dash checked\n'
      + '* [ ] Asterisk unchecked\n\n'
      + '- Dash item\n'
      + '* Asterisk item';

    expect(markdownBlocks(body)).toEqual([
      { kind: 'task', checked: false, text: 'Parenthesized unchecked' },
      { kind: 'task', checked: true, text: 'Square checked' },
      { kind: 'task', checked: true, text: 'Dash checked' },
      { kind: 'task', checked: false, text: 'Asterisk unchecked' },
      { kind: 'list', ordered: false, items: ['Dash item', 'Asterisk item'] },
    ]);

    const html = renderToStaticMarkup(<MarkdownBody body={body} />);
    expect(html.match(/type="checkbox"/g)).toHaveLength(4);
    expect(html.match(/checked=""/g)).toHaveLength(2);
    expect(html).toContain('<ul><li>Dash item</li><li>Asterisk item</li></ul>');
  });
});
