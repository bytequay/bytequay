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
import { attachTerminal, type TerminalStatus } from '../../terminalSession';
import type { Bridge } from '../../types';
import { remoteErrorMessage } from '../../remoteError';
import { codeHostKey, type CodeHostRef } from './contracts';

export type TerminalBridge = Pick<Bridge, 'openCodeTerminal' | 'writeReviewTerminal' | 'resizeReviewTerminal' | 'onReviewTerminalEvent'>;

/** The host's login shell. Unmounting only detaches: the shell keeps running and replays its output on the next attach. */
export default function TerminalView({ host: codeHost, bridge }: { host: CodeHostRef; bridge: TerminalBridge }) {
  const hostKey = codeHostKey(codeHost);
  const currentHost = useRef(codeHost); currentHost.current = codeHost;
  const host = useRef<HTMLDivElement>(null);
  const [status, setStatus] = useState<TerminalStatus>({ kind: 'opening' });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    const element = host.current;
    if (element === null) return;
    return attachTerminal(element, bridge, (cols, rows) => bridge.openCodeTerminal(currentHost.current, cols, rows), {
      scrollback: 5000, onStatus: setStatus, exitMessage: exitCode => `shell exited with status ${exitCode}`,
    });
  }, [attempt, bridge, hostKey]);

  return (
    <div className="ct-terminal">
      <div className="ct-terminal__screen" ref={host} />
      {status.kind === 'opening' && <div className="ct-terminal__status" role="status">Starting a login shell in the working copy…</div>}
      {(status.kind === 'exited' || status.kind === 'failed') && (
        <div className={`ct-terminal__status${status.kind === 'failed' ? ' is-failed' : ''}`} role={status.kind === 'failed' ? 'alert' : 'status'}>
          <span>{status.kind === 'failed' ? remoteErrorMessage(status.error) : `The shell exited with status ${status.exitCode}.`}</span>
          <button type="button" className="ct-text-button" onClick={() => setAttempt(value => value + 1)}>
            {status.kind === 'failed' ? 'Try again' : 'Start a new shell'}
          </button>
        </div>
      )}
    </div>
  );
}
