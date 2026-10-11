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
import { useState } from 'react';
import { createRoot } from 'react-dom/client';
import { ReviewRequestCard, type ReviewCardFocus } from '../ReviewRequestCard';
import AgentReviewTab from '../../pulls/agent-review/AgentReviewTab';
import { reviewRequests } from '../../pulls/agent-review/model';
import { reviewFixture } from '../../pulls/agent-review/fixtures/review';
import { findingConversation, findingFixPrompt } from '../../pulls/local-review/conversation';
import { TurnView } from '../AgentConversation';
import { parseAgentTurn } from '../contracts';
import contracts from './agent-contracts-v1.json';
import '../../css/base.css';
import '../../css/agents.css';
import '../../css/agent-review.css';
import { assert, settle } from '../../fixtures/harness';

const question = "Please provide the Maven command that you used. Why did you compile the tests directly rather than building the Snowflake module first?";
const body = question + '\n\n' + 'Keep this complete question available when I open the card. '.repeat(12)
  + '\n' + 'module/'.repeat(36) + 'SnowflakeCatalogConfig.java';
const detail = 'Limitations · revision 1 · reviewed head 79e7210';

const host = document.createElement('main');
host.style.padding = '24px';
document.body.append(host);
const root = createRoot(host);
let actions = 0;
let openedReviews = 0;
let closedHeight = 0;

function card() {
  const result = host.querySelector<HTMLElement>('.agt-review-request');
  assert(result !== null, 'Review request must render a compact card');
  return result;
}
function toggle() {
  const result = card().querySelector<HTMLButtonElement>('.agt-review-request__toggle');
  assert(result !== null, 'Review request must have an independent disclosure button');
  return result;
}

export async function mount() {
  actions = 0;
  openedReviews = 0;
  flushSync(() => root.render(<div className="agt-user">
    <ReviewRequestCard key={innerWidth} onOpen={() => { openedReviews++; }} action="Ask about review" topic="Limitations · 79e7210" body={body} detail={detail}>
      <div className="agt-review-request__body">{body}</div>
      <div style={{ display: 'flex', gap: 12 }}>
        <button type="button" onClick={() => { actions++; }}>Retry</button>
      </div>
    </ReviewRequestCard>
  </div>));
  await settle();
  closedHeight = card().getBoundingClientRect().height;
  assert(closedHeight <= 100, 'Collapsed request must stay compact even with a long question');
  assert(toggle().querySelector('button, a') === null, 'Disclosure must not nest another action');
  const bar = card().querySelector<HTMLElement>('.agt-review-request__bar');
  assert(bar !== null && getComputedStyle(bar).backgroundColor === 'rgb(232, 244, 241)', 'The request title bar must use the private-review green background');
  toggle().focus();
  assert(document.activeElement === toggle(), 'Disclosure must be keyboard focusable');
}

export async function verify(open: boolean, expectedActions = 0, expectedOpens = 0) {
  await settle();
  const request = card();
  assert((toggle().getAttribute('aria-expanded') === 'true') === open, `Expected card to be ${open ? 'expanded' : 'collapsed'}`);
  assert(request.textContent?.includes(body) === true && request.textContent.includes(detail), 'Complete question and context must survive disclosure');
  assert(actions === expectedActions && openedReviews === expectedOpens, 'Disclosure and review navigation must remain separate actions');
  assert(document.documentElement.scrollWidth <= innerWidth, 'Request must not overflow the viewport');
  assert(request.scrollWidth <= request.clientWidth + 1, 'Request content must not overflow horizontally');
  assert(request.getBoundingClientRect().right <= innerWidth, 'Request must fit the narrow transcript');
  if (open) {
    assert(request.querySelector('.agt-review-request__body')?.textContent === body, 'Expanded request must expose the complete original body');
    assert(request.getBoundingClientRect().height > closedHeight, 'Opening the request must reveal the complete question');
    const button = request.querySelector('.agt-review-request__content button');
    assert(button !== null && button.getBoundingClientRect().height > 0, 'Local discussion actions must remain visible on expansion');
  }
  return { width: innerWidth, open, height: request.getBoundingClientRect().height };
}

export function clickToggle() { toggle().click(); }
export function openReview() { card().querySelector<HTMLButtonElement>('.agt-review-request__open')?.click(); }
export function retry() { card().querySelector<HTMLButtonElement>('.agt-review-request__content button')?.click(); }

export async function fixRequest(delivery = 'TURN', snapshot = false) {
  const turn = parseAgentTurn(contracts.turn);
  const review = reviewFixture({ sourceTaskId: turn.taskId });
  const finding = review.findings[0];
  const instruction = 'Please investigate and address this finding. Keep the existing public API.';
  const prompt = snapshot ? `${instruction}\n\n${findingConversation(review, finding)}` : findingFixPrompt(review, finding, instruction);
  function Surface() {
    const [focus, setFocus] = useState<ReviewCardFocus | null>(null);
    return <><TurnView
      turn={{ turnId: turn.id, ordinal: 2, blocks: [], answer: null, startedAt: null, endedAt: null, outcome: null,
        users: [...(delivery === 'STEER' ? [{ id: 'ordinary', text: 'Continue the implementation.', attachments: [], delivery: 'TURN', at: '' }] : []),
          { id: 'fix', text: prompt, attachments: [], delivery, at: '' }] }}
      first={false} open={false} now={0} models={[]} durableTurns={{ [turn.id]: turn }} activityChoice={{}} previews={{}} agentLabels={{}}
      onToggle={() => {}} onToggleActivity={() => {}} onOpenReview={target => setFocus(value => ({ target, token: (value?.token ?? 0) + 1 }))} />
      {focus !== null && <div data-fix-review><AgentReviewTab review={{ ...review, state: 'CLOSED', findings: [{ ...finding, state: 'RESOLVED' }] }}
        workspaceId={review.workspaceId} viewer={null} ownStream={false} onReviewChange={() => {}} focusFindingId={null}
        focusReviewCard={focus} onNavigateToChanges={() => {}} /></div>}</>;
  }
  flushSync(() => root.render(<Surface key={`${innerWidth}:${delivery}:${snapshot}`} />));
  await settle();
  const request = card();
  assert(request.innerText.includes(snapshot ? 'Fix the agent review' : 'Request fix') && request.innerText.includes(`#${finding.label}`)
    && request.innerText.includes(finding.title), 'The fix card must name its finding');
  assert(request.innerText.includes(instruction) && !request.innerText.includes('Private ByteQuay review context'),
    'Only the user instruction belongs in the compact preview');
  assert(request.getBoundingClientRect().height <= 130 && request.scrollWidth <= request.clientWidth + 1,
    'The long fix prompt must stay compact and fit narrow conversations');
  const topic = request.querySelector<HTMLElement>('.agt-review-request__topic');
  assert(topic !== null && topic.clientWidth >= 140, 'The finding number and title need their own readable line at narrow widths');
  if (delivery === 'STEER') assert(host.querySelector('.agt-user__bubble')?.textContent === 'Continue the implementation.',
    'A queued fix steered into a turn must preserve the ordinary opening message');
  toggle().click(); await settle();
  assert(request.innerText.includes(instruction) && !request.innerText.includes(finding.id) && !request.innerText.includes(finding.evidence[0].excerpt)
    && !request.innerText.includes('list_local_review_comments'), 'Expanded fix details must show the request without machine context or saved logs');
  const sentPrompt = request.querySelector<HTMLDetailsElement>('.agt-review-request__prompt');
  assert(sentPrompt !== null && !sentPrompt.open, 'Exact agent instructions must have their own closed disclosure');
  sentPrompt.querySelector('summary')?.click(); await settle();
  assert(sentPrompt.open && sentPrompt.innerText.includes(finding.id) && sentPrompt.innerText.includes(finding.validatedHeadSha),
    'The exact request sent to the agent must remain inspectable');
  assert(snapshot ? sentPrompt.innerText.includes(finding.evidence[0].excerpt)
    : sentPrompt.innerText.includes('list_local_review_comments') && !sentPrompt.innerText.includes(finding.evidence[0].excerpt),
  'New requests must fetch evidence through tools while saved requests retain their original text');
  assert(document.documentElement.scrollWidth <= innerWidth && request.scrollWidth <= request.clientWidth + 1,
    'The complete prompt must wrap within narrow conversations');
  toggle().click(); await settle();
  openReview(); await settle();
  const destination = host.querySelector<HTMLElement>(`[data-fix-review] [data-finding-id="${finding.id}"]`);
  assert(destination !== null && destination.querySelector('details')?.open === true
    && (document.activeElement === destination || destination.contains(document.activeElement)),
  'Opening a fix must show, expand and focus its original finding even after resolution');
  assert(toggle().getAttribute('aria-expanded') === 'false', 'Opening the finding must keep the generated prompt folded');
  return { width: innerWidth, delivery, snapshot };
}

export async function generatedPrompt(markdown = false) {
  const path = 'worktree/'.repeat(60) + 'Implementation.java';
  const prompt = markdown ? '# ByteQuay · Refining summary\n\nRefresh the summary from the final code and actual commits.\n\n'
    + '## Scope\n\n- **Worktree:** `' + path + '`\n- **Expected HEAD:** `9d4fa8f`\n\n'
    + '## Instructions\n\n- Read the current diff and commit history, including uncommitted changes.\n'
    + '- Explain the task’s intention and each commit’s design.\n- Preserve the existing question.\n\n'
    + '## Report\n\nCall `report_development_result` with `readyForDelivery=true` and `learningSummary`.\n\n'
    + 'This records the private summary; it does not authorize delivery of held work.'
    : 'ByteQuay · Refining summary\nRefresh this development task’s private Review & Learning cards from the final code and actual commits.\n'
    + 'Read the current diff and commit history, including any uncommitted changes. '.repeat(30)
    + '\n' + path;
  const turn = parseAgentTurn({ ...contracts.turn, developmentOrigin: { kind: 'REFINE_SUMMARY', parentUserTurnId: 'source-turn' } });
  flushSync(() => root.render(<TurnView
    turn={{ turnId: turn.id, ordinal: 1, blocks: [], answer: null, startedAt: null, endedAt: null, outcome: null,
      users: [{ id: 'generated', text: prompt, attachments: [], delivery: 'TURN', at: '2026-01-01T12:00:00Z' }] }}
    first={false} open={false} now={0} models={[]} durableTurns={{ [turn.id]: turn }} activityChoice={{}} previews={{}} agentLabels={{}}
    onToggle={() => {}} onToggleActivity={() => {}} />));
  await settle();
  const request = card();
  const content = request.querySelector<HTMLElement>('.agt-review-request__content');
  assert(content !== null, 'Generated prompt must have a disclosure');
  assert(host.querySelector('.agt-user__bubble') === null, 'Generated prompt must not render as an ordinary user bubble');
  assert(request.querySelector('.agt-user__request-action')?.textContent === 'ByteQuay · Refine summary', 'Card must name the trusted origin');
  assert(content.hidden && content.getBoundingClientRect().height === 0, 'Generated instructions must start hidden');
  assert(request.getBoundingClientRect().height <= 100, 'Generated request must remain compact');
  assert(request.scrollWidth <= request.clientWidth + 1, 'Collapsed generated request must fit its card');
  toggle().click();
  await settle();
  assert(!content.hidden && content.getBoundingClientRect().height > 0, 'Disclosure must expose generated instructions');
  const rendered = request.querySelector('.agt-review-request__body > .agt-md');
  assert(rendered !== null, 'Expanded instructions must use the conversation Markdown renderer');
  if (markdown) {
    assert(rendered.querySelector('p > strong')?.textContent === 'ByteQuay · Refining summary', 'Markdown headings must render without their source markers');
    assert(rendered.querySelectorAll('li').length === 5 && rendered.querySelector('code')?.textContent === path,
      'Instructions must render readable lists and complete code-formatted paths');
    assert(rendered.textContent?.includes('report_development_result') && !rendered.textContent?.includes('`'), 'Report fields must render as inline code');
  } else {
    assert(rendered.textContent === prompt, 'Saved plain-text instructions must retain their complete text and line breaks');
  }
  assert(document.documentElement.scrollWidth <= innerWidth && request.scrollWidth <= request.clientWidth + 1, 'Expanded instructions must wrap at narrow widths');
  toggle().click();
  await settle();
  assert(content.hidden && request.getBoundingClientRect().height <= 100, 'Generated request must collapse back to its summary');
  return { width: innerWidth, compact: content.hidden };
}

export async function generatedReview(snapshot = false) {
  const turn = parseAgentTurn(contracts.turn);
  const review = reviewFixture();
  const prompt = snapshot ? 'Saved review instructions.\n' + 'Historical evidence '.repeat(1500)
    : 'Review the frozen head. Read review_get_context kind assignment and scope.';
  const requests = reviewRequests({ ...review, byteQuayTurnIds: [turn.id], messages: [], runs: [{ ...review.runs[0], turnIds: [turn.id] }] });
  flushSync(() => root.render(<TurnView key={`${innerWidth}:${snapshot}`}
    turn={{ turnId: turn.id, ordinal: 1, blocks: [], answer: null, startedAt: null, endedAt: null, outcome: null,
      users: [{ id: 'generated-review', text: prompt, attachments: [], delivery: 'TURN', at: '' }] }}
    first={false} open={false} now={0} models={[]} durableTurns={{ [turn.id]: turn }} activityChoice={{}} previews={{}} agentLabels={{}}
    reviewRequests={requests} onToggle={() => {}} onToggleActivity={() => {}} />));
  await settle();
  const request = card();
  assert(request.getBoundingClientRect().height <= 130 && !request.innerText.includes(prompt), 'Generated review must start as a compact summary');
  toggle().click(); await settle();
  const sent = request.querySelector<HTMLDetailsElement>('.agt-review-request__prompt');
  assert(sent !== null && !sent.open && !request.innerText.includes(prompt), 'Expanding the review must keep machine instructions folded');
  assert(request.innerText.includes(requests[0].body), 'Review scope must be readable before opening the machine prompt');
  sent.querySelector('summary')?.click(); await settle();
  assert(sent.open && sent.querySelector('.agt-review-request__body')?.textContent === prompt, 'Exact delivered instructions must remain inspectable');
  assert(document.documentElement.scrollWidth <= innerWidth && request.scrollWidth <= request.clientWidth + 1, 'Expanded review prompt must fit narrow conversations');
  return { width: innerWidth, snapshot };
}
