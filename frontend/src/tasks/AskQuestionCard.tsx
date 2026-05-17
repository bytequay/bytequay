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
import type { CSSProperties } from 'react';

/**
 * AskUserQuestion is Claude's interactive question tool. In our
 * non-interactive CLI environment, the built-in tool would return an
 * empty answer; instead we deny the call in McpController and render
 * the question(s) in the chat as a friendly card. The user replies
 * via the normal chat input — that reply becomes the next user turn.
 *
 * Shape of the tool input (matches Claude's AskUserQuestion schema):
 * {
 *   "questions": [
 *     {
 *       "question": "What should we…?",
 *       "header": "Short label",
 *       "multiSelect": false,
 *       "options": [
 *         { "label": "Option A", "description": "…" },
 *         { "label": "Option B", "description": "…" }
 *       ]
 *     }
 *   ]
 * }
 */
type Option = { label?: unknown; description?: unknown };
type Question = {
  question?: unknown;
  header?: unknown;
  multiSelect?: unknown;
  options?: unknown;
};

type Props = {
  input: unknown;
  variant?: 'card' | 'terminal';
};

export function AskQuestionCard({ input, variant = 'card' }: Props) {
  const questions = extractQuestions(input);
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

  return (
    <div style={styles.root}>
      <div style={styles.header}>
        <span style={styles.glyph}>?</span>
        <span>{questions.length === 1 ? 'Question for you' : `${questions.length} questions for you`}</span>
      </div>
      {questions.map((q, i) => {
        const text = asString(q.question);
        const header = asString(q.header);
        const multi = q.multiSelect === true;
        const options = asOptions(q.options);
        return (
          <div key={i} style={styles.qBlock}>
            {header && <div style={styles.qHeader}>{header}</div>}
            {text && <div style={styles.qText}>{text}</div>}
            {options.length > 0 && (
              <ul style={styles.qOptions}>
                {options.map((o, j) => {
                  const label = asString(o.label);
                  const desc = asString(o.description);
                  return (
                    <li key={j} style={styles.qOption}>
                      <span style={styles.qOptionLabel}>{label || `Option ${j + 1}`}</span>
                      {desc && <span style={styles.qOptionDesc}> — {desc}</span>}
                    </li>
                  );
                })}
              </ul>
            )}
            {multi && <div style={styles.qMultiHint}>You may pick more than one.</div>}
          </div>
        );
      })}
      <div style={styles.hint}>Reply via the chat input below.</div>
    </div>
  );
}

function extractQuestions(input: unknown): Question[] {
  if (!input || typeof input !== 'object') return [];
  const obj = input as Record<string, unknown>;
  const raw = obj.questions;
  if (!Array.isArray(raw)) return [];
  return raw.filter((q): q is Question => !!q && typeof q === 'object');
}

function asOptions(v: unknown): Option[] {
  if (!Array.isArray(v)) return [];
  return v.filter((o): o is Option => !!o && typeof o === 'object');
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
