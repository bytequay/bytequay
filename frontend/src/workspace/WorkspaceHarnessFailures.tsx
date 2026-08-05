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
import { useState } from 'react';
import type { CiHarnessFailureDto } from './workspaceApi';

export type FailureActions = {
  busy: boolean;
  onResolve: (failureId: string, note: string) => void;
  onRetry: (failureId: string, note: string) => void;
};

export function isEscalated(failure: CiHarnessFailureDto): boolean {
  const status = failure.status.toLowerCase();
  return status === 'escalated' || status === 'failed';
}

/** Every open escalation, oldest first — the harness paused on each one and
 * cannot move past it without a human decision. */
export function EscalationQueue({ failures, actions }: {
  failures: CiHarnessFailureDto[];
  actions: FailureActions;
}) {
  const open = failures.filter(isEscalated);
  if (open.length === 0) return null;
  return (
    <section className="ci-harness-escalations">
      <header><h2>Needs you</h2><span>{open.length}</span></header>
      {open.map(failure => <EscalationRow key={failure.id} failure={failure} actions={actions} />)}
    </section>
  );
}

function EscalationRow({ failure, actions }: {
  failure: CiHarnessFailureDto;
  actions: FailureActions;
}) {
  const [note, setNote] = useState('');
  const [open, setOpen] = useState(false);
  return (
    <article className="ci-harness-escalation">
      <header>
        <span aria-hidden>!</span>
        <div><strong>{failure.signature}</strong>
          <small>{failure.jobName} · {failure.module} · {failure.bucket}</small></div>
        <button type="button" aria-expanded={open} onClick={() => setOpen(value => !value)}>
          {open ? 'Hide detail' : 'Open escalation'}
        </button>
      </header>
      {failure.verification !== null && !failure.verification.passed
        && failure.verification.reason !== null && (
        <p className="ci-harness-escalation__cause">Verification failed: {failure.verification.reason}</p>
      )}
      {open && <FailureDetail failure={failure} />}
      <footer>
        <label>
          <span>Your decision</span>
          <input value={note} placeholder="Optional note — kept in the run history"
            onChange={event => setNote(event.target.value)} />
        </label>
        <button type="button" disabled={actions.busy}
          onClick={() => actions.onResolve(failure.id, note)}>Resolve</button>
        <button type="button" className="wu-primary-button" disabled={actions.busy}
          title="Resolve this escalation and start a new cycle with your note as guidance"
          onClick={() => actions.onRetry(failure.id, note)}>Resolve &amp; run cycle</button>
      </footer>
    </article>
  );
}

/** The failure table plus its drawer. Every row is inspectable — the point of
 * the harness is that a human can audit what it proposed and why. */
export function FailureTable({ rows, actions, heading = 'Failures' }: {
  rows: CiHarnessFailureDto[];
  actions: FailureActions;
  heading?: string;
}) {
  const [openId, setOpenId] = useState<string | null>(null);
  const open = rows.find(row => row.id === openId) ?? null;
  return (
    <section className="ci-harness-failures">
      <header><h2>{heading}</h2><span>{rows.length}</span></header>
      <div role="table">
        {rows.map(row => (
          <button type="button" role="row" key={row.id}
            className={row.id === openId ? 'selected' : ''}
            aria-expanded={row.id === openId}
            onClick={() => setOpenId(current => current === row.id ? null : row.id)}>
            <code>{row.module}</code><strong>{row.signature}</strong><i>{row.bucket}</i>
            <span>{row.ruleId ?? row.targetSubject ?? 'unrouted'}</span>
            <b className={`is-${row.status.toLowerCase()}`}>
              {row.status.toLowerCase().replace('_', ' ')}</b>
          </button>
        ))}
        {rows.length === 0 && <p className="ci-harness-empty-feed">No failures in this cycle.</p>}
      </div>
      {open !== null && (
        <aside className="ci-harness-drawer" aria-label={`Failure detail for ${open.signature}`}>
          <header>
            <div><strong>{open.signature}</strong>
              <small>{open.jobName} · {open.module}
                {open.testClass === null ? '' : ` · ${open.testClass}`}
                {open.testMethod === null ? '' : `#${open.testMethod}`}</small></div>
            <button type="button" aria-label="Close failure detail"
              onClick={() => setOpenId(null)}>×</button>
          </header>
          <FailureDetail failure={open} />
          {isEscalated(open) && (
            <footer>
              <button type="button" disabled={actions.busy}
                onClick={() => actions.onResolve(open.id, '')}>Resolve</button>
            </footer>
          )}
        </aside>
      )}
    </section>
  );
}

function FailureDetail({ failure }: { failure: CiHarnessFailureDto }) {
  const verification = failure.verification;
  return (
    <div className="ci-harness-failure-detail">
      {failure.fix !== null && failure.fix.filesChanged.length > 0 && (
        <section>
          <h3>Files the program changed</h3>
          <ul>{failure.fix.filesChanged.map(file => <li key={file}><code>{file}</code></li>)}</ul>
        </section>
      )}
      {verification !== null && (
        <section>
          <h3>Verification <b className={verification.passed ? 'passed' : 'failed'}>
            {verification.passed ? 'passed' : 'failed'}</b></h3>
          {verification.reason !== null && <p>{verification.reason}</p>}
          {verification.commands.map((command, index) => (
            <details key={`${command.command}-${index}`}>
              <summary><code>{command.command}</code>
                <b>{command.timedOut ? 'timed out' : `exit ${command.exitCode}`}</b></summary>
              <pre>{command.outputTail ?? ''}</pre>
            </details>
          ))}
        </section>
      )}
      <section>
        <h3>Log excerpt</h3>
        <pre>{failure.logExcerpt}</pre>
      </section>
    </div>
  );
}
