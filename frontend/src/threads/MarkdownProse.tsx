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
    p: ({ children }) => {
      // Tooling / process asides ("Note on tooling: …") are noise for the
      // user reading a task result — collapse them behind a disclosure so
      // the outcome stays front-and-centre, detail one click away.
      const note = matchMetaNote(leadingText(children));
      if (note !== null) {
        return (
          <details style={styles.metaDetails}>
            <summary style={styles.metaSummary}>{note}</summary>
            <p style={styles.para}>{children}</p>
          </details>
        );
      }
      return <p style={styles.para}>{children}</p>;
    },
    h1: ({ children }) => <h1 style={styles.h1}>{children}</h1>,
    h2: ({ children }) => <h2 style={styles.h2}>{children}</h2>,
    h3: ({ children }) => <h3 style={styles.h3}>{children}</h3>,
    h4: ({ children }) => <h4 style={styles.h4}>{children}</h4>,
    h5: ({ children }) => <h4 style={styles.h4}>{children}</h4>,
    h6: ({ children }) => <h4 style={styles.h4}>{children}</h4>,
    ul: ({ children }) => <ul style={styles.list}>{children}</ul>,
    ol: ({ children }) => <ol style={styles.list}>{children}</ol>,
    li: ({ children }) => <li style={styles.li}>{children}</li>,
    a: ({ children, href }) => {
      // A bare GitHub PR link is the most important artifact a task
      // produces — render it as a scannable chip rather than a long URL
      // buried in a sentence.
      const prNumber = href ? parsePrNumber(href) : null;
      if (prNumber !== null && href) {
        return (
          <a href={href} target="_blank" rel="noopener noreferrer" style={styles.prChip}>
            <span aria-hidden>⊕</span>
            PR #{prNumber}
            <span aria-hidden>→</span>
          </a>
        );
      }
      return (
        <a href={href} target="_blank" rel="noopener noreferrer" style={styles.link}>
          {children}
        </a>
      );
    },
    code: ({ className, children }) => {
      const isBlock = typeof className === 'string' && className.startsWith('language-');
      if (isBlock) {
        // Block code: inherit the pre's styling; don't wrap children.
        return <code className={className} style={styles.codeInBlock}>{children}</code>;
      }
      // A short hex run on its own (a commit SHA) reads better as a small
      // git chip than as a generic inline-code span.
      if (isCommitSha(plainText(children))) {
        return (
          <code style={styles.commitChip}>
            <span aria-hidden style={{ marginRight: 4, opacity: 0.7 }}>⎇</span>
            {children}
          </code>
        );
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

const PR_URL_RE = /^https?:\/\/github\.com\/[^/\s]+\/[^/\s]+\/pull\/(\d+)(?:[/?#].*)?$/i;

/** PR number for a github.com/owner/repo/pull/N link, else null. */
export function parsePrNumber(href: string): number | null {
  const m = PR_URL_RE.exec(href.trim());
  return m ? Number(m[1]) : null;
}

const SHA_RE = /^[0-9a-f]{7,40}$/i;

/** True for a lone 7–40 char hex run — i.e. a git object id. */
export function isCommitSha(text: string): boolean {
  return SHA_RE.test(text.trim());
}

// Process / tooling asides the agent appends ("Note on tooling: …").
// Conservative on purpose — only collapse meta-commentary about how the
// work was carried out, never substantive "Note:" content.
const META_NOTE_RE =
  /^\s*(note on tooling|tooling note|note on the environment|environment note|note on how this (?:was )?(?:run|executed))\b/i;

/** Returns the disclosure label if a paragraph opens with a tooling/meta
 *  aside, else null. The label is the matched lead phrase, title-cased. */
export function matchMetaNote(lead: string): string | null {
  const m = META_NOTE_RE.exec(lead);
  if (m === null) return null;
  const phrase = m[1].trim();
  return phrase.charAt(0).toUpperCase() + phrase.slice(1);
}

/** First non-empty string chunk of a markdown node's children — used to
 *  sniff a paragraph's opening words without rendering it first. */
function leadingText(children: ReactNode): string {
  if (typeof children === 'string') return children;
  if (Array.isArray(children)) {
    for (const c of children) {
      if (typeof c === 'string') {
        if (c.trim() === '') continue;
        return c;
      }
      break;
    }
  }
  return '';
}

/** Flattens an inline-code node's children to a plain string when it is a
 *  single text run; returns '' for anything richer (so it won't match a
 *  SHA and stays a normal code span). */
function plainText(children: ReactNode): string {
  if (typeof children === 'string') return children;
  if (Array.isArray(children) && children.length === 1 && typeof children[0] === 'string') {
    return children[0];
  }
  return '';
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
  prChip: CSSProperties;
  commitChip: CSSProperties;
  metaDetails: CSSProperties;
  metaSummary: CSSProperties;
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
    // Long identifiers/calls break instead of overflowing a narrow card.
    overflowWrap: 'break-word',
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
    width: '100%', tableLayout: 'auto',
  },
  tableHeader: {
    border: '1px solid var(--border)',
    padding: '4px 8px',
    background: 'var(--bg-elevated)',
    fontWeight: 600, textAlign: 'left',
    // Short column labels stay on one line; long code tokens in the body
    // cells wrap instead of stretching the table past the bubble and
    // squeezing the headers into char-by-char breaks ("Seve rity").
    whiteSpace: 'nowrap', verticalAlign: 'top',
  },
  tableCell: {
    border: '1px solid var(--border)',
    padding: '4px 8px',
    // break-word (not anywhere) only breaks a token when it can't fit, so a
    // column never collapses to a single character — short cells like
    // "F2" / "Question" stay whole and on one line, while long prose still
    // wraps at spaces.
    verticalAlign: 'top', overflowWrap: 'break-word', wordBreak: 'normal',
  },
  hr: { border: 'none', borderTop: '1px dashed var(--border)', margin: '10px 0' },
  strong: { fontWeight: 700 },
  em: { fontStyle: 'italic' },
  prChip: {
    display: 'inline-flex', alignItems: 'center', gap: 5,
    padding: '1px 9px', margin: '0 1px',
    border: '1px solid var(--accent)', borderRadius: 999,
    background: 'var(--bg-elevated)', color: 'var(--accent)',
    fontSize: '0.9em', fontWeight: 600, lineHeight: 1.5,
    textDecoration: 'none', whiteSpace: 'nowrap', verticalAlign: 'baseline',
  },
  commitChip: {
    fontFamily: monoFont, fontSize: '0.9em',
    background: 'var(--bg-elevated)', border: '1px solid var(--border)',
    padding: '1px 6px', borderRadius: 4,
    color: 'var(--text-2)', whiteSpace: 'nowrap',
  },
  metaDetails: { margin: '4px 0 10px' },
  metaSummary: {
    cursor: 'pointer', color: 'var(--text-3)',
    fontSize: 12.5, fontWeight: 600, padding: '2px 0',
  },
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
    width: '100%', tableLayout: 'auto',
  },
  tableHeader: {
    border: '1px solid var(--term-border)',
    padding: '4px 8px',
    background: 'var(--term-user-bg)',
    fontWeight: 600, textAlign: 'left',
    color: 'var(--term-text-bright)',
    whiteSpace: 'nowrap', verticalAlign: 'top',
  },
  tableCell: {
    border: '1px solid var(--term-border)',
    padding: '4px 8px',
    verticalAlign: 'top', overflowWrap: 'anywhere',
  },
  hr: { border: 'none', borderTop: '1px dashed var(--term-border)', margin: '10px 0' },
  strong: { color: 'var(--term-text-bright)', fontWeight: 700 },
  em: { fontStyle: 'italic' },
  prChip: {
    display: 'inline-flex', alignItems: 'center', gap: 5,
    padding: '1px 9px', margin: '0 1px',
    border: '1px solid var(--term-user)', borderRadius: 999,
    background: 'var(--term-kbd-bg)', color: 'var(--term-user)',
    fontSize: '0.9em', fontWeight: 600, lineHeight: 1.5,
    textDecoration: 'none', whiteSpace: 'nowrap', verticalAlign: 'baseline',
  },
  commitChip: {
    fontFamily: monoFont, fontSize: '0.9em',
    background: 'var(--term-kbd-bg)', border: '1px solid var(--term-border)',
    padding: '1px 6px', borderRadius: 4,
    color: 'var(--term-text)', whiteSpace: 'nowrap',
  },
  metaDetails: { margin: '4px 0 10px' },
  metaSummary: {
    cursor: 'pointer', color: 'var(--term-text-dim)',
    fontSize: 12.5, fontWeight: 600, padding: '2px 0',
  },
};
