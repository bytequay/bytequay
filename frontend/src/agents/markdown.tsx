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
import { parseGitHubReference } from '../githubReference';
import { linkIssueNumbers, ReferenceLink, tableAlignments, tableCells } from '../pulls/MarkdownBody';

/**
 * One rendered block and the text it reads as on screen.
 *
 * <p>A discussion's footer hangs under the block holding its quoted passage, and the quote is
 * stored as the reader saw it, so the caller finds its block by searching this text. The blocks
 * carry no durable identity: a block is addressed only for the duration of one render.
 */
export type MarkdownBlock = { node: React.ReactNode; text: string };

/** The rendered blocks of one answer, in document order, for a caller that interleaves its own. */
export function renderMarkdownBlocks(text: string, repository?: string | null, preserveLineBreaks = false): MarkdownBlock[] {
  const lines = text.replace(/\r\n?/g, '\n').split('\n');
  const blocks: MarkdownBlock[] = [];
  let paragraph: string[] = [];
  let key = 0;
  const push = (node: React.ReactNode, source: string): void => { blocks.push({ node, text: source }); };
  const flush = (): void => {
    if (paragraph.length === 0) return;
    const source = paragraph.join(preserveLineBreaks ? '\n' : ' ');
    push(<p key={key++}>{inline(source, repository)}</p>, source);
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
      push(<pre className="agt-code" key={key++}>{code.join('\n')}</pre>, code.join('\n'));
      continue;
    }
    const heading = /^\s*#{1,6}\s+(.*)$/.exec(line);
    if (heading !== null) {
      flush();
      push(<p key={key++}><strong>{inline(heading[1], repository)}</strong></p>, heading[1]);
      continue;
    }
    const bullet = /^\s*[-*+]\s+/.test(line);
    const numbered = /^\s*\d+[.)]\s+/.test(line);
    if (bullet || numbered) {
      flush();
      const items: React.ReactNode[] = [];
      const sources: string[] = [];
      const ordered = numbered;
      while (index < lines.length && (ordered ? /^\s*\d+[.)]\s+/ : /^\s*[-*+]\s+/).test(lines[index])) {
        const text = lines[index].replace(ordered ? /^\s*\d+[.)]\s+/ : /^\s*[-*+]\s+/, '');
        const task = !ordered ? /^\[([ xX])\]\s+(.*)$/.exec(text) : null;
        sources.push(task === null ? text : task[2]);
        items.push(task === null ? <li key={items.length}>{inline(text, repository)}</li>
          : <li className="agt-md__task" key={items.length}><input type="checkbox" checked={task[1].toLowerCase() === 'x'} disabled aria-label={task[2]} /><span>{inline(task[2], repository)}</span></li>);
        index++;
      }
      index--;
      push(ordered ? <ol key={key++}>{items}</ol> : <ul key={key++}>{items}</ul>, sources.join(' '));
      continue;
    }
    const headers = tableCells(line);
    const aligns = index + 1 < lines.length ? tableAlignments(lines[index + 1]) : null;
    if (headers !== null && aligns !== null && headers.length === aligns.length) {
      flush();
      const rows: string[][] = [];
      index += 2;
      for (; index < lines.length; index++) {
        const cells = tableCells(lines[index]);
        if (cells === null) break;
        rows.push(headers.map((_header, cell) => cells[cell] ?? ''));
      }
      index--;
      push(
        <div className="agt-md__table" key={key++}>
          <table>
            <thead><tr>{headers.map((header, cell) => <th key={cell} style={{ textAlign: aligns[cell] }}>{inline(header, repository)}</th>)}</tr></thead>
            <tbody>{rows.map((row, rowIndex) => (
              <tr key={rowIndex}>{row.map((cell, cellIndex) => <td key={cellIndex} style={{ textAlign: aligns[cellIndex] }}>{inline(cell, repository)}</td>)}</tr>
            ))}</tbody>
          </table>
        </div>,
        [headers, ...rows].map(row => row.join(' ')).join(' '));
      continue;
    }
    const quote = /^\s*>\s?(.*)$/.exec(line);
    if (quote !== null) {
      flush();
      push(<blockquote key={key++}>{inline(quote[1], repository)}</blockquote>, quote[1]);
      continue;
    }
    if (line.trim() === '') {
      flush();
      continue;
    }
    paragraph.push(preserveLineBreaks ? line : line.trim());
  }
  flush();
  return blocks;
}

/**
 * The Markdown an agent's prose actually uses, rendered without a library: paragraphs, headings,
 * bullet and numbered lists, fenced code, block quotes, tables, and inline bold, italics, code and
 * links.
 * Anything else is left as the text it is; nothing here executes, and only a link to a GitHub pull
 * request or issue reads that item, for its title and preview.
 */
export function renderMarkdown(text: string, repository?: string | null, preserveLineBreaks = false): React.ReactNode {
  return <div className="agt-md">{renderMarkdownBlocks(text, repository, preserveLineBreaks).map(block => block.node)}</div>;
}

// Underscores only mark emphasis at word edges; a_b_c is an identifier, not italics.
const INLINE = /(`[^`]+`)|(\*\*[^*]+\*\*)|(?<![\w])(__[^_]+__)(?![\w])|(\*[^*\s][^*]*\*)|(?<![\w])(_[^_\s][^_]*_)(?![\w])|(\[[^\]]+\]\([^)\s]+\))|(https?:\/\/[^\s<>()\]]*[^\s<>()\].,!?;:'"])/;

/** Inline marks. A link to a file rather than a page is shown as its text; nothing opens a path. */
function inline(text: string, repository: string | null | undefined): React.ReactNode[] {
  const nodes: React.ReactNode[] = [];
  let rest = text;
  let key = 0;
  while (rest !== '') {
    const match = INLINE.exec(rest);
    if (match === null || match.index === undefined) {
      nodes.push(<React.Fragment key={key++}>{linkIssueNumbers(rest, repository)}</React.Fragment>);
      break;
    }
    if (match.index > 0) nodes.push(<React.Fragment key={key++}>{linkIssueNumbers(rest.slice(0, match.index), repository)}</React.Fragment>);
    const token = match[0];
    if (token.startsWith('`')) {
      nodes.push(<code key={key++}>{token.slice(1, -1)}</code>);
    } else if (token.startsWith('**') || token.startsWith('__')) {
      nodes.push(<strong key={key++}>{inline(token.slice(2, -2), repository)}</strong>);
    } else if (token.startsWith('*') || token.startsWith('_')) {
      nodes.push(<em key={key++}>{inline(token.slice(1, -1), repository)}</em>);
    } else {
      const link = /^\[([^\]]+)\]\(([^)\s]+)\)$/.exec(token);
      const label = link?.[1] ?? token;
      const url = link?.[2] ?? token;
      const reference = parseGitHubReference(url);
      // A label that only names the item, like "PR #12" or its URL, reads as the title; any other label is kept.
      const bare = /^https?:\/\//i.test(label);
      nodes.push(reference !== null
        ? <ReferenceLink key={key++} reference={reference} showTitle={bare || /#\d+$/.test(label)}>
          {bare ? <>{reference.owner}/{reference.repo} <small>#{reference.number}</small></> : inline(label, null)}
        </ReferenceLink>
        : /^https?:\/\//i.test(url)
          ? <a key={key++} href={url} target="_blank" rel="noreferrer">{link === null ? label : inline(label, null)}</a>
          : <code key={key++} title={url}>{label}</code>);
    }
    rest = rest.slice(match.index + token.length);
  }
  return nodes;
}
