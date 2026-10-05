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
import { useEffect, useId, useRef, useState } from 'react';
import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import '@xterm/xterm/css/xterm.css';
import { TERMINAL_THEME } from '../pulls/code-tests/TerminalView';
import type { Bridge } from '../types';
import './css/provider-signin.css';

export type SignInBridge = Pick<Bridge, 'openProviderSignIn' | 'writeReviewTerminal' | 'resizeReviewTerminal' | 'onReviewTerminalEvent'>;

type Status =
  | { kind: 'starting' }
  | { kind: 'running' }
  | { kind: 'done'; exitCode: number }
  | { kind: 'failed'; message: string };

/**
 * Runs a provider's own sign-in command in a terminal, so the lane signs into ByteQuay's provider
 * home rather than the user's. The command itself comes from readiness, through the main process:
 * this dialog names only the lane.
 *
 * The shell is one-shot, so its exit is the sign-in's own result. The sign-in still finishes in a
 * browser, and the CLI may ask its own questions first, which is why this is a terminal and not a
 * progress bar.
 */
export default function ProviderSignInDialog({ laneId, providerName, bridge, onClose }: {
  laneId: string;
  providerName: string;
  bridge: SignInBridge;
  onClose: (signedIn: boolean) => void;
}) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const screen = useRef<HTMLDivElement>(null);
  const [status, setStatus] = useState<Status>({ kind: 'starting' });
  const id = useId();
  const signedIn = status.kind === 'done' && status.exitCode === 0;

  useEffect(() => {
    const dialog = dialogRef.current;
    const previousFocus = document.activeElement;
    dialog?.showModal();
    return () => {
      dialog?.close();
      if (previousFocus instanceof HTMLElement && previousFocus.isConnected) previousFocus.focus();
    };
  }, []);

  useEffect(() => {
    const element = screen.current;
    if (element === null) return;
    const term = new Terminal({
      fontFamily: "ui-monospace, Menlo, 'Roboto Mono', monospace", fontSize: 12, cursorBlink: true, scrollback: 2000, theme: TERMINAL_THEME,
    });
    const fit = new FitAddon();
    term.loadAddon(fit);
    term.open(element);
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
      setStatus({ kind: 'done', exitCode: event.exitCode });
    });
    // The CLI may still ask which account to use, so keystrokes reach it.
    const input = term.onData(data => {
      if (terminalId !== null) bridge.writeReviewTerminal(terminalId, data);
    });
    const resize = term.onResize(({ cols, rows }) => {
      if (terminalId !== null) bridge.resizeReviewTerminal(terminalId, cols, rows);
    });
    const observer = new ResizeObserver(refit);
    observer.observe(element);

    bridge.openProviderSignIn(laneId, term.cols, term.rows).then(opened => {
      if (!live) return;
      terminalId = opened.terminalId;
      term.write(opened.scrollback);
      bridge.resizeReviewTerminal(opened.terminalId, term.cols, term.rows);
      setStatus({ kind: 'running' });
      term.focus();
    }, (failure: unknown) => {
      if (live) setStatus({ kind: 'failed', message: failure instanceof Error ? failure.message : String(failure) });
    });

    return () => {
      live = false;
      stopEvents();
      input.dispose();
      resize.dispose();
      observer.disconnect();
      term.dispose();
    };
  }, [bridge, laneId]);

  return (
    <dialog
      ref={dialogRef}
      className="bq-signin-dialog"
      aria-labelledby={`${id}-title`}
      aria-modal="true"
      onCancel={event => {
        event.preventDefault();
        onClose(signedIn);
      }}
      onKeyDown={event => event.stopPropagation()}
    >
      <h2 id={`${id}-title`} className="bq-signin-dialog__title">Sign in to {providerName}</h2>
      <p className="bq-signin-dialog__lede" role="status">
        {status.kind === 'starting' && 'Starting the sign-in…'}
        {status.kind === 'running' && `Choose the intended email in your browser. This signs in only to ${providerName}’s separate ByteQuay profile.`}
        {signedIn && `${providerName} sign-in finished. Close this dialog to check the signed-in account.`}
        {status.kind === 'done' && status.exitCode !== 0 && `The sign-in ended with status ${status.exitCode}. Check the account status before trying again.`}
        {status.kind === 'failed' && status.message}
      </p>
      <div className="bq-signin-dialog__screen" ref={screen} />
      <div className="bq-signin-dialog__actions">
        <button type="button" className="bq-signin-dialog__button" onClick={() => onClose(signedIn)}>
          {status.kind === 'done' || status.kind === 'failed' ? 'Done' : 'Cancel'}
        </button>
      </div>
    </dialog>
  );
}
