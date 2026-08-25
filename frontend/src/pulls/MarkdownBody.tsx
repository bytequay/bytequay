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
import { Fragment, useEffect, useId, useRef, useState, type CSSProperties, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import {
  openGitHubReference,
  parseGitHubReference,
  type GitHubReference,
  type LocalGitHubReference,
  resolveLocalGitHubReference,
} from '../githubReference';
import { relativeTime } from '../relativeTime';

type Block =
  | { kind: 'heading'; level: number; text: string }
  | { kind: 'paragraph'; text: string }
  | { kind: 'task'; checked: boolean; text: string }
  | { kind: 'code'; language: string; text: string }
  | { kind: 'list'; ordered: boolean; items: string[] }
  | { kind: 'table'; headers: string[]; rows: string[][]; aligns: Array<'left' | 'center' | 'right'> }
  | { kind: 'quote'; text: string }
  | { kind: 'alert'; label: string; text: string }
  | { kind: 'rule' };

type Segment =
  | { kind: 'markdown'; text: string }
  | { kind: 'details'; summary: string; text: string };

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

export function markdownBlocks(markdown: string): Block[] {
  const blocks: Block[] = [];
  const lines = markdown.replaceAll('\r\n', '\n').split('\n');
  let paragraph: string[] = [];
  let list: { ordered: boolean; items: string[] } | null = null;
  let code: { language: string; lines: string[] } | null = null;

  const flushParagraph = () => {
    if (paragraph.length > 0) blocks.push({ kind: 'paragraph', text: paragraph.join(' ') });
    paragraph = [];
  };
  const flushList = () => {
    if (list) blocks.push({ kind: 'list', ordered: list.ordered, items: list.items });
    list = null;
  };

  for (let index = 0; index < lines.length; index++) {
    const line = lines[index];
    if (code) {
      if (line.startsWith('```')) {
        blocks.push({ kind: 'code', language: code.language, text: code.lines.join('\n') });
        code = null;
      }
      else code.lines.push(line);
      continue;
    }
    const fence = line.match(/^```([^\s]*)\s*$/);
    if (fence) {
      flushParagraph();
      flushList();
      code = { language: fence[1], lines: [] };
      continue;
    }
    if (!line.trim()) {
      flushParagraph();
      flushList();
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
    const task = line.match(/^\s*(?:[-*+]\s+)?(?:\(([ xX])\)|\[([ xX])\])\s+(.+)$/);
    if (task) {
      flushParagraph();
      flushList();
      blocks.push({ kind: 'task', checked: (task[1] ?? task[2]).toLowerCase() === 'x', text: task[3] });
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
    if (line.startsWith('> ')) {
      flushParagraph();
      flushList();
      blocks.push({ kind: 'quote', text: line.slice(2) });
      continue;
    }
    flushList();
    paragraph.push(line.trim());
  }
  if (code) blocks.push({ kind: 'code', language: code.language, text: code.lines.join('\n') });
  flushParagraph();
  flushList();
  return blocks;
}

function inline(text: string, keyPrefix: string): ReactNode[] {
  const token = /(\[!\[[^\]]*\]\([^)]+\)\]\([^)]+\)|\*\*[^*]+\*\*|_[^_]+_|`[^`]+`|\[[^\]]+\]\([^)]+\)|https?:\/\/[^\s<>()\]]*[^\s<>()\].,!?;:'"]|@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?|#[0-9]+)/g;
  return text.split(token).filter(Boolean).map((part, index) => {
    const key = `${keyPrefix}-${index}`;
    const linkedImage = part.match(/^\[!\[([^\]]*)\]\(([^)]+)\)\]\(([^)]+)\)$/);
    if (linkedImage) return (
      <a className="md-image-link" key={key} href={linkedImage[3]} onClick={event => {
        event.preventDefault();
        void window.bridge.openInAppBrowser(linkedImage[3]);
      }}>
        <img src={linkedImage[2]} alt={linkedImage[1]} />
      </a>
    );
    if (part.startsWith('**') && part.endsWith('**')) return <strong key={key}>{inline(part.slice(2, -2), `${key}-strong`)}</strong>;
    if (part.startsWith('_') && part.endsWith('_')) return <em key={key}>{inline(part.slice(1, -1), `${key}-em`)}</em>;
    if (part.startsWith('`') && part.endsWith('`')) return <code key={key}>{part.slice(1, -1)}</code>;
    const link = part.match(/^\[([^\]]+)\]\(([^)]+)\)$/);
    if (link) {
      const href = link[2].startsWith('/') ? `https://github.com${link[2]}` : link[2];
      const reference = link[1].match(/^(.*) (#\d+)$/);
      const githubReference = parseGitHubReference(href);
      const showTitle = /^https?:\/\//i.test(link[1]);
      const label = showTitle && githubReference
        ? <>{githubReference.owner}/{githubReference.repo} <small>#{githubReference.number}</small></>
        : reference ? <>{reference[1]} <small>{reference[2]}</small></> : inline(link[1], `${key}-link`);
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
          <MarkdownBody body={body || 'No description provided.'} />
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

export default function MarkdownBody({ body, className = '' }: { body: string; className?: string }) {
  return (
    <div className={`markdown-body ${className}`.trim()}>
      {markdownSegments(body).map((segment, segmentIndex) => segment.kind === 'details' ? (
        <details className="md-details" key={`details-${segmentIndex}`}>
          <summary>{inline(segment.summary, `details-${segmentIndex}`)}</summary>
          <MarkdownBody body={segment.text} />
        </details>
      ) : <MarkdownBlocks markdown={htmlToMarkdown(segment.text)} key={`markdown-${segmentIndex}`} />)}
    </div>
  );
}

function MarkdownBlocks({ markdown }: { markdown: string }) {
  const blocks = markdownBlocks(markdown);
  return <>
      {blocks.map((block, index) => {
        const key = `${block.kind}-${index}`;
        if (block.kind === 'heading') {
          const Heading = `h${block.level}` as keyof React.JSX.IntrinsicElements;
          return <Heading key={key}>{inline(block.text, key)}</Heading>;
        }
        if (block.kind === 'paragraph') return <p key={key}>{inline(block.text, key)}</p>;
        if (block.kind === 'task') return (
          <p key={key}><label><input type="checkbox" checked={block.checked} disabled readOnly /> {inline(block.text, key)}</label></p>
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
        if (block.kind === 'alert') return (
          <aside className={`md-alert is-${block.label.toLowerCase()}`} key={key}>
            <strong>ⓘ {block.label[0]}{block.label.slice(1).toLowerCase()}</strong>
            <p>{inline(block.text, key)}</p>
          </aside>
        );
        if (block.kind === 'table') return (
          <div className="md-table-scroll" key={key}>
            <table>
              <thead><tr>{block.headers.map((header, cellIndex) => (
                <th key={`${key}-header-${cellIndex}`} style={{ textAlign: block.aligns[cellIndex] }}>{inline(header, `${key}-header-${cellIndex}`)}</th>
              ))}</tr></thead>
              <tbody>{block.rows.map((row, rowIndex) => (
                <tr key={`${key}-row-${rowIndex}`}>{row.map((cell, cellIndex) => (
                  <td key={`${key}-row-${rowIndex}-${cellIndex}`} style={{ textAlign: block.aligns[cellIndex] }}>{inline(cell, `${key}-row-${rowIndex}-${cellIndex}`)}</td>
                ))}</tr>
              ))}</tbody>
            </table>
          </div>
        );
        if (block.kind === 'quote') return <blockquote key={key}>{inline(block.text, key)}</blockquote>;
        if (block.kind === 'rule') return <hr key={key} />;
        const List = block.ordered ? 'ol' : 'ul';
        return <List key={key}>{block.items.map((item, itemIndex) => (
          <li key={`${key}-${itemIndex}`}>{inline(item, `${key}-${itemIndex}`)}</li>
        ))}</List>;
      })}
    </>;
}
