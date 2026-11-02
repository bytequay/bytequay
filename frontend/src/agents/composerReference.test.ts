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
import { expect, it } from 'vitest';
import { editReferenceDraft, referenceDraft, type ComposerReference } from './composerReference';

it('keeps a URL-shaped title stable while editing instructions', () => {
  const url = 'https://github.com/owner/repo/issues/7';
  const reference: ComposerReference = { command: 'FIX', item: { title: url, url, number: 7, author: 'contributor', updatedAt: '' } };
  let draft = `@fix ${url} `;
  for (const word of ['add ', 'tests']) {
    const visible = referenceDraft(draft, reference);
    if (visible === null) throw new Error('The selected reference must stay visible');
    expect(visible.text.match(/#7/g)).toHaveLength(1);
    const edited = editReferenceDraft(visible.text + word, reference);
    expect(edited.reference).toBe(reference);
    draft = edited.draft;
  }
  expect(draft).toBe(`@fix ${url} add tests`);
  expect(referenceDraft(draft, reference)?.text).toBe(`@fix ${url} #7 add tests`);
});
