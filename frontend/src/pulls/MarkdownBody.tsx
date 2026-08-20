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
import { Fragment, type ReactNode } from 'react';

type Block =
  | { kind: 'heading'; level: number; text: string }
  | { kind: 'paragraph'; text: string }
  | { kind: 'code'; language: string; text: string }
  | { kind: 'list'; ordered: boolean; items: string[] }
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
  const token = /(\[!\[[^\]]*\]\([^)]+\)\]\([^)]+\)|\*\*[^*]+\*\*|_[^_]+_|`[^`]+`|\[[^\]]+\]\([^)]+\)|@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?|#[0-9]+)/g;
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
    if (part.startsWith('**') && part.endsWith('**')) return <strong key={key}>{part.slice(2, -2)}</strong>;
    if (part.startsWith('_') && part.endsWith('_')) return <em key={key}>{part.slice(1, -1)}</em>;
    if (part.startsWith('`') && part.endsWith('`')) return <code key={key}>{part.slice(1, -1)}</code>;
    const link = part.match(/^\[([^\]]+)\]\(([^)]+)\)$/);
    if (link) {
      const href = link[2].startsWith('/') ? `https://github.com${link[2]}` : link[2];
      const reference = link[1].match(/^(.*) (#\d+)$/);
      const referenceType = /github\.com\/[^/]+\/[^/]+\/pull\/\d+$/.test(href)
        ? 'pull'
        : /github\.com\/[^/]+\/[^/]+\/issues\/\d+$/.test(href)
          ? 'issue'
          : null;
      return (
        <a className={referenceType ? `md-reference is-${referenceType}` : undefined} key={key} href={href} onClick={event => {
          event.preventDefault();
          void window.bridge.openInAppBrowser(href);
        }}>
          {referenceType && <ReferenceIcon pull={referenceType === 'pull'} />}
          {reference ? <>{reference[1]} <small>{reference[2]}</small></> : link[1]}
        </a>
      );
    }
    if (part.startsWith('@') || /^#[0-9]+$/.test(part)) return <span className="md-link" key={key}>{part}</span>;
    return <Fragment key={key}>{part}</Fragment>;
  });
}

function ReferenceIcon({ pull }: { pull: boolean }) {
  return pull
    ? <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.4" aria-hidden="true"><circle cx="4" cy="3" r="1.5" /><circle cx="12" cy="13" r="1.5" /><path d="M4 4.5v4A4.5 4.5 0 0 0 8.5 13h2M8 3h4v5" /></svg>
    : <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.4" aria-hidden="true"><circle cx="8" cy="8" r="6" /><circle cx="8" cy="8" r="1" fill="currentColor" stroke="none" /></svg>;
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
        if (block.kind === 'code') return (
          <div className="md-code-block" key={key}>
            <span>{block.language || 'Plain text'}</span>
            <pre><code data-language={block.language}>{block.text}</code></pre>
          </div>
        );
        if (block.kind === 'alert') return (
          <aside className={`md-alert is-${block.label.toLowerCase()}`} key={key}>
            <strong>ⓘ {block.label[0]}{block.label.slice(1).toLowerCase()}</strong>
            <p>{inline(block.text, key)}</p>
          </aside>
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
