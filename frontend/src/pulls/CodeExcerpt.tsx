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
import hljs from './highlight';
import type { ReactNode } from 'react';

export type ReviewCommentLine = {
  lineNumber: number | null;
  prefix: ' ' | '+' | '-';
  text: string;
};

/** highlight.js's own aliases name a grammar by file extension (java, ts, py, sh, yml…); anything else stays plain. */
export function codeLanguage(path: string | null): string | null {
  const name = path?.slice(path.lastIndexOf('/') + 1) ?? '';
  const extension = name.includes('.') ? name.slice(name.lastIndexOf('.') + 1).toLowerCase() : '';
  return extension !== '' && hljs.getLanguage(extension) !== undefined ? extension : null;
}

// Diff rows re-render on every hover and selection change, so each distinct line is highlighted once.
// ponytail: whole-cache clear at the cap; an LRU if very large diffs keep refilling it.
const highlighted = new Map<string, string>();

function highlightedHtml(text: string, language: string): string {
  const key = `${language}\n${text}`;
  let html = highlighted.get(key);
  if (html === undefined) {
    if (highlighted.size >= 20_000) highlighted.clear();
    html = hljs.highlight(text, { language, ignoreIllegals: true }).value;
    highlighted.set(key, html);
  }
  return html;
}

/**
 * One source line in its file's keyword colors. Lines are colored one at a time, so a line inside a block comment or
 * text block reads as code; very long lines, usually minified, stay plain. hljs escapes the source it highlights.
 */
export function SourceCode({ text, language }: { text: string; language: string | null }) {
  return language === null || text.length > 10_000
    ? <code>{text || ' '}</code>
    : <code className="hljs" dangerouslySetInnerHTML={{ __html: highlightedHtml(text || ' ', language) }} />;
}

/** Commented lines drawn as Changes draws them: its gutter, marker, tint and keyword colors. */
export default function CodeExcerpt({ path, lines, caption }: { path: string | null; lines: readonly ReviewCommentLine[]; caption?: ReactNode }) {
  const language = codeLanguage(path);
  return (
    <table className="pull-diff-code is-excerpt">
      <colgroup><col className="pull-diff-code__line-column" /><col /></colgroup>
      <tbody>
        {caption && <tr className="is-hunk"><td colSpan={2}>{caption}</td></tr>}
        {lines.map((line, index) => (
          <tr key={line.lineNumber ?? `index:${index}`} className={line.prefix === '+' ? 'is-add' : line.prefix === '-' ? 'is-del' : 'is-context'}>
            <td className="is-line">{line.lineNumber}</td>
            <td className="is-source"><span>{line.prefix === '+' ? '+' : line.prefix === '-' ? '−' : ' '}</span><SourceCode text={line.text} language={language} /></td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
