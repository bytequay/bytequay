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
import { createRoot } from 'react-dom/client';
import { assert, changeInput, settle } from '../../fixtures/harness';
import { DiscussionComposer } from '../PullOverview';

export async function run(): Promise<string> {
  const host = document.createElement('main');
  document.body.append(host);
  const root = createRoot(host);
  const posted: string[] = [];
  let rejectPost = true;
  let rejectClose = true;
  let closes = 0;
  const unhandled: unknown[] = [];
  const rejected = (event: PromiseRejectionEvent) => { event.preventDefault(); unhandled.push(event.reason); };
  window.addEventListener('unhandledrejection', rejected);
  const editor = () => {
    const input = host.querySelector('textarea');
    assert(input !== null, 'The comment draft must be visible');
    return input;
  };
  const click = async (label: string) => {
    const button = [...host.querySelectorAll('button')].find(item => item.textContent?.trim() === label);
    assert(button !== undefined && !button.disabled, `${label} must be available`);
    button.click();
    await settle();
  };
  const alert = () => host.querySelector('[role="alert"]')?.textContent;
  try {
    flushSync(() => root.render(<DiscussionComposer repository="owner/repo" pullRequestNumber={42} candidates={[]}
      onSubmit={async body => {
        if (rejectPost) throw new Error('GitHub refused this comment.');
        posted.push(body);
      }}
      onClose={async () => {
        closes++;
        if (rejectClose) throw new Error('GitHub refused to close this pull request.');
      }} />));
    changeInput(editor(), 'Please fix this'); await settle();
    await click('Comment');
    assert(alert() === 'GitHub refused this comment.' && editor().value === 'Please fix this', 'A failed post must explain the failure and keep the draft');
    await click('Close with comment');
    assert(closes === 0 && posted.length === 0 && alert() === 'GitHub refused this comment.', 'A failed comment must leave the pull request open');
    rejectPost = false;
    await click('Comment');
    assert(posted.join() === 'Please fix this' && editor().value === '' && alert() === undefined, 'Retrying a successful comment clears its draft and old error');

    changeInput(editor(), 'Closing explanation'); await settle();
    await click('Close with comment');
    assert(alert() === 'GitHub refused to close this pull request.' && editor().value === '', 'A failed close must explain the failure without restoring an already posted comment');
    assert(Number(closes) === 1 && posted.join('|') === 'Please fix this|Closing explanation', 'Close with comment must post once before closing');
    rejectClose = false;
    await click('Close pull request');
    assert(Number(closes) === 2 && Number(posted.length) === 2 && alert() === undefined, 'Retrying close must not post the comment again');
    await settle();
    assert(unhandled.length === 0, 'Handled action failures must not leak rejected promises');
    return 'Comment and close recovery passed';
  }
  finally {
    window.removeEventListener('unhandledrejection', rejected);
    flushSync(() => root.unmount());
    host.remove();
  }
}
