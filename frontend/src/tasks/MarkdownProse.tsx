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
import ReactMarkdown from 'react-markdown';
import type { Components } from 'react-markdown';
import remarkGfm from 'remark-gfm';

/**
 * Renders assistant prose as GitHub-flavored markdown. We use this in
 * two places:
 *  • {@link StructuredConversation}'s ProseRow — light "card" variant
 *    that picks up the assistant-card background.
 *  • {@link ConversationPane}'s ProseBlock — terminal variant that
 *    reads the {@code --term-*} CSS variables so the chat-style view
 *    keeps its dark/light theming.
 *
 * User-typed messages don't go through markdown — they're rendered
 * with the simpler {@code renderInline} helper. The model occasionally
 * emits a list inline (e.g. "1. … 2. … 3. …") with no real newline
 * between items; we splice in a blank line before each list marker so
 * react-markdown sees a proper list block.
 */
type Variant = 'card' | 'terminal';

type Props = {
  text: string;
  variant?: Variant;
};

const monoFont = '"SF Mono", "JetBrains Mono", Menlo, Consolas, monospace';

export function MarkdownProse({ text, variant = 'card' }: Props) {
  const styles = variant === 'terminal' ? terminalStyles : cardStyles;
  const components: Components = {
    p: ({ children }) => <p style={styles.para}>{children}</p>,
    h1: ({ children }) => <h1 style={styles.h1}>{children}</h1>,
    h2: ({ children }) => <h2 style={styles.h2}>{children}</h2>,
    h3: ({ children }) => <h3 style={styles.h3}>{children}</h3>,
    h4: ({ children }) => <h4 style={styles.h4}>{children}</h4>,
    h5: ({ children }) => <h4 style={styles.h4}>{children}</h4>,
    h6: ({ children }) => <h4 style={styles.h4}>{children}</h4>,
    ul: ({ children }) => <ul style={styles.list}>{children}</ul>,
    ol: ({ children }) => <ol style={styles.list}>{children}</ol>,
    li: ({ children }) => <li style={styles.li}>{children}</li>,
    a: ({ children, href }) => (
      <a href={href} target="_blank" rel="noopener noreferrer" style={styles.link}>
        {children}
      </a>
    ),
    code: ({ className, children }) => {
      const isBlock = typeof className === 'string' && className.startsWith('language-');
      if (isBlock) {
        // Block code: inherit the pre's styling; don't wrap children.
        return <code className={className} style={styles.codeInBlock}>{children}</code>;
      }
      return <code style={styles.inlineCode}>{children}</code>;
    },
    pre: ({ children }) => <pre style={styles.codeBlock}>{children}</pre>,
    blockquote: ({ children }) => <blockquote style={styles.blockquote}>{children}</blockquote>,
    table: ({ children }) => <table style={styles.table}>{children}</table>,
    th: ({ children }) => <th style={styles.tableHeader}>{children}</th>,
    td: ({ children }) => <td style={styles.tableCell}>{children}</td>,
    hr: () => <hr style={styles.hr} />,
    strong: ({ children }) => <strong style={styles.strong}>{children}</strong>,
    em: ({ children }) => <em style={styles.em}>{children}</em>,
  };
  return (
    <div style={styles.root}>
      <ReactMarkdown remarkPlugins={[remarkGfm]} components={components}>
        {normalizeForMarkdown(text)}
      </ReactMarkdown>
    </div>
  );
}

/**
 * The model sometimes flattens lists into a single line (e.g.
 * {@code "1. foo. 2. bar. 3. baz."}). CommonMark won't recognize
 * those as a list, so we inject a blank line before each inline list
 * marker that follows a sentence terminator. We also bump single-
 * newline list markers to blank-line-separated so each item gets
 * proper paragraph spacing inside the rendered list.
 */
function normalizeForMarkdown(text: string): string {
  return text
    .replace(/(?<=[.!?])[ \t]+(?=\d+\.\s)/g, '\n\n')
    .replace(/(?<=[.!?])[ \t]+(?=[-*•]\s)/g, '\n\n');
}

type StyleBundle = {
  root: CSSProperties;
  para: CSSProperties;
  h1: CSSProperties;
  h2: CSSProperties;
  h3: CSSProperties;
  h4: CSSProperties;
  list: CSSProperties;
  li: CSSProperties;
  link: CSSProperties;
  inlineCode: CSSProperties;
  codeInBlock: CSSProperties;
  codeBlock: CSSProperties;
  blockquote: CSSProperties;
  table: CSSProperties;
  tableHeader: CSSProperties;
  tableCell: CSSProperties;
  hr: CSSProperties;
  strong: CSSProperties;
  em: CSSProperties;
};

const cardStyles: StyleBundle = {
  root: { color: 'var(--text-1)', lineHeight: 1.6 },
  para: { margin: '0 0 10px', lineHeight: 1.6, color: 'var(--text-1)' },
  h1: { fontSize: 18, fontWeight: 700, margin: '14px 0 8px', color: 'var(--text-1)' },
  h2: { fontSize: 16, fontWeight: 700, margin: '12px 0 6px', color: 'var(--text-1)' },
  h3: { fontSize: 14, fontWeight: 700, margin: '10px 0 4px', color: 'var(--text-1)' },
  h4: { fontSize: 13, fontWeight: 600, margin: '8px 0 4px', color: 'var(--text-1)' },
  list: { margin: '4px 0 10px', paddingLeft: 22, color: 'var(--text-1)' },
  li: { margin: '2px 0', lineHeight: 1.55 },
  link: { color: 'var(--accent)', textDecoration: 'underline' },
  inlineCode: {
    fontFamily: monoFont, fontSize: '0.92em',
    background: 'var(--bg-elevated)',
    padding: '1px 5px', borderRadius: 3,
    color: 'var(--text-1)',
  },
  codeInBlock: {
    fontFamily: monoFont, fontSize: 12,
    color: 'var(--text-1)',
    background: 'transparent', padding: 0,
  },
  codeBlock: {
    margin: '8px 0',
    padding: '8px 10px',
    background: 'var(--bg-elevated)',
    border: '1px solid var(--border)',
    borderRadius: 6,
    fontFamily: monoFont, fontSize: 12,
    lineHeight: 1.55,
    overflowX: 'auto',
    whiteSpace: 'pre',
  },
  blockquote: {
    margin: '8px 0',
    padding: '4px 12px',
    borderLeft: '3px solid var(--border)',
    color: 'var(--text-3)',
    fontStyle: 'italic',
  },
  table: {
    margin: '8px 0', borderCollapse: 'collapse',
    fontSize: 12.5,
  },
  tableHeader: {
    border: '1px solid var(--border)',
    padding: '4px 8px',
    background: 'var(--bg-elevated)',
    fontWeight: 600, textAlign: 'left',
  },
  tableCell: {
    border: '1px solid var(--border)',
    padding: '4px 8px',
  },
  hr: { border: 'none', borderTop: '1px dashed var(--border)', margin: '10px 0' },
  strong: { fontWeight: 700 },
  em: { fontStyle: 'italic' },
};

const terminalStyles: StyleBundle = {
  root: { color: 'var(--term-text)' },
  para: { margin: '0 0 10px', lineHeight: 1.7, color: 'var(--term-text)' },
  h1: { fontSize: 16, fontWeight: 700, margin: '14px 0 8px', color: 'var(--term-text-bright)' },
  h2: { fontSize: 14, fontWeight: 700, margin: '12px 0 6px', color: 'var(--term-text-bright)' },
  h3: { fontSize: 13, fontWeight: 700, margin: '10px 0 4px', color: 'var(--term-text-bright)' },
  h4: { fontSize: 13, fontWeight: 600, margin: '8px 0 4px', color: 'var(--term-text-bright)' },
  list: { margin: '4px 0 10px', paddingLeft: 22, color: 'var(--term-text)' },
  li: { margin: '2px 0', lineHeight: 1.65 },
  link: { color: 'var(--term-user)', textDecoration: 'underline' },
  inlineCode: {
    fontFamily: monoFont, fontSize: '0.92em',
    background: 'var(--term-kbd-bg)',
    padding: '1px 5px', borderRadius: 3,
    color: 'var(--term-text-bright)',
  },
  codeInBlock: {
    fontFamily: monoFont, fontSize: 12,
    color: 'var(--term-text)',
    background: 'transparent', padding: 0,
  },
  codeBlock: {
    margin: '8px 0',
    padding: '8px 10px',
    background: 'var(--term-user-bg)',
    border: '1px solid var(--term-border)',
    borderRadius: 6,
    fontFamily: monoFont, fontSize: 12,
    lineHeight: 1.55,
    overflowX: 'auto',
    whiteSpace: 'pre',
  },
  blockquote: {
    margin: '8px 0',
    padding: '4px 12px',
    borderLeft: '3px solid var(--term-border)',
    color: 'var(--term-text-dim)',
    fontStyle: 'italic',
  },
  table: {
    margin: '8px 0', borderCollapse: 'collapse',
    fontSize: 12.5,
  },
  tableHeader: {
    border: '1px solid var(--term-border)',
    padding: '4px 8px',
    background: 'var(--term-user-bg)',
    fontWeight: 600, textAlign: 'left',
    color: 'var(--term-text-bright)',
  },
  tableCell: {
    border: '1px solid var(--term-border)',
    padding: '4px 8px',
  },
  hr: { border: 'none', borderTop: '1px dashed var(--term-border)', margin: '10px 0' },
  strong: { color: 'var(--term-text-bright)', fontWeight: 700 },
  em: { fontStyle: 'italic' },
};
