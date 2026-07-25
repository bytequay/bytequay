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
import { MarkdownProse } from '../../threads/MarkdownProse';

export type AskQuestionOption = { id: string; label: string; extra?: string | null };

/** Grace period between picking an answer and posting it, so a misclick on
 *  a one-way question ("Cut this task?") is recoverable. The agent is
 *  blocked meanwhile, so keep it short. */
const UNDO_WINDOW_MS = 5000;

type Sent = { label: string; undoable: boolean };

/**
 * An agent's clarifying question, rendered as a lit amber card in the
 * conversation: the question + optional context, multiple-choice option
 * buttons, and a free-form reply. Picking an option or sending free-form
 * resolves it (the answer is posted as the next message) after a short
 * undo window. Numbered "i of N" when several are open at once.
 */
export function AskUserQuestionCard({
  question, context, options, allowFreeForm, index, total, onAnswer,
}: {
  question: string;
  context?: string | null;
  options: AskQuestionOption[];
  allowFreeForm: boolean;
  /** 1-based position when multiple questions are open. */
  index?: number;
  total?: number;
  onAnswer: (optionId?: string, freeForm?: string) => void;
}) {
  const [text, setText] = useState('');
  const [sent, setSent] = useState<Sent | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const post = useRef<(() => void) | null>(null);

  // Navigating away mid-window must not swallow the answer the agent is
  // waiting on — flush it instead of dropping the timer.
  useEffect(() => () => {
    if (timer.current !== null) {
      clearTimeout(timer.current);
      post.current?.();
    }
  }, []);

  const answer = (label: string, optionId?: string, freeForm?: string) => {
    if (sent !== null) return;
    setSent({ label, undoable: true });
    post.current = () => onAnswer(optionId, freeForm);
    timer.current = setTimeout(() => {
      timer.current = null;
      setSent(s => (s === null ? null : { ...s, undoable: false }));
      post.current?.();
    }, UNDO_WINDOW_MS);
  };

  const undo = () => {
    if (timer.current === null) return;
    clearTimeout(timer.current);
    timer.current = null;
    post.current = null;
    setSent(null);
  };

  const submitFreeForm = () => {
    const t = text.trim();
    if (t.length > 0) answer(t, undefined, t);
  };

  return (
    <div className="ask-question-card">
      <div className="ask-question-card__frame">
        <div className="ask-question-card__inner">
          <div className="ask-question-card__head">
            <span className="ask-question-card__pill" aria-hidden>?</span>
            <span className="ask-question-card__label">
              {total !== undefined && total > 1 ? `Question ${index ?? 1} of ${total}` : 'Agent question'}
            </span>
          </div>
          <div className="ask-question-card__q">{question}</div>
          {context != null && context.length > 0 && (
            <div className="ask-question-card__ctx">
              <MarkdownProse text={context.replaceAll('\\n', '\n')} />
            </div>
          )}
          {sent !== null ? (
            <div className="ask-question-card__sent" role="status">
              <span className="ask-question-card__check" aria-hidden>
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                  strokeWidth="3" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M20 6 9 17l-5-5" />
                </svg>
              </span>
              <span className="ask-question-card__sent-copy">
                <small>Answer sent to agent</small>
                <span>{sent.label}</span>
              </span>
              {sent.undoable && (
                <button type="button" className="ask-question-card__undo" onClick={undo}>Undo</button>
              )}
            </div>
          ) : (
            <>
              {options.length > 0 && (
                <div className="ask-question-card__options">
                  {options.map((o, i) => (
                    <button
                      key={o.id}
                      type="button"
                      className="ask-question-card__opt"
                      // The first option is the card's default: focused on
                      // mount, so the ↵ keycap it shows is real.
                      autoFocus={i === 0 && (index ?? 1) === 1}
                      onClick={() => answer(o.label, o.id, undefined)}
                    >
                      <span className="ask-question-card__opt-label">{o.label}</span>
                      {o.extra != null && o.extra.length > 0 && (
                        <span className="ask-question-card__extra">{o.extra}</span>
                      )}
                      {i === 0 && <span className="ask-question-card__key" aria-hidden>↵</span>}
                    </button>
                  ))}
                </div>
              )}
              {allowFreeForm && (
                <div className="ask-question-card__free">
                  <input
                    className="ask-question-card__input"
                    value={text}
                    onChange={e => setText(e.target.value)}
                    onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); submitFreeForm(); } }}
                    placeholder={options.length > 0 ? 'Or type your own answer…' : 'Type your answer…'}
                    aria-label="Free-form answer"
                  />
                  <button
                    type="button"
                    className="ask-question-card__send"
                    onClick={submitFreeForm}
                    disabled={text.trim().length === 0}
                  >Send</button>
                </div>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  );
}
