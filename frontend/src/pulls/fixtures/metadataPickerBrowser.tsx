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
import { assert, button, changeInput, element, settle } from '../../fixtures/harness';
import { createFixtureRoot } from '../../fixtures/reactFixture';
import type { Bridge, PullRequestDetailDto } from '../../types';
import PullOverview from '../PullOverview';
import { pr, prDetail } from './pullRequest';
import '../../css/base.css';

type Kind = 'reviewers' | 'assignees';

function mount(failReviewerCall = 0, holdWrites = false) {
  const previous = window.bridge;
  const { host, root, dispose } = createFixtureRoot();
  let detail = prDetail({
    assignees: ['bob'], labels: [{ name: 'bug', color: 'ff0000' }],
    reviewers: [{ login: 'bob', avatarUrl: null, state: 'PENDING' }, { login: 'approved', avatarUrl: null, state: 'APPROVED' }],
  });
  const reviewerWrites: Parameters<Bridge['updatePrReviewer']>[] = [];
  const assigneeWrites: Parameters<Bridge['updatePrMetadata']>[] = [];
  const pending: ReturnType<typeof Promise.withResolvers<void>>[] = [];
  window.bridge = { ...previous,
    getWorkspaceHostingViewer: async () => (await window.bridge.getUserProfile?.().catch(() => null))?.login ?? null,
    fetchWorkspaceHostingAttention: async () => ({ login: (await window.bridge.getUserProfile?.().catch(() => null))?.login ?? null, pullRequests: await window.bridge.fetchAttentionPrs?.().catch(() => []) ?? [] }),
    getUserProfile: async () => { throw new Error('No viewer in this fixture'); },
    fetchRepoUsers: async () => ['alice', 'bob', 'carol', 'approved'].map(login => ({ login, avatarUrl: null, bot: false })),
    fetchRepoTeams: async () => ['bytequay/core'],
    updatePrReviewer: async (...args) => {
      reviewerWrites.push(args);
      if (reviewerWrites.length === failReviewerCall) throw new Error('Review request denied');
      if (holdWrites) {
        const answer = Promise.withResolvers<void>();
        pending.push(answer);
        await answer.promise;
      }
    },
    updatePrMetadata: async (...args) => { assigneeWrites.push(args); },
  };
  const render = () => flushSync(() => root.render(<PullOverview pr={pr({})} detail={detail} onRefresh={async () => {}}
    onDetailUpdate={(repo, number, update) => {
      if (repo === detail.repo && number === detail.number) { detail = update(detail); render(); }
    }} />));
  render();
  const search = (kind: Kind) => element<HTMLInputElement>(`[aria-label="Search ${kind}"]`, host);
  const option = (login: string) => element<HTMLButtonElement>(`[role="option"][aria-label="${login}"]`, host);
  const open = async (kind: Kind) => {
    const index = kind === 'reviewers' ? 0 : 1;
    const chip = host.querySelectorAll<HTMLButtonElement>('.pr-meta__chip')[index];
    assert(chip !== undefined, 'The metadata chip must be visible');
    chip.click(); await settle();
  };
  const choose = async (kind: Kind, login: string) => {
    changeInput(search(kind), login); await settle();
    option(login).click(); await settle();
    assert(search(kind).value === '' && document.activeElement === search(kind), 'Choosing a person must clear and refocus search for the next selection');
  };
  return {
    host, reviewerWrites, assigneeWrites, pending, search, option, open, choose,
    detail: () => detail,
    refresh: (update: (value: PullRequestDetailDto) => PullRequestDetailDto) => { detail = update(detail); render(); },
    click: async (label: string) => { button(label, host).click(); await settle(); },
    dispose: () => { dispose(); window.bridge = previous; },
  };
}

export async function runBatch(): Promise<string> {
  const f = mount();
  try {
    await settle();
    await f.open('reviewers');
    assert(button('Save changes', f.host).disabled, 'An unchanged selection must not write');
    const picker = element('.pr-people-picker', f.host).getBoundingClientRect();
    const count = element('.pr-people-picker__actions > span', f.host).getBoundingClientRect();
    const cancel = button('Cancel', f.host).getBoundingClientRect();
    const save = button('Save changes', f.host).getBoundingClientRect();
    assert(count.width > 0 && count.right <= cancel.left && cancel.right <= save.left && save.right <= picker.right
      && save.bottom <= picker.bottom, 'The selected count and save controls must fit visibly inside the picker without overlapping');
    assert(f.option('approved').disabled, 'Completed reviews cannot be removed');
    await f.choose('reviewers', 'bob');
    await f.choose('reviewers', 'carol');
    await f.choose('reviewers', 'bytequay/core');
    await f.choose('reviewers', 'alice');
    await f.choose('reviewers', 'alice');
    assert(f.reviewerWrites.length === 0 && f.detail().reviewers.map(value => value.login).join() === 'bob,approved',
      'Search and selection must not write or change the saved reviewer list');
    await f.click('Save changes');
    assert(f.reviewerWrites.map(([, , login, requested]) => `${login}:${requested}`).sort().join() === 'bob:false,bytequay/core:true,carol:true',
      'Save must apply only the net reviewer changes, including teams');
    assert(f.host.querySelector('[aria-label="Search reviewers"]') === null, 'A successful batch must close the picker');
    assert(f.detail().reviewers.map(value => value.login).sort().join() === 'approved,bytequay/core,carol', 'Saved reviewers must reach the shared detail');

    await f.open('assignees');
    await f.choose('assignees', 'bob');
    await f.choose('assignees', 'carol');
    await f.choose('assignees', 'alice');
    assert(f.assigneeWrites.length === 0 && f.detail().assignees.join() === 'bob', 'Assignee drafts must remain local until Save');
    await f.click('Save changes');
    assert(Number(f.assigneeWrites.length) === 1 && f.assigneeWrites[0][2].slice().sort().join() === 'alice,carol'
      && f.assigneeWrites[0][3].join() === 'bug', 'Save must send all assignees once and preserve labels');
    assert(f.detail().assignees.slice().sort().join() === 'alice,carol', 'Saved assignees must reach the shared detail');
    return 'Passed';
  }
  finally { f.dispose(); }
}

export async function runDismissal(): Promise<string> {
  const f = mount();
  try {
    await settle();
    for (const kind of ['reviewers', 'assignees'] as const) {
      for (const dismiss of ['cancel', 'escape', 'outside'] as const) {
        await f.open(kind);
        await f.choose(kind, 'carol');
        if (dismiss === 'cancel') await f.click('Cancel');
        else {
          if (dismiss === 'escape') f.search(kind).dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
          else document.body.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }));
          await settle();
        }
        assert(f.host.querySelector(`[aria-label="Search ${kind}"]`) === null, `${dismiss} must close the picker`);
        await f.open(kind);
        assert(f.option('carol').getAttribute('aria-selected') === 'false' && button('Save changes', f.host).disabled,
          'Reopening must discard the cancelled draft');
        await f.click('Cancel');
      }
    }
    assert(f.reviewerWrites.length === 0 && f.assigneeWrites.length === 0, 'Dismissing drafts must never write to GitHub');

    await f.open('reviewers');
    await f.choose('reviewers', 'bob');
    await f.choose('reviewers', 'carol');
    f.refresh(detail => ({ ...detail, reviewers: detail.reviewers.map(value => ({ ...value, state: 'APPROVED' })) }));
    await settle();
    assert(f.option('bob').disabled && f.option('bob').getAttribute('aria-selected') === 'true', 'A newly completed review must stay selected and locked');
    await f.click('Save changes');
    assert(f.reviewerWrites.map(([, , login, requested]) => `${login}:${requested}`).join() === 'carol:true',
      'Saving after refresh must preserve completed reviews and keep the remaining draft');
    return 'Passed';
  }
  finally { f.dispose(); }
}

export async function runPartialFailure(): Promise<string> {
  const f = mount(2);
  try {
    await settle();
    await f.open('reviewers');
    await f.choose('reviewers', 'bob');
    await f.choose('reviewers', 'carol');
    await f.choose('reviewers', 'bytequay/core');
    await f.click('Save changes');
    assert(f.reviewerWrites.length === 2 && f.host.querySelector('[role="alert"]')?.textContent?.includes('Review request denied'),
      'A failed batch must show its error and stop at the failed reviewer');
    const [, , savedLogin, requested] = f.reviewerWrites[0];
    assert(f.detail().reviewers.some(value => value.login === savedLogin) === requested, 'The successful request must remain in the saved detail');
    assert(f.option('bob').getAttribute('aria-selected') === 'false' && f.option('carol').getAttribute('aria-selected') === 'true'
      && f.option('bytequay/core').getAttribute('aria-selected') === 'true', 'Failure must retain all pending choices for retry');
    await f.click('Save changes');
    assert(Number(f.reviewerWrites.length) === 4 && f.reviewerWrites.filter(([, , login]) => login === savedLogin).length === 1,
      'Retry must send only outstanding changes, never replay the successful request');
    assert(f.detail().reviewers.map(value => value.login).sort().join() === 'approved,bytequay/core,carol'
      && f.host.querySelector('[aria-label="Search reviewers"]') === null, 'Successful retry must finish and close the batch');
    return 'Passed';
  }
  finally { f.dispose(); }
}

export async function runPendingSave(): Promise<string> {
  const f = mount(0, true);
  try {
    await settle();
    await f.open('reviewers');
    await f.choose('reviewers', 'carol');
    const save = button('Save changes', f.host);
    save.click(); save.click(); await settle();
    assert(f.reviewerWrites.length === 1 && save.disabled && button('Cancel', f.host).disabled
      && [...f.host.querySelectorAll<HTMLButtonElement>('.pr-meta__chip')].every(chip => chip.disabled),
    'A pending save must block duplicate submission, cancellation and switching pickers');
    f.search('reviewers').dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
    document.body.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }));
    await settle();
    assert(f.host.querySelector('[aria-label="Search reviewers"]') !== null, 'A pending save must remain visible after dismissal attempts');
    f.refresh(detail => ({ ...detail, repo: 'other/repo', number: 99, reviewers: [] }));
    await settle();
    f.pending[0].resolve(); await settle();
    assert(f.detail().repo === 'other/repo' && f.detail().reviewers.length === 0, 'A late save must not replace another pull request’s metadata');
    await f.open('reviewers');
    assert(f.option('carol').getAttribute('aria-selected') === 'false' && button('Save changes', f.host).disabled,
      'Another pull request must open with its own saved selection');
    return 'Passed';
  }
  finally { f.dispose(); }
}
