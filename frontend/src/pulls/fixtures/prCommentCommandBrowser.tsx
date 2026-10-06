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
import type { Bridge, DiffCommitDto, PullRequestDetailDto } from '../../types';
import { assert, changeInput, settle } from '../../fixtures/harness';
import { DiscussionComposer } from '../PullOverview';
import { remotePullRequest, ReviewDialog } from '../PullsScreen';
import '../../css/base.css';

const command = '/test-with-secrets';
const firstSha = 'b695e382a9c1750f5a9940d24bbcdc37a36da7d9';
const commit = (sha: string): DiffCommitDto => ({ sha, parentSha: null, message: 'Commit', author: null, authoredAt: null });
const detail: PullRequestDetailDto = {
  id: 42, repo: 'owner/repo', number: 42, title: 'Command completion', htmlUrl: null, state: 'open', draft: false,
  mergeable: null, mergeableState: null, author: 'alice', authorAvatarUrl: null, createdAt: null, updatedAt: null,
  mergedAt: null, baseRef: 'main', headRef: 'feature', body: '', labels: [], assignees: [], milestone: null,
  reviewers: [], additions: 0, deletions: 0, changedFiles: 0, approvalCount: 0, ciStatus: null,
  recentActivity: [], checkRuns: [], unavailableSections: [], viewerCanWrite: true,
};

export async function run(): Promise<string> {
  const previous = window.bridge;
  let head = firstSha;
  let fail = false;
  let read: (() => Promise<DiffCommitDto[]>) | null = null;
  let reads = 0;
  const posted: string[] = [];
  let closed = false;
  const bridge = {
    fetchPrDiffCommits: async (repo, number) => {
      assert(repo === detail.repo && number === detail.number, 'Completion must read this PR');
      reads++;
      if (fail) throw new Error('Unavailable');
      return read ? read() : [commit('a'.repeat(40)), commit(head)];
    },
    submitReview: async (_id, _repo, _number, _event, body) => { posted.push(body); return detail; },
  } satisfies Pick<Bridge, 'fetchPrDiffCommits' | 'submitReview'>;
  Object.assign(window, { bridge });
  const host = document.createElement('main');
  document.body.append(host);
  const root = createRoot(host);
  const editor = () => {
    const input = host.querySelector<HTMLTextAreaElement>('textarea');
    assert(input !== null, 'Composer must exist');
    return input;
  };
  const type = async (text: string) => { editor().focus(); changeInput(editor(), text); await settle(); };
  const key = async (key: string) => {
    editor().dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
    await settle();
  };
  const option = () => host.querySelector<HTMLButtonElement>('[aria-label="Pull request commands"] [role="option"]');
  const render = (number?: number) => flushSync(() => root.render(<DiscussionComposer key={`discussion-${number}`}
    repository={detail.repo} pullRequestNumber={number} candidates={[{ login: 'alice', avatarUrl: null }]}
    onSubmit={async body => { posted.push(body); }} />));
  try {
    render(detail.number);
    for (let length = 1; length < command.length; length++) {
      const prefix = command.slice(0, length);
      await type(prefix);
      assert(option()?.textContent?.includes(command) === true, `Suggest ${prefix}`);
    }
    assert(reads === 0 && posted.length === 0, 'Prefixes must neither read commits nor post a comment');
    await key('Enter');
    assert(editor().value === `${command} sha=${head}` && posted.length === 0, 'Enter fills the full head SHA without posting');
    assert(editor().selectionStart === editor().value.length && option() === null, 'Completion restores the caret and dismisses suggestions');
    head = 'c'.repeat(40);
    await type('/t'); await key('Tab');
    assert(editor().value === `${command} sha=${head}`, 'The next completion must read the new PR head');
    await type('/tes'); option()?.click(); await settle();
    assert(editor().value === `${command} sha=${head}`, 'Click accepts the suggestion');
    await type(''); await type(command);
    assert(editor().value === `${command} sha=${head}`, 'Typing the full command auto-fills the SHA');
    await type(command);
    assert(editor().value === command, 'Deleting an auto-filled argument must not immediately put it back');
    await type(`${command} sha=${firstSha}`);
    assert(editor().value.endsWith(firstSha) && option() === null, 'An explicit SHA is preserved');
    await type('/tes'); await key('Escape');
    assert(option() === null && editor().value === '/tes', 'Escape dismisses without editing');
    await type('/te'); editor().setSelectionRange(0, 0); await key('Enter');
    assert(option() === null && editor().value === '/te', 'Moving the caret away stops command acceptance');
    await type('Thanks @al'); await key('Enter');
    assert(editor().value === 'Thanks @alice ', 'Existing mention completion still works');
    await type('Please run CI.\n/tes\nThanks.');
    // Type at the caret in a multi-line draft, retaining its suffix.
    Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set?.call(editor(), 'Please run CI.\n/test\nThanks.');
    editor().setSelectionRange(20, 20);
    editor().dispatchEvent(new Event('input', { bubbles: true }));
    await settle(); await key('Tab');
    assert(editor().value === `Please run CI.\n${command} sha=${head}\nThanks.`, 'Completion preserves surrounding lines');
    let finish: (commits: DiffCommitDto[]) => void = () => {};
    read = () => new Promise(resolve => { finish = resolve; });
    await type(''); await type(command);
    assert(host.querySelector<HTMLButtonElement>('.pr-composer footer button')?.disabled === true, 'Posting waits for the SHA');
    await type('Changed my mind');
    finish([commit(head)]); await settle();
    assert(editor().value === 'Changed my mind', 'A late read cannot overwrite a newer draft');
    read = null;
    fail = true;
    await type(command);
    assert(host.querySelector('[role="alert"]') !== null && editor().value === command, 'Read failure keeps the draft and explains recovery');
    fail = false;
    option()?.click(); await settle();
    assert(editor().value === `${command} sha=${head}`, 'The suggestion can retry a failed read');
    head = 'short'; await type('/t'); await key('Enter');
    assert(editor().value === '/t' && host.querySelector('[role="alert"]') !== null, 'An unavailable full SHA leaves the prefix intact');
    head = firstSha; option()?.click(); await settle();
    assert(posted.length === 0, 'Auto-completion never publishes');
    host.querySelector<HTMLButtonElement>('.pr-composer footer button')?.click(); await settle();
    assert(posted[0] === `${command} sha=${head}`, 'Explicit Comment posts the completed draft');
    render(); await type('/tes');
    assert(option() === null, 'Issue composers must not offer PR commands');

    flushSync(() => root.render(<ReviewDialog pr={remotePullRequest(detail, 'alice')} detail={detail}
      onReviewSubmitted={() => {}} onClose={() => { closed = true; }} />));
    await type('/tes'); await key('Escape');
    assert(option() === null && !closed, 'Escape dismisses the suggestion before the review dialog');
    await type('/te'); await key('Enter');
    assert(editor().value === `${command} sha=${head}` && Number(posted.length) === 1, 'Review command acceptance does not submit the review');
    host.querySelector<HTMLButtonElement>('.pr-review-sheet__footer .is-primary')?.click(); await settle();
    assert(posted[1] === `${command} sha=${head}`, 'Submit review posts the completed draft');
    return 'PR command completion passed';
  }
  finally { root.unmount(); host.remove(); Object.assign(window, { bridge: previous }); }
}
