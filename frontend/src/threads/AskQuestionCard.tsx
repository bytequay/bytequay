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
import { useEffect, useMemo, useRef, useState } from 'react';
import type { CSSProperties } from 'react';

/**
 * AskUserQuestion is Claude's interactive question tool. The CLI runs
 * headless (no TTY), so its built-in selector can't render; we deny the
 * tool in McpController and surface the question(s) here instead.
 *
 * Two modes:
 * - Read-only (no {@code onAnswer}) — used in the structured/terminal
 *   transcript views; the user replies via the chat input.
 * - Interactive ({@code onAnswer} supplied) — the in-app equivalent of
 *   the CLI selector: click an option, or navigate with ↑/↓ and pick
 *   with Enter; the chosen label(s) are sent as the next user turn.
 *
 * Shape of the tool input (matches Claude's AskUserQuestion schema):
 * { "questions": [ { "question", "header", "multiSelect", "options":
 *   [ { "label", "description" } ] } ] }
 */
type Props = {
  input: unknown;
  variant?: 'card' | 'terminal';
  /** When supplied, the card becomes an interactive selector and calls
   *  this with the composed answer text once the user confirms. */
  onAnswer?: (text: string) => void;
};

type ParsedOption = { label: string; desc: string };
type ParsedQuestion = { header: string; text: string; multi: boolean; options: ParsedOption[] };

export function AskQuestionCard({ input, variant = 'card', onAnswer }: Props) {
  const questions = useMemo(() => parseQuestions(input), [input]);
  const styles = variant === 'terminal' ? terminalStyles : cardStyles;

  if (questions.length === 0) {
    return (
      <div style={styles.root}>
        <div style={styles.header}>
          <span style={styles.glyph}>?</span>
          <span>Question for you</span>
        </div>
        <div style={styles.hint}>Reply via the chat input below.</div>
      </div>
    );
  }
  if (onAnswer !== undefined) {
    return <InteractiveQuestions questions={questions} styles={styles} onAnswer={onAnswer} />;
  }
  return <StaticQuestions questions={questions} styles={styles} />;
}

function StaticQuestions({ questions, styles }: { questions: ParsedQuestion[]; styles: StyleBundle }) {
  return (
    <div style={styles.root}>
      <CardHeader count={questions.length} styles={styles} />
      {questions.map((q, i) => (
        <div key={i} style={styles.qBlock}>
          {q.header && <div style={styles.qHeader}>{q.header}</div>}
          {q.text && <div style={styles.qText}>{q.text}</div>}
          {q.options.length > 0 && (
            <ul style={styles.qOptions}>
              {q.options.map((o, j) => (
                <li key={j} style={styles.qOption}>
                  <span style={styles.qOptionLabel}>{o.label || `Option ${j + 1}`}</span>
                  {o.desc && <span style={styles.qOptionDesc}> — {o.desc}</span>}
                </li>
              ))}
            </ul>
          )}
          {q.multi && <div style={styles.qMultiHint}>You may pick more than one.</div>}
        </div>
      ))}
      <div style={styles.hint}>Reply via the chat input below.</div>
    </div>
  );
}

/** Flattened (question, option) cursor so ↑/↓ walk every option across
 *  every question in one pass. */
type FlatRef = { qi: number; oi: number };

function InteractiveQuestions({
  questions, styles, onAnswer,
}: {
  questions: ParsedQuestion[];
  styles: StyleBundle;
  onAnswer: (text: string) => void;
}) {
  const flat = useMemo<FlatRef[]>(
    () => questions.flatMap((q, qi) => q.options.map((_, oi) => ({ qi, oi }))),
    [questions]);
  // The single common case — one single-select question — answers on
  // Enter/click immediately, matching the CLI selector. Anything else
  // (multiSelect, or multiple questions) accumulates and confirms.
  const immediate = questions.length === 1 && !questions[0].multi;
  const [cursor, setCursor] = useState(0);
  // Per-question selected option indices.
  const [selected, setSelected] = useState<Record<number, Set<number>>>({});
  const rootRef = useRef<HTMLDivElement | null>(null);

  // Grab focus so the arrows work the moment the prompt appears.
  useEffect(() => { rootRef.current?.focus(); }, []);

  const choose = (qi: number, oi: number) => {
    if (immediate) {
      onAnswer(questions[0].options[oi].label);
      return;
    }
    setSelected(prev => {
      const next = { ...prev };
      const set = new Set(next[qi] ?? []);
      if (questions[qi].multi) {
        if (set.has(oi)) set.delete(oi); else set.add(oi);
      } else {
        set.clear();
        set.add(oi);
      }
      next[qi] = set;
      return next;
    });
  };

  const everyAnswered = questions.every((_, qi) => (selected[qi]?.size ?? 0) > 0);

  const confirm = () => {
    if (!everyAnswered) return;
    onAnswer(composeAnswer(questions, selected));
  };

  const onKeyDown = (e: React.KeyboardEvent<HTMLDivElement>) => {
    if (flat.length === 0) return;
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setCursor(c => Math.min(c + 1, flat.length - 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setCursor(c => Math.max(c - 1, 0));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      const ref = flat[cursor];
      if (ref) choose(ref.qi, ref.oi);
      else if (!immediate) confirm();
    }
  };

  let flatIndex = -1;
  return (
    <div
      ref={rootRef}
      role="listbox"
      tabIndex={0}
      onKeyDown={onKeyDown}
      style={{ ...styles.root, outline: 'none' }}
      aria-label="Choose an answer"
    >
      <CardHeader count={questions.length} styles={styles} />
      {questions.map((q, qi) => (
        <div key={qi} style={styles.qBlock}>
          {q.header && <div style={styles.qHeader}>{q.header}</div>}
          {q.text && <div style={styles.qText}>{q.text}</div>}
          <div style={optionListStyle}>
            {q.options.map((o, oi) => {
              flatIndex += 1;
              const onCursor = flatIndex === cursor;
              const isSelected = selected[qi]?.has(oi) ?? false;
              return (
                <button
                  key={oi}
                  type="button"
                  role="option"
                  aria-selected={isSelected}
                  onMouseEnter={(idx => () => setCursor(idx))(flatIndex)}
                  onClick={() => choose(qi, oi)}
                  style={optionStyle(styles, onCursor, isSelected)}
                >
                  <span style={optionMarkStyle}>
                    {q.multi ? (isSelected ? '☑' : '☐') : (isSelected ? '◉' : '○')}
                  </span>
                  <span>
                    <span style={styles.qOptionLabel}>{o.label || `Option ${oi + 1}`}</span>
                    {o.desc && <span style={styles.qOptionDesc}> — {o.desc}</span>}
                  </span>
                </button>
              );
            })}
          </div>
          {q.multi && <div style={styles.qMultiHint}>You may pick more than one.</div>}
        </div>
      ))}
      {!immediate && (
        <button
          type="button"
          onClick={confirm}
          disabled={!everyAnswered}
          style={sendStyle(styles, everyAnswered)}
        >
          Send answer{questions.length > 1 ? 's' : ''} →
        </button>
      )}
      <div style={styles.hint}>↑/↓ to move · Enter to {immediate ? 'pick' : 'toggle'} · or click</div>
    </div>
  );
}

function CardHeader({ count, styles }: { count: number; styles: StyleBundle }) {
  return (
    <div style={styles.header}>
      <span style={styles.glyph}>?</span>
      <span>{count === 1 ? 'Question for you' : `${count} questions for you`}</span>
    </div>
  );
}

/** Compose the user-turn text from the selections. One question → just
 *  the chosen label(s); several → "header: choice" lines. */
function composeAnswer(questions: ParsedQuestion[], selected: Record<number, Set<number>>): string {
  const lineFor = (qi: number): string =>
    [...(selected[qi] ?? [])]
      .sort((a, b) => a - b)
      .map(oi => questions[qi].options[oi]?.label ?? '')
      .filter(Boolean)
      .join(', ');
  if (questions.length === 1) {
    return lineFor(0);
  }
  return questions
    .map((q, qi) => `${q.header || q.text || `Question ${qi + 1}`}: ${lineFor(qi)}`)
    .join('\n');
}

function parseQuestions(input: unknown): ParsedQuestion[] {
  if (!input || typeof input !== 'object') return [];
  const raw = (input as Record<string, unknown>).questions;
  if (!Array.isArray(raw)) return [];
  return raw
    .filter((q): q is Record<string, unknown> => !!q && typeof q === 'object')
    .map(q => ({
      header: asString(q.header),
      text: asString(q.question),
      multi: q.multiSelect === true,
      options: asOptions(q.options),
    }));
}

function asOptions(v: unknown): ParsedOption[] {
  if (!Array.isArray(v)) return [];
  return v
    .filter((o): o is Record<string, unknown> => !!o && typeof o === 'object')
    .map(o => ({ label: asString(o.label), desc: asString(o.description) }));
}

function asString(v: unknown): string {
  return typeof v === 'string' ? v : '';
}

type StyleBundle = {
  root: CSSProperties;
  header: CSSProperties;
  glyph: CSSProperties;
  qBlock: CSSProperties;
  qHeader: CSSProperties;
  qText: CSSProperties;
  qOptions: CSSProperties;
  qOption: CSSProperties;
  qOptionLabel: CSSProperties;
  qOptionDesc: CSSProperties;
  qMultiHint: CSSProperties;
  hint: CSSProperties;
};

const optionListStyle: CSSProperties = {
  display: 'flex', flexDirection: 'column', gap: 3, marginTop: 4,
};

const optionMarkStyle: CSSProperties = {
  flexShrink: 0, width: 14, textAlign: 'center',
};

function optionStyle(styles: StyleBundle, onCursor: boolean, selected: boolean): CSSProperties {
  const base = styles.qOption;
  return {
    ...base,
    display: 'flex', alignItems: 'baseline', gap: 7,
    width: '100%', textAlign: 'left', cursor: 'pointer',
    margin: 0, padding: '4px 8px', borderRadius: 6,
    border: '1px solid transparent',
    background: onCursor ? 'rgba(146, 64, 14, 0.10)' : 'transparent',
    borderColor: selected ? '#FCD34D' : (onCursor ? 'rgba(146, 64, 14, 0.25)' : 'transparent'),
    fontWeight: selected ? 600 : 400,
  };
}

function sendStyle(styles: StyleBundle, enabled: boolean): CSSProperties {
  return {
    alignSelf: 'flex-start',
    marginTop: 2,
    padding: '5px 12px',
    borderRadius: 6,
    border: 'none',
    fontSize: 12,
    fontWeight: 700,
    cursor: enabled ? 'pointer' : 'not-allowed',
    background: enabled ? '#92400E' : 'rgba(146,64,14,0.35)',
    color: '#fff',
    // referenced so the param isn't flagged unused on the disabled path
    opacity: styles === terminalStyles ? 0.95 : 1,
  };
}

const cardStyles: StyleBundle = {
  root: {
    background: '#FFFBEB',
    border: '1px solid #FCD34D',
    borderRadius: 8,
    padding: '10px 14px',
    display: 'flex', flexDirection: 'column', gap: 10,
  },
  header: {
    display: 'flex', alignItems: 'center', gap: 8,
    color: '#92400E', fontSize: 12.5, fontWeight: 700,
    letterSpacing: 0.3, textTransform: 'uppercase',
  },
  glyph: {
    display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
    width: 18, height: 18, borderRadius: '50%',
    background: '#FCD34D', color: '#78350F',
    fontWeight: 800, fontSize: 12,
  },
  qBlock: {
    display: 'flex', flexDirection: 'column', gap: 6,
    paddingTop: 6, borderTop: '1px dashed #FCD34D',
  },
  qHeader: {
    fontSize: 10.5, fontWeight: 700, color: '#92400E',
    letterSpacing: 0.4, textTransform: 'uppercase',
  },
  qText: {
    color: '#78350F', fontSize: 13.5, lineHeight: 1.5,
  },
  qOptions: { margin: '2px 0 0', paddingLeft: 20 },
  qOption: { color: '#78350F', fontSize: 12.5, lineHeight: 1.55, margin: '2px 0' },
  qOptionLabel: { fontWeight: 600 },
  qOptionDesc: { color: '#92400E' },
  qMultiHint: { fontSize: 11, color: '#92400E', fontStyle: 'italic' },
  hint: {
    fontSize: 11, color: '#92400E', fontStyle: 'italic',
    paddingTop: 4, borderTop: '1px dashed #FCD34D',
  },
};

const terminalStyles: StyleBundle = {
  root: {
    background: 'var(--term-user-bg)',
    border: '1px solid var(--term-user)',
    borderRadius: 6,
    padding: '10px 14px',
    margin: '10px 0',
    display: 'flex', flexDirection: 'column', gap: 10,
    color: 'var(--term-text)',
  },
  header: {
    display: 'flex', alignItems: 'center', gap: 8,
    color: 'var(--term-user)', fontSize: 12, fontWeight: 700,
    letterSpacing: 0.3, textTransform: 'uppercase',
  },
  glyph: {
    display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
    width: 18, height: 18, borderRadius: '50%',
    background: 'var(--term-user)', color: 'var(--term-bg)',
    fontWeight: 800, fontSize: 12,
  },
  qBlock: {
    display: 'flex', flexDirection: 'column', gap: 6,
    paddingTop: 6, borderTop: '1px dashed var(--term-border)',
  },
  qHeader: {
    fontSize: 10.5, fontWeight: 700, color: 'var(--term-user)',
    letterSpacing: 0.4, textTransform: 'uppercase',
  },
  qText: {
    color: 'var(--term-text-bright)', fontSize: 13, lineHeight: 1.5,
  },
  qOptions: { margin: '2px 0 0', paddingLeft: 20 },
  qOption: { color: 'var(--term-text)', fontSize: 12.5, lineHeight: 1.55, margin: '2px 0' },
  qOptionLabel: { color: 'var(--term-text-bright)', fontWeight: 600 },
  qOptionDesc: { color: 'var(--term-text-dim)' },
  qMultiHint: { fontSize: 11, color: 'var(--term-text-dim)', fontStyle: 'italic' },
  hint: {
    fontSize: 11, color: 'var(--term-text-dim)', fontStyle: 'italic',
    paddingTop: 4, borderTop: '1px dashed var(--term-border)',
  },
};
