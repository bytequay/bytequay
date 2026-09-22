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
import { Fragment, useEffect, useId, useState, type ChangeEvent, type MouseEvent, type ReactNode } from 'react';
import { ArrowUpRight, CircleDot, GitPullRequest, Info, Lightbulb, MessageSquareWarning, OctagonAlert, TriangleAlert, type LucideIcon } from 'lucide-react';
import hljs from 'highlight.js/lib/common';
import {
  openGitHubReference,
  parseGitHubReference,
  type GitHubReference,
  type GitHubReferencePreview,
  resolveGitHubReference,
} from '../githubReference';
import ReferenceCard, { previewMarkdown, stateChip, useHoverCard, type HoverCard, type ReferenceCardItem } from '../ReferenceCard';
import type { GitHubCodeSnippetDto } from '../types';
import { once } from '../dataCache';

type GitHubCodePermalink = {
  href: string;
  owner: string;
  repo: string;
  commit: string;
  path: string;
  startLine: number;
  endLine: number;
};

type Block =
  | { kind: 'heading'; level: number; text: string }
  | { kind: 'paragraph'; text: string }
  | { kind: 'task'; checked: boolean; text: string }
  | { kind: 'code'; language: string; text: string }
  | { kind: 'list'; ordered: boolean; items: string[] }
  | { kind: 'table'; headers: string[]; rows: string[][]; aligns: Array<'left' | 'center' | 'right'> }
  | { kind: 'quote'; text: string }
  | { kind: 'alert'; label: string; text: string }
  | { kind: 'details'; summary: string; text: string }
  | { kind: 'code-reference'; reference: GitHubCodePermalink }
  | { kind: 'rule' };

type Fence = { marker: '`' | '~'; length: number; language: string };

/** The head-side lines a review comment covers; `lines` is null when their text could not be resolved. */
export type SuggestionBase = { startLine: number; lines: string[] | null };

const TASK_LINE = /^(\s*(?:[-*+]\s+)?)(?:\(([ xX*✓])\)|\[([ xX])\])\s+(.+)$/;
const SOURCE_TASK_LINE = /^(\s*(?:[-*+]\s+)?)(?:\(([ xX*✓]|&#(?:x0*2713|0*10003);)\)|\[([ xX])\])(\s+.+)$/i;
// ponytail: Covers bot-generated HTTP definitions; use a GFM parser for the full reference grammar.
const HTTP_LINK_REFERENCE_DEFINITION = /^ {0,3}\[(?![ \t]*\])([^\]\r\n]+)\]:[ \t]*(https?:\/\/[^\s<>()]+)[ \t]*$/i;

function openingFence(line: string): Fence | null {
  const match = line.match(/^ {0,3}(`{3,}|~{3,})(.*)$/);
  if (!match || (match[1][0] === '`' && match[2].includes('`'))) return null;
  return {
    marker: match[1][0] as Fence['marker'],
    length: match[1].length,
    language: match[2].trim().split(/\s+/, 1)[0] ?? '',
  };
}

function closesFence(line: string, fence: Fence) {
  const match = line.match(/^ {0,3}(`{3,}|~{3,})\s*$/);
  return match?.[1][0] === fence.marker && match[1].length >= fence.length;
}

const CODE_INDENT = /^(?: {4}| {0,3}\t)/;
const ALERT_ICONS: Record<string, LucideIcon> = {
  note: Info, tip: Lightbulb, important: MessageSquareWarning, warning: TriangleAlert, caution: OctagonAlert,
};

/** Flags the lines GitHub renders as indented code: 4+ columns in after a blank line, outside fences and list items. */
function indentedCodeLines(lines: string[]) {
  const code = lines.map(() => false);
  let fence: Fence | null = null;
  let blank = true;
  let listItem = false;
  let lastCode = -1;
  for (let index = 0; index < lines.length; index++) {
    const line = lines[index];
    if (fence) {
      if (closesFence(line, fence)) fence = null;
    }
    else if (!line.trim()) {
      blank = true;
      continue;
    }
    else if (CODE_INDENT.test(line) && (lastCode >= 0 || (blank && !listItem))) {
      code.fill(true, lastCode >= 0 ? lastCode + 1 : index, index + 1);
      lastCode = index;
    }
    else {
      lastCode = -1;
      fence = openingFence(line);
      if (/^\s*(?:[-*+]|\d{1,9}[.)])(?:\s|$)/.test(line)) listItem = true;
      else if (blank && !/^\s/.test(line)) listItem = false;
    }
    blank = false;
  }
  return code;
}

/** Rewrites indented code as fenced code, which the rest of the pipeline already keeps verbatim. */
function fenceIndentedCode(markdown: string) {
  const lines = markdown.split(/\r\n|\r|\n/);
  const code = indentedCodeLines(lines);
  const output: string[] = [];
  for (let start = 0; start < lines.length; start++) {
    if (!code[start]) {
      output.push(lines[start]);
      continue;
    }
    let end = start;
    while (code[end + 1]) end++;
    const content = lines.slice(start, end + 1).map(line => line.replace(/^(?: {0,3}\t| {1,4})/, ''));
    const fence = '`'.repeat(Math.max(3, ...content.map(line => (line.match(/^ {0,3}(`+)/)?.[1].length ?? 0) + 1)));
    output.push(fence, ...content, fence);
    start = end;
  }
  return output.join('\n');
}

function withoutHtmlComments(line: string, insideComment: boolean) {
  let visible = '';
  let cursor = 0;
  while (cursor < line.length) {
    if (insideComment) {
      const end = line.indexOf('-->', cursor);
      if (end < 0) return { visible: visible + ' '.repeat(line.length - cursor), insideComment: true };
      visible += ' '.repeat(end + 3 - cursor);
      cursor = end + 3;
      insideComment = false;
      continue;
    }
    const start = line.indexOf('<!--', cursor);
    if (start < 0) return { visible: visible + line.slice(cursor), insideComment: false };
    visible += line.slice(cursor, start) + ' '.repeat(4);
    cursor = start + 4;
    insideComment = true;
  }
  return { visible, insideComment };
}

function normalizeLinkLabel(label: string) {
  return label.trim().replace(/\s+/g, ' ').toLowerCase();
}

function extractHttpLinkDefinitions(markdown: string) {
  const sourceParts = markdown.split(/(\r\n|\r|\n)/);
  const definitions = new Map<string, string>();
  let fence: Fence | null = null;
  let insideComment = false;
  let paragraph = false;

  for (let index = 0; index < sourceParts.length; index += 2) {
    const line = sourceParts[index];
    if (fence) {
      if (closesFence(line, fence)) fence = null;
      continue;
    }
    if (!insideComment) {
      const opening = openingFence(line);
      if (opening) {
        fence = opening;
        paragraph = false;
        continue;
      }
    }
    const masked = withoutHtmlComments(line, insideComment);
    insideComment = masked.insideComment;
    if (!masked.visible.trim()) {
      paragraph = false;
      continue;
    }
    const definition = paragraph ? null : masked.visible.match(HTTP_LINK_REFERENCE_DEFINITION);
    if (!definition) {
      paragraph = true;
      continue;
    }
    const label = normalizeLinkLabel(definition[1]);
    if (!definitions.has(label)) definitions.set(label, definition[2]);
    sourceParts[index] = '';
  }
  return { body: sourceParts.join(''), definitions };
}

/** Toggles one rendered task by ordinal without rewriting unrelated Markdown. */
export function toggleMarkdownTask(markdown: string, taskIndex: number, checked: boolean) {
  if (!Number.isSafeInteger(taskIndex) || taskIndex < 0) return markdown;
  const sourceParts = markdown.split(/(\r\n|\r|\n)/);
  const indentedCode = indentedCodeLines(sourceParts.filter((_part, index) => index % 2 === 0));
  let fence: Fence | null = null;
  let insideComment = false;
  let quote = false;
  let alert = false;
  let currentTask = 0;

  for (let index = 0; index < sourceParts.length; index += 2) {
    const sourceLine = sourceParts[index];
    if (indentedCode[index / 2]) continue;
    if (fence) {
      if (closesFence(sourceLine, fence)) fence = null;
      continue;
    }
    if (!insideComment) {
      const opening = openingFence(sourceLine);
      if (opening) {
        fence = opening;
        continue;
      }
    }
    const masked = withoutHtmlComments(sourceLine, insideComment);
    const visibleLine = masked.visible;
    insideComment = masked.insideComment;
    if (alert) {
      if (visibleLine.startsWith('>')) continue;
      alert = false;
    }
    if (quote) {
      if (visibleLine.trim()) continue;
      quote = false;
      continue;
    }
    if (/^> \[!(?:NOTE|TIP|IMPORTANT|WARNING|CAUTION)\]\s*$/i.test(visibleLine)) {
      alert = true;
      continue;
    }
    if (visibleLine.startsWith('>')) {
      quote = true;
      continue;
    }
    const task = visibleLine.match(SOURCE_TASK_LINE);
    if (!task) continue;
    if (currentTask++ !== taskIndex) continue;

    const parenthesized = task[2] !== undefined;
    const wasChecked = (task[2] ?? task[3]).trim() !== '';
    if (wasChecked === checked) return markdown;
    const marker = parenthesized ? `(${checked ? '*' : ' '})` : `[${checked ? 'x' : ' '}]`;
    const markerStart = task[1].length;
    const markerLength = parenthesized ? task[2].length + 2 : 3;
    sourceParts[index] = sourceParts[index].slice(0, markerStart)
      + marker
      + sourceParts[index].slice(markerStart + markerLength);
    return sourceParts.join('');
  }
  return markdown;
}

/** The offset just past the `</details>` closing the tag at `start`, or the end of the text when it never closes. */
function detailsEnd(text: string, start: number) {
  const tag = /<\/?details(?:\s[^>]*)?>/gi;
  tag.lastIndex = start;
  let depth = 0;
  for (let match = tag.exec(text); match; match = tag.exec(text)) {
    depth += match[0].startsWith('</') ? -1 : 1;
    if (depth === 0) return tag.lastIndex;
  }
  return text.length;
}

function stripHtml(value: string) {
  return decodeEntities(value.replace(/<[^>]+>/g, '')).trim();
}

function htmlToMarkdown(value: string) {
  return decodeEntities(value
    .replace(/```[^]*?(?:```|$)|`[^`\n]+`/g,
      code => code.replaceAll('<', '&lt;').replaceAll('>', '&gt;'))
    .replace(/<!--[^]*?-->/g, '')
    .replace(/<(https?:\/\/[^>\s]+)>/gi, '$1')
    .replace(/<img\s[^>]*>/gi, tag => {
      const source = tag.match(/\ssrc=["']([^"']+)["']/i)?.[1];
      return source ? `![${tag.match(/\salt=["']([^"']*)["']/i)?.[1] ?? ''}](${source})` : '';
    })
    .replace(/<a\s+[^>]*href=["']([^"']+)["'][^>]*>([\s\S]*?)<\/a>/gi,
      (_match, href: string, label: string) => `[${stripHtml(label)}](${href})`)
    .replace(/<br\s*\/?\s*>/gi, '\n')
    .replace(/<\/(?:p|div|li|tr|table)>/gi, '\n')
    .replace(/<(?!\/?(?:details|summary)[\s>])[^>]+>/g, ''));
}

function decodeEntities(value: string) {
  return value
    .replace(/&#(x[0-9a-f]+|\d+);/gi, (entity, encoded: string) => {
      const codePoint = Number(encoded[0].toLowerCase() === 'x' ? `0${encoded}` : encoded);
      return codePoint <= 0x10ffff ? String.fromCodePoint(codePoint) : entity;
    })
    .replaceAll('&lt;', '<')
    .replaceAll('&gt;', '>')
    .replaceAll('&quot;', '"')
    .replaceAll('&#39;', "'")
    .replaceAll('&amp;', '&');
}

export function tableCells(line: string) {
  const trimmed = line.trim();
  if (!trimmed.includes('|')) return null;
  return trimmed.replace(/^\|/, '').replace(/\|$/, '').split('|').map(cell => cell.trim());
}

export function tableAlignments(line: string) {
  const cells = tableCells(line);
  if (!cells?.length || cells.some(cell => !/^:?-{3,}:?$/.test(cell))) return null;
  return cells.map((cell): 'left' | 'center' | 'right' => {
    if (cell.startsWith(':') && cell.endsWith(':')) return 'center';
    return cell.endsWith(':') ? 'right' : 'left';
  });
}

// Like GitHub, `_` emphasis cannot open or close inside a word, so ICEBERG_FILESYSTEM_ERROR stays literal.
// Its content admits only intraword underscores, which keeps an unclosed `_` from scanning the rest of the text.
const INLINE_TOKEN = /(\\[\\`]|\[!\[[^\]]*\]\([^)]+\)\]\([^)]+\)|!\[[^\]]*\]\([^)]+\)|\*\*[^*]+\*\*|(?<=^|[\s\p{P}\p{S}])_(?!\s)(?:[^_]|(?<=[^\s\p{P}\p{S}])_(?=[^\s\p{P}\p{S}]))+?(?<!\s)_(?=$|[\s\p{P}\p{S}])|`[^`]+`|\[[^\]]+\]\([^)]+\)|\[[^\]]+\]\[[^\]]*\]|\[[^\]]+\]|https?:\/\/[^\s<>()\]]*[^\s<>()\].,!?;:'"]|@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?|#[0-9]+)/gu;

export function parseGitHubCodePermalink(value: string, repository?: string): GitHubCodePermalink | null {
  if (!repository) return null;
  let url: URL;
  try {
    url = new URL(value);
  }
  catch {
    return null;
  }
  if (url.protocol !== 'https:' || url.hostname.toLowerCase() !== 'github.com'
      || url.port || url.username || url.password
      || (url.search !== '' && url.search !== '?plain=1')) return null;
  const file = url.pathname.match(/^\/([^/]+)\/([^/]+)\/blob\/([0-9a-f]{40})\/(.+)$/i);
  const range = url.hash.match(/^#L(\d+)(?:-L(\d+))?$/i);
  if (!file || !range) return null;
  let owner: string;
  let repo: string;
  let path: string;
  try {
    owner = decodeURIComponent(file[1]);
    repo = decodeURIComponent(file[2]);
    path = decodeURIComponent(file[4]);
  }
  catch {
    return null;
  }
  if (`${owner}/${repo}`.toLowerCase() !== repository.toLowerCase() || !path) return null;
  const startLine = Number(range[1]);
  const endLine = Number(range[2] ?? range[1]);
  if (!Number.isSafeInteger(startLine) || !Number.isSafeInteger(endLine)
      || startLine <= 0 || endLine < startLine || endLine - startLine >= 20) return null;
  return { href: value, owner, repo, commit: file[3], path, startLine, endLine };
}

export function markdownBlocks(markdown: string, repository?: string): Block[] {
  const blocks: Block[] = [];
  const lines = markdown.replaceAll('\r\n', '\n').split('\n');
  let paragraph: string[] = [];
  let list: { ordered: boolean; items: string[] } | null = null;
  let code: { fence: Fence; lines: string[] } | null = null;

  const flushParagraph = () => {
    if (paragraph.length === 0) return;
    const text = paragraph.join('\n');
    let cursor = 0;
    for (const match of text.matchAll(INLINE_TOKEN)) {
      const start = match.index ?? 0;
      const end = start + match[0].length;
      const before = text[start - 1];
      const after = text[end];
      if ((before && !/\s/.test(before)) || (after && !/\s/.test(after))) continue;
      const reference = parseGitHubCodePermalink(match[0], repository);
      if (!reference) continue;
      const leading = text.slice(cursor, start).trim();
      if (leading) blocks.push({ kind: 'paragraph', text: leading });
      blocks.push({ kind: 'code-reference', reference });
      cursor = end;
    }
    const trailing = text.slice(cursor).trim();
    if (trailing) blocks.push({ kind: 'paragraph', text: trailing });
    paragraph = [];
  };
  const flushList = () => {
    if (list) blocks.push({ kind: 'list', ordered: list.ordered, items: list.items });
    list = null;
  };

  for (let index = 0; index < lines.length; index++) {
    const line = lines[index];
    if (code) {
      if (closesFence(line, code.fence)) {
        blocks.push({ kind: 'code', language: code.fence.language, text: code.lines.join('\n') });
        code = null;
      }
      else code.lines.push(line);
      continue;
    }
    const fence = openingFence(line);
    if (fence) {
      flushParagraph();
      flushList();
      code = { fence, lines: [] };
      continue;
    }
    if (!line.trim()) {
      flushParagraph();
      flushList();
      continue;
    }
    const disclosure = line.match(/<details(?:\s[^>]*)?>/i);
    if (disclosure) {
      const lead = line.slice(0, disclosure.index).trim();
      if (lead) paragraph.push(lead);
      flushParagraph();
      flushList();
      const rest = lines.slice(index).join('\n');
      const open = (disclosure.index ?? 0) + disclosure[0].length;
      const end = detailsEnd(rest, disclosure.index ?? 0);
      const inner = rest.slice(open, end).replace(/<\/details>$/i, '');
      const summary = inner.match(/^\s*<summary(?:\s[^>]*)?>([\s\S]*?)<\/summary>/i);
      blocks.push({ kind: 'details', summary: summary?.[1].trim() ?? '', text: summary ? inner.slice(summary[0].length) : inner });
      index += rest.slice(0, end).split('\n').length - 1;
      const tail = rest.slice(end).split('\n', 1)[0];
      // Re-read what followed the closing tag on its own line instead of dropping it.
      if (tail.trim()) {
        lines[index] = tail;
        index--;
      }
      continue;
    }
    const codeReference = parseGitHubCodePermalink(line.trim(), repository);
    if (codeReference) {
      flushParagraph();
      flushList();
      blocks.push({ kind: 'code-reference', reference: codeReference });
      continue;
    }
    const headers = tableCells(line);
    const aligns = index + 1 < lines.length ? tableAlignments(lines[index + 1]) : null;
    if (headers && aligns && headers.length === aligns.length) {
      flushParagraph();
      flushList();
      const rows: string[][] = [];
      let cursor = index + 2;
      for (; cursor < lines.length; cursor++) {
        const cells = tableCells(lines[cursor]);
        if (!cells) break;
        rows.push(headers.map((_header, cellIndex) => cells[cellIndex] ?? ''));
      }
      blocks.push({ kind: 'table', headers, rows, aligns });
      index = cursor - 1;
      continue;
    }
    const heading = line.match(/^(#{1,6})\s+(.+)$/);
    if (heading) {
      flushParagraph();
      flushList();
      blocks.push({ kind: 'heading', level: heading[1].length, text: heading[2] });
      continue;
    }
    if (/^\s*([-*_])(?:\s*\1){2,}\s*$/.test(line)) {
      flushParagraph();
      flushList();
      blocks.push({ kind: 'rule' });
      continue;
    }
    const task = line.match(TASK_LINE);
    if (task) {
      flushParagraph();
      flushList();
      blocks.push({ kind: 'task', checked: (task[2] ?? task[3]) !== ' ', text: task[4] });
      continue;
    }
    const item = line.match(/^\s*(?:(\d+)\.|[-*+])\s+(.+)$/);
    if (item) {
      flushParagraph();
      const ordered = item[1] !== undefined;
      if (list && list.ordered !== ordered) flushList();
      if (!list) list = { ordered, items: [] };
      list.items.push(item[2]);
      continue;
    }
    const alert = line.match(/^> \[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)\]\s*$/i);
    if (alert) {
      flushParagraph();
      flushList();
      const content: string[] = [];
      while (index + 1 < lines.length && lines[index + 1].startsWith('>')) {
        content.push(lines[++index].replace(/^>\s?/, ''));
      }
      blocks.push({ kind: 'alert', label: alert[1], text: content.join('\n') });
      continue;
    }
    const quote = line.match(/^>\s?(.*)$/);
    if (quote) {
      flushParagraph();
      flushList();
      const content = [quote[1]];
      while (index + 1 < lines.length) {
        const next = lines[index + 1];
        const continuedQuote = next.match(/^>\s?(.*)$/);
        if (continuedQuote) {
          content.push(continuedQuote[1]);
          index++;
        }
        else if (next.trim()) content.push(lines[++index]);
        else break;
      }
      blocks.push({ kind: 'quote', text: content.join('\n') });
      continue;
    }
    // Like GitHub, a line that starts no block of its own continues the list item or task above it.
    const previous = blocks.at(-1);
    if (list) list.items[list.items.length - 1] += `\n${line.trim()}`;
    else if (previous?.kind === 'task' && paragraph.length === 0 && lines[index - 1].trim()) previous.text += `\n${line.trim()}`;
    else paragraph.push(line.trim());
  }
  if (code) blocks.push({ kind: 'code', language: code.fence.language, text: code.lines.join('\n') });
  flushParagraph();
  flushList();
  return blocks;
}

const EMPTY_LINK_DEFINITIONS = new Map<string, string>();

function inline(text: string, keyPrefix: string, definitions: ReadonlyMap<string, string> = EMPTY_LINK_DEFINITIONS): ReactNode[] {
  return text.split(INLINE_TOKEN).filter(Boolean).map((part, index) => {
    const key = `${keyPrefix}-${index}`;
    if (part === '\\`' || part === '\\\\') return <Fragment key={key}>{part.slice(1)}</Fragment>;
    const linkedImage = part.match(/^\[!\[([^\]]*)\]\(([^)]+)\)\]\(([^)]+)\)$/);
    if (linkedImage) return (
      <a className="md-image-link" key={key} href={linkedImage[3]} onClick={event => {
        event.preventDefault();
        void window.bridge.openInAppBrowser(linkedImage[3]);
      }}>
        <img src={linkedImage[2]} alt={linkedImage[1]} />
      </a>
    );
    const image = part.match(/^!\[([^\]]*)\]\(([^)]+)\)$/);
    if (image) return <img className="md-image" key={key} src={image[2]} alt={image[1]} />;
    if (part.startsWith('**') && part.endsWith('**')) return <strong key={key}>{inline(part.slice(2, -2), `${key}-strong`, definitions)}</strong>;
    if (part.startsWith('_') && part.endsWith('_')) return <em key={key}>{inline(part.slice(1, -1), `${key}-em`, definitions)}</em>;
    if (part.startsWith('`') && part.endsWith('`')) return <code key={key}>{part.slice(1, -1)}</code>;
    const directLink = part.match(/^\[([^\]]+)\]\(([^)]+)\)$/);
    const referenceLink = directLink ? null : part.match(/^\[([^\]]+)\](?:\[([^\]]*)\])?$/);
    const definitionLabel = referenceLink ? referenceLink[2] || referenceLink[1] : null;
    const definition = definitionLabel ? definitions.get(normalizeLinkLabel(definitionLabel)) : undefined;
    const linkText = directLink?.[1] ?? (definition ? referenceLink?.[1] : undefined);
    const linkHref = directLink?.[2] ?? definition;
    if (linkText && linkHref) {
      const href = linkHref.startsWith('/') ? `https://github.com${linkHref}` : linkHref;
      const reference = linkText.match(/^(.*) (#\d+)$/);
      const githubReference = parseGitHubReference(href);
      const showTitle = /^https?:\/\//i.test(linkText);
      const label = showTitle && githubReference
        ? <>{githubReference.owner}/{githubReference.repo} <small>#{githubReference.number}</small></>
        : reference ? <>{reference[1]} <small>{reference[2]}</small></> : inline(linkText, `${key}-link`, definitions);
      if (githubReference) return (
        <ReferenceLink key={key} reference={githubReference} showTitle={showTitle}>{label}</ReferenceLink>
      );
      return (
        <a key={key} href={href} onClick={event => {
          event.preventDefault();
          void window.bridge.openInAppBrowser(href);
        }}>
          {label}
        </a>
      );
    }
    if (referenceLink) return (
      <Fragment key={key}>
        [{inline(referenceLink[1], `${key}-reference-text`, definitions)}]
        {referenceLink[2] !== undefined && <>[{inline(referenceLink[2], `${key}-reference-label`, definitions)}]</>}
      </Fragment>
    );
    const bareReference = parseGitHubReference(part);
    if (bareReference) return (
      <ReferenceLink key={key} reference={bareReference} showTitle>
        {bareReference.owner}/{bareReference.repo} <small>#{bareReference.number}</small>
      </ReferenceLink>
    );
    if (/^https?:\/\//i.test(part)) return (
      <a key={key} href={part} onClick={event => {
        event.preventDefault();
        void window.bridge.openInAppBrowser(part);
      }}>
        {part}
      </a>
    );
    if (part.startsWith('@') || /^#[0-9]+$/.test(part)) return <span className="md-link" key={key}>{part}</span>;
    // Unlike Markdown files, GitHub descriptions and comments keep every source line break.
    return <Fragment key={key}>{part.split('\n').map((line, lineIndex) => (
      <Fragment key={lineIndex}>{lineIndex > 0 && <br />}{line}</Fragment>
    ))}</Fragment>;
  });
}

const codeSnippetRequests = new Map<string, Promise<GitHubCodeSnippetDto | null>>();

function loadCodeSnippet(reference: GitHubCodePermalink) {
  return once(codeSnippetRequests, reference.href, () => window.bridge.fetchGitHubCodeSnippet({
    owner: reference.owner,
    repo: reference.repo,
    commit: reference.commit,
    path: reference.path,
    startLine: reference.startLine,
    endLine: reference.endLine,
  }).then(value => value.lines.length > 0 ? value : null));
}

function CodeReferenceBlock({ reference }: { reference: GitHubCodePermalink }) {
  const [snippet, setSnippet] = useState<GitHubCodeSnippetDto | null | undefined>();
  const { href, owner, repo, commit, path, startLine, endLine } = reference;

  useEffect(() => {
    let current = true;
    setSnippet(undefined);
    void loadCodeSnippet({ href, owner, repo, commit, path, startLine, endLine }).then(value => {
      if (current) setSnippet(value);
    });
    return () => { current = false; };
  }, [commit, endLine, href, owner, path, repo, startLine]);

  return <CodeReferenceCard reference={reference} snippet={snippet} />;
}

function dedentCodeReferenceLines(lines: GitHubCodeSnippetDto['lines']) {
  const contentLines = lines.filter(line => line.content.trim().length > 0);
  const commonIndent = contentLines.length === 0
    ? 0
    : Math.min(...contentLines.map(line => line.content.match(/^[\t ]*/)?.[0].length ?? 0));
  return commonIndent === 0
    ? lines
    : lines.map(line => ({ ...line, content: line.content.slice(commonIndent) }));
}

export function CodeReferenceCard({ reference, snippet }: {
  reference: GitHubCodePermalink;
  snippet: GitHubCodeSnippetDto | null | undefined;
}) {
  if (snippet === null) return <p>{inline(reference.href, 'code-reference-fallback')}</p>;
  const lines = snippet ? dedentCodeReferenceLines(snippet.lines) : [];
  const lineLabel = reference.startLine === reference.endLine
    ? `Line ${reference.startLine}`
    : `Lines ${reference.startLine}–${reference.endLine}`;
  const highlightJava = /\.java$/i.test(reference.path);
  const open = (event: MouseEvent<HTMLAnchorElement>) => {
    event.preventDefault();
    void window.bridge.openInAppBrowser(reference.href);
  };
  return (
    <div className="md-code-reference">
      <div className="md-code-reference__header">
        <a href={reference.href} title={`${reference.repo}/${reference.path}`} onClick={open}>
          {reference.repo}/{reference.path}
        </a>
        <span>{lineLabel} in <a href={reference.href} onClick={open}>{reference.commit.slice(0, 7)}</a></span>
      </div>
      {snippet === undefined
        ? <div className="md-code-reference__status" role="status">Loading code…</div>
        : <div className="md-code-reference__source">
            {lines.map(line => (
              <pre key={line.number}>
                <span>{line.number}</span>
                {highlightJava
                  ? <code className="hljs" dangerouslySetInnerHTML={{ __html: highlightedCode(line.content || ' ', 'java') }} />
                  : <code>{line.content || ' '}</code>}
              </pre>
            ))}
          </div>}
    </div>
  );
}

type ReferencePreview = GitHubReferencePreview;

const previewRequests = new Map<string, Promise<ReferencePreview | null>>();

export function GitHubReferenceLink({ href, children }: { href: string | null; children: ReactNode }) {
  const reference = href ? parseGitHubReference(href) : null;
  return reference
    ? <ReferenceLink reference={reference}>{children}</ReferenceLink>
    : <span className="md-link">{children}</span>;
}

/** Hovering the same reference twice, anywhere, makes one request. */
function loadPreview(reference: GitHubReference): Promise<ReferencePreview | null> {
  const key = `${reference.kind}:${reference.owner}/${reference.repo}#${reference.number}`.toLowerCase();
  return once(previewRequests, key, () => resolveGitHubReference(reference));
}

/** The card's fields, whichever half of the union GitHub answered a reference with. */
export function referenceCardItem(reference: GitHubReference, preview: ReferencePreview | null | undefined): ReferenceCardItem {
  const detail = preview?.kind === 'pull' ? preview.detail : null;
  const issue = preview?.kind === 'issue' ? preview.issue : null;
  const repo = `${reference.owner}/${reference.repo}`;
  return {
    type: (preview?.kind ?? reference.kind) === 'pull' ? 'pr' : 'issue',
    repo,
    number: reference.number,
    title: detail?.title ?? issue?.title ?? `${repo} #${reference.number}`,
    author: detail?.author ?? issue?.author ?? null,
    authorAvatarUrl: detail?.authorAvatarUrl,
    createdAt: detail?.createdAt,
    updatedAt: detail?.updatedAt ?? issue?.updatedAt,
    labels: detail ? detail.labels.map(label => label.name) : issue?.labels ?? [],
    chip: stateChip(detail?.mergedAt != null ? 'merged' : detail?.state ?? issue?.state, detail?.draft),
  };
}

/** A reference's preview once `load` turns true: undefined while it loads, null when GitHub has none. */
export function useReferencePreview(reference: GitHubReference, load: boolean): ReferencePreview | null | undefined {
  const [preview, setPreview] = useState<ReferencePreview | null | undefined>();
  const { href, kind, number, owner, repo } = reference;
  useEffect(() => {
    if (!load) return;
    let current = true;
    void loadPreview({ href, kind, number, owner, repo }).then(value => {
      if (current) setPreview(value);
    });
    return () => { current = false; };
  }, [href, kind, load, number, owner, repo]);
  return preview;
}

/** The hover card of one issue or pull request reference, whatever shows the reference itself. */
export function ReferencePreviewCard({ id, hover, reference, preview, actions }: {
  id: string;
  hover: HoverCard;
  reference: GitHubReference;
  preview: ReferencePreview | null | undefined;
  actions?: ReactNode;
}) {
  const body = preview?.kind === 'pull' ? preview.detail.body : preview?.kind === 'issue' ? preview.issue.body : null;
  return (
    <ReferenceCard
      id={id}
      hover={hover}
      item={referenceCardItem(reference, preview)}
      body={preview === undefined
        ? <span className="ref-card__muted">Loading details…</span>
        : preview === null
          ? <span className="ref-card__muted">Details are unavailable.</span>
          : <MarkdownBody
              body={previewMarkdown(body) || 'No description provided.'}
              repository={`${reference.owner}/${reference.repo}`}
            />}
      actions={actions}
    />
  );
}

export function ReferenceLink({ reference, showTitle = false, children }: {
  reference: GitHubReference;
  showTitle?: boolean;
  children: ReactNode;
}) {
  const previewId = useId();
  const hover = useHoverCard();
  const open = hover.anchor !== null;
  // A reference the reader passed over is never fetched; the hover delay and the shown title are the two reasons to.
  const preview = useReferencePreview(reference, open || showTitle);

  const kind = preview?.kind ?? reference.kind;
  const title = preview?.kind === 'pull' ? preview.detail.title : preview?.kind === 'issue' ? preview.issue.title : null;
  const openReference = () => {
    hover.close();
    void openGitHubReference({ ...reference, kind });
  };

  return <>
    <a
      className={`md-reference is-${kind}`}
      href={reference.href}
      aria-describedby={open ? previewId : undefined}
      onMouseEnter={event => hover.show(event.currentTarget)}
      onMouseLeave={() => hover.hide()}
      onFocus={event => hover.show(event.currentTarget, 0)}
      onBlur={() => hover.hide()}
      onClick={event => {
        hover.close();
        // A surface that already took the click, like a task showing its own pull request in a panel, keeps it.
        if (event.defaultPrevented) return;
        event.preventDefault();
        void openGitHubReference({ ...reference, kind });
      }}
    >
      <ReferenceIcon pull={kind === 'pull'} />
      {showTitle && title ? <>{title} <small>#{reference.number}</small></> : children}
    </a>
    <ReferencePreviewCard id={previewId} hover={hover} reference={reference} preview={preview}
      actions={<button className="bq-button" type="button" onClick={openReference}>
        Open <ArrowUpRight aria-hidden="true" />
      </button>} />
  </>;
}

function ReferenceIcon({ pull }: { pull: boolean }) {
  return pull
    ? <GitPullRequest size={14} strokeWidth={1.4} aria-hidden="true" />
    : <CircleDot size={14} strokeWidth={1.4} aria-hidden="true" />;
}

function highlightedCode(text: string, language: string) {
  const normalizedLanguage = language.toLowerCase();
  return normalizedLanguage && hljs.getLanguage(normalizedLanguage)
    ? hljs.highlight(text, { language: normalizedLanguage, ignoreIllegals: true }).value
    : hljs.highlightAuto(text).value;
}

/** Common prefix and suffix lengths around the differing middle of two lines; null when they share neither. */
export function changedSpan(before: string, after: string): { prefix: number; suffix: number } | null {
  if (before === after) return null;
  const shortest = Math.min(before.length, after.length);
  let prefix = 0;
  while (prefix < shortest && before[prefix] === after[prefix]) prefix++;
  let suffix = 0;
  while (suffix < shortest - prefix && before[before.length - 1 - suffix] === after[after.length - 1 - suffix]) suffix++;
  return prefix + suffix > 0 ? { prefix, suffix } : null;
}

// GitHub lists every original line as removed and then every suggested line as added; it does not line-diff.
function SuggestedChange({ base, text }: { base: SuggestionBase; text: string }) {
  const removed = base.lines ?? [];
  // ponytail: a suggestion of exactly one blank line also reads as a deletion because code blocks keep joined text.
  const added = text === '' ? [] : text.split('\n');
  const spans = removed.length === added.length ? removed.map((line, index) => changedSpan(line, added[index])) : [];
  const row = (kind: 'add' | 'del', line: string, index: number) => {
    const span = spans[index];
    const middle = span ? line.slice(span.prefix, line.length - span.suffix) : '';
    return (
      <tr className={`md-suggestion__row is-${kind}`} key={`${kind}-${index}`}>
        <td className="md-suggestion__number">{base.startLine + index}</td>
        <td className="md-suggestion__code"><span>{kind === 'add' ? '+' : '−'}</span>{span
          ? <>{line.slice(0, span.prefix)}{middle && <mark>{middle}</mark>}{line.slice(line.length - span.suffix)}</>
          : line || ' '}</td>
      </tr>
    );
  };
  return (
    <div className="md-suggestion">
      <div className="md-suggestion__header">Suggested change</div>
      <table className="md-suggestion__rows" aria-label="Suggested change">
        <colgroup><col className="md-suggestion__number-column" /><col /></colgroup>
        <tbody>
          {removed.map((line, index) => row('del', line, index))}
          {added.map((line, index) => row('add', line, index))}
        </tbody>
      </table>
    </div>
  );
}

export default function MarkdownBody({ body, className = '', repository, suggestion, onTaskToggle, tasksDisabled = false }: {
  body: string;
  className?: string;
  repository?: string;
  /** Renders ```suggestion blocks as a diff against these lines; without it they stay plain code. */
  suggestion?: SuggestionBase;
  onTaskToggle?: (taskIndex: number, checked: boolean) => void;
  tasksDisabled?: boolean;
}) {
  const toggleTask = (event: ChangeEvent<HTMLDivElement>) => {
    if (!onTaskToggle || !(event.target instanceof HTMLInputElement)
        || event.target.dataset.markdownTask === undefined) return;
    const tasks = Array.from(event.currentTarget.querySelectorAll<HTMLInputElement>('input[data-markdown-task]'));
    const taskIndex = tasks.indexOf(event.target);
    if (taskIndex >= 0) onTaskToggle(taskIndex, event.target.checked);
  };
  const document = extractHttpLinkDefinitions(fenceIndentedCode(body));
  return (
    <div className={`markdown-body ${className}`.trim()} onChange={toggleTask}>
      <MarkdownBlocks markdown={htmlToMarkdown(document.body)} definitions={document.definitions} repository={repository}
        suggestion={suggestion} tasksInteractive={onTaskToggle !== undefined} tasksDisabled={tasksDisabled} />
    </div>
  );
}

function MarkdownBlocks({ markdown, definitions, repository, suggestion, tasksInteractive, tasksDisabled }: {
  markdown: string;
  definitions: ReadonlyMap<string, string>;
  repository?: string;
  suggestion?: SuggestionBase;
  tasksInteractive: boolean;
  tasksDisabled: boolean;
}) {
  const blocks = markdownBlocks(markdown, repository);
  return <>
      {blocks.map((block, index) => {
        const key = `${block.kind}-${index}`;
        if (block.kind === 'heading') {
          const Heading = `h${block.level}` as keyof React.JSX.IntrinsicElements;
          return <Heading key={key}>{inline(block.text, key, definitions)}</Heading>;
        }
        if (block.kind === 'paragraph') return <p key={key}>{inline(block.text, key, definitions)}</p>;
        if (block.kind === 'task') return (
          <p key={key}><label><input type="checkbox" checked={block.checked}
            disabled={!tasksInteractive || tasksDisabled} readOnly={!tasksInteractive}
            data-markdown-task={tasksInteractive ? '' : undefined}
            onChange={tasksInteractive ? () => {} : undefined} /> {inline(block.text, key, definitions)}</label></p>
        );
        if (block.kind === 'code') {
          if (suggestion && block.language.toLowerCase() === 'suggestion') return <SuggestedChange base={suggestion} text={block.text} key={key} />;
          const isMarkdown = /^(?:md|markdown)$/i.test(block.language);
          return (
            <div className="md-code-block" key={key}>
              {!isMarkdown && <span>{block.language || 'Plain text'}</span>}
              <pre><code className="hljs" data-language={block.language}
                dangerouslySetInnerHTML={{ __html: highlightedCode(block.text, block.language) }} /></pre>
            </div>
          );
        }
        if (block.kind === 'code-reference') return <CodeReferenceBlock reference={block.reference} key={key} />;
        if (block.kind === 'alert') {
          const label = block.label.toLowerCase();
          const Icon = ALERT_ICONS[label] ?? Info;
          return (
            <aside className={`md-alert is-${label}`} key={key}>
              <strong><Icon size={16} aria-hidden="true" />{block.label[0]}{label.slice(1)}</strong>
              <MarkdownBlocks markdown={fenceIndentedCode(block.text)} definitions={definitions} repository={repository}
                tasksInteractive={false} tasksDisabled={tasksDisabled} />
            </aside>
          );
        }
        if (block.kind === 'details') return (
          <details className="md-details" key={key}>
            <summary>{inline(block.summary, key, definitions)}</summary>
            <div className="markdown-body">
              <MarkdownBlocks markdown={block.text} definitions={definitions} repository={repository}
                suggestion={suggestion} tasksInteractive={tasksInteractive} tasksDisabled={tasksDisabled} />
            </div>
          </details>
        );
        if (block.kind === 'table') return (
          <div className="md-table-scroll" key={key}>
            <table>
              <thead><tr>{block.headers.map((header, cellIndex) => (
                <th key={`${key}-header-${cellIndex}`} style={{ textAlign: block.aligns[cellIndex] }}>{inline(header, `${key}-header-${cellIndex}`, definitions)}</th>
              ))}</tr></thead>
              <tbody>{block.rows.map((row, rowIndex) => (
                <tr key={`${key}-row-${rowIndex}`}>{row.map((cell, cellIndex) => (
                  <td key={`${key}-row-${rowIndex}-${cellIndex}`} style={{ textAlign: block.aligns[cellIndex] }}>{inline(cell, `${key}-row-${rowIndex}-${cellIndex}`, definitions)}</td>
                ))}</tr>
              ))}</tbody>
            </table>
          </div>
        );
        if (block.kind === 'quote') return (
          <blockquote key={key}><MarkdownBlocks markdown={fenceIndentedCode(block.text)} definitions={definitions} repository={repository}
            tasksInteractive={false} tasksDisabled={tasksDisabled} /></blockquote>
        );
        if (block.kind === 'rule') return <hr key={key} />;
        const List = block.ordered ? 'ol' : 'ul';
        return <List key={key}>{block.items.map((item, itemIndex) => (
          <li key={`${key}-${itemIndex}`}>{inline(item, `${key}-${itemIndex}`, definitions)}</li>
        ))}</List>;
      })}
    </>;
}
