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
import { createFixtureRoot } from '../../../fixtures/reactFixture';
import ArchivedCode, { SourceSnapshot } from '../ArchivedCode';
import RunPanel from '../RunPanel';
import type { LocalRunDto, ReviewCodeFilesDto } from '../contracts';
import type { CodeTestsBridge } from '../CodeTestsTab';
import { useCodeComments, type CodeCommentsOptions } from '../useCodeComments';
import { textButton, element as fixtureElement, assert, changeInput, settle } from '../../../fixtures/harness';
import type { InlineReviewCommentDto, PullRequestDetailDto, PullRequestDto } from '../../../types';
import { reviewFixture } from '../../agent-review/fixtures/review';
import TestRuns from '../../../ide/TestRuns';
import type { IdeContext } from '../../../ide/contracts';
import type { IdeRuntime } from '../../../ide/runtime';
const pr = { repo: 'owner/repo', number: 1, headRef: 'feature' } as PullRequestDto;

export async function runArchivedCodeScenarios(): Promise<{ name: string; error?: string }[]> {
  const results: { name: string; error?: string }[] = [];
  const { host, root, dispose } = createFixtureRoot();
  const options: CodeCommentsOptions = { repo: 'owner/repo', number: 1, canReview: true, viewer: null, pending: [], onPendingChange: () => {},
    threads: { activities: [], pr, unavailable: false, onRefresh: async () => {}, onDetailUpdate: () => {} }, onDetailChange: () => {},
    localReview: { threads: [], onReply: async () => {}, onResolve: async () => {}, onRetry: async () => {} } };
  const text = Array.from({ length: 20 }, (_, index) => `line ${index + 1}`).join('\n');
  const Harness = () => {
    const comments = useCodeComments(options, { path: 'notes.txt', text, prPatch: '@@ -1,8 +1,8 @@\n' + text.split('\n').slice(0, 8).map(line => ' ' + line).join('\n'),
      draft: false, uncommitted: false, sourceHead: 'abc', observedHead: 'abc', askBlocked: null });
    return <SourceSnapshot path="notes.txt" text={text} cards={comments.cards} renderCard={comments.renderCard}
      onComment={comments.onComment} commentRange={comments.commentRange} />;
  };
  try {
    flushSync(() => root.render(<SourceSnapshot path="notes.txt" text={text} cards={[]} commentRange={null} lineChanges={[
      { kind: 'ADDED', line: 2, count: 2 }, { kind: 'MODIFIED', line: 7, count: 1 },
      { kind: 'DELETED', line: 0, count: 3 }, { kind: 'DELETED', line: 20, count: 2 },
      { kind: 'ADDED', line: 0, count: 1 }, { kind: 'MODIFIED', line: 21, count: 1 },
    ]} />)); await settle();
    assert(host.querySelectorAll('.ct-line-change.is-added').length === 2, 'Each added line has a green marker');
    assert(host.querySelectorAll('.ct-line-change.is-modified').length === 1, 'Updated lines have a separate marker and invalid ranges are omitted');
    assert(host.querySelectorAll('.ct-line-change.is-deleted').length === 2, 'Both boundary deletions remain visible');
    assert(host.querySelector('.ct-line-change.is-before')?.getAttribute('aria-label') === 'Deleted 3 lines before line 1 compared with the base', 'Deletion labels include their count and position');
    results.push({ name: 'Historical source shows added, updated and deleted line markers' });
  } catch (error) { results.push({ name: 'Historical source change markers', error: String(error) }); }
  try {
    flushSync(() => root.render(<Harness />)); await settle();
    host.querySelector<HTMLButtonElement>('[aria-label="Comment on line 3"]')?.click(); await settle();
    const input = fixtureElement<HTMLTextAreaElement>('.pull-diff-review textarea', host, 'A line must open the shared composer');
    changeInput(input, 'Keep this private draft'); await settle();
    host.querySelector('[aria-label="Comment on line 6"]')?.dispatchEvent(new MouseEvent('click', { shiftKey: true, bubbles: true })); await settle();
    assert(host.querySelector('.pull-diff-review__header strong')?.textContent === 'Add a comment on lines R3 to R6', 'Shift-click must extend the retained source range');
    assert(host.querySelector<HTMLTextAreaElement>('.pull-diff-review textarea')?.value === 'Keep this private draft', 'Range changes preserve the draft');
    host.querySelector('[aria-label="Comment on line 11"]')?.dispatchEvent(new MouseEvent('click', { shiftKey: true, bubbles: true })); await settle();
    assert([...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Post now')?.disabled, 'A source range outside the actual PR hunk cannot be posted');
    results.push({ name: 'Historical source preserves shared comment ranges and patch guards' });
  } catch (error) { results.push({ name: 'Historical source comments', error: String(error) }); }
  const originalBridge = window.bridge;
  try {
    const head = 'b'.repeat(40);
    const review = reviewFixture({ observedHeadSha: head });
    review.findings = [{ ...review.findings[0], path: 'notes.txt', startLine: 3, endLine: 3, excerpt: ['line 3'] }];
    let observedHead: string | null = head;
    let uncommitted = false;
    let pending: InlineReviewCommentDto[] = [];
    const posts: InlineReviewCommentDto[] = [];
    const keys: string[] = [];
    const bridge: CodeTestsBridge = {
      ...originalBridge,
      listCodeFiles: async (): Promise<ReviewCodeFilesDto> => ({ source: 'WORKING_COPY', revision: head, sourceFingerprint: uncommitted ? 'edited' : 'clean',
        truncated: false, savedExperiment: false, files: [{ path: 'notes.txt', changedInPr: true, addedInPr: false, onlyInWorkingCopy: false, uncommitted }] }),
      listCodeRuns: async () => [],
      readCodeFile: async () => ({ path: 'notes.txt', content: text, contentHash: 'hash', editable: false, readOnlyReason: null, unavailableReason: null,
        prPatch: '@@ -1,8 +1,8 @@\n' + text.split('\n').slice(0, 8).map(line => ' ' + line).join('\n'), lineChanges: [] }),
    };
    Object.defineProperty(window, 'bridge', { configurable: true, value: { ...originalBridge,
      postPrReviewComment: async (repo: string, number: number, comment: InlineReviewCommentDto, key: string) => {
        assert(repo === options.repo && number === options.number, 'Publication keeps the current PR identity');
        keys.push(key);
        if (keys.length === 1) throw new Error('GitHub did not confirm this comment, so it may have been posted.');
        posts.push(comment); return {} as PullRequestDetailDto;
      },
    } });
    const render = () => flushSync(() => root.render(<ArchivedCode active bridge={bridge}
      host={{ kind: 'review', review: { ...review, observedHeadSha: observedHead } }} onReviewChange={() => { throw new Error('Do not rerun the agent review'); }}
      comments={{ ...options, pending, onPendingChange: next => { pending = next; render(); } }} />));
    const button = (label: string) => textButton(label, host, `Missing ${label}`);
    const compose = async (line: number, body: string) => {
      host.querySelector<HTMLButtonElement>(`[aria-label="Comment on line ${line}"]`)?.click(); await settle();
      const input = fixtureElement<HTMLTextAreaElement>('.pull-diff-review textarea', host, 'The current source must allow a comment');
      changeInput(input, body); await settle();
    };
    const blocked = () => button('Add to review').disabled && button('Post now').disabled;
    render(); await settle(); button('notes.txt').click(); await settle();
    assert(host.querySelector('.ct-unplaced .agr-finding') !== null && host.querySelector('.ct-inline-card .agr-finding') === null,
      'The old agent finding must stay unplaced even when its excerpt matches the latest source');
    await compose(3, 'My pending comment');
    assert(!button('Add to review').disabled && !button('Post now').disabled, 'Human comments on the latest PR do not wait for an agent review');
    button('Add to review').click(); await settle();
    assert(pending.length === 1 && pending[0].commitId === head && pending[0].line === 3 && pending[0].path === 'notes.txt'
      && pending[0].body === 'My pending comment' && posts.length === 0, 'Staging binds the comment to the displayed commit without publishing');
    assert(host.querySelector('.ct-inline-card .pull-diff-pending')?.textContent?.includes('My pending comment') === true,
      'The pending comment stays beside its line on the latest PR');
    await compose(4, 'My immediate comment');
    button('Post now').click(); await settle();
    button('Post now').click(); await settle();
    assert(Number(posts.length) === 1 && posts[0].commitId === head && posts[0].line === 4 && posts[0].body === 'My immediate comment',
      'Post now sends exactly once against the displayed PR commit');
    assert(keys.length === 2 && /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(keys[0]) && keys[1] === keys[0],
      'A retry from the same comment box sends its key again, so the backend cannot post it twice');
    await compose(5, 'Keep this draft');
    for (const movedHead of ['c'.repeat(40), null]) {
      observedHead = movedHead; render(); await settle();
      assert(blocked(), 'A newer or unknown PR revision must disable both actions');
    }
    observedHead = head; render(); await settle();
    assert(!blocked(), 'Matching the current PR re-enables the retained draft');
    uncommitted = true;
    host.querySelector<HTMLButtonElement>('[aria-label="Reload files"]')?.click(); await settle(); await settle();
    assert(blocked(), 'Saved local changes must disable both actions');
    assert(host.querySelector<HTMLTextAreaElement>('.pull-diff-review textarea')?.value === 'Keep this draft', 'Revision checks preserve the composed comment');
    results.push({ name: 'Latest PR source supports human staging and publication independently of the agent review' });
  } catch (error) { results.push({ name: 'Human comments on latest PR source', error: String(error) }); }
  finally { Object.defineProperty(window, 'bridge', { configurable: true, value: originalBridge }); }
  try {
    flushSync(() => root.render(<SourceSnapshot path="Example.java" text={'class Example {}\n'.repeat(1100)} cards={[]} commentRange={null} />)); await settle();
    assert(host.querySelectorAll('.ct-snapshot__line').length === 500 && host.textContent?.includes('Lines 1–500 of 1101'), 'Large snapshots must bound rendered lines');
    [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Next lines')?.click(); await settle();
    assert(host.querySelector('.ct-snapshot__number')?.textContent === '501' && host.querySelector('.hljs-keyword')?.textContent === 'class', 'Paging keeps source line identity and syntax coloring');
    results.push({ name: 'Historical source pages retain line numbers and syntax' });
  } catch (error) { results.push({ name: 'Historical source paging', error: String(error) }); }
  try {
    const requested: number[] = [];
    const run: LocalRunDto = { id: 'streamed', ordinal: 1, initiator: 'USER', scope: 'TESTS', path: 'main_test.go', testClass: null, testMethod: null,
      module: null, command: 'Run tests', sourceHeadSha: 'abc', localPaths: [], sourceFingerprint: 'fingerprint', status: 'RUNNING', startedAt: '', endedAt: null,
      exitCode: null, tests: null, failures: null, errors: null, skipped: null, outputTruncated: false, omittedBytes: null, treeUnverified: false, results: [] };
    let finished = false;
    const readOutput = async (_id: string, after: number) => {
      requested.push(after);
      return finished ? { text: 'head\n… omitted …\nfinal failure', nextOffset: 32, skippedBytes: 0, complete: true }
        : { text: 'live output', nextOffset: 2_000_000, skippedBytes: 1_999_989, complete: false };
    };
    const render = () => flushSync(() => root.render(<RunPanel runs={[finished ? { ...run, status: 'FAILED_ASSERTION' } : run]} files={null}
      showRunHint hostKind="task" onStop={() => {}}
      readOutput={readOutput} selectedRunId={run.id} onSelectRun={() => {}} />));
    render(); await settle();
    assert(requested.join(',') === '0', 'The active run must request live output');
    finished = true; render(); await settle();
    assert(requested.length === 2 && requested[1] === 0, 'Completion must reread the compacted final output instead of losing its tail at the live offset');
    results.push({ name: 'Run history reloads final evidence after live output compaction' });
  } catch (error) { results.push({ name: 'Run history final output', error: String(error) }); }
  try {
    const run: LocalRunDto = { id: 'failed', ordinal: 1, initiator: 'USER', scope: 'TESTS', path: 'TestExample.java', testClass: null, testMethod: null,
      module: null, command: 'Run tests', sourceHeadSha: 'abc', localPaths: [], sourceFingerprint: 'fingerprint', status: 'FAILED_ASSERTION',
      startedAt: '2026-01-01T00:00:00Z', endedAt: '2026-01-01T00:00:01Z', exitCode: null, tests: 2, failures: 1, errors: 0, skipped: 0,
      outputTruncated: false, omittedBytes: null, treeUnverified: false, results: [{ className: 'TestExample', durationMs: 10, methods: [
        { name: 'fails', status: 'FAILED', durationMs: 5, message: 'NoClassDefFoundError: com/example/TestSupport', trace: 'at TestExample.fails' },
        { name: 'passes', status: 'PASSED', durationMs: 5, message: null, trace: null },
        { name: 'reads like a sentence', status: 'PASSED', durationMs: 5, message: null, trace: null }] }] };
    const asked: string[] = [];
    const ran: string[][] = [];
    flushSync(() => root.render(<RunPanel embedded runs={[run]} files={null} hostKind="review" onStop={() => {}}
      readOutput={async () => ({ text: 'runner output line', nextOffset: 18, skippedBytes: 0, complete: true })}
      selectedRunId={run.id} onSelectRun={() => {}} onAskAgent={(_run, text) => asked.push(text)} onRunWithAgent={(_run, tests) => ran.push(tests)} />));
    await settle(); await settle();
    const views = () => [...host.querySelectorAll<HTMLButtonElement>('[aria-label="Run log views"] [role="tab"]')];
    const output = () => fixtureElement<HTMLElement>('.ct-run__output', host, 'The run output must stay mounted');
    assert(views().map(tab => `${tab.textContent}:${tab.getAttribute('aria-selected')}`).join() === 'Failure:true,Output:false', 'A failed run opens on its failure');
    assert(host.querySelector('[aria-label="Test failure details"]')?.textContent?.includes('TestSupport') === true && output().hidden, 'The failure fills the log pane alone');
    [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Ask agent about failure')?.click();
    assert(asked.length === 1 && asked[0].includes('TestSupport') && asked[0].includes('at TestExample.fails'), 'Ask agent sends the failure message and trace');
    [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Ask agent to run it')?.click();
    assert(JSON.stringify(ran) === '[["fails","passes"]]', `Ask agent to run it names the run's tests by code name only: ${JSON.stringify(ran)}`);
    views()[1].click(); await settle();
    assert(host.querySelector('[aria-label="Test failure details"]') === null && !output().hidden && output().textContent?.includes('runner output line') === true,
      'Output shows the whole run log in place of the failure');
    [...host.querySelectorAll<HTMLButtonElement>('.ct-run__row-name')].find(button => button.textContent?.startsWith('fails'))?.click(); await settle();
    assert(views()[0].getAttribute('aria-selected') === 'true' && output().hidden, 'Picking a failed test shows its failure again');
    [...host.querySelectorAll<HTMLButtonElement>('.ct-run__row-name')].find(button => button.textContent?.startsWith('passes'))?.click(); await settle();
    assert(views().length === 0 && !output().hidden, 'A passing test has no failure tab, only the output');
    results.push({ name: 'A failure and the run output take separate tabs' });
  } catch (error) { results.push({ name: 'Run failure and output tabs', error: String(error) }); }
  const originalWorkbench = window.ideWorkbench;
  try {
    const run = (id: string, ordinal: number, debug = false): LocalRunDto => ({ id, ordinal, initiator: 'USER', scope: 'TESTS',
      path: 'main_test.go', testClass: null, testMethod: null, module: null, command: debug ? 'Debug tests' : 'Run tests',
      sourceHeadSha: 'abc', localPaths: [], sourceFingerprint: 'fingerprint', status: 'PASSED', startedAt: '2026-01-01T00:00:00Z',
      endedAt: '2026-01-01T00:00:01Z', exitCode: 0, tests: 1, failures: 0, errors: 0, skipped: 0, outputTruncated: false,
      omittedBytes: null, treeUnverified: false, results: [{ className: id, durationMs: 10,
        methods: [{ name: `${id}Test`, status: 'PASSED', durationMs: 10, message: null, trace: null }] }] });
    let runs = [run('debug-new', 4, true), run('run-new', 3), run('debug-old', 2, true), run('run-old', 1)];
    runs.unshift({ ...run('configuration', 5), scope: 'DEBUG', command: 'Launch configuration' });
    const outputs: string[] = [];
    let historyReads = 0;
    window.ideWorkbench = { request: async (method: string, ...args: unknown[]) => {
      if (method === 'listCodeRuns') { historyReads++; return runs; }
      if (method === 'readCodeRunOutput') {
        outputs.push(String(args[1]));
        return { text: `Output for ${args[1]}`, nextOffset: 10, skippedBytes: 0, complete: true };
      }
      throw new Error(`Unexpected request: ${method}`);
    } } as typeof window.ideWorkbench;
    const context = { host: { kind: 'task', taskId: 'task' }, review: null, task: null } as IdeContext;
    const runtime = { executions: { run: null } } as IdeRuntime;
    const render = (mode: 'run' | 'debug' | null) => flushSync(() => root.render(<TestRuns context={context} files={null} runtime={runtime} mode={mode} />));
    const panel = (mode: 'Run' | 'Debug') => {
      const element = host.querySelector<HTMLElement>(`[aria-label="${mode} tests"]`);
      assert(element !== null, `${mode} must have a result panel`); return element;
    };
    const selected = (mode: 'Run' | 'Debug') => panel(mode).querySelector('[role="tab"][aria-selected="true"]')?.textContent;
    render('run'); await settle(); await settle();
    assert(selected('Run')?.includes('#3') && selected('Debug')?.includes('#4'), 'Each panel selects its latest matching execution');
    assert(panel('Run').querySelector('[role="treeitem"]')?.textContent?.includes('run-newTest'), 'Passing tests are visible by default');
    assert(!host.querySelector('.ct-run__mode') && !host.textContent?.includes('Launch configuration'), 'Embedded results have no nested Run label or launch configuration');
    const history = (mode: 'Run' | 'Debug') => {
      const button = fixtureElement<HTMLButtonElement>('.ct-run__history', panel(mode), 'History button must remain available');
      flushSync(() => button.click());
    };
    history('Run'); await settle();
    assert(panel('Run').querySelectorAll('.ct-history__row').length === 1 && !panel('Run').querySelector('.ct-history')?.textContent?.includes('Debug tests'), 'Run history excludes debug executions');
    panel('Run').querySelector<HTMLButtonElement>('.ct-history__row')?.click(); await settle();
    render('debug'); await settle();
    assert(getComputedStyle(panel('Run')).display === 'none' && getComputedStyle(panel('Debug')).display !== 'none', 'Debug shows only its own results');
    history('Debug'); await settle(); panel('Debug').querySelector<HTMLButtonElement>('.ct-history__row')?.click(); await settle();
    const readsBeforeRefresh = historyReads;
    render(null); render('run'); document.dispatchEvent(new Event('visibilitychange')); await settle();
    assert(historyReads > readsBeforeRefresh, 'Returning to the app must refresh its run history');
    assert(selected('Run')?.includes('#1') && selected('Debug')?.includes('#2'), 'Switching panels and refreshing history preserves both selections');
    assert(outputs.includes('run-old') && outputs.includes('debug-old'), 'Selecting either history reads its retained log');
    runs = [run('run-latest', 6), ...runs]; document.dispatchEvent(new Event('visibilitychange')); await settle();
    assert(selected('Run')?.includes('#6') && selected('Debug')?.includes('#2'), 'A new run selects its result without changing the Debug selection');
    results.push({ name: 'Run and Debug retain separate test results, logs and history selections' });
  } catch (error) { results.push({ name: 'Run and Debug result panels', error: String(error) }); }
  finally { dispose(); window.ideWorkbench = originalWorkbench; }
  return results;
}
