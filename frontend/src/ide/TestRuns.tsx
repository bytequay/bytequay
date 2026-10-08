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
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import RunPanel from '../pulls/code-tests/RunPanel';
import { parseLocalRun, parseLocalRunOutput, codeHostKey, type LocalRunDto, type ReviewCodeFilesDto } from '../pulls/code-tests/contracts';
import type { IdeContext } from './contracts';
import type { IdeRuntime } from './runtime';

type Mode = 'run' | 'debug';

export function testRunPanel(run: LocalRunDto): Mode | null {
  // Test debug profiles are recorded by the test controller, including their debugger's output.
  if (run.scope === 'TESTS' && run.command === 'Debug tests') return 'debug';
  return run.scope === 'DEBUG' ? null : 'run';
}

export default function TestRuns({ context, files, runtime, mode }: {
  context: IdeContext; files: ReviewCodeFilesDto | null; runtime: IdeRuntime; mode: Mode | null;
}) {
  const [runs, setRuns] = useState<LocalRunDto[]>([]);
  const [selected, setSelected] = useState<Record<Mode, string | null>>({ run: null, debug: null });
  const newest = useRef<Record<Mode, string | null>>({ run: null, debug: null });
  const [notice, setNotice] = useState<string | null>(null);
  const key = codeHostKey(context.host);
  const host = useMemo(() => context.host, [key]); // eslint-disable-line react-hooks/exhaustive-deps -- IPC context objects refresh; host identity is stable.
  useEffect(() => {
    let live = true;
    const load = async () => {
      try {
        const value = await window.ideWorkbench.request('listCodeRuns', host);
        if (!Array.isArray(value)) throw new Error('Invalid run history');
        if (live) {
          const next = value.map(parseLocalRun);
          setRuns(next);
          for (const panel of ['run', 'debug'] as const) {
            const id = next.find(run => testRunPanel(run) === panel)?.id ?? null;
            if (id !== newest.current[panel]) {
              newest.current[panel] = id;
              setSelected(current => ({ ...current, [panel]: id }));
            }
          }
        }
      } catch (error) { if (live) setNotice(String(error)); }
    };
    void load(); const timer = setInterval(() => { void load(); }, 1500);
    return () => { live = false; clearInterval(timer); };
  }, [host]);
  const output = useCallback(async (id: string, after: number) => parseLocalRunOutput(await window.ideWorkbench.request('readCodeRunOutput', host, id, after)), [host]);
  const showRunHint = files !== null && (context.review
    ? context.review.state === 'OPEN' && files.source === 'WORKING_COPY'
    : context.task?.interactive !== false && context.task?.state === 'OPEN' && context.task.checkoutRemovedAt === null);
  const stop = async (run: LocalRunDto) => {
    try {
      if (runtime.executions.run?.id === run.id) await runtime.executions.stop(run.treeUnverified);
      else if (['TESTS', 'DEBUG', 'RUN'].includes(run.scope)) await window.ideWorkbench.stopExecution(run.id);
      else await window.ideWorkbench.request('stopCodeRun', host, run.id);
    } catch (error) { setNotice(String(error)); }
  };
  return <div className="ide-test-runs" style={{ display: mode === null ? 'none' : undefined }}>
    {notice && <div className="ide-notice" role="status">{notice}<button onClick={() => setNotice(null)}>Dismiss</button></div>}
    {(['run', 'debug'] as const).map(panel => <div key={panel} className="ide-test-runs" aria-label={panel === 'run' ? 'Run tests' : 'Debug tests'}
      style={{ display: mode === panel ? undefined : 'none' }}>
      <RunPanel embedded runs={runs.filter(run => testRunPanel(run) === panel)} files={files} showRunHint={showRunHint} hostKind={host.kind}
        onStop={run => { void stop(run); }} readOutput={output} selectedRunId={selected[panel]} onSelectRun={id => setSelected(current => ({ ...current, [panel]: id }))}
        onAskAgent={(context.review ?? context.task)?.state === 'OPEN' ? (run, text) => {
          void window.ideWorkbench.request('run.ask', run.id, text).then(() => setNotice('Added to the agent draft. Open the conversation to review and send.'), error => setNotice(String(error)));
        } : undefined} />
    </div>)}
  </div>;
}
