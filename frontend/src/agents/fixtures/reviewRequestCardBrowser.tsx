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
import type { LocalReviewMessageDto, LocalReviewThreadDto } from '../../pulls/local-review/types';
import { TurnView } from '../AgentConversation';
import { parseAgentTurn } from '../contracts';
import contracts from './agent-contracts-v1.json';
import '../../css/base.css';
import '../../css/pulls.css';
import '../../css/agents.css';
import '../../css/agent-review.css';
import { assert, settle, element } from '../../fixtures/harness';

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
  const result = element<HTMLElement>('.agt-review-request', host, 'Review request must render a compact card');
  return result;
}
function toggle() {
  const result = element<HTMLButtonElement>('.agt-review-request__toggle', card(), 'Review request must have an independent disclosure button');
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
  assert(closedHeight <= 100, `Collapsed request must stay compact even with a long question (${closedHeight}px at ${innerWidth}px)`);
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
  assert(request.querySelector('.agt-review-request__prompt') === null && !request.textContent?.includes(finding.validatedHeadSha),
    'The fix card must not show the prompt sent to the agent');
  assert(document.documentElement.scrollWidth <= innerWidth && request.scrollWidth <= request.clientWidth + 1,
    'Expanded fix details must fit narrow conversations');
  toggle().click(); await settle();
  openReview(); await settle();
  const destination = host.querySelector<HTMLElement>(`[data-fix-review] [data-finding-id="${finding.id}"]`);
  assert(destination !== null && destination.querySelector('details')?.open === true
    && (document.activeElement === destination || destination.contains(document.activeElement)),
  'Opening a fix must show, expand and focus its original finding even after resolution');
  assert(toggle().getAttribute('aria-expanded') === 'false', 'Opening the finding must keep the generated prompt folded');
  return { width: innerWidth, delivery, snapshot };
}

export async function generatedPrompt(kind: 'AUTO_COMMIT' | 'REFINE_SUMMARY' | 'CREATE_DRAFT_PR' = 'REFINE_SUMMARY', snapshot = false, existing = false) {
  const branch = 'developer/bytequay/' + 'completed-change-'.repeat(15);
  const title = kind === 'AUTO_COMMIT' ? 'Auto commit' : kind === 'REFINE_SUMMARY' ? 'Refining summary' : 'Create draft PR';
  const prompt = `ByteQuay · ${title}\nWorktree: '/private/worktree'. Branch: '${branch}'. Expected HEAD: abc1234.\n`
    + "Base repository/branch: upstream/repo / 'release'. Push repository: developer/repo.\n"
    + (existing ? 'Pull request https://github.com/upstream/repo/pull/12 already tracks this branch; publishing updates it and creates no second one.\n' : '')
    + (snapshot ? '## Saved instructions\n\n' + 'Historical follow-up context. '.repeat(1500) : 'Inspect the current code and Git state. Follow the accepted authorization.');
  const turn = parseAgentTurn({ ...contracts.turn, developmentOrigin: { kind, parentUserTurnId: 'source-turn' } });
  flushSync(() => root.render(<TurnView key={`${innerWidth}:${kind}:${snapshot}:${existing}`}
    turn={{ turnId: turn.id, ordinal: 1, blocks: [], answer: null, startedAt: null, endedAt: null, outcome: null,
      users: [{ id: 'generated', text: prompt, attachments: [], delivery: 'TURN', at: '2026-01-01T12:00:00Z' },
        { id: 'steer', text: 'Keep my requested behavior.', attachments: [], delivery: 'STEER', at: '' }] }}
    first={false} open={false} now={0} models={[]} durableTurns={{ [turn.id]: turn }} activityChoice={{}} previews={{}} agentLabels={{}} generatedBranch={branch}
    onToggle={() => {}} onToggleActivity={() => {}} />));
  await settle();
  const request = card();
  assert(host.querySelector('.agt-user__bubble')?.textContent?.includes('Keep my requested behavior.'), 'User steering must stay visible');
  if (kind === 'REFINE_SUMMARY') {
    assert(request.querySelector('.agt-review-request__bar') === null && request.innerText.includes('Refresh the change intention')
      && !request.innerText.includes('/private/worktree'), 'Refining the summary must show only what it does');
    assert(document.documentElement.scrollWidth <= innerWidth && request.scrollWidth <= request.clientWidth + 1, 'The request must fit narrow conversations');
    return { width: innerWidth, kind, snapshot, existing };
  }
  const content = request.querySelector<HTMLElement>('.agt-review-request__content');
  const action = kind === 'AUTO_COMMIT' ? 'ByteQuay · Auto commit' : existing ? 'Update pull request #12' : 'Open draft pull request';
  assert(request.querySelector('.agt-user__request-action')?.textContent === action, 'The trusted origin must determine the action');
  assert(content !== null && content.hidden && !request.innerText.includes('/private/worktree'), 'Machine instructions must start hidden');
  assert(request.getBoundingClientRect().height <= 130 && request.scrollWidth <= request.clientWidth + 1, 'Generated requests must stay compact');
  const topic = request.querySelector<HTMLElement>('.agt-review-request__topic');
  assert(topic !== null && topic.clientWidth >= 140, 'Scope must have its own readable line at narrow widths');
  if (kind === 'CREATE_DRAFT_PR') assert(topic.textContent === `${branch} → release`, 'The publication destination must remain visible');
  toggle().click(); await settle();
  assert(!content.hidden && request.querySelector('.agt-review-request__prompt') === null && !request.textContent?.includes('/private/worktree'),
    'Opening details must not show the prompt sent to the agent');
  assert(request.innerText.includes(kind === 'AUTO_COMMIT' ? 'Authorized local commits' : 'authorized publication'),
    'Expanded details must explain the authorization scope');
  assert(document.documentElement.scrollWidth <= innerWidth && request.scrollWidth <= request.clientWidth + 1, 'Expanded details must fit narrow conversations');
  toggle().click(); await settle();
  assert(content.hidden && request.getBoundingClientRect().height <= 130, 'The generated request must fold back to its summary');
  return { width: innerWidth, kind, snapshot, existing };
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
  assert(request.querySelector('.agt-review-request__prompt') === null && !request.textContent?.includes(prompt), 'Expanding the review must not show machine instructions');
  assert(request.innerText.includes(requests[0].body), 'Review scope must be readable');
  assert(document.documentElement.scrollWidth <= innerWidth && request.scrollWidth <= request.clientWidth + 1, 'Expanded review must fit narrow conversations');
  return { width: innerWidth, snapshot };
}

export async function generatedCi(snapshot = false) {
  const prompt = 'Verified current CI context:\n- Pull request: acme/demo#17\n- Remote head: ' + 'a'.repeat(40)
    + (snapshot ? '\nSaved job evidence '.repeat(1500) : '\nRead read_pull_request_ci for current failures.');
  const turn = parseAgentTurn({ ...contracts.turn, settings: { ...contracts.turn.settings, permissionPolicy: 'CI_AUTOFIX' } });
  flushSync(() => root.render(<TurnView key={`ci:${innerWidth}:${snapshot}`}
    turn={{ turnId: turn.id, ordinal: 1, blocks: [], answer: null, startedAt: null, endedAt: null, outcome: null,
      users: [{ id: 'generated-ci', text: prompt, attachments: [], delivery: 'TURN', at: '' },
        { id: 'ci-steer', text: 'Keep the compatibility fix.', attachments: [], delivery: 'STEER', at: '' }] }}
    first={false} open={false} now={0} models={[]} durableTurns={{ [turn.id]: turn }} activityChoice={{}} previews={{}} agentLabels={{}}
    onToggle={() => {}} onToggleActivity={() => {}} />));
  await settle();
  const request = card();
  assert(request.innerText.includes('ByteQuay · Fix CI') && request.innerText.includes('acme/demo#17'), 'CI card must name the repair and PR');
  assert(request.getBoundingClientRect().height <= 130 && !request.innerText.includes('Remote head:'), 'CI request must start compact');
  assert(host.querySelector('.agt-user__bubble')?.textContent?.includes('Keep the compatibility fix.'), 'User steering must remain visible');
  toggle().click(); await settle();
  assert(request.querySelector('.agt-review-request__prompt') === null && !request.textContent?.includes('Remote head:'),
    'Opening the CI card must not show the prompt sent to the agent');
  assert(request.innerText.includes('ByteQuay verifies repairs and handles authorized publication.'), 'Expanded details must explain publication ownership');
  assert(document.documentElement.scrollWidth <= innerWidth && request.scrollWidth <= request.clientWidth + 1, 'Expanded CI details must fit narrow conversations');
  return { width: innerWidth, snapshot };
}

export async function localDiscussion(learning = false, snapshot = false) {
  let mutations = 0;
  const turn = parseAgentTurn(contracts.turn);
  const savedContext = 'Saved private context. '.repeat(300);
  const prompt = snapshot ? 'Historical private discussion instructions.\n' + savedContext
    : 'Read list_local_review_comments with commentId="local-thread". Keep this discussion private.';
  const comment: LocalReviewMessageDto = { id: 'question', author: 'USER', body: 'Why is this change needed?', createdAt: '2026-01-01T12:00:00Z',
    turnId: 'previous-turn', queuedInputId: 'queue', messageEventId: 'local-event', deliveryState: 'COMPLETED', deliveryError: null };
  const thread: LocalReviewThreadDto = { id: 'local-thread', taskId: turn.taskId, taskVersion: 1, workspaceId: 'workspace', repo: 'acme/demo', number: learning ? 0 : 17,
    path: learning ? '' : 'src/Example.java', side: 'RIGHT', startLine: learning ? 0 : 12, line: learning ? 0 : 13,
    baseSha: null, headSha: null, code: learning ? '' : savedContext, resolved: false, version: 1,
    createdAt: comment.createdAt, updatedAt: comment.createdAt, messages: [comment],
    ...(learning ? { learningAnchor: { turnId: 'summary-turn', cardId: 'purpose', title: 'What this change does', body: savedContext } } : {}) };
  flushSync(() => root.render(<TurnView key={`local:${innerWidth}:${learning}:${snapshot}`}
    turn={{ turnId: turn.id, ordinal: 1, blocks: [], answer: null, startedAt: null, endedAt: null, outcome: null,
      users: [{ id: 'local-event', text: prompt, attachments: [], delivery: 'QUEUED', at: '' },
        { id: 'local-steer', text: 'Also explain the edge case.', attachments: [], delivery: 'STEER', at: '' }] }}
    first={false} open={false} now={0} models={[]} durableTurns={{ [turn.id]: turn }} activityChoice={{}} previews={{}} agentLabels={{}}
    localReview={{ threads: [thread], onReply: async () => { mutations++; }, onResolve: async () => { mutations++; }, onRetry: async () => { mutations++; } }}
    onToggle={() => {}} onToggleActivity={() => {}} />));
  await settle();
  const request = card();
  assert(request.innerText.includes(learning ? 'Ask about summary' : 'Ask about review') && request.innerText.includes(comment.body),
    'The compact local request must show the question and distinguish summaries from code');
  assert(request.getBoundingClientRect().height <= 130 && !request.innerText.includes(savedContext), 'Saved context must start folded');
  assert(host.querySelector('.agt-user__bubble')?.textContent?.includes('Also explain the edge case.'), 'Unrelated steering must remain visible');
  toggle().click(); await settle();
  assert(request.querySelector('.agt-review-request__prompt') === null && !request.textContent?.includes(prompt),
    'Opening the discussion must not show the prompt sent to the agent');
  const saved = request.querySelector<HTMLElement>(learning ? '.lrc-message__body' : '.is-excerpt');
  const savedText = learning ? saved?.textContent : [...saved?.querySelectorAll('.is-source code') ?? []].map(code => code.textContent).join('\n');
  assert(saved !== null && savedText?.trim() === savedContext.trim() && saved.getBoundingClientRect().height > 0,
    `The saved ${learning ? 'summary' : 'code'} must remain readable`);
  assert(request.innerText.includes(learning ? 'Private summary discussion' : 'Private code discussion'), 'The discussion must show its private scope');
  const buttons = Array.from(request.querySelectorAll('button')).filter(button => button.getBoundingClientRect().height > 0).map(button => button.textContent);
  assert(buttons.includes('Reply') && buttons.includes('Resolve'), 'Private reply and resolution controls must remain available');
  assert(mutations === 0, 'Inspecting the discussion must not write or resolve anything');
  assert(document.documentElement.scrollWidth <= innerWidth && request.scrollWidth <= request.clientWidth + 1, 'Local context must fit narrow conversations');
  return { width: innerWidth, learning, snapshot };
}
