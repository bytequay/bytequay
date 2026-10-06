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
import { runGate, taskRunGate } from '../pulls/code-tests/runModel';
import type { IdeContext } from './contracts';
import type { IdeRuntime } from './runtime';

export default function RunHistory({ context, files, runtime, onTests }: {
  context: IdeContext; files: ReviewCodeFilesDto | null; runtime: IdeRuntime; onTests: () => void;
}) {
  const [runs, setRuns] = useState<LocalRunDto[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const selectedOnce = useRef(false);
  const [notice, setNotice] = useState<string | null>(null);
  const key = codeHostKey(context.host);
  const host = useMemo(() => context.host, [key]); // eslint-disable-line react-hooks/exhaustive-deps -- IPC context objects refresh; host identity is stable.
  useEffect(() => {
    let live = true;
    const load = async () => {
      try {
        const value = await window.ideWorkbench.request('listCodeRuns', host);
        if (!Array.isArray(value)) throw new Error('Invalid run history');
        if (live) { const next = value.map(parseLocalRun); setRuns(next); if (!selectedOnce.current) { selectedOnce.current = true; setSelected(next[0]?.id ?? null); } }
      } catch (error) { if (live) setNotice(String(error)); }
    };
    void load(); const timer = setInterval(() => { void load(); }, 1500);
    return () => { live = false; clearInterval(timer); };
  }, [host]);
  const output = useCallback(async (id: string, after: number) => parseLocalRunOutput(await window.ideWorkbench.request('readCodeRunOutput', host, id, after)), [host]);
  const gate = context.review ? runGate(context.review, files, runs) : context.task ? taskRunGate(context.task, files, runs) : { hidden: true, blocked: null };
  const stop = async (run: LocalRunDto) => {
    try {
      if (runtime.executions.run?.id === run.id) await runtime.executions.stop(run.treeUnverified);
      else if (['TESTS', 'DEBUG', 'RUN'].includes(run.scope)) await window.ideWorkbench.stopExecution(run.id);
      else await window.ideWorkbench.request('stopCodeRun', host, run.id);
    } catch (error) { setNotice(String(error)); }
  };
  return <div className="ide-run-history">
    {notice && <div className="ide-notice" role="status">{notice}<button onClick={() => setNotice(null)}>Dismiss</button></div>}
    <RunPanel embedded runs={runs} files={files} gate={gate} refusal={null} onDismissRefusal={() => {}} terminal={null} hostKind={host.kind}
      onStop={run => { void stop(run); }} onBrowseTests={onTests} readOutput={output} selectedRunId={selected} onSelectRun={setSelected}
      onAskAgent={(context.review ?? context.task)?.state === 'OPEN' ? (run, text) => {
        void window.ideWorkbench.request('run.ask', run.id, text).then(() => setNotice('Added to the agent draft. Open the conversation to review and send.'), error => setNotice(String(error)));
      } : undefined} />
  </div>;
}
