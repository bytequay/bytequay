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
import { runBrowserFixture, type ScenarioResult } from '../fixtures/browserFixture';
import { commentIndexTarget } from './ReviewCommentIndex';

it('bounds previews to 20 words or 240 characters without splitting Unicode characters', () => {
  const preview = (body: string) => commentIndexTarget('human', 'alice', body)['data-review-comment-preview'];
  expect(preview('  A short\n comment. ')).toBe('A short comment.');
  expect(preview('')).toBe('');
  const words = Array.from({ length: 20 }, (_, index) => `word${index}`).join(' ');
  expect(preview(words)).toBe(words);
  expect(preview(`${words} omitted words`)).toBe(`${words}…`);
  expect(preview('🙂'.repeat(241))).toBe(`${'🙂'.repeat(240)}…`);
});

it('indexes human and agent comments in PR and Summary, opening the right filtered discussion', async () => {
  const results = await runBrowserFixture<ScenarioResult[]>({
    prefix: 'bytequay-comment-index-', entry: 'src/pulls/fixtures/reviewCommentIndexBrowser.tsx',
    global: 'ReviewCommentIndexTests', styles: true, width: 1000, height: 800,
  });
  expect(results).toHaveLength(4);
  expect(results.filter(result => result.error !== undefined)).toEqual([]);
}, 30_000);
