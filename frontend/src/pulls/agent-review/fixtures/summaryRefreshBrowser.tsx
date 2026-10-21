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
import { createFixtureRoot } from '../../../fixtures/reactFixture';
import type { Bridge } from '../../../types';
import type { ReviewCardFocus } from '../../../agents/ReviewRequestCard';
import { element as fixtureElement, assert, changeInput, settle } from '../../../fixtures/harness';
import AgentReviewTab from '../AgentReviewTab';
import type { AgentReviewDto } from '../contracts';
import { explanationRunFixture, reviewFixture } from './review';
import '../../../css/base.css';
import '../../../css/pulls.css';
import '../../../css/markdown-editor.css';
import '../../../css/agent-review.css';

export async function run() {
  const { host, root, dispose } = createFixtureRoot();
  host.style.cssText = 'width:900px;background:white';
  const original = reviewFixture({ messages: [] });
  const attempt = explanationRunFixture({ state: 'RUNNING', endedAt: null, headSha: 'a'.repeat(40) });
  let server = original;
  let publish: (value: AgentReviewDto) => void = () => {};
  let focusCard: (value: ReviewCardFocus) => void = () => {};
  let rejectRequest: (error: Error) => void = () => {};
  const calls: Parameters<Bridge['refreshAgentReviewExplanation']>[] = [];
  const questions: Parameters<Bridge['askAgentReview']>[] = [];
  const diffs: string[] = [];
  const previous = window.bridge;
  const bridge = {
    getAgentReview: async () => server,
    refreshAgentReviewExplanation: async (...args) => {
      calls.push(args);
      if (calls.length === 1) return new Promise<AgentReviewDto>((_resolve, reject) => { rejectRequest = reject; });
      server = { ...original, observedHeadSha: attempt.headSha, runs: [...original.runs, attempt] };
      if (calls.length === 3) return new Promise<AgentReviewDto>((_resolve, reject) => { rejectRequest = reject; });
      return server;
    },
    askAgentReview: async (...args) => { questions.push(args); return server; },
  } satisfies Pick<Bridge, 'getAgentReview' | 'refreshAgentReviewExplanation' | 'askAgentReview'>;
  Object.defineProperty(window, 'bridge', { configurable: true, writable: true, value: bridge });
  function Surface() {
    const [value, setValue] = useState(server);
    const [focus, setFocus] = useState<ReviewCardFocus | null>(null);
    publish = next => { server = next; setValue(next); };
    focusCard = setFocus;
    return <AgentReviewTab review={value} workspaceId={value.workspaceId} viewer={null} ownStream={false}
      onReviewChange={publish} focusFindingId={null} focusReviewCard={focus} onNavigateToChanges={() => {}}
      onSelectCommit={(sha, snapshot) => diffs.push(`${sha}:${snapshot?.headSha}`)} />;
  }
  const refresh = () => {
    const button = fixtureElement<HTMLButtonElement>('[aria-label="Refresh change intention and commit summaries"]', host, 'Refresh must be a labeled native button');
    return button;
  };
  try {
    flushSync(() => root.render(<Surface />));
    await settle();
    const title = host.querySelector<HTMLElement>('.agr-explanation__heading > .lrc__title');
    assert(title !== null && refresh().getBoundingClientRect().left - title.getBoundingClientRect().right < 12,
      'The refresh icon must sit beside Change intention');
    refresh().click(); await settle();
    assert(refresh().disabled && refresh().getAttribute('aria-busy') === 'true', 'Starting the request prevents duplicate refreshes');
    assert(host.querySelector('[data-review-card="run-1:purpose"]') !== null, 'The existing text stays visible while starting');
    assert(host.querySelector('.agr-explanation.is-purpose > details')?.hasAttribute('open') === true, 'Refresh must not collapse the card');
    rejectRequest(new Error('Provider is unavailable')); await settle();
    assert(host.querySelector('[role="alert"]')?.textContent?.includes('Provider is unavailable'), 'A request error stays beside the explanation');
    assert(!refresh().disabled && host.querySelector('[data-review-card="run-1:purpose"]') !== null, 'Failure keeps the prior text available for retry');

    refresh().click(); await settle();
    assert(JSON.stringify(calls[1]) === JSON.stringify([original.id, original.runs[0].id, original.runs[0].summary?.revision]),
      'Refresh sends the displayed owner and revision to its dedicated bridge action');
    assert(refresh().disabled && host.textContent?.includes('Refreshing change intention and commit summaries…'),
      'Durable RUNNING refresh state keeps the control busy after the request finishes');
    assert(!host.textContent?.includes('Refreshed change intention.'), 'A saved draft is not shown before successful provider completion');
    publish({ ...server, runs: [...original.runs, { ...attempt, state: 'FAILED', outcome: 'Provider stopped before completion.' }] });
    await settle();
    assert(!refresh().disabled && host.textContent?.includes('Provider stopped before completion.'), 'A failed refresh displays its durable error and allows retry');
    assert(host.querySelectorAll('.agr-finding').length === original.findings.length, 'Refresh failure must retain finding cards');

    refresh().click(); await settle();
    const accepted = { ...attempt, state: 'ACCEPTED' as const, endedAt: original.runs[0].endedAt };
    publish({ ...server, runs: [...original.runs, accepted] }); await settle();
    rejectRequest(new Error('Refresh response was lost')); await settle();
    assert(!host.querySelector('.agr-explanation.is-purpose [role="alert"]'),
      'A lost request response must not leave an old error on the explanation already accepted by the server');
    assert(host.querySelector('[data-review-card="explanation-1:purpose"]') !== null && host.querySelector('[data-review-card="run-1:purpose"]') === null,
      'Only the accepted complete explanation replaces the saved intention');
    assert(host.querySelectorAll('.agr-explanation.is-commit').length === accepted.commits.length
      && [...host.querySelectorAll('.agr-explanation.is-commit')].every(card => card.textContent?.includes('Refreshed explanation for')),
    'All commit summaries switch with the intention');
    assert(host.querySelectorAll('.agr-finding').length === original.findings.length
      && host.querySelector('[data-review-card="run-1:details"]') !== null, 'Finding results and their details remain tied to the ordinary review');
    assert(!host.querySelector('.agr-explanation.is-purpose')?.textContent?.includes('Provisional'), 'Accepted explanations do not require findings coverage');
    assert(host.textContent?.includes('New commits'), 'Refreshing the explanation must not claim that the new head was reviewed for findings');

    const commit = host.querySelector('.agr-explanation.is-commit');
    commit?.querySelector<HTMLButtonElement>('[aria-label="View changes"]')?.click(); await settle();
    assert(diffs[0] === `${accepted.commits[0].sha}:${accepted.headSha}`, 'The refreshed commit diff uses its new frozen head');
    commit?.querySelector<HTMLButtonElement>('[aria-label="Ask reviewer about this commit"]')?.click(); await settle();
    const question = commit?.querySelector('textarea');
    assert(question != null, 'The refreshed explanation retains discussion');
    changeInput(question, 'Explain this updated commit'); await settle();
    const submit = [...commit.querySelectorAll<HTMLButtonElement>('.agr-composer button')].find(button => button.textContent?.trim() === 'Ask reviewer');
    assert(submit !== undefined, 'The refreshed discussion is ready to send');
    submit.click(); await settle();
    assert(questions[0]?.[5] === `${accepted.id}:commit:${accepted.commits[0].sha}` && questions[0]?.[2] === accepted.summary?.revision,
      'Questions bind to the new explanation owner and summary revision');

    focusCard({ target: { kind: 'card', runId: original.runs[0].id, cardId: `${original.runs[0].id}:purpose` }, token: 1 }); await settle();
    assert(host.querySelector('[data-review-card="run-1:purpose"]') !== null && host.textContent?.includes('Earlier explanation'),
      'A historical request can still open the original explanation even when that run remains the findings baseline');
    assert(host.querySelector('[aria-label="Refresh change intention and commit summaries"]') === null, 'Historical explanations have no refresh action');
    const latest = [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Show latest review');
    assert(latest !== undefined, 'Historical explanation navigation must offer a return to the current explanation');
    latest.click(); await settle();
    assert(host.querySelector('[data-review-card="explanation-1:purpose"]') !== null, 'Returning restores the accepted refresh');

    host.style.width = '420px'; await settle();
    assert(host.scrollWidth <= host.clientWidth + 1, 'The refresh control must fit narrow layouts');
    return [{ name: 'Explanation refresh preserves findings, atomically replaces text and retains bindings' }];
  }
  catch (error) { return [{ name: 'Explanation refresh', error: error instanceof Error ? error.message : String(error) }]; }
  finally {
    dispose();
    Object.defineProperty(window, 'bridge', { configurable: true, writable: true, value: previous });
  }
}
