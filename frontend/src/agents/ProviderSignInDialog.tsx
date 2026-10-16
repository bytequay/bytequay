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
import { attachTerminal, type TerminalStatus } from '../terminalSession';
import type { Bridge } from '../types';
import './css/provider-signin.css';

export type SignInBridge = Pick<Bridge, 'openProviderSignIn' | 'writeReviewTerminal' | 'resizeReviewTerminal' | 'onReviewTerminalEvent'>;

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
  const [status, setStatus] = useState<TerminalStatus>({ kind: 'opening' });
  const id = useId();
  const signedIn = status.kind === 'exited' && status.exitCode === 0;

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
    return attachTerminal(element, bridge, (cols, rows) => bridge.openProviderSignIn(laneId, cols, rows), {
      scrollback: 2000, onStatus: setStatus,
    });
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
        {status.kind === 'opening' && 'Starting the sign-in…'}
        {status.kind === 'open' && `Choose the intended email in your browser. This signs in only to ${providerName}’s separate ByteQuay profile.`}
        {signedIn && `${providerName} sign-in finished. Close this dialog to check the signed-in account.`}
        {status.kind === 'exited' && status.exitCode !== 0 && `The sign-in ended with status ${status.exitCode}. Check the account status before trying again.`}
        {status.kind === 'failed' && (status.error instanceof Error ? status.error.message : String(status.error))}
      </p>
      <div className="bq-signin-dialog__screen" ref={screen} />
      <div className="bq-signin-dialog__actions">
        <button type="button" className="bq-signin-dialog__button" onClick={() => onClose(signedIn)}>
          {status.kind === 'exited' || status.kind === 'failed' ? 'Done' : 'Cancel'}
        </button>
      </div>
    </dialog>
  );
}
