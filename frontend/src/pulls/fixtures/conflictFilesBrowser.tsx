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

import { createFixtureRoot } from '../../fixtures/reactFixture';
import { flushSync } from 'react-dom';
import type { Bridge, PullRequestConflictsDto } from '../../types';
import { assert, settle } from '../../fixtures/harness';
import PullRequestConflictFiles from '../PullRequestConflictFiles';
import '../../css/base.css';
import '../../css/pulls.css';

export async function run(): Promise<string> {
  const originalBridge = window.bridge;
  const calls: { repo: string; number: number; response: ReturnType<typeof Promise.withResolvers<PullRequestConflictsDto>> }[] = [];
  const bridge = {
    fetchPrConflicts: (repo, number) => {
      const response = Promise.withResolvers<PullRequestConflictsDto>();
      calls.push({ repo, number, response });
      return response.promise;
    },
  } satisfies Pick<Bridge, 'fetchPrConflicts'>;
  Object.assign(window, { bridge });
  const { host, root, dispose } = createFixtureRoot();
  host.style.width = '360px';
  const render = (number: number) => flushSync(() => root.render(<PullRequestConflictFiles key={number} repo="owner/repo" number={number} />));
  const reply = (files: string[]): PullRequestConflictsDto => ({ baseRef: 'release/next', files });
  const paths = () => [...host.querySelectorAll('li code')].map(node => node.textContent);
  const button = () => {
    const value = host.querySelector<HTMLButtonElement>('button');
    assert(value !== null, 'Refresh files button exists');
    return value;
  };
  try {
    render(7);
    await settle();
    assert(calls.length === 1 && calls[0].repo === 'owner/repo' && calls[0].number === 7, 'Reads only the selected PR');
    assert(host.textContent?.includes('Checking conflicted files…') && button().disabled, 'Shows a loading state');
    const names = ['src/目录/' + 'long-name-'.repeat(18) + '.ts', 'file with spaces\nand newline.txt', '<img src=x onerror=alert(1)>.txt'];
    calls[0].response.resolve(reply(names));
    await settle();
    assert(JSON.stringify(paths()) === JSON.stringify(names), 'Preserves conflict paths as plain text');
    assert(!host.querySelector('img'), 'File names cannot inject markup');
    assert(host.textContent?.includes('Conflicted files (3)') && host.textContent.includes('release/next'), 'Shows the count and compared branch');
    assert(host.scrollWidth <= host.clientWidth, 'Long paths wrap in a narrow panel');
    button().click();
    await settle();
    assert(paths().length === 0 && button().disabled, 'Refresh clears the previous comparison');
    calls[1].response.reject(new Error('Watch repository to list conflicted files'));
    await settle();
    assert(host.textContent?.includes('Watch repository to list conflicted files') && button().textContent === 'Retry', 'Explains unavailable local code');
    button().click();
    await settle();
    calls[2].response.resolve(reply([]));
    await settle();
    assert(host.textContent?.includes('No conflicted files found. Refresh the pull request'), 'An empty result asks for a status refresh');
    button().click();
    await settle();
    render(8);
    await settle();
    calls[3].response.resolve(reply(['old-pr.txt']));
    await settle();
    assert(!host.textContent?.includes('old-pr.txt'), 'An old response cannot populate another PR');
    calls[4].response.resolve(reply(['new-pr.txt']));
    await settle();
    assert(paths().join() === 'new-pr.txt', 'Shows only the current PR response');
    return 'conflict files, unavailable, retry, empty, stale response';
  }
  finally {
    dispose();
    Object.assign(window, { bridge: originalBridge });
  }
}
