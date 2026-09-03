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

import React from 'react';

/**
 * The Markdown an agent's prose actually uses, rendered without a library: paragraphs, headings,
 * bullet and numbered lists, fenced code, block quotes, and inline bold, italics, code and links.
 * Anything else is left as the text it is; nothing here executes or fetches.
 */
export function renderMarkdown(text: string): React.ReactNode {
  const lines = text.replace(/\r\n?/g, '\n').split('\n');
  const blocks: React.ReactNode[] = [];
  let paragraph: string[] = [];
  let key = 0;
  const flush = (): void => {
    if (paragraph.length === 0) return;
    blocks.push(<p key={key++}>{inline(paragraph.join(' '))}</p>);
    paragraph = [];
  };
  for (let index = 0; index < lines.length; index++) {
    const line = lines[index];
    const fence = /^\s*```/.exec(line);
    if (fence !== null) {
      flush();
      const code: string[] = [];
      index++;
      while (index < lines.length && !/^\s*```/.test(lines[index])) code.push(lines[index++]);
      blocks.push(<pre className="agt-code" key={key++}>{code.join('\n')}</pre>);
      continue;
    }
    const heading = /^\s*#{1,6}\s+(.*)$/.exec(line);
    if (heading !== null) {
      flush();
      blocks.push(<p key={key++}><strong>{inline(heading[1])}</strong></p>);
      continue;
    }
    const bullet = /^\s*[-*+]\s+/.test(line);
    const numbered = /^\s*\d+[.)]\s+/.test(line);
    if (bullet || numbered) {
      flush();
      const items: React.ReactNode[] = [];
      const ordered = numbered;
      while (index < lines.length && (ordered ? /^\s*\d+[.)]\s+/ : /^\s*[-*+]\s+/).test(lines[index])) {
        items.push(<li key={items.length}>{inline(lines[index].replace(ordered ? /^\s*\d+[.)]\s+/ : /^\s*[-*+]\s+/, ''))}</li>);
        index++;
      }
      index--;
      blocks.push(ordered ? <ol key={key++}>{items}</ol> : <ul key={key++}>{items}</ul>);
      continue;
    }
    const quote = /^\s*>\s?(.*)$/.exec(line);
    if (quote !== null) {
      flush();
      blocks.push(<blockquote key={key++}>{inline(quote[1])}</blockquote>);
      continue;
    }
    if (line.trim() === '') {
      flush();
      continue;
    }
    paragraph.push(line.trim());
  }
  flush();
  return <div className="agt-md">{blocks}</div>;
}

// Underscores only mark emphasis at word edges; a_b_c is an identifier, not italics.
const INLINE = /(`[^`]+`)|(\*\*[^*]+\*\*)|(?<![\w])(__[^_]+__)(?![\w])|(\*[^*\s][^*]*\*)|(?<![\w])(_[^_\s][^_]*_)(?![\w])|(\[[^\]]+\]\([^)\s]+\))/;

/** Inline marks. A link to a file rather than a page is shown as its text; nothing opens a path. */
function inline(text: string): React.ReactNode[] {
  const nodes: React.ReactNode[] = [];
  let rest = text;
  let key = 0;
  while (rest !== '') {
    const match = INLINE.exec(rest);
    if (match === null || match.index === undefined) {
      nodes.push(rest);
      break;
    }
    if (match.index > 0) nodes.push(rest.slice(0, match.index));
    const token = match[0];
    if (token.startsWith('`')) {
      nodes.push(<code key={key++}>{token.slice(1, -1)}</code>);
    } else if (token.startsWith('**') || token.startsWith('__')) {
      nodes.push(<strong key={key++}>{inline(token.slice(2, -2))}</strong>);
    } else if (token.startsWith('*') || token.startsWith('_')) {
      nodes.push(<em key={key++}>{inline(token.slice(1, -1))}</em>);
    } else {
      const link = /^\[([^\]]+)\]\(([^)\s]+)\)$/.exec(token);
      const label = link?.[1] ?? token;
      const url = link?.[2] ?? '';
      nodes.push(/^https?:\/\//i.test(url)
        ? <a key={key++} href={url} target="_blank" rel="noreferrer">{inline(label)}</a>
        : <code key={key++} title={url}>{label}</code>);
    }
    rest = rest.slice(match.index + token.length);
  }
  return nodes;
}
