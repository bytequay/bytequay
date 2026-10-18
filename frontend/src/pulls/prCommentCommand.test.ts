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
import { runBrowserFixture, deadline } from '../fixtures/browserFixture';
import { activePrCommentCommand } from './usePrCommentCommand';

it('matches command prefixes on their own lines without altering prose, arguments or code', () => {
  for (const prefix of ['/', '/test-', '/test-with-secrets']) {
    const text = `Please run CI.\n${prefix}\nThanks.`;
    expect(activePrCommentCommand(text, 15 + prefix.length)).toEqual({ start: 15, end: 15 + prefix.length, query: prefix });
  }
  for (const text of ['', '/toast', '/test-with-secrets-extra', 'Try /tes', '> /tes', '`/tes', '/test-with-secrets sha=custom',
    '```\n/tes', '~~~text\n/tes', '    /tes']) {
    expect(activePrCommentCommand(text, text.length)).toBeNull();
  }
  expect(activePrCommentCommand('/test-other', 4)).toBeNull();
  expect(activePrCommentCommand('```\ncode\n```\n/tes', 17)?.query).toBe('/tes');
});

it('completes PR commands with the latest full SHA in both comment composers in Chromium', async () => {
  expect(await runBrowserFixture<string>({
    prefix: 'bytequay-pr-command-', entry: 'src/pulls/fixtures/prCommentCommandBrowser.tsx', global: 'PrCommandTests',
    styles: true, charset: true,
  })).toBe('PR command completion passed');
}, deadline(40_000));
