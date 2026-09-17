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

import { useEffect, useRef, useState } from 'react';
import type { AgentDevelopmentCiDto, Bridge, CiAutofixRunDto } from '../types';
import { remoteErrorMessage } from '../pulls/PullOverview';
import { ciActivity } from './ciAutofix';

type CiBridge = Pick<Bridge, 'getCiAutofixRun' | 'listCiAutofixRuns' | 'startCiAutofixRun' | 'cancelCiAutofixRun'>;

/** A new standalone run uses the existing service; a terminal delivery is never resumed. */
export default function CiAutofixRecovery({ previous, workspaceId, laneId, disabled, bridge, onRunningChange }: {
  previous: AgentDevelopmentCiDto;
  workspaceId: string;
  laneId: string;
  disabled: boolean;
  bridge: CiBridge;
  onRunningChange: (running: boolean) => void;
}) {
  const [run, setRun] = useState<CiAutofixRunDto | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [pollError, setPollError] = useState<string | null>(null);
  const current = useRef<CiAutofixRunDto | null>(null);
  const requestKey = useRef<string | null>(null);
  const mutation = useRef(0);
  const mounted = useRef(false);

  useEffect(() => {
    mounted.current = true;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      const revision = mutation.current;
      try {
        let value: CiAutofixRunDto;
        if (current.current === null) {
          const original = await bridge.getCiAutofixRun(previous.runId);
          if (original.workspaceId !== workspaceId || original.pullRequestNumber !== previous.pullRequestNumber || original.headRef !== previous.headRef) {
            throw new Error('CI run does not match this task’s pull request');
          }
          const recent = await bridge.listCiAutofixRuns();
          value = recent.find(candidate => candidate.workspaceId === workspaceId && candidate.repository === original.repository
            && candidate.pullRequestNumber === original.pullRequestNumber && candidate.headRef === original.headRef) ?? original;
        } else {
          value = await bridge.getCiAutofixRun(current.current.id);
        }
        if (!cancelled && mutation.current === revision) {
          current.current = value;
          setRun(value);
          setPollError(null);
        }
      } catch (failure) {
        if (!cancelled) setPollError(remoteErrorMessage(failure));
      } finally {
        if (!cancelled) timer = setTimeout(() => { void poll(); }, 5_000);
      }
    };
    void poll();
    return () => { cancelled = true; mounted.current = false; clearTimeout(timer); };
  }, [bridge, previous.runId, previous.pullRequestNumber, previous.headRef, workspaceId]);

  const active = run !== null && run.status !== 'FINISHED';
  useEffect(() => { onRunningChange(active || busy); }, [active, busy, onRunningChange]);
  useEffect(() => () => onRunningChange(false), [onRunningChange]);

  const act = async (stop: boolean) => {
    if (busy || current.current === null) return;
    const target = current.current;
    mutation.current++;
    setBusy(true);
    setError(null);
    try {
      // A retry in this window must not allocate another run after a lost start response.
      const storageKey = `ci-autofix-start:${workspaceId}:${target.repository}:${target.pullRequestNumber}:${target.headRef}`;
      requestKey.current ??= sessionStorage.getItem(storageKey) ?? crypto.randomUUID();
      if (!stop) sessionStorage.setItem(storageKey, requestKey.current);
      const next = stop ? await bridge.cancelCiAutofixRun(target.id)
        : await bridge.startCiAutofixRun(workspaceId, target.pullRequestNumber, laneId, requestKey.current);
      if (next.workspaceId !== workspaceId || next.repository !== target.repository || next.pullRequestNumber !== target.pullRequestNumber || next.headRef !== target.headRef) {
        throw new Error('CI response does not match this task’s pull request');
      }
      sessionStorage.removeItem(storageKey);
      requestKey.current = null;
      if (mounted.current) {
        mutation.current++;
        current.current = next;
        setRun(next);
        setPollError(null);
      }
    } catch (failure) {
      if (mounted.current) setError(remoteErrorMessage(failure));
    } finally {
      if (mounted.current) setBusy(false);
    }
  };

  const state = run === null ? previous : { ...run, maxAttempts: run.authorization.maxAttempts };
  return <div className="agt-ci-recovery">
    <div role="status" aria-live="polite">
      <strong>{busy ? 'Updating CI fix…' : ciActivity(state)}</strong>
      <span>{state.attemptsUsed}/{state.maxAttempts} repairs · {state.rerunsUsed}/3 reruns</span>
      {run?.status === 'ACTIVE' && <span>Rechecks automatically until {new Date(run.authorization.expiresAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</span>}
      {!active && state.finishReason !== 'GREEN' && <span>Automatic rechecking has stopped. Restarting the app does not start another repair.</span>}
    </div>
    {state.finishReason !== 'GREEN' && <button type="button" className="agt-action" disabled={busy || run === null || pollError !== null || (!active && disabled)}
      title={active ? 'Stop this CI run' : 'Start a new CI run with 3 repair attempts and up to 3 reruns per head for 8 hours. It may repair the PR description, commit fixes, rebase and push this PR branch.'}
      onClick={() => { void act(active); }}>{active ? 'Stop CI fix' : 'Restart CI fix'}</button>}
    {run?.status === 'PAUSED' && <p>Automatic repair is paused. {run.summary ?? 'CI is paused; it needs attention before it can continue.'}</p>}
    {(error ?? pollError) !== null && <p role="alert">{error ?? pollError}</p>}
  </div>;
}
