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
import { useCallback, useRef, type ReactNode, type RefObject } from 'react';
import { Mic } from 'lucide-react';
import SharedComposer, { type ComposerProps } from '@bytequay/ui/Composer';
import { IconButton } from '@bytequay/ui/cards';
import { useDictation } from './dictation/useDictation';
import './css/agents.css';

export { ComposerAttachments } from '@bytequay/ui/Composer';

type Dictation = ReturnType<typeof useDictation>;

const clock = (seconds: number) => `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;

/** The line above the draft while dictating; only finished segments are announced. */
function strip(dictation: Dictation): ReactNode {
  const { view } = dictation;
  switch (view.kind) {
    case 'idle':
      return null;
    case 'listening':
    case 'finishing':
      return <div className="agt-dictation">
        {view.kind === 'listening' && <span className="agt-dictation__dot" aria-hidden="true" />}
        <span className="agt-dictation__words">
          <span aria-live="polite">{view.finished}</span>
          <span className="agt-dictation__partial">{view.finished === '' && view.partial === '' ? 'Listening…' : view.partial}</span>
        </span>
        <span className="agt-dictation__meta">
          {view.kind === 'finishing' ? 'Finishing up' : `${clock(dictation.elapsed)} · ${view.hold ? 'release to stop' : 'click the mic to stop'} · Esc cancels`}
        </span>
      </div>;
    case 'failed':
      return <div className="agt-dictation is-failed" role="alert">
        <span className="agt-dictation__words">Couldn’t turn {view.seconds} s of speech into text. Your recording is kept.</span>
        <button type="button" onClick={() => void dictation.retry()}>Retry</button>
        <button type="button" onClick={dictation.discard}>Discard</button>
      </div>;
    case 'notice':
      return <div className="agt-dictation" role="status">
        <span className="agt-dictation__words">{view.text}</span>
        {view.settings && <button type="button" onClick={dictation.openSettings}>Open Voice settings</button>}
      </div>;
  }
}

/** The shared composer plus dictation; hosts still own the draft, and dictated words reach it as if typed. */
export default function Composer({ inputRef, header, children, onKeyDown, onKeyUp, disabled, ...props }: ComposerProps) {
  const field = useRef<HTMLTextAreaElement | null>(null);
  const dictation = useDictation(field, disabled === true);
  const ref = useCallback((node: HTMLTextAreaElement | null) => {
    field.current = node;
    if (typeof inputRef === 'function') inputRef(node);
    else if (inputRef) (inputRef as RefObject<HTMLTextAreaElement | null>).current = node;
  }, [inputRef]);
  const line = strip(dictation);
  const listening = dictation.view.kind === 'listening';
  const mic = dictation.available
    ? <IconButton label={listening ? 'Stop dictation' : 'Dictate (hold right ⌥)'} tone={listening ? 'active' : undefined} pressed={listening}
      disabled={dictation.micDisabled} onClick={dictation.toggle}>
      <Mic size={15} aria-hidden="true" />
    </IconButton>
    : null;
  return <SharedComposer {...props} disabled={disabled} inputRef={ref}
    header={line === null ? header : <>{header}{line}</>}
    onKeyDown={event => {
      dictation.keyDown(event);
      if (!event.isPropagationStopped()) onKeyDown?.(event);
    }}
    onKeyUp={event => {
      dictation.keyUp(event);
      onKeyUp?.(event);
    }}>
    {mic === null ? children : <>{children}{mic}</>}
  </SharedComposer>;
}
