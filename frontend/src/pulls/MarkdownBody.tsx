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
import { Fragment, useEffect, useId, useRef, useState, type ChangeEvent, type CSSProperties, type MouseEvent, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import {
  openGitHubReference,
  parseGitHubReference,
  type GitHubReference,
  type LocalGitHubReference,
  resolveLocalGitHubReference,
} from '../githubReference';
import { relativeTime } from '../relativeTime';
import type { GitHubCodeSnippetDto } from '../types';

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
  | { kind: 'code-reference'; reference: GitHubCodePermalink }
  | { kind: 'rule' };

type Segment =
  | { kind: 'markdown'; text: string }
  | { kind: 'details'; summary: string; text: string };

type Fence = { marker: '`' | '~'; length: number; language: string };

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
  let fence: Fence | null = null;
  let insideComment = false;
  let quote = false;
  let alert = false;
  let currentTask = 0;

  for (let index = 0; index < sourceParts.length; index += 2) {
    const sourceLine = sourceParts[index];
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

function markdownSegments(markdown: string): Segment[] {
  const segments: Segment[] = [];
  const opening = /<details(?:\s[^>]*)?>/gi;
  const tag = /<\/?details(?:\s[^>]*)?>/gi;
  let cursor = 0;
  while (cursor < markdown.length) {
    opening.lastIndex = cursor;
    const start = opening.exec(markdown);
    if (!start) break;
    tag.lastIndex = start.index + start[0].length;
    let depth = 1;
    let end = -1;
    for (let match = tag.exec(markdown); match; match = tag.exec(markdown)) {
      depth += match[0].startsWith('</') ? -1 : 1;
      if (depth === 0) {
        end = tag.lastIndex;
        break;
      }
    }
    if (end < 0) break;
    if (start.index > cursor) segments.push({ kind: 'markdown', text: markdown.slice(cursor, start.index) });
    const details = markdown.slice(start.index, end)
      .match(/^<details(?:\s[^>]*)?>\s*<summary>([\s\S]*?)<\/summary>([\s\S]*)<\/details>$/i);
    if (details) segments.push({ kind: 'details', summary: stripHtml(details[1]), text: details[2] });
    else segments.push({ kind: 'markdown', text: markdown.slice(start.index, end) });
    cursor = end;
  }
  if (cursor < markdown.length) segments.push({ kind: 'markdown', text: markdown.slice(cursor) });
  return segments.length > 0 ? segments : [{ kind: 'markdown', text: markdown }];
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
    .replace(/<a\s+[^>]*href=["']([^"']+)["'][^>]*>([\s\S]*?)<\/a>/gi,
      (_match, href: string, label: string) => `[${stripHtml(label)}](${href})`)
    .replace(/<br\s*\/?\s*>/gi, '\n')
    .replace(/<\/(?:p|div|li|tr|table)>/gi, '\n')
    .replace(/<[^>]+>/g, ''));
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

function tableCells(line: string) {
  const trimmed = line.trim();
  if (!trimmed.includes('|')) return null;
  return trimmed.replace(/^\|/, '').replace(/\|$/, '').split('|').map(cell => cell.trim());
}

function tableAlignments(line: string) {
  const cells = tableCells(line);
  if (!cells?.length || cells.some(cell => !/^:?-{3,}:?$/.test(cell))) return null;
  return cells.map((cell): 'left' | 'center' | 'right' => {
    if (cell.startsWith(':') && cell.endsWith(':')) return 'center';
    return cell.endsWith(':') ? 'right' : 'left';
  });
}

const INLINE_TOKEN = /(\\[\\`]|\[!\[[^\]]*\]\([^)]+\)\]\([^)]+\)|\*\*[^*]+\*\*|_[^_]+_|`[^`]+`|\[[^\]]+\]\([^)]+\)|\[[^\]]+\]\[[^\]]*\]|\[[^\]]+\]|https?:\/\/[^\s<>()\]]*[^\s<>()\].,!?;:'"]|@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?|#[0-9]+)/g;

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
    const text = paragraph.join(' ');
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
      blocks.push({ kind: 'alert', label: alert[1], text: content.join(' ') });
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
    flushList();
    paragraph.push(line.trim());
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
    return <Fragment key={key}>{part}</Fragment>;
  });
}

const codeSnippetRequests = new Map<string, Promise<GitHubCodeSnippetDto | null>>();

function loadCodeSnippet(reference: GitHubCodePermalink) {
  const existing = codeSnippetRequests.get(reference.href);
  if (existing) return existing;
  const request = window.bridge.fetchGitHubCodeSnippet({
    owner: reference.owner,
    repo: reference.repo,
    commit: reference.commit,
    path: reference.path,
    startLine: reference.startLine,
    endLine: reference.endLine,
  }).then(value => value.lines.length > 0 ? value : null).catch((): null => null);
  codeSnippetRequests.set(reference.href, request);
  void request.then(value => {
    if (value === null) codeSnippetRequests.delete(reference.href);
  });
  return request;
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

const JAVA_KEYWORDS = new Set('abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for goto if implements import instanceof int interface long native new package private protected public record return sealed short static strictfp super switch synchronized this throw throws transient try var void volatile while yield true false null'.split(' '));
const JAVA_TOKEN = /(\/\/.*|\/\*.*?\*\/|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|\b[A-Za-z_$][\w$]*\b)/g;

function javaCode(line: string, keyPrefix: string) {
  return line.split(JAVA_TOKEN).filter(Boolean).map((part, index) => {
    const className = part.startsWith('//') || part.startsWith('/*')
      ? 'md-code-comment'
      : part.startsWith('"') || part.startsWith("'")
        ? 'md-code-string'
        : JAVA_KEYWORDS.has(part)
          ? 'md-code-keyword'
          : /^[A-Z]/.test(part)
            ? 'md-code-type'
            : null;
    return className
      ? <span className={className} key={`${keyPrefix}-${index}`}>{part}</span>
      : <Fragment key={`${keyPrefix}-${index}`}>{part}</Fragment>;
  });
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
                <code>{highlightJava ? javaCode(line.content || ' ', `line-${line.number}`) : line.content || ' '}</code>
              </pre>
            ))}
          </div>}
    </div>
  );
}

type ReferencePreview = LocalGitHubReference;

const previewRequests = new Map<string, Promise<ReferencePreview | null>>();

export function GitHubReferenceLink({ href, children }: { href: string | null; children: ReactNode }) {
  const reference = href ? parseGitHubReference(href) : null;
  return reference
    ? <ReferenceLink reference={reference}>{children}</ReferenceLink>
    : <span className="md-link">{children}</span>;
}

function loadPreview(reference: GitHubReference): Promise<ReferencePreview | null> {
  const key = `${reference.kind}:${reference.owner}/${reference.repo}#${reference.number}`.toLowerCase();
  const existing = previewRequests.get(key);
  if (existing) return existing;
  const request = resolveLocalGitHubReference(reference).catch((): null => null);
  previewRequests.set(key, request);
  void request.then(value => {
    if (value === null) previewRequests.delete(key);
  });
  return request;
}

function ReferenceLink({ reference, showTitle = false, children }: {
  reference: GitHubReference;
  showTitle?: boolean;
  children: ReactNode;
}) {
  const previewId = useId();
  const anchor = useRef<HTMLAnchorElement>(null);
  const showTimer = useRef<number | null>(null);
  const hideTimer = useRef<number | null>(null);
  const [open, setOpen] = useState(false);
  const [position, setPosition] = useState<CSSProperties>({});
  const [preview, setPreview] = useState<ReferencePreview | null | undefined>();

  const clearTimer = (timer: typeof showTimer) => {
    if (timer.current !== null) window.clearTimeout(timer.current);
    timer.current = null;
  };
  const show = (delay: number) => {
    clearTimer(hideTimer);
    clearTimer(showTimer);
    showTimer.current = window.setTimeout(() => {
      const rect = anchor.current?.getBoundingClientRect();
      if (!rect) return;
      const below = window.innerHeight - rect.bottom >= 260 || rect.top < 300;
      const available = below ? window.innerHeight - rect.bottom - 20 : rect.top - 20;
      setPosition({
        left: Math.max(12, Math.min(rect.left, window.innerWidth - 492)),
        ...(below ? { top: rect.bottom + 8 } : { bottom: window.innerHeight - rect.top + 8 }),
        maxHeight: Math.max(180, Math.min(440, available)),
      });
      setOpen(true);
      if (preview == null) {
        setPreview(undefined);
        void loadPreview(reference).then(setPreview);
      }
    }, delay);
  };
  const hide = (delay = 140) => {
    clearTimer(showTimer);
    clearTimer(hideTimer);
    hideTimer.current = window.setTimeout(() => setOpen(false), delay);
  };

  useEffect(() => () => {
    clearTimer(showTimer);
    clearTimer(hideTimer);
  }, []);

  useEffect(() => {
    if (!showTitle) return;
    let current = true;
    void loadPreview({
      href: reference.href,
      kind: reference.kind,
      number: reference.number,
      owner: reference.owner,
      repo: reference.repo,
    }).then(value => {
      if (current) setPreview(value);
    });
    return () => { current = false; };
  }, [reference.href, reference.kind, reference.number, reference.owner, reference.repo, showTitle]);

  const kind = preview?.kind ?? reference.kind;
  const title = preview?.kind === 'pull' ? preview.detail.title : preview?.kind === 'issue' ? preview.issue.title : null;

  return <>
    <a
      ref={anchor}
      className={`md-reference is-${kind}`}
      href={reference.href}
      aria-describedby={open ? previewId : undefined}
      onMouseEnter={() => show(350)}
      onMouseLeave={() => hide()}
      onFocus={() => show(0)}
      onBlur={() => hide()}
      onClick={event => {
        event.preventDefault();
        hide(0);
        void openGitHubReference({ ...reference, kind });
      }}
    >
      <ReferenceIcon pull={kind === 'pull'} />
      {showTitle && title ? <>{title} <small>#{reference.number}</small></> : children}
    </a>
    {open && createPortal(
      <ReferencePreviewCard
        id={previewId}
        reference={reference}
        preview={preview}
        style={position}
        onEnter={() => clearTimer(hideTimer)}
        onLeave={() => hide()}
      />,
      document.body,
    )}
  </>;
}

function ReferencePreviewCard({ id, reference, preview, style, onEnter, onLeave }: {
  id: string;
  reference: GitHubReference;
  preview: ReferencePreview | null | undefined;
  style: CSSProperties;
  onEnter: () => void;
  onLeave: () => void;
}) {
  const item = preview?.kind === 'pull' ? preview.detail : preview?.kind === 'issue' ? preview.issue : null;
  const body = preview?.kind === 'pull' ? preview.detail.body : preview?.kind === 'issue' ? preview.issue.body : null;
  const labels = preview?.kind === 'pull' ? preview.detail.labels.map(label => label.name) : preview?.kind === 'issue' ? preview.issue.labels : [];
  const author = preview?.kind === 'pull' ? preview.detail.author : preview?.kind === 'issue' ? preview.issue.author : null;
  const updatedAt = preview?.kind === 'pull' ? preview.detail.updatedAt : preview?.kind === 'issue' ? preview.issue.updatedAt : null;
  const kind = preview?.kind ?? reference.kind;
  return (
    <aside
      className={`md-reference-preview is-${kind}`}
      id={id}
      role="dialog"
      aria-label={`${kind === 'pull' ? 'Pull request' : 'Issue'} preview`}
      style={style}
      tabIndex={0}
      onMouseEnter={onEnter}
      onMouseLeave={onLeave}
      onFocus={onEnter}
      onBlur={onLeave}
    >
      <header>
        <span><ReferenceIcon pull={kind === 'pull'} /> {kind === 'pull' ? 'Pull request' : 'Issue'}</span>
        <small>{reference.owner}/{reference.repo} #{reference.number}</small>
      </header>
      {preview === undefined && <div className="md-reference-preview__status">Loading local details…</div>}
      {preview === null && (
        <div className="md-reference-preview__status">No local details are available. Click to open on GitHub.</div>
      )}
      {item && <>
        <h3>{item.title ?? `${reference.owner}/${reference.repo} #${reference.number}`}</h3>
        <div className="md-reference-preview__meta">
          {author && <span>by <strong>{author}</strong></span>}
          {updatedAt && <span>updated {relativeTime(updatedAt)}</span>}
          {preview.kind === 'pull' && preview.detail.state && <span>{preview.detail.state.toLowerCase()}</span>}
          {preview.kind === 'issue' && <span>open</span>}
        </div>
        {labels.length > 0 && <div className="md-reference-preview__labels">
          {labels.map(label => <span key={label}>{label}</span>)}
        </div>}
        <div className="md-reference-preview__body">
          <MarkdownBody
            body={body || 'No description provided.'}
            repository={`${reference.owner}/${reference.repo}`}
          />
        </div>
      </>}
    </aside>
  );
}

function ReferenceIcon({ pull }: { pull: boolean }) {
  return pull
    ? <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.4" aria-hidden="true"><circle cx="4" cy="3" r="1.5" /><circle cx="12" cy="13" r="1.5" /><path d="M4 4.5v4A4.5 4.5 0 0 0 8.5 13h2M8 3h4v5" /></svg>
    : <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.4" aria-hidden="true"><circle cx="8" cy="8" r="6" /><circle cx="8" cy="8" r="1" fill="currentColor" stroke="none" /></svg>;
}

function markdownCode(text: string, keyPrefix: string) {
  return text.split('\n').map((line, index) => {
    const heading = line.match(/^(#{1,6}\s+)(.*)$/);
    const content = heading ? heading[2] : line;
    const highlighted = content.split(/(`[^`]+`)/g).filter(Boolean).map((part, partIndex) => (
      part.startsWith('`') && part.endsWith('`')
        ? <span className="md-code-markdown-inline" key={`${keyPrefix}-${index}-${partIndex}`}>{part}</span>
        : <Fragment key={`${keyPrefix}-${index}-${partIndex}`}>{part}</Fragment>
    ));
    return (
      <Fragment key={`${keyPrefix}-${index}`}>
        {index > 0 && '\n'}
        {heading
          ? <span className="md-code-markdown-heading">{heading[1]}{highlighted}</span>
          : highlighted}
      </Fragment>
    );
  });
}

export default function MarkdownBody({ body, className = '', repository, onTaskToggle, tasksDisabled = false }: {
  body: string;
  className?: string;
  repository?: string;
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
  const document = extractHttpLinkDefinitions(body);
  return <MarkdownBodyContent body={document.body} definitions={document.definitions} className={className} repository={repository}
    tasksInteractive={onTaskToggle !== undefined} tasksDisabled={tasksDisabled} onTaskChange={toggleTask} />;
}

function MarkdownBodyContent({ body, definitions, className = '', repository, tasksInteractive, tasksDisabled, onTaskChange }: {
  body: string;
  definitions: ReadonlyMap<string, string>;
  className?: string;
  repository?: string;
  tasksInteractive: boolean;
  tasksDisabled: boolean;
  onTaskChange?: (event: ChangeEvent<HTMLDivElement>) => void;
}) {
  return (
    <div className={`markdown-body ${className}`.trim()} onChange={onTaskChange}>
      {markdownSegments(body).map((segment, segmentIndex) => segment.kind === 'details' ? (
        <details className="md-details" key={`details-${segmentIndex}`}>
          <summary>{inline(segment.summary, `details-${segmentIndex}`, definitions)}</summary>
          <MarkdownBodyContent body={segment.text} definitions={definitions} repository={repository}
            tasksInteractive={tasksInteractive} tasksDisabled={tasksDisabled} />
        </details>
      ) : <MarkdownBlocks markdown={htmlToMarkdown(segment.text)} repository={repository}
        definitions={definitions} tasksInteractive={tasksInteractive} tasksDisabled={tasksDisabled} key={`markdown-${segmentIndex}`} />)}
    </div>
  );
}

function MarkdownBlocks({ markdown, definitions, repository, tasksInteractive, tasksDisabled }: {
  markdown: string;
  definitions: ReadonlyMap<string, string>;
  repository?: string;
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
          const isMarkdown = /^(?:md|markdown)$/i.test(block.language);
          return (
            <div className="md-code-block" key={key}>
              {!isMarkdown && <span>{block.language || 'Plain text'}</span>}
              <pre><code data-language={block.language}>{isMarkdown ? markdownCode(block.text, key) : block.text}</code></pre>
            </div>
          );
        }
        if (block.kind === 'code-reference') return <CodeReferenceBlock reference={block.reference} key={key} />;
        if (block.kind === 'alert') return (
          <aside className={`md-alert is-${block.label.toLowerCase()}`} key={key}>
            <strong>ⓘ {block.label[0]}{block.label.slice(1).toLowerCase()}</strong>
            <p>{inline(block.text, key, definitions)}</p>
          </aside>
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
          <blockquote key={key}><MarkdownBlocks markdown={block.text} definitions={definitions} repository={repository}
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
