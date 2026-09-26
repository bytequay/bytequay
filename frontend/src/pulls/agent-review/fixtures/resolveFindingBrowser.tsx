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
import type { Bridge, PullRequestDetailDto } from '../../../types';
import { DiffViewer } from '../../PullChanges';
import AgentReviewTab from '../AgentReviewTab';
import type { AgentReviewDto } from '../contracts';
import { reviewFixture } from './review';
import { assert, settle } from '../../../fixtures/harness';


export async function resolveFinding(projection: 'review' | 'changes'): Promise<void> {
  const finding = { ...reviewFixture().findings[0], path: 'src/Example.java', startLine: 2, endLine: 2, range: 'R2', excerpt: ['new code'] };
  let saved = reviewFixture({ findings: [finding], messages: [] });
  const requests: Parameters<Bridge['resolveAgentReviewFinding']>[] = [];
  const completions: Array<(failure?: Error) => void> = [];
  const originalBridge = window.bridge;
  const bridge = {
    getAgentReview: async () => saved,
    subscribeAgentReview: async () => () => {},
    resolveAgentReviewFinding: (...args) => {
      requests.push(args);
      return new Promise<AgentReviewDto>((resolve, reject) => completions.push(failure => {
        if (failure) { reject(failure); return; }
        saved = { ...saved, findings: [{ ...saved.findings[0], state: 'RESOLVED', stateReason: args[3] || null, version: args[2] + 1 }] };
        resolve(saved);
      }));
    },
    reopenAgentReviewFinding: async () => {
      saved = { ...saved, findings: [{ ...saved.findings[0], state: 'OPEN', stateReason: null, version: saved.findings[0].version + 1 }] };
      return saved;
    },
  } satisfies Pick<Bridge, 'getAgentReview' | 'subscribeAgentReview' | 'resolveAgentReviewFinding' | 'reopenAgentReviewFinding'>;
  Object.defineProperty(window, 'bridge', { configurable: true, value: bridge });
  const detail: PullRequestDetailDto = {
    id: 1, repo: saved.repo, number: saved.number, title: 'Example', htmlUrl: null, state: 'open', draft: false,
    mergeable: true, mergeableState: 'clean', author: 'author', authorAvatarUrl: null, createdAt: null, updatedAt: null,
    mergedAt: null, baseRef: 'master', headRef: 'example', body: null, labels: [], assignees: [], milestone: null,
    reviewers: [], additions: 1, deletions: 1, changedFiles: 1, approvalCount: 0, ciStatus: null,
    recentActivity: [], checkRuns: [], unavailableSections: [], viewerCanWrite: false,
  };
  function Surface() {
    const [review, setReview] = useState(saved);
    return projection === 'review'
      ? <AgentReviewTab detail={detail} workspaceId={review.workspaceId} viewer={null} review={review}
        onReviewChange={setReview} onNavigateToChanges={() => {}} focusFindingId={null} />
      : <DiffViewer files={[{ filename: finding.path, status: 'modified', additions: 1, deletions: 1,
        patch: '@@ -1,2 +1,2 @@\n context\n-old code\n+new code' }]}
      agentReview={{ review, viewer: null, navigation: null, onReviewChange: setReview, onOpenInReview: () => {} }} />;
  }
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  const card = () => {
    const value = host.querySelector<HTMLElement>(`[data-finding-id="${finding.id}"]`);
    assert(value !== null, 'The finding must render on the selected surface');
    return value;
  };
  const button = (text: string) => {
    const value = [...card().querySelectorAll<HTMLButtonElement>('button')].find(item => item.textContent === text);
    assert(value !== undefined, `Missing ${text} button`);
    return value;
  };
  const input = () => {
    const value = card().querySelector<HTMLTextAreaElement>('textarea[aria-label="Resolution reason (optional)"]');
    assert(value !== null, 'Resolve locally must open an in-app optional reason input');
    return value;
  };
  const write = async (text: string) => {
    Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set?.call(input(), text);
    input().dispatchEvent(new Event('input', { bubbles: true }));
    await settle();
  };
  try {
    flushSync(() => root.render(<Surface />));
    button('Resolve').click(); await settle();
    assert(document.activeElement === input(), 'Opening resolution must focus its reason input');
    assert(requests.length === 0 && !button('Resolve locally').disabled, 'Opening the input must not resolve; its reason is optional');
    await write('Cancel this reason');
    button('Cancel').click(); await settle();
    assert(card().querySelector('textarea') === null && requests.length === 0, 'Cancelling must leave the finding unchanged');
    button('Resolve').click(); await settle();
    assert(input().value === '', 'Cancelled reasons must be cleared');
    input().dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })); await settle();
    assert(card().querySelector('textarea') === null && requests.length === 0, 'Escape must cancel without saving');
    button('Resolve').click(); await settle();
    await write('  This case is already covered.  ');
    button('Resolve locally').click(); await settle();
    assert(Number(requests.length) === 1 && input().disabled && button('Cancel').disabled,
      'Pending resolution must retain its reason and disable editing/cancellation');
    assert(JSON.stringify(requests[0]) === JSON.stringify([saved.id, finding.id, finding.version, 'This case is already covered.']),
      'Resolution must retain review/finding/version identity and trim the optional reason');
    completions[0](new Error('The review changed; try again.')); await settle();
    assert(input().value.trim() === 'This case is already covered.' && !input().disabled
      && host.textContent?.includes('The review changed; try again.') === true, 'Failure must retain the reason and expose a retryable error');
    button('Resolve locally').click(); await settle();
    assert(Number(requests.length) === 2 && JSON.stringify(requests[1]) === JSON.stringify(requests[0]),
      'Only an explicit retry may resend the retained resolution');
    completions[1](); await settle();
    assert(card().querySelector('textarea') === null && card().textContent?.includes('Resolved locally by you') === true
      && card().textContent?.includes('This case is already covered.') === true, 'Accepted resolution must show the saved reason and close the input');
    assert(!host.textContent?.includes('The review changed; try again.'), 'Successful retry must clear its previous error');
    button('Reopen').click(); await settle();
    button('Resolve').click(); await settle();
    assert(input().value === '' && !button('Resolve locally').disabled, 'A reopened finding must allow resolution with no reason');
    button('Resolve locally').click(); await settle();
    assert(requests[2]?.[3] === '', 'Empty optional reason must reach the local resolution API');
    completions[2](); await settle();
    assert(card().textContent?.includes('Resolved locally by you') === true, 'Blank-reason resolution must complete');
    // The bridge has only private read/resolve/reopen operations; any outgoing call fails this scenario.
  }
  finally { flushSync(() => root.unmount()); host.remove(); Object.defineProperty(window, 'bridge', { configurable: true, value: originalBridge }); }
}
