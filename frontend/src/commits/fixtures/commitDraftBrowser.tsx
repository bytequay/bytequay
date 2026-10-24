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
import { flushSync } from 'react-dom';
import { assert, changeInput, element, settle } from '../../fixtures/harness';
import { createFixtureRoot } from '../../fixtures/reactFixture';
import type { Bridge } from '../../types';
import ChangesView from '../ChangesView';

export async function run(): Promise<string> {
  const previous = window.bridge;
  const { host, root, dispose } = createFixtureRoot('main');
  let commit = Promise.withResolvers<Awaited<ReturnType<Bridge['gitCommit']>>>();
  const submitted: Parameters<Bridge['gitCommit']>[] = [];
  const status: Awaited<ReturnType<Bridge['gitStatus']>> = {
    branch: 'main', files: [{ path: 'file.txt', status: 'modified', additions: 1, deletions: 0, patch: null }],
  };
  const bridge = {
    gitStatus: async () => status,
    gitCommit: (...args) => { submitted.push(args); return commit.promise; },
  } satisfies Pick<Bridge, 'gitStatus' | 'gitCommit'>;
  Object.assign(window, { bridge });
  try {
    flushSync(() => root.render(<ChangesView workspaceId="workspace" onCommitted={() => {}} />));
    await settle();
    const summary = element<HTMLInputElement>('input', host);
    const description = element<HTMLTextAreaElement>('textarea', host);
    const button = element<HTMLButtonElement>('.cmt-commit-btn', host);
    changeInput(summary, 'Commit summary'); changeInput(description, 'Commit description'); await settle();
    button.click(); await settle();
    assert(summary.disabled && description.disabled && button.disabled, 'A pending commit must lock its message so a later edit cannot be cleared');
    commit.reject(new Error('Signing failed')); await settle();
    assert(!summary.disabled && !description.disabled && summary.value === 'Commit summary' && description.value === 'Commit description',
      'A failed commit must unlock and retain both fields');
    commit = Promise.withResolvers<Awaited<ReturnType<Bridge['gitCommit']>>>();
    button.click(); await settle();
    commit.resolve({ sha: 'committed' }); await settle();
    assert(String(summary.value) === '' && String(description.value) === '' && !summary.disabled && !description.disabled, 'A successful commit must clear and unlock its submitted message');
    assert(submitted.length === 2 && submitted.every(args => args[1] === 'Commit summary' && args[2] === 'Commit description'), 'Retry must submit the preserved message');
    return 'Commit messages survive delayed writes and failures';
  }
  finally {
    dispose();
    Object.assign(window, { bridge: previous });
  }
}
