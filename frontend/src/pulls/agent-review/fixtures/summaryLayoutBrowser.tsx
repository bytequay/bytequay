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
import { element as fixtureElement, assert, changeInput, settle } from '../../../fixtures/harness';
import { PullDetailHeader } from '../../PullsScreen';
import { pr } from '../../fixtures/pullRequest';
import AgentReviewTab from '../AgentReviewTab';
import type { AgentReviewDto, ReviewRunDto } from '../contracts';
import { candidateFixture, reviewFixture, runningReviewFixture } from './review';
import '../../../css/base.css';
import '../../../css/pulls.css';
import '../../../css/markdown-editor.css';
import '../../../css/agent-review.css';

export function preview(host: HTMLElement) {
  const review = runningReviewFixture();
  const run = review.runs[0];
  const commits = run.commits.slice(0, 3);
  const original = reviewFixture().runs[0];
  const value: AgentReviewDto = { ...review, observedHeadSha: 'b'.repeat(40), messages: [], findings: reviewFixture().findings.slice(0, 2).map((finding, index) => ({ ...finding,
    state: index === 0 ? 'OPEN' as const : 'RESOLVED' as const })), runs: [{ ...original, commits,
    summary: original.summary === null ? null : { ...original.summary, commits: original.summary.commits.slice(0, 3) } },
  ...Array.from({ length: 7 }, (_, index): ReviewRunDto => ({ ...original, id: `run-${index + 2}`, ordinal: index + 2, summary: null, headSha: String(index + 2).repeat(40) })),
  { ...run, id: 'run-9', ordinal: 9, commits, headSha: 'b'.repeat(40),
    createdAt: new Date(Date.now() - 1_803_000).toISOString(), summary: null,
    investigation: { changeMap: 'Follow the sort field from parsing to storage.', reviewedFiles: ['src/SortField.java'], contextEvidenceRefs: [], perspectives: [], candidates: [candidateFixture('Check nested lookup')], validations: [], answers: [] },
    steps: [{ number: 2, kind: 'VALIDATE' as const, repair: false, investigation: null, validation: null }],
  }] };
  const previous = window.bridge;
  const calls: string[] = [];
  const bridge = { subscribeAgentReview: async () => () => {}, getAgentReview: async () => value,
    askAgentReview: async (_id, _finding, revision, body, _key, cardId) => { calls.push(`${cardId}:rev${revision}:${body}`); return value; },
    stopAgentReview: async () => { calls.push('stop'); return value; },
  } satisfies Pick<Bridge, 'subscribeAgentReview' | 'getAgentReview' | 'askAgentReview' | 'stopAgentReview'>;
  Object.defineProperty(window, 'bridge', { configurable: true, writable: true, value: bridge });
  function Surface() {
    const [target, setTarget] = useState<HTMLDivElement | null>(null);
    const [tab, setTab] = useState<'review' | 'files'>('review');
    return <>
      <PullDetailHeader pr={pr({ number: 30304, repo: value.repo })} detail={null} tab={tab} review={value}
        onSummaryToolbar={setTarget} onTab={next => setTab(next === 'review' ? 'review' : 'files')}
        onRefresh={async () => {}} onReviewSubmitted={() => {}} refreshing={false} pendingComments={[]} onPendingCommentsChange={() => {}} />
      <div className="pull-detail__body pull-detail__review" hidden={tab !== 'review'}>
        <AgentReviewTab review={value} workspaceId={value.workspaceId} viewer={null} ownStream={false}
          toolbarTarget={target} onReviewChange={() => {}} focusFindingId={null} onNavigateToChanges={() => {}}
          onSelectCommit={(sha, snapshot) => calls.push(`diff:${sha}:${snapshot?.headSha}`)} />
      </div>
    </>;
  }
  host.style.cssText = 'display:flex;flex-direction:column;width:1060px;height:900px;background:white';
  const root = createRoot(host);
  flushSync(() => root.render(<Surface />));
  return { calls, review: value, close: () => {
    flushSync(() => root.unmount());
    Object.defineProperty(window, 'bridge', { configurable: true, writable: true, value: previous });
  } };
}

export async function run() {
  const host = document.createElement('div');
  document.body.append(host);
  const fixture = preview(host);
  const button = (selector: string, label: string) => {
    const found = [...host.querySelectorAll<HTMLButtonElement>(selector)].find(item => item.textContent?.trim().startsWith(label));
    assert(found !== undefined, `Missing ${label}`);
    return found;
  };
  try {
    await settle();
    const toolbar = host.querySelector('.pull-detail__subtoolbar');
    assert(toolbar?.querySelector('.agr-strip') !== null && !host.querySelector('.agr-pane > .agr-strip'), 'Review controls must share the WorkBench tab row');
    const intention = host.querySelector<HTMLElement>('.agr-explanation.is-purpose');
    const commits = [...host.querySelectorAll<HTMLElement>('.agr-explanation.is-commit')];
    assert(host.textContent?.includes('Original change explanation · head d9a0c41') === true && !intention?.textContent?.includes('Provisional'),
      'The retained explanation must identify its original head and keep its accepted status');
    assert(host.querySelector('[data-review-card="run-9:details"]')?.textContent?.includes('Follow the sort field from parsing to storage.') === true,
      'Current review details must remain visible beside the original explanation');
    const outline = [...host.querySelectorAll<HTMLButtonElement>('.agr-summary-nav__item')];
    assert(intention !== null && commits.length === 3 && outline.length === 4 && intention.getBoundingClientRect().height > 0
      && commits.every(card => card.getBoundingClientRect().height === 0), 'The outline must list the intention and three commits and open on the intention');
    assert(outline[1].getBoundingClientRect().left < intention.getBoundingClientRect().left, 'The outline must sit beside the selected card');
    outline[1].click(); await settle();
    assert(commits[0].getBoundingClientRect().height > 0 && intention.getBoundingClientRect().height === 0
      && outline[1].getAttribute('aria-current') === 'true', 'Picking a commit must show only that commit');
    const finding = host.querySelector<HTMLElement>('.agr-finding');
    assert(finding !== null && finding.querySelector('.lrc.is-local') !== null, 'Findings must retain the existing local card');
    assert(finding.getBoundingClientRect().height === 0 && host.textContent?.includes('No findings in this round.') === true,
      'Findings must open on the last review round');
    const filters = host.querySelector('.agr-findings__filters');
    const others = filters?.querySelector('select');
    assert(filters !== null && others !== null && others !== undefined, 'Older rounds must be available in Others');
    assert([...filters.querySelectorAll('button')].map(item => item.textContent).join('|') === 'All rounds2|Last round0|Round 80',
      'Only All rounds and the two latest rounds must be shown as buttons');
    assert([...others.options].map(option => option.textContent).join('|') === 'Others|Round 7 · 0|Round 6 · 0|Round 5 · 0|Round 4 · 0|Round 3 · 0|Round 2 · 0|Round 1 · 2',
      'Others must list every older round newest first, including empty rounds and finding counts');
    others.value = fixture.review.runs[0].id;
    others.dispatchEvent(new Event('change', { bubbles: true })); await settle();
    assert(finding.getBoundingClientRect().height > 0 && others.classList.contains('is-selected')
      && host.querySelector('.agr-findings__meta')?.textContent?.startsWith('Round 1 · head') === true,
      'Selecting an older round must show its findings and identify the active round');
    button('.agr-finding button', 'Ask reviewer').click(); await settle();
    const draft = fixtureElement<HTMLTextAreaElement>('textarea', finding, 'Finding discussion must remain available');
    changeInput(draft, 'Keep this private draft'); await settle();
    button('.agr-findings__filters button', 'Last round').click(); await settle();
    assert(finding.getBoundingClientRect().height === 0 && others.value === '' && !others.classList.contains('is-selected'),
      'Returning to a recent round must hide older findings and reset Others');
    button('.agr-findings__filters button', 'All rounds').click(); await settle();
    assert(finding.querySelector('textarea')?.value === 'Keep this private draft', 'Switching rounds must retain a finding draft and its card instance');
    button('.agr-explanation.is-commit button', 'Ask reviewer').click(); await settle();
    const question = fixtureElement<HTMLTextAreaElement>('textarea', commits[0], 'The compact question icon must open its own composer');
    changeInput(question, 'Explain the boundary'); await settle();
    button('.agr-explanation.is-commit .agr-composer button', 'Queue question').click(); await settle();
    assert(fixture.calls[0] === `${fixture.review.runs[0].id}:commit:${fixture.review.runs[0].commits[0].sha}:rev1:Explain the boundary`,
      'Questions must retain their original run, commit and summary revision');
    button('.agr-explanation.is-commit button', 'Diff').click(); await settle();
    assert(fixture.calls[1] === `diff:${fixture.review.runs[0].commits[0].sha}:${fixture.review.runs[0].headSha}`, 'Diff must retain the frozen head');
    for (const width of [600, 420]) {
      host.style.width = `${width}px`; await settle();
      assert(host.scrollWidth <= host.clientWidth + 1 && commits.every(card => card.scrollWidth <= card.clientWidth + 1), 'Narrow panes must wrap without horizontal overflow');
    }
    button('.pull-detail__subtabs button', 'Changes').click(); await settle();
    assert(host.querySelector('.pull-detail__summary-toolbar') === null, 'Summary controls must leave the header on other tabs');
    button('.pull-detail__subtabs button', 'Summary').click(); await settle();
    button('.agr-strip button', 'Stop').click(); await settle();
    assert(fixture.calls.at(-1) === 'stop', 'Stop must remain wired after returning to Summary');
    return [{ name: 'Compact Summary retains controls, discussions and narrow layouts' }];
  }
  catch (error) { return [{ name: 'Compact Summary', error: error instanceof Error ? error.message : String(error) }]; }
  finally { fixture.close(); host.remove(); }
}
