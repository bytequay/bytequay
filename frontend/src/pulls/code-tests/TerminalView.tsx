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
import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import '@xterm/xterm/css/xterm.css';
import type { Bridge } from '../../types';
import { remoteErrorMessage } from '../PullOverview';
import type { CodeHostRef } from './contracts';

export type TerminalBridge = Pick<Bridge, 'openCodeTerminal' | 'writeReviewTerminal' | 'resizeReviewTerminal' | 'onReviewTerminalEvent'>;

type Status = { kind: 'opening' } | { kind: 'open' } | { kind: 'exited'; exitCode: number } | { kind: 'failed'; message: string };

// GitHub light's ANSI palette; xterm's defaults are tuned for a dark ground.
export const TERMINAL_THEME = {
  background: '#ffffff', foreground: '#1f2328', cursor: '#1f2328', cursorAccent: '#ffffff', selectionBackground: '#b6e3ff',
  black: '#24292f', red: '#cf222e', green: '#116329', yellow: '#4d2d00', blue: '#0550ae', magenta: '#8250df', cyan: '#1b7c83', white: '#6e7781',
  brightBlack: '#57606a', brightRed: '#a40e26', brightGreen: '#1a7f37', brightYellow: '#633c01', brightBlue: '#218bff', brightMagenta: '#a475f9',
  brightCyan: '#3192aa', brightWhite: '#8c959f',
};

/** The host's login shell. Unmounting only detaches: the shell keeps running and replays its output on the next attach. */
export default function TerminalView({ host: codeHost, bridge, clearRequest }: { host: CodeHostRef; bridge: TerminalBridge; clearRequest: number }) {
  const host = useRef<HTMLDivElement>(null);
  const terminal = useRef<Terminal | null>(null);
  const [status, setStatus] = useState<Status>({ kind: 'opening' });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    const element = host.current;
    if (element === null) return;
    const term = new Terminal({ fontFamily: "ui-monospace, Menlo, 'Roboto Mono', monospace", fontSize: 12, cursorBlink: true, scrollback: 5000, theme: TERMINAL_THEME });
    const fit = new FitAddon();
    term.loadAddon(fit);
    term.open(element);
    terminal.current = term;
    // Hidden panes measure zero, and fitting to zero would shrink the shell to one column.
    const refit = () => {
      if (element.clientWidth > 0 && element.clientHeight > 0) fit.fit();
    };
    refit();

    let live = true;
    let terminalId: string | null = null;
    const stopEvents = bridge.onReviewTerminalEvent(event => {
      if (event.terminalId !== terminalId) return;
      if ('data' in event) {
        term.write(event.data);
        return;
      }
      terminalId = null;
      term.write(`\r\n\x1b[90m[shell exited with status ${event.exitCode}]\x1b[0m\r\n`);
      setStatus({ kind: 'exited', exitCode: event.exitCode });
    });
    const input = term.onData(data => {
      if (terminalId !== null) bridge.writeReviewTerminal(terminalId, data);
    });
    const resize = term.onResize(({ cols, rows }) => {
      if (terminalId !== null) bridge.resizeReviewTerminal(terminalId, cols, rows);
    });
    const observer = new ResizeObserver(refit);
    observer.observe(element);

    setStatus({ kind: 'opening' });
    // Output pushed before the attach answers is already in the scrollback, so it is dropped until the id is known.
    bridge.openCodeTerminal(codeHost, term.cols, term.rows).then(opened => {
      if (!live) return;
      terminalId = opened.terminalId;
      term.write(opened.scrollback);
      bridge.resizeReviewTerminal(opened.terminalId, term.cols, term.rows);
      setStatus({ kind: 'open' });
      term.focus();
    }, failure => {
      if (live) setStatus({ kind: 'failed', message: remoteErrorMessage(failure) });
    });

    return () => {
      live = false;
      stopEvents();
      input.dispose();
      resize.dispose();
      observer.disconnect();
      term.dispose();
      terminal.current = null;
    };
  }, [attempt, bridge, codeHost]);

  useEffect(() => {
    if (clearRequest > 0) terminal.current?.clear();
  }, [clearRequest]);

  return (
    <div className="ct-terminal">
      <div className="ct-terminal__screen" ref={host} />
      {status.kind === 'opening' && <div className="ct-terminal__status" role="status">Starting a login shell in the working copy…</div>}
      {(status.kind === 'exited' || status.kind === 'failed') && (
        <div className={`ct-terminal__status${status.kind === 'failed' ? ' is-failed' : ''}`} role={status.kind === 'failed' ? 'alert' : 'status'}>
          <span>{status.kind === 'failed' ? status.message : `The shell exited with status ${status.exitCode}.`}</span>
          <button type="button" className="ct-text-button" onClick={() => setAttempt(value => value + 1)}>
            {status.kind === 'failed' ? 'Try again' : 'Start a new shell'}
          </button>
        </div>
      )}
    </div>
  );
}
