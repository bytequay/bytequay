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
import { useState } from 'react';
import { flushSync } from 'react-dom';
import { createRoot } from 'react-dom/client';
import type { Bridge } from '../../../types';
import { assert, settle } from '../../../fixtures/harness';
import AgentReviewTab from '../AgentReviewTab';
import type { AgentReviewDto } from '../contracts';
import { reviewFixture, runningReviewFixture } from './review';

export async function run(): Promise<{ name: string; error?: string }[]> {
  const previous = window.bridge;
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  const results: { name: string; error?: string }[] = [];
  const completed = reviewFixture();
  let saved = completed;
  let finish: (value: AgentReviewDto) => void = () => {};
  let fail: (error: Error) => void = () => {};
  let show: (review: AgentReviewDto) => void = () => {};
  let resumes = 0;
  const bridge = {
    getAgentReview: async () => saved,
    updateAgentReview: (id: string) => {
      assert(id === saved.id, 'Update the selected review');
      return new Promise<AgentReviewDto>((resolve, reject) => {
        finish = value => { saved = value; resolve(value); };
        fail = reject;
      });
    },
    resumeAgentReview: (id: string) => {
      assert(id === saved.id, 'Resume the selected review');
      resumes++;
      return new Promise<AgentReviewDto>((resolve, reject) => {
        finish = value => { saved = value; resolve(value); };
        fail = reject;
      });
    },
  } satisfies Pick<Bridge, 'getAgentReview' | 'updateAgentReview' | 'resumeAgentReview'>;
  Object.defineProperty(window, 'bridge', { configurable: true, value: bridge });
  const mount = (initial = completed) => {
    saved = initial;
    function Surface() {
      const [review, setReview] = useState(initial);
      show = setReview;
      return <AgentReviewTab workspaceId={review.workspaceId} viewer={null} review={review} onReviewChange={setReview}
        ownStream={false} focusFindingId={null} onNavigateToChanges={() => {}} />;
    }
    flushSync(() => root.render(<Surface />));
  };
  const buttons = (label: string) => [...host.querySelectorAll<HTMLButtonElement>('button')].filter(button => button.textContent === label);
  const notice = () => host.querySelector('[role="status"]');
  const click = async (index = 0) => {
    const button = buttons('Update review')[index];
    assert(button !== undefined && !button.disabled, 'Update review must be available');
    button.click(); await settle();
    assert(buttons('Checking…').every(item => item.disabled && item.getAttribute('aria-busy') === 'true')
      && buttons('Checking…').length > 0, 'Show checking and prevent duplicate updates until the response arrives');
    assert(notice() === null, 'Clear the previous result while checking');
  };
  const scenario = async (name: string, test: () => Promise<void>) => {
    try { await test(); results.push({ name }); }
    catch (cause) { results.push({ name, error: cause instanceof Error ? cause.message : String(cause) }); }
    finally { flushSync(() => root.render(null)); }
  };
  try {
    await scenario('An unfinished review resumes from its failure banner and remains retryable after an error', async () => {
      mount({ ...completed, runs: [{ ...completed.runs[0], state: 'FAILED', coverage: null, outcome: 'Provider rate limit' }] });
      const bannerButton = host.querySelector<HTMLButtonElement>('.agr-banner.is-danger .is-primary');
      assert(bannerButton?.textContent === 'Resume review' && !bannerButton.disabled, 'Put the resume action beside the failure');
      bannerButton.click(); await settle();
      assert(resumes === 1 && buttons('Resume review').length === 0, 'Start one review attempt and prevent duplicate submissions');
      fail(new Error('Provider is still rate limited')); await settle();
      assert(host.querySelector('[role="alert"]')?.textContent === 'Provider is still rate limited', 'Keep the retry error visible');
      const toolbarButton = buttons('Resume review')[0];
      assert(toolbarButton !== undefined && !toolbarButton.disabled, 'Restore the resume action after a rejected attempt');
      toolbarButton.click(); await settle();
      finish(runningReviewFixture()); await settle();
      assert(Number(resumes) === 2 && buttons('Stop').length === 1 && buttons('Resume review').length === 0,
        'Adopt the resumed review and replace recovery actions with Stop');
    });
    await scenario('An unchanged review confirms the check, clears on retry, and preserves errors', async () => {
      mount();
      assert(notice() === null, 'Only a deliberate update should show the result');
      await click(); finish(completed); await settle();
      assert(notice()?.textContent?.includes('Nothing new to review') === true, 'Confirm that the review already covers the changes');
      await click(); fail(new Error('Cannot fetch pull request')); await settle();
      assert(host.querySelector('[role="alert"]')?.textContent === 'Cannot fetch pull request' && notice() === null,
        'A failed refresh must show its error, not claim the review is current');
      await click(); finish(completed); await settle();
      assert(notice() !== null, 'A successful retry must show the result again');
      flushSync(() => show({ ...completed, observedHeadSha: 'new-head' }));
      assert(notice() === null, 'New commits must hide a stale up-to-date notice');
    });
    await scenario('The new-commits control explains when the previous review is reused', async () => {
      const head = 'new-head';
      mount({ ...completed, observedHeadSha: head });
      assert(buttons('Update review').length === 2, 'Exercise the banner control as well as the toolbar');
      await click(1);
      finish({ ...completed, observedHeadSha: head, runs: [...completed.runs, {
        ...completed.runs[0], id: 'carried-run', ordinal: 2, headSha: head, worktreeTreeSha: null, priorHeadSha: completed.runs[0].headSha, turnIds: [], summary: null,
      }] });
      await settle();
      assert(notice()?.textContent?.includes('Previous review reused') === true
        && notice()?.textContent?.includes('code and commit messages are unchanged') === true, 'Explain why no new agent turn started');
      assert(host.textContent?.includes('Original change explanation') === true, 'Retain the separately refreshed explanation');
      flushSync(() => show({ ...completed, id: 'another-review' }));
      assert(notice() === null, 'Do not carry feedback into a different review');
    });
    const responses: [string, AgentReviewDto][] = [
      ['a running review', runningReviewFixture()],
      ['a queued input', { ...completed, queuedInputIds: ['queued-input'] }],
      ['a failed run', { ...completed, runs: [{ ...completed.runs[0], state: 'FAILED', outcome: 'Review failed' }] }],
      ['a new review that completed quickly', { ...completed, runs: [...completed.runs, { ...completed.runs[0], id: 'new-run', ordinal: 2, turnIds: ['new-turn'] }] }],
    ];
    for (const [name, response] of responses) {
      await scenario(`Do not report nothing new for ${name}`, async () => {
        mount(); await click(); finish(response); await settle();
        assert(notice() === null, 'Only a completed check with no new review work should report nothing new');
      });
    }
  }
  finally {
    flushSync(() => root.unmount()); host.remove();
    Object.defineProperty(window, 'bridge', { configurable: true, value: previous });
  }
  return results;
}
