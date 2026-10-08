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
import { createRoot } from 'react-dom/client';
import { SourceSnapshot } from '../ArchivedCode';
import RunPanel from '../RunPanel';
import type { LocalRunDto } from '../contracts';
import { useCodeComments, type CodeCommentsOptions } from '../useCodeComments';
import { assert, changeInput, settle } from '../../../fixtures/harness';
import type { PullRequestDto } from '../../../types';
import TestRuns from '../../../ide/TestRuns';
import type { IdeContext } from '../../../ide/contracts';
import type { IdeRuntime } from '../../../ide/runtime';
const pr = { repo: 'owner/repo', number: 1, headRef: 'feature' } as PullRequestDto;

export async function runArchivedCodeScenarios(): Promise<{ name: string; error?: string }[]> {
  const results: { name: string; error?: string }[] = [];
  const host = document.createElement('div'); document.body.append(host); const root = createRoot(host);
  const options: CodeCommentsOptions = { repo: 'owner/repo', number: 1, canReview: true, viewer: null, pending: [], onPendingChange: () => {},
    threads: { activities: [], pr, unavailable: false, onRefresh: async () => {}, onDetailUpdate: () => {} }, onDetailChange: () => {},
    localReview: { threads: [], onReply: async () => {}, onResolve: async () => {}, onRetry: async () => {} } };
  const text = Array.from({ length: 20 }, (_, index) => `line ${index + 1}`).join('\n');
  const Harness = () => {
    const comments = useCodeComments(options, { path: 'notes.txt', text, prPatch: '@@ -1,8 +1,8 @@\n' + text.split('\n').slice(0, 8).map(line => ' ' + line).join('\n'),
      draft: false, uncommitted: false, atReviewedRevision: true, reviewedHead: 'abc', observedHead: 'abc', askBlocked: null });
    return <SourceSnapshot path="notes.txt" text={text} cards={comments.cards} renderCard={comments.renderCard}
      onComment={comments.onComment} commentRange={comments.commentRange} />;
  };
  try {
    flushSync(() => root.render(<Harness />)); await settle();
    host.querySelector<HTMLButtonElement>('[aria-label="Comment on line 3"]')?.click(); await settle();
    const input = host.querySelector<HTMLTextAreaElement>('.pull-diff-review textarea'); assert(input !== null, 'A line must open the shared composer');
    changeInput(input, 'Keep this private draft'); await settle();
    host.querySelector('[aria-label="Comment on line 6"]')?.dispatchEvent(new MouseEvent('click', { shiftKey: true, bubbles: true })); await settle();
    assert(host.querySelector('.pull-diff-review__header strong')?.textContent === 'Add a comment on lines R3 to R6', 'Shift-click must extend the retained source range');
    assert(host.querySelector<HTMLTextAreaElement>('.pull-diff-review textarea')?.value === 'Keep this private draft', 'Range changes preserve the draft');
    host.querySelector('[aria-label="Comment on line 11"]')?.dispatchEvent(new MouseEvent('click', { shiftKey: true, bubbles: true })); await settle();
    assert([...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Post now')?.disabled, 'A source range outside the actual PR hunk cannot be posted');
    results.push({ name: 'Historical source preserves shared comment ranges and patch guards' });
  } catch (error) { results.push({ name: 'Historical source comments', error: String(error) }); }
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
    window.ideWorkbench = { request: async (method: string, ...args: unknown[]) => {
      if (method === 'listCodeRuns') return runs;
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
      const button = panel(mode).querySelector<HTMLButtonElement>('.ct-run__history');
      assert(button !== null, 'History button must remain available'); flushSync(() => button.click());
    };
    history('Run'); await settle();
    assert(panel('Run').querySelectorAll('.ct-history__row').length === 1 && !panel('Run').querySelector('.ct-history')?.textContent?.includes('Debug tests'), 'Run history excludes debug executions');
    panel('Run').querySelector<HTMLButtonElement>('.ct-history__row')?.click(); await settle();
    render('debug'); await settle();
    assert(getComputedStyle(panel('Run')).display === 'none' && getComputedStyle(panel('Debug')).display !== 'none', 'Debug shows only its own results');
    history('Debug'); await settle(); panel('Debug').querySelector<HTMLButtonElement>('.ct-history__row')?.click(); await settle();
    render(null); render('run'); await new Promise(resolve => setTimeout(resolve, 1600));
    assert(selected('Run')?.includes('#1') && selected('Debug')?.includes('#2'), 'Switching panels and refreshing history preserves both selections');
    assert(outputs.includes('run-old') && outputs.includes('debug-old'), 'Selecting either history reads its retained log');
    runs = [run('run-latest', 6), ...runs]; await new Promise(resolve => setTimeout(resolve, 1600));
    assert(selected('Run')?.includes('#6') && selected('Debug')?.includes('#2'), 'A new run selects its result without changing the Debug selection');
    results.push({ name: 'Run and Debug retain separate test results, logs and history selections' });
  } catch (error) { results.push({ name: 'Run and Debug result panels', error: String(error) }); }
  finally { flushSync(() => root.unmount()); host.remove(); window.ideWorkbench = originalWorkbench; }
  return results;
}
