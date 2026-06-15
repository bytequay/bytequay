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
import type { CSSProperties, ReactNode } from 'react';

/**
 * Lightweight shell tokenizer — enough to read a Bash command at a
 * glance without pulling in a full grammar. It single-passes the source
 * and classifies strings, comments, `$variables`, and operators; quoting
 * is respected so a `#` or `|` inside a string isn't mis-coloured.
 * Command substitution (`$(…)`) is left to tokenize normally so the pipes
 * and strings inside it still highlight.
 */
export type ShellTokenKind = 'text' | 'string' | 'comment' | 'var' | 'op';
export type ShellToken = { kind: ShellTokenKind; text: string };

const OPERATOR_CHARS = new Set(['|', '&', ';', '<', '>', '(', ')']);

export function tokenizeShell(source: string): ShellToken[] {
  const tokens: ShellToken[] = [];
  const push = (kind: ShellTokenKind, text: string) => {
    if (text.length === 0) return;
    const last = tokens[tokens.length - 1];
    if (last && last.kind === kind) last.text += text;
    else tokens.push({ kind, text });
  };

  let i = 0;
  const n = source.length;
  while (i < n) {
    const c = source[i];

    // Comment: '#' at start-of-token (after whitespace or start) to EOL.
    if (c === '#' && (i === 0 || /\s/.test(source[i - 1]))) {
      let j = source.indexOf('\n', i);
      if (j === -1) j = n;
      push('comment', source.slice(i, j));
      i = j;
      continue;
    }
    // Single-quoted string — no escapes inside.
    if (c === '\'') {
      let j = source.indexOf('\'', i + 1);
      if (j === -1) j = n - 1;
      push('string', source.slice(i, j + 1));
      i = j + 1;
      continue;
    }
    // Double-quoted string — honour \" escapes.
    if (c === '"') {
      let j = i + 1;
      while (j < n && !(source[j] === '"' && source[j - 1] !== '\\')) j += 1;
      push('string', source.slice(i, Math.min(j + 1, n)));
      i = j + 1;
      continue;
    }
    // Variable: ${…}, $name, or a bare $ before a command-sub paren.
    if (c === '$') {
      if (source[i + 1] === '{') {
        let j = source.indexOf('}', i + 2);
        if (j === -1) j = n - 1;
        push('var', source.slice(i, j + 1));
        i = j + 1;
        continue;
      }
      if (source[i + 1] === '(') {
        push('var', '$');
        i += 1; // leave '(' to be picked up as an operator
        continue;
      }
      let j = i + 1;
      while (j < n && /[A-Za-z0-9_]/.test(source[j])) j += 1;
      push('var', source.slice(i, j));
      i = j;
      continue;
    }
    // Operators / redirections / pipes (collapse runs like && >> ||).
    if (OPERATOR_CHARS.has(c)) {
      let j = i;
      while (j < n && OPERATOR_CHARS.has(source[j])) j += 1;
      push('op', source.slice(i, j));
      i = j;
      continue;
    }
    // Plain run up to the next interesting character.
    let j = i;
    while (j < n) {
      const d = source[j];
      if (d === '\'' || d === '"' || d === '$' || OPERATOR_CHARS.has(d)
          || (d === '#' && /\s/.test(source[j - 1] ?? ' '))) {
        break;
      }
      j += 1;
    }
    push('text', source.slice(i, j === i ? i + 1 : j));
    i = j === i ? i + 1 : j;
  }
  return tokens;
}

const TOKEN_STYLE: Record<ShellTokenKind, CSSProperties> = {
  text: {},
  string: { color: '#15803d' },
  comment: { color: '#94a3b8', fontStyle: 'italic' },
  var: { color: '#2563eb' },
  op: { color: '#b45309' },
};

/** Render a shell command as syntax-highlighted React nodes. Whitespace
 *  (incl. newlines) is preserved verbatim, so the caller's `<pre>` shows
 *  the command on its real lines. */
export function highlightShell(source: string): ReactNode[] {
  return tokenizeShell(source).map((t, i) =>
    t.kind === 'text'
      ? t.text
      : <span key={i} style={TOKEN_STYLE[t.kind]}>{t.text}</span>);
}
