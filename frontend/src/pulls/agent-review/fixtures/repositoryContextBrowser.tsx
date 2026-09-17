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
import { createRoot, type Root } from 'react-dom/client';
import type { Bridge } from '../../../types';
import type { ContextHistoryReadDto, ContextSourceEvidenceDto, RepositoryNotebookDto, ReviewContextSnapshotDto, SaveRepositoryContextLessonRequest } from '../../../repository-context/contracts';
import ReviewDetails from '../ReviewDetails';
import ReviewContextDetails from '../ReviewContextDetails';
import ReviewThread from '../ReviewThread';
import { reviewFixture } from './review';
import type { ReviewRunDto } from '../contracts';
import '../../../css/base.css';
import '../../../css/pulls.css';
import '../../../css/agent-review.css';

const settle = () => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));
function assert(condition: boolean, message: string): asserts condition { if (!condition) throw new Error(message); }
async function waitFor(condition: () => boolean, message: string): Promise<void> {
  const deadline = Date.now() + 5_000;
  while (!condition()) {
    assert(Date.now() < deadline, message);
    await new Promise<void>(resolve => setTimeout(resolve, 10));
  }
}
const base = reviewFixture();
const run = { ...base.runs[0], evidence: [{ ref: 'E1', source: 'Original run source', provenance: 'Saved at this run', excerpt: 'source', explanation: '' }] };
const message = { ...base.messages[0], id: 'saved-discussion', runId: run.id, body: 'Keep field IDs scoped to their parent struct.' };
const review = { ...base, runs: [run, { ...run, id: 'newer-run', ordinal: 2 }], messages: [message] };
const empty: RepositoryNotebookDto = { schemaVersion: 1, revision: 4, useInReviews: false, sources: [], activePreparation: null,
  scopes: [{ id: 'project', title: 'Project', match: { kind: 'REPOSITORY' }, enabled: true, preparation: null }], entries: [], suppressedKnowledgeIds: [], sourceEvidence: [] };
const brief = { investigationStep: 1, text: 'Keep field IDs scoped to their parent struct.\n<script>Reference text stays inert.</script>',
  evidenceRefs: ['E1'], preparationRefs: ['prepared:project'], limitations: ['Dependency version applicability is unverified.'] };
const delivery = { turnId: 'validation-turn', packetDigest: `sha256:${'c'.repeat(64)}`, utf8Bytes: 420, contributionCount: 1,
  inputDigest: `sha256:${'d'.repeat(64)}`, inputUtf8Bytes: 900, discussionMessageId: 'original-discussion', preparedAt: '2026-01-03T00:00:00Z' };
const historyReads: ContextHistoryReadDto[] = [
  { kind: 'HISTORY', id: 'history:merged', reference: { kind: 'PR', repositoryId: 7, repository: { owner: 'trinodb', repo: 'trino' }, number: 30280, commentId: null },
    state: 'MERGED', sourceUpdatedAt: '2026-01-03T00:00:00Z', sourceRevision: 'd'.repeat(40), contentHash: 'e'.repeat(64), readAt: '2026-01-04T00:00:00Z',
    utf8Bytes: 6, truncated: true, refreshFailed: true, gaps: ['The retained discussion is partial; inspect the final change before applying its lesson.'], evidenceRef: 'E1' },
  { kind: 'HISTORY', id: 'history:closed', reference: { kind: 'ISSUE', repositoryId: 7, repository: { owner: 'trinodb', repo: 'trino' }, number: 29912, commentId: null },
    state: 'CLOSED', sourceUpdatedAt: null, sourceRevision: null, contentHash: 'f'.repeat(64), readAt: '2026-01-04T00:00:00Z', utf8Bytes: 6,
    truncated: false, refreshFailed: false, gaps: ['The closure reason is unknown.'], evidenceRef: 'E1' },
];

function button(scope: Element, text: string): HTMLButtonElement {
  const found = [...scope.querySelectorAll<HTMLButtonElement>('button')].find(item => item.textContent === text);
  assert(found !== undefined, `Missing ${text} button`);
  return found;
}

function field(dialog: Element, label: string): HTMLInputElement | HTMLTextAreaElement {
  const found = [...dialog.querySelectorAll('label')].find(item => item.firstChild?.textContent === label)?.querySelector('input, textarea');
  assert(found instanceof HTMLInputElement || found instanceof HTMLTextAreaElement, `Missing ${label} field`);
  return found;
}

function fill(input: HTMLInputElement | HTMLTextAreaElement, value: string) {
  const prototype = input instanceof HTMLInputElement ? HTMLInputElement.prototype : HTMLTextAreaElement.prototype;
  Object.getOwnPropertyDescriptor(prototype, 'value')?.set?.call(input, value);
  flushSync(() => input.dispatchEvent(new Event('input', { bubbles: true })));
}

function selectScope(dialog: Element, value: string) {
  const select = dialog.querySelector('select');
  assert(select !== null, 'Lesson scope must be selectable');
  select.value = value;
  flushSync(() => select.dispatchEvent(new Event('change', { bubbles: true })));
}

function stored(notebook: RepositoryNotebookDto, request: SaveRepositoryContextLessonRequest): RepositoryNotebookDto {
  const sourceIds = ['message-source', ...request.evidenceRefs.map(ref => `evidence-${ref}`)];
  const sources = sourceIds.map((id, index): ContextSourceEvidenceDto => ({ id, reference: { kind: 'REVIEW', reviewId: request.reviewId, runId: request.runId,
    messageId: index === 0 ? request.messageId ?? null : null, evidenceRef: index === 0 ? null : request.evidenceRefs[index - 1] },
  provenance: 'Saved discussion', excerpt: 'original', capture: 'BYTEQUAY_READ' as const, retrievedAt: null, contentHash: null, version: null, truncated: false }));
  return { ...notebook, revision: notebook.revision + 1, sourceEvidence: sources, entries: [{ id: 'lesson:accepted', kind: 'MEMORY', scopeId: request.scopeId,
    title: request.title, summary: request.summary, body: request.body, claims: [], sourceIds, origin: 'USER', enabled: true, version: notebook.revision + 1 }] };
}

export async function runScenarios(): Promise<string[]> {
  const host = document.createElement('main');
  document.body.append(host);
  const root = createRoot(host);
  const results: string[] = [];
  let notebook = empty;
  const writes: SaveRepositoryContextLessonRequest[] = [];
  let responseLost = false;
  const bridge = {
    getRepositoryContext: async (workspaceId: string) => { assert(workspaceId === review.workspaceId, 'Notebook must belong to review workspace'); return notebook; },
    saveRepositoryContextLesson: async (workspaceId: string, request: SaveRepositoryContextLessonRequest) => {
      assert(workspaceId === review.workspaceId, 'Save must retain workspace ownership');
      writes.push(request);
      if (!responseLost && writes.length === 1) { notebook = { ...notebook, revision: 5 }; throw new Error('Notebook revision changed'); }
      notebook = stored(notebook, request);
      if (responseLost) throw new Error('Reply interrupted after save');
      return notebook;
    },
  } satisfies Pick<Bridge, 'getRepositoryContext' | 'saveRepositoryContextLesson'>;
  Object.defineProperty(window, 'bridge', { configurable: true, value: bridge });
  const open = async (key: string) => {
    flushSync(() => root.render(<ReviewThread key={key} review={review} messages={review.messages} viewerLogin="reader" viewerAvatarUrl={null} />));
    button(host, 'Save lesson').click();
    await waitFor(() => host.querySelector('dialog')?.open === true, 'Save lesson must open an accessible native dialog');
    const dialog = host.querySelector('dialog');
    assert(dialog !== null && dialog.open, 'Save lesson must open an accessible native dialog');
    fill(field(dialog, 'Title'), 'Parent-scoped field IDs');
    fill(field(dialog, 'Summary'), 'Use each parent struct when resolving a nested field.');
    fill(field(dialog, 'Details'), 'Apply this when nested structs reuse a leaf name.');
    const scope = dialog.querySelector('select');
    assert(scope !== null && scope.value === '' && scope.required, 'A new lesson must require an explicit scope choice');
    assert(scope.selectedOptions[0]?.textContent === 'Choose where this lesson applies', 'The unset scope must explain what to choose');
    assert(button(dialog, 'Save lesson').disabled, 'Completed prose without a scope must not enable saving');
    const attempts = writes.length;
    flushSync(() => dialog.querySelector('form')?.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })));
    assert(writes.length === attempts, 'Submitting without a scope must not save a lesson');
    selectScope(dialog, 'project');
    await waitFor(() => !button(dialog, 'Save lesson').disabled, 'An explicit Project choice must allow saving completed lesson fields');
    assert(dialog.textContent?.includes('This scope applies across the repository.') === true, 'Project choice must disclose repository-wide selection');
    return dialog;
  };
  try {
    const dialog = await open('conflict');
    results.push('Saving requires an explicit scope choice and permits a disclosed Project choice');
    const evidence = dialog.querySelector<HTMLInputElement>('.agr-lesson-evidence input');
    assert(evidence !== null, 'Original run evidence must be selectable');
    evidence.click();
    await waitFor(() => dialog.querySelector('legend')?.textContent?.includes('1 of 7 selected') === true, 'Selected evidence must be reflected in the draft');
    button(dialog, 'Save lesson').click();
    await waitFor(() => dialog.textContent?.includes('Notebook revision changed') === true, 'Stale notebook must show its error');
    assert(dialog.textContent?.includes('Notebook revision changed') === true, 'Stale notebook must show its error');
    assert(button(dialog, 'Save lesson').disabled, 'Stale writes must not be retried without reload');
    fill(field(dialog, 'Title'), 'Revised conditional lesson');
    button(dialog, 'Reload notebook').click();
    await waitFor(() => !button(dialog, 'Save lesson').disabled, 'Reloaded notebook must allow an explicit retry');
    assert(field(dialog, 'Title').value === 'Revised conditional lesson', 'Reload must preserve edited draft');
    assert(Number(writes.length) === 1, 'Reload must not save automatically');
    const save = button(dialog, 'Save lesson');
    save.click(); save.click();
    await waitFor(() => host.textContent?.includes('Lesson saved') === true && host.querySelector('dialog') === null, 'Accepted save must close with confirmation');
    assert(Number(writes.length) === 2, 'A double click must make one save attempt');
    const accepted = writes[1];
    assert(accepted.expectedRevision === 5 && accepted.title === 'Revised conditional lesson', 'Retry must use latest revision and preserved draft');
    assert(accepted.scopeId === 'project', 'The explicitly chosen Project scope must be retained on save');
    assert(accepted.reviewId === review.id && accepted.runId === run.id && accepted.messageId === message.id, 'Lesson must use message original run, never newest run');
    assert(accepted.evidenceRefs.length === 1 && accepted.evidenceRefs[0] === 'E1', 'Only selected original-run evidence belongs in the lesson');
    assert(host.textContent?.includes('Lesson saved') === true && host.querySelector('dialog') === null, 'Accepted save must close with confirmation');
    results.push('Explicit edited save keeps original message/run and preserves conflict drafts');

    notebook = empty; responseLost = true;
    const uncertain = await open('response-lost');
    button(uncertain, 'Save lesson').click();
    await waitFor(() => uncertain.textContent?.includes('Reply interrupted after save') === true, 'Uncertain receipt must retain draft');
    const attempts = writes.length;
    assert(uncertain.textContent?.includes('Reply interrupted after save') === true, 'Uncertain receipt must retain draft');
    button(uncertain, 'Reload notebook').click();
    await waitFor(() => host.textContent?.includes('Lesson saved') === true && host.querySelector('dialog') === null, 'Reload must recognize an already accepted save');
    assert(writes.length === attempts && host.textContent?.includes('Lesson saved') === true,
      'Reload must recognize already accepted message/evidence/content and avoid duplicate lesson');
    results.push('Reload reconciles an accepted save whose response was lost');

    notebook = empty;
    const changed = await open('response-lost-with-new-draft');
    button(changed, 'Save lesson').click();
    await waitFor(() => changed.textContent?.includes('Reply interrupted after save') === true, 'Uncertain receipt must allow the draft to remain open');
    fill(field(changed, 'Title'), 'A newer draft after the interrupted reply');
    const previousAttempts = writes.length;
    button(changed, 'Reload notebook').click();
    await waitFor(() => changed.textContent?.includes('The earlier lesson was saved. Your newer edits are still a draft.') === true
      && !button(changed, 'Save lesson').disabled, 'Reload must distinguish the accepted lesson from the newer draft');
    assert(changed.open && field(changed, 'Title').value === 'A newer draft after the interrupted reply',
      'Finding the previous accepted save must not discard edits made after that attempt');
    assert(changed.textContent?.includes('The earlier lesson was saved. Your newer edits are still a draft.') === true,
      'Accepted previous content and the newer draft must be distinguished');
    assert(writes.length === previousAttempts, 'Keeping a changed draft must not create another lesson');
    results.push('Reconciliation preserves newer edits after a lost reply');

    const context: ReviewContextSnapshotDto = { id: 'context', enabled: true, notebookRevision: 4, repositoryId: 7, baselineSha: 'a'.repeat(40),
      bootstrap: { text: 'Initial packet', digest: `sha256:${'a'.repeat(64)}`, utf8Bytes: 14 }, selectedEntries: [], relatedItems: [], inputFingerprints: [],
      sourceReads: historyReads, gaps: [], contributions: [brief], deliveries: [delivery] };
    for (const [outcome, label] of [['SUBMITTED', 'Waiting for confirmation'], ['UNCERTAIN', 'Delivery uncertain'], ['CONFIRMED', 'Provided to agent']] as const) {
      flushSync(() => root.render(<ReviewContextDetails run={{ ...run, context, contextDeliveryStates: { 'validation-turn': { outcome, detail: null } } }} />));
      assert(host.querySelector('h3')?.textContent === `Repository context · ${label}`, 'Delivery heading must follow the actual receipt state');
    }
    const briefSummary = [...host.querySelectorAll('summary')].find(item => item.textContent?.startsWith('Completed context brief'));
    assert(briefSummary !== undefined && briefSummary.parentElement instanceof HTMLDetailsElement, 'The saved brief must have a native disclosure');
    assert(!briefSummary.parentElement.open, 'The saved brief starts folded');
    briefSummary.click();
    await settle();
    assert(briefSummary.parentElement.open, 'The saved brief can be expanded');
    assert(briefSummary.parentElement.querySelector('pre')?.textContent === brief.text, 'The exact brief must remain available as text');
    assert(host.querySelector('.agr-context script') === null, 'Source markup must remain inert');
    const deliverySummary = [...host.querySelectorAll('summary')].find(item => item.textContent?.startsWith('Turn delivery'));
    assert(deliverySummary !== undefined, 'The run must disclose its delivery records'); deliverySummary.click();
    await settle();
    assert(host.textContent?.includes('original-discussion') === true && host.textContent.includes(delivery.packetDigest), 'Delivery details preserve the original message and digest');
    const historySummary = [...host.querySelectorAll('summary')].find(item => item.textContent?.startsWith('Merged PR #30280'));
    assert(historySummary !== undefined, 'Retained merged PR reads must be available in the source disclosures'); historySummary.click();
    await settle();
    assert(historySummary.parentElement?.textContent?.includes('The retained discussion is partial') === true, 'History limitations remain visible');
    assert(host.textContent?.includes('Closed issue #29912') === true && host.textContent.includes('The closure reason is unknown.'), 'Closed issue state retains its missing closure reason');
    results.push('Context delivery follows receipts and keeps exact brief text in folded disclosures');
  }
  finally { flushSync(() => root.unmount()); host.remove(); }
  return results;
}

let previewRoot: Root | null = null;
let previewHost: HTMLElement | null = null;

/** Static, synthetic content for visual checks; all notebook actions stay inside this fixture. */
export async function showFixture(mode: 'context' | 'lesson'): Promise<void> {
  previewRoot?.unmount(); previewHost?.remove();
  const host = document.createElement('main');
  host.style.cssText = 'padding: 24px; max-width: 1080px; margin: auto; font: 13px system-ui; color: #1f2328; background: #fff';
  document.body.append(host); previewHost = host; previewRoot = createRoot(host);
  const overview = 'Repository context for this review\n\nThe Iceberg connector resolves nested field paths against their parent structs.\n'
    + 'Stored conclusions are reference material; verify them against the reviewed code.\n\nSource: README.md at the frozen monitored baseline.';
  const context: ReviewContextSnapshotDto = { id: 'context:run-1', enabled: true, notebookRevision: 4, repositoryId: 7,
    baselineSha: 'a'.repeat(40), bootstrap: { text: overview, digest: `sha256:${'f'.repeat(64)}`, utf8Bytes: new TextEncoder().encode(overview).length },
    selectedEntries: [{ entryId: 'knowledge:iceberg', entryVersion: 2, title: 'Iceberg connector', text: 'Resolve field IDs within the selected parent struct.',
      sourceEvidenceRefs: { 'source-1': 'E1' }, matchReasons: ['Changed path: plugin/trino-iceberg/'], applicability: 'UNVERIFIED', inclusion: 'INCLUDED' }],
    relatedItems: [{ sourceId: 'E1', matchReasons: ['Previous review touched the same connector'], presentInReviewedBase: null }],
    inputFingerprints: [{ scopeId: 'project', inputs: { 'git:README.md': '100644 blob source-identity' }, digest: null, gaps: ['Effective dependency version was not resolved.'] }],
    sourceReads: [{ kind: 'REPOSITORY_FILE', id: 'context-read:example', reference: { kind: 'REPOSITORY_FILE', repositoryId: 7,
      repository: { owner: 'trinodb', repo: 'trino' }, path: 'README.md', commit: 'a'.repeat(40) }, blobId: 'b'.repeat(40),
      readAt: '2026-01-01T00:00:00Z', startLine: 1, endLine: 2, totalLines: 30, utf8Bytes: 42, truncated: false, evidenceRef: 'E1' }, ...historyReads],
    gaps: ['Selected notebook claims have not been verified for this pull request.', 'Recent history is limited to locally cached records.'],
    contributions: [brief], deliveries: [delivery] };
  Object.defineProperty(window, 'bridge', { configurable: true, value: {
    getRepositoryContext: async () => empty,
    saveRepositoryContextLesson: async (_workspaceId: string, request: SaveRepositoryContextLessonRequest) => stored(empty, request),
  } satisfies Pick<Bridge, 'getRepositoryContext' | 'saveRepositoryContextLesson'> });
  const displayRun: ReviewRunDto = { ...run, context, contextDeliveryStates: { 'validation-turn': { outcome: 'CONFIRMED', detail: null } } };
  flushSync(() => previewRoot?.render(mode === 'context' ? <ReviewDetails run={displayRun} repository={review.repo} />
    : <ReviewThread review={review} messages={review.messages} viewerLogin="reader" viewerAvatarUrl={null} />));
  if (mode === 'context') {
    const details = host.querySelector<HTMLDetailsElement>('.agr-review-details');
    assert(details !== null, 'Context preview requires review details'); details.open = true;
    const packet = host.querySelector<HTMLDetailsElement>('.agr-context__item');
    assert(packet !== null, 'Context preview requires packet'); packet.open = true;
  }
  else {
    button(host, 'Save lesson').click();
    await waitFor(() => host.querySelector('dialog')?.open === true, 'Lesson preview requires an open editor');
    const dialog = host.querySelector('dialog');
    assert(dialog !== null, 'Lesson preview requires editor');
    fill(field(dialog, 'Title'), 'Resolve nested fields within their parent');
    fill(field(dialog, 'Summary'), 'When leaf names repeat, preserve the complete parent path.');
    fill(field(dialog, 'Details'), 'For nested struct fields, resolve each segment within the selected parent. A matching leaf name in a different struct is not the same field.');
    selectScope(dialog, 'project');
  }
  await settle();
}
