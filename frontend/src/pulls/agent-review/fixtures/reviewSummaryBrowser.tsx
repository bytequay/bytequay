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
import { assert, changeInput, settle } from '../../../fixtures/harness';
import ReviewFindingCard from '../ReviewFindingCard';
import { useFindingActions } from '../useFindingActions';
import { reviewFixture } from './review';
import type { ReviewFindingDto } from '../contracts';

export async function run(): Promise<{ name: string; error?: string }[]> {
  const name = 'Whole-PR review prose stays editable and removable without changing private findings';
  const finding: ReviewFindingDto = { ...reviewFixture().findings[0], title: 'Clarify cancellation in the commit message',
    body: 'Queued loads are canceled when their last caller cancels; started loads run to completion.',
    severity: null, path: null, side: null, startLine: null, endLine: null, range: null, excerpt: [] };
  const other: ReviewFindingDto = { ...finding, id: 'other-finding', label: 'F-2', body: 'Describe the provider scope.' };
  const originalFindings = JSON.stringify([finding, other]);
  const originalBody = `Thanks for the change.\n\n${finding.title}\n\n${finding.body}\n\n${other.title}\n\n${other.body}\n\nMy own closing note.`;
  let saved = reviewFixture({ findings: [finding, other], messages: [], draft: {
    repo: 'trinodb/trino', number: 30304, body: originalBody, event: 'APPROVE', items: [], version: 4, updatedAt: finding.updatedAt,
  } });
  const requests: Parameters<Bridge['saveReviewDraft']>[] = [];
  const completions: Array<(failure?: Error) => void> = [];
  const previous = window.bridge;
  const bridge = {
    getAgentReview: async () => saved,
    betterWords: async (text, repo, workspaceId) => {
      assert(text === originalBody && repo === saved.repo && workspaceId === saved.workspaceId,
        'Rewriting uses the outgoing text and the review workspace');
      return originalBody.replace('Thanks for the change.', 'Thank you for the update.');
    },
    saveReviewDraft: (...args) => {
      requests.push(args);
      return new Promise<NonNullable<typeof saved.draft>>((resolve, reject) => completions.push(failure => {
        if (failure) { reject(failure); return; }
        assert(saved.draft !== null, 'The draft must exist');
        assert(args[0] === saved.repo && args[1] === saved.number && args[2] === saved.draft.version,
          'Summary edits must retain the exact PR and latest durable draft version');
        const draft = { ...saved.draft, body: args[3], event: args[4], version: args[2] + 1 };
        saved = { ...saved, draft };
        resolve(draft);
      }));
    },
    addReviewDraftFinding: async () => {
      assert(saved.draft !== null, 'The draft must exist');
      const draft = { ...saved.draft, body: `${saved.draft.body}\n\n${finding.title}\n\n${finding.body}`, version: saved.draft.version + 1 };
      saved = { ...saved, draft };
      return draft;
    },
  } satisfies Pick<Bridge, 'getAgentReview' | 'saveReviewDraft' | 'addReviewDraftFinding' | 'betterWords'>;
  Object.defineProperty(window, 'bridge', { configurable: true, value: bridge });
  function Surface() {
    const [review, setReview] = useState(saved);
    const { actions, busy } = useFindingActions({ review, onReviewChange: setReview, onNavigate: () => {} });
    return <ReviewFindingCard review={review} finding={review.findings[0]} projection="review"
      viewerLogin="reviewer" viewerAvatarUrl={null} actions={actions} busy={busy} />;
  }
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  const button = (text: string) => {
    const value = [...host.querySelectorAll<HTMLButtonElement>('button')].find(item => item.textContent === text);
    assert(value !== undefined, `Missing ${text} button`);
    return value;
  };
  const input = () => {
    const value = host.querySelector<HTMLTextAreaElement>('textarea[aria-label="Review summary text"]');
    assert(value !== null, 'Edit must open the review summary composer');
    return value;
  };
  const write = async (text: string) => { changeInput(input(), text); await settle(); };
  try {
    flushSync(() => root.render(<Surface />));
    assert(host.textContent?.includes('Outgoing review summary') === true, 'The finding must show its outgoing summary');
    button('Edit').click(); await settle();
    assert(input().value === originalBody && document.activeElement === input(), 'Edit must seed and focus the entire saved summary');
    assert(input().closest('.agr-finding__details') !== null && host.querySelector('.agr-draft, .lrc__foot') === null,
      'The summary editor must replace its outgoing box in place');
    button('Preview').click(); await settle();
    assert(button('Better words').disabled, 'Rewriting is disabled in Preview');
    button('Write').click(); await settle();
    button('Better words').click(); await settle();
    assert(input().value.includes('Thank you for the update.') && saved.draft?.body === originalBody && requests.length === 0,
      'Better words changes only the outgoing editor until Save');
    button('Discard').click(); await settle();
    assert(input().value === originalBody, 'Discard restores the outgoing text');
    await write('Discard this edit');
    button('Cancel').click(); await settle();
    assert(requests.length === 0 && saved.draft?.body === originalBody, 'Cancel must not change the saved summary');
    button('Edit').click(); await settle();
    const edited = originalBody.replace('Thanks for the change.', 'Thanks; I have two small comments.');
    await write(edited);
    button('Save').click(); await settle();
    assert(Number(requests.length) === 1 && requests[0][3] === edited && requests[0][4] === 'APPROVE'
      && input().disabled && button('Cancel').disabled, 'Saving must preserve the chosen event and block edits while pending');
    completions[0](new Error('The review changed; retry.')); await settle();
    assert(input().value === edited && !input().disabled && host.textContent?.includes('The review changed; retry.') === true,
      'A failed save must keep the editor text and expose a retryable error');
    button('Save').click(); await settle();
    assert(JSON.stringify(requests[1]) === JSON.stringify(requests[0]), 'Explicit retry must use the retained summary');
    completions[1](); await settle();
    assert(host.querySelector('textarea') === null && saved.draft?.body === edited,
      'Accepted edits must close the composer and refresh the saved summary');
    assert(!host.textContent?.includes('The review changed; retry.'), 'Accepted retry must clear the error');
    button('Remove').click(); await settle();
    const remaining = `Thanks; I have two small comments.\n\n\n\n${other.title}\n\n${other.body}\n\nMy own closing note.`;
    assert(requests[2]?.[3] === remaining && requests[2]?.[2] === 5,
      'Remove must save only the selected excerpt removal, retaining manual prose and the other finding');
    completions[2](new Error('Could not save the removal.')); await settle();
    assert(saved.draft?.body === edited && host.textContent?.includes('Could not save the removal.') === true,
      'Failed removal must keep the outgoing summary and show its error');
    button('Remove').click(); await settle();
    completions[3](); await settle();
    assert(saved.draft?.body === remaining && host.querySelector('.agr-draft') === null,
      'Accepted removal must update the finding publication state');
    button('Add to my review').click(); await settle();
    button('Edit').click(); await settle();
    await write('');
    assert(!button('Save').disabled, 'A review summary may be cleared');
    button('Save').click(); await settle();
    assert(requests[4]?.[3] === '', 'Clearing must persist the empty review summary');
    completions[4](); await settle();
    assert(saved.draft?.body === '' && host.querySelector('textarea') === null, 'An empty summary save must complete');
    assert(JSON.stringify(saved.findings) === originalFindings, 'Outgoing edits and removal must never rewrite agent findings');
    return [{ name }];
  }
  catch (error) { return [{ name, error: error instanceof Error ? error.message : String(error) }]; }
  finally { flushSync(() => root.unmount()); host.remove(); Object.defineProperty(window, 'bridge', { configurable: true, value: previous }); }
}
