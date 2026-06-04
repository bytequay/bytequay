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
 * Tiny JSON formatter + highlighter the prompt-context inspector
 * uses for the TOOLS / HISTORY / NEW_TURN sections and the full-
 * request view. No new deps — the regex-based tokeniser is enough
 * for the small payloads we display.
 *
 * <p>Falls back to the raw text when the input isn't parseable so
 * a malformed tool definition or a trailing comma still shows up
 * verbatim instead of getting swallowed.
 */
export function prettyJson(input: string, indent: number = 2): string {
  if (input == null || input.length === 0) return '';
  const trimmed = input.trim();
  if (trimmed.length === 0) return '';
  try {
    const parsed: unknown = JSON.parse(trimmed);
    return JSON.stringify(parsed, null, indent);
  }
  catch {
    return input;
  }
}

/**
 * Renders a pretty-printed JSON string into colored React nodes.
 * Keys, strings, numbers, booleans, and {@code null} each pick up
 * the matching theme color. Punctuation stays the surrounding
 * text color so brackets read as structure, not noise.
 */
export function highlightJson(pretty: string, theme: JsonTheme = LIGHT_JSON_THEME): ReactNode {
  if (pretty.length === 0) return null;
  // Order matters — the regex alternation greedily takes the
  // first match, so string-keys (string followed by colon) need
  // to be tried before string-values. Numbers, true/false/null
  // come last.
  const TOKEN_RE = /("(?:\\.|[^"\\])*")(\s*:)|("(?:\\.|[^"\\])*")|\b(true|false|null)\b|(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)/g;
  const parts: ReactNode[] = [];
  let lastIndex = 0;
  let key = 0;
  let m: RegExpExecArray | null = TOKEN_RE.exec(pretty);
  while (m !== null) {
    if (m.index > lastIndex) {
      parts.push(pretty.slice(lastIndex, m.index));
    }
    if (m[1] !== undefined && m[2] !== undefined) {
      // String key + colon. Colon stays default colour.
      parts.push(<span key={key++} style={{ color: theme.keyColor }}>{m[1]}</span>);
      parts.push(m[2]);
    }
    else if (m[3] !== undefined) {
      parts.push(<span key={key++} style={{ color: theme.stringColor }}>{m[3]}</span>);
    }
    else if (m[4] !== undefined) {
      parts.push(<span key={key++} style={{ color: theme.literalColor }}>{m[4]}</span>);
    }
    else if (m[5] !== undefined) {
      parts.push(<span key={key++} style={{ color: theme.numberColor }}>{m[5]}</span>);
    }
    lastIndex = m.index + m[0].length;
    m = TOKEN_RE.exec(pretty);
  }
  if (lastIndex < pretty.length) {
    parts.push(pretty.slice(lastIndex));
  }
  return parts;
}

/** Try to pull a {@code "name":"…"} value off a JSON snippet so a
 *  tool / message card header can show what it is without parsing
 *  the whole payload. Returns {@code null} when there's no name
 *  field. */
export function extractJsonName(json: string): string | null {
  const re = /"name"\s*:\s*"((?:\\.|[^"\\])*)"/;
  const m = re.exec(json);
  return m === null ? null : m[1];
}

export type JsonTheme = {
  keyColor: string;
  stringColor: string;
  numberColor: string;
  literalColor: string;
};

/** Inspector's section view uses a light theme to match the
 *  surrounding chrome. */
export const LIGHT_JSON_THEME: JsonTheme = {
  keyColor: '#1f5fbf',
  stringColor: '#0e6c4f',
  numberColor: '#b56f00',
  literalColor: '#8b2db8',
};

/** Inspector's full-request view uses a dark code-block theme. */
export const DARK_JSON_THEME: JsonTheme = {
  keyColor: '#7eb6ff',
  stringColor: '#a9e9a4',
  numberColor: '#ffc266',
  literalColor: '#e6a3ff',
};

export const JSON_PRE_STYLE: CSSProperties = {
  margin: 0,
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  fontSize: 12,
  lineHeight: 1.45,
  whiteSpace: 'pre-wrap',
};
