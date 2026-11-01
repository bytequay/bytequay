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
import { InstructionCard, type ReviewCardFocus } from '../InstructionCard';
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

const host = document.createElement('main');
host.style.padding = '24px';
document.body.append(host);
const root = createRoot(host);
let actions = 0;
let openedReviews = 0;
let closedHeight = 0;

function card() {
  const result = element<HTMLElement>('.agt-instruction', host, 'An instruction must render as its card');
  return result;
}
function toggle() {
  const result = element<HTMLButtonElement>('.agt-instruction__toggle', card(), 'An instruction must have an independent disclosure button');
  return result;
}
/** The prompt the agent received, shown whole and scrolling past the design's height. */
function shownPrompt(request: HTMLElement, prompt: string) {
  const content = element<HTMLElement>('.agt-instruction__content', request, 'An instruction must have a prompt panel');
  const pre = element<HTMLElement>('.agt-instruction__prompt > pre', request, 'The prompt must be shown as sent');
  assert(!content.hidden && pre.textContent === prompt, 'Opening an instruction must show the exact prompt the agent received');
  assert(pre.getBoundingClientRect().height <= 181 && request.querySelector('.agt-instruction__prompt .pr-copy-button') !== null,
    'The prompt must stay within its scrolling panel beside a copy button');
}
/** One quiet row while folded: a 36px row inside its border. */
const ROW = 40;

export async function mount() {
  actions = 0;
  openedReviews = 0;
  flushSync(() => root.render(<div className="agt-user">
    <InstructionCard key={innerWidth} onOpen={() => { openedReviews++; }} action="Ask about review" topic="Limitations · 79e7210" prompt={body}>
      <div style={{ display: 'flex', gap: 12 }}>
        <button type="button" onClick={() => { actions++; }}>Retry</button>
      </div>
    </InstructionCard>
  </div>));
  await settle();
  closedHeight = card().getBoundingClientRect().height;
  assert(closedHeight <= ROW, `Collapsed request must stay one row even with a long question (${closedHeight}px at ${innerWidth}px)`);
  assert(toggle().querySelector('button, a') === null, 'Disclosure must not nest another action');
  toggle().focus();
  assert(document.activeElement === toggle(), 'Disclosure must be keyboard focusable');
}

export async function verify(open: boolean, expectedActions = 0, expectedOpens = 0) {
  await settle();
  const request = card();
  assert((toggle().getAttribute('aria-expanded') === 'true') === open, `Expected card to be ${open ? 'expanded' : 'collapsed'}`);
  assert(request.textContent?.includes(body) === true, 'The complete prompt must survive disclosure');
  assert(actions === expectedActions && openedReviews === expectedOpens, 'Disclosure and review navigation must remain separate actions');
  assert(document.documentElement.scrollWidth <= innerWidth, 'Request must not overflow the viewport');
  assert(request.scrollWidth <= request.clientWidth + 1, 'Request content must not overflow horizontally');
  assert(request.getBoundingClientRect().right <= innerWidth, 'Request must fit the narrow transcript');
  if (open) {
    shownPrompt(request, body);
    assert(request.getBoundingClientRect().height > closedHeight, 'Opening the request must reveal the prompt');
    const button = request.querySelector('.agt-instruction__extra button');
    assert(button !== null && button.getBoundingClientRect().height > 0, 'Local discussion actions must remain visible on expansion');
  }
  return { width: innerWidth, open, height: request.getBoundingClientRect().height };
}

export function clickToggle() { toggle().click(); }
export function openReview() { card().querySelector<HTMLButtonElement>('.agt-instruction__open')?.click(); }
export function retry() { card().querySelector<HTMLButtonElement>('.agt-instruction__extra button')?.click(); }

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
  assert(!request.innerText.includes(instruction) && request.getBoundingClientRect().height <= ROW && request.scrollWidth <= request.clientWidth + 1,
    'The long fix prompt must fold to one row that fits narrow conversations');
  if (delivery === 'STEER') assert(host.querySelector('.agt-user__bubble')?.textContent === 'Continue the implementation.',
    'A queued fix steered into a turn must preserve the ordinary opening message');
  toggle().click(); await settle();
  shownPrompt(request, prompt);
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
  const content = element<HTMLElement>('.agt-instruction__content', request, 'An instruction must have a prompt panel');
  const action = request.querySelector('.agt-instruction__action')?.textContent;
  if (kind === 'REFINE_SUMMARY') assert(action === undefined && request.innerText.includes('Refresh the change intention'),
    'Refining the summary needs no action: its one line says what it does');
  else assert(action === (kind === 'AUTO_COMMIT' ? 'ByteQuay · Auto commit' : existing ? 'Update pull request #12' : 'Open draft pull request'),
    'The trusted origin must determine the action');
  assert(content.hidden && !request.innerText.includes('/private/worktree'), 'The prompt must start folded');
  assert(request.getBoundingClientRect().height <= ROW && request.scrollWidth <= request.clientWidth + 1, 'Generated requests must stay one row');
  if (kind === 'CREATE_DRAFT_PR') assert(request.querySelector('.agt-instruction__topic')?.textContent === `${branch} → release`
    && request.querySelector('.agt-instruction__icon.lucide-git-pull-request') !== null, 'The publication must show as a pull request and its destination');
  toggle().click(); await settle();
  shownPrompt(request, prompt);
  assert(document.documentElement.scrollWidth <= innerWidth && request.scrollWidth <= request.clientWidth + 1, 'Expanded details must fit narrow conversations');
  toggle().click(); await settle();
  assert(content.hidden && request.getBoundingClientRect().height <= ROW, 'The generated request must fold back to its row');
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
  assert(request.getBoundingClientRect().height <= ROW && !request.innerText.includes(prompt), 'Generated review must start as one row');
  toggle().click(); await settle();
  shownPrompt(request, prompt);
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
  assert(request.getBoundingClientRect().height <= ROW && !request.innerText.includes('Remote head:'), 'CI request must start as one row');
  assert(host.querySelector('.agt-user__bubble')?.textContent?.includes('Keep the compatibility fix.'), 'User steering must remain visible');
  toggle().click(); await settle();
  shownPrompt(request, prompt);
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
  const thread: LocalReviewThreadDto = { id: 'local-thread', taskId: turn.taskId, taskVersion: 1, laneId: 'codex', workspaceId: 'workspace', repo: 'acme/demo', number: learning ? 0 : 17,
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
  assert(request.innerText.includes(learning ? 'Ask about summary' : 'Ask about review'),
    'The compact local request must distinguish summaries from code');
  assert(request.getBoundingClientRect().height <= ROW && !request.innerText.includes(savedContext), 'Saved context must start folded');
  assert(host.querySelector('.agt-user__bubble')?.textContent?.includes('Also explain the edge case.'), 'Unrelated steering must remain visible');
  toggle().click(); await settle();
  shownPrompt(request, prompt);
  assert(request.innerText.includes(comment.body), 'Opening the discussion must show the question in its thread');
  const saved = request.querySelector<HTMLElement>(learning ? '.lrc-message__body' : '.is-excerpt');
  const savedText = learning ? saved?.textContent : [...saved?.querySelectorAll('.is-source code') ?? []].map(code => code.textContent).join('\n');
  assert(saved !== null && savedText?.trim() === savedContext.trim() && saved.getBoundingClientRect().height > 0,
    `The saved ${learning ? 'summary' : 'code'} must remain readable`);
  const buttons = Array.from(request.querySelectorAll('button')).filter(button => button.getBoundingClientRect().height > 0).map(button => button.textContent);
  assert(buttons.includes('Reply') && buttons.includes('Resolve'), 'Private reply and resolution controls must remain available');
  assert(mutations === 0, 'Inspecting the discussion must not write or resolve anything');
  assert(document.documentElement.scrollWidth <= innerWidth && request.scrollWidth <= request.clientWidth + 1, 'Local context must fit narrow conversations');
  return { width: innerWidth, learning, snapshot };
}
