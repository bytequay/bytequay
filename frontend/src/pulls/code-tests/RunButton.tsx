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
import { LoaderCircle, Play } from 'lucide-react';
import type { LocalRunDto } from './contracts';
import { isRunning, lastOutcome, type RunGate } from './runModel';

export type RunTarget = { path: string; className: string; method: string | null };

/**
 * The run icon for a test class or method, wherever the design places one. It becomes a spinner on the run it
 * started, and while anything holds the working copy it stays visible, disabled, with the reason as its title.
 */
export default function RunButton({ target, runs, gate, onRun, size = 18 }: {
  target: RunTarget;
  runs: readonly LocalRunDto[];
  gate: RunGate;
  onRun: (target: RunTarget) => void;
  size?: number;
}) {
  if (gate.hidden) return null;
  const name = target.method === null ? `class ${target.className}` : `${target.method}()`;
  if (isRunning(runs, target.path, target.method)) {
    return (
      <span className="ct-run-button is-running" style={{ width: size, height: size }} role="status"
        title="A run is using this working copy — reading stays available">
        <LoaderCircle className="ct-spin" size={size - 6} strokeWidth={2} aria-label={`Running ${name}`} />
      </span>
    );
  }
  const outcome = lastOutcome(runs, target.className, target.method);
  const title = gate.blocked ?? (outcome === null ? `Run ${name}` : `Run ${name} — ${outcome.tone === 'pass' ? 'passed' : 'failed'} in run #${outcome.ordinal}`);
  return (
    <button type="button" className={`ct-run-button is-${outcome?.tone ?? 'idle'}`} style={{ width: size, height: size }}
      disabled={gate.blocked !== null} title={title} aria-label={`Run ${name}`}
      onClick={event => {
        event.stopPropagation();
        onRun(target);
      }}>
      <Play size={size - 8} strokeWidth={2} fill="currentColor" aria-hidden="true" />
    </button>
  );
}
