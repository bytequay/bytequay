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
  it('renders parenthesized checkbox options on separate lines', () => {
    const body = '( ) No release notes are required.\n'
      + '( ) Please propose a release note for me.\n'
      + '(x) Release notes are required, with the following suggested text:';

    expect(markdownBlocks(body)).toEqual([
      { kind: 'paragraph', text: '( ) No release notes are required.' },
      { kind: 'paragraph', text: '( ) Please propose a release note for me.' },
      { kind: 'paragraph', text: '(x) Release notes are required, with the following suggested text:' },
    ]);

    const html = renderToStaticMarkup(<MarkdownBody body={body} />);
    expect(html).toContain('<p>( ) No release notes are required.</p><p>( ) Please propose a release note for me.</p><p>(x) Release notes are required');
  });
});
