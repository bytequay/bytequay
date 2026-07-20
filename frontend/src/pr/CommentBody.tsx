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
import { parseUnifiedDiff } from '../diffParse';
import { highlightToHtml, languageForPath } from '../highlight';
import { MarkdownWithPermalinks } from './GithubPermalinkCard';
import { lastTouchedLine } from './utils';

/**
 * Renders a unified-diff hunk with GitHub-style coloring: green background
 * for added lines (`+`), red for removed (`-`), neutral for context, and
 * a muted blue tint for the `@@` hunk header. Input is the raw string
 * GitHub returns on review-thread.diffHunk.
 *
 * When a `range` is provided, only the lines whose target-side line
 * number falls within {startLine, endLine} are rendered, with a gutter
 * showing the actual line numbers and a "Comment on line +X" header —
 * matches GitHub's web UI for multi-line review comments. A
 * {@code contextFilePath} renders the complete hunk with old/new gutters and
 * syntax highlighting; without either option the hunk renders verbatim.
 */
type Range = {
  startLine: number;
  endLine: number;
  side: 'LEFT' | 'RIGHT';
};

export function DiffHunk({ hunk, range, contextFilePath }: {
  hunk: string;
  range?: Range;
  /** Render the complete hunk with old/new gutters and syntax highlighting. */
  contextFilePath?: string;
}) {
  if (contextFilePath !== undefined) {
    const language = languageForPath(contextFilePath);
    return (
      <div className="prc-review-thread__context-hunk">
        {parseUnifiedDiff(hunk).flatMap(diff => diff.rows
          .filter(row => row.kind !== 'hunk-header')
          .map((row, index) => (
            <div key={`${diff.header}:${index}`} className={`diff-row diff-row--${row.kind}`}>
              <span className="diff-row__gutter">{row.oldLine ?? ''}</span>
              <span className="diff-row__gutter">{row.newLine ?? ''}</span>
              <span className="diff-row__content">
                <span className="diff-row__sigil">{row.kind === 'add' ? '+' : row.kind === 'del' ? '-' : ' '}</span>
                <span className="hljs" dangerouslySetInnerHTML={{ __html: highlightToHtml(row.content, language) }} />
              </span>
            </div>
          ))) }
      </div>
    );
  }
  if (range) {
    const sliced = sliceHunkToRange(hunk, range);
    if (sliced.length === 0) {
      // Range fell outside the hunk (e.g. an outdated thread anchored
      // to a line GitHub no longer exposes in diff_hunk). Fall back to
      // showing the full hunk so the user at least sees something.
      return <FullHunk hunk={hunk} />;
    }
    const sigil = range.side === 'LEFT' ? '−' : '+';
    const header = range.startLine === range.endLine
      ? `Comment on line ${sigil}${range.startLine}`
      : `Comment on lines ${sigil}${range.startLine} to ${sigil}${range.endLine}`;
    return (
      <div className="prc-review-thread__hunk-block">
        <div className="prc-review-thread__hunk-loc">{header}</div>
        <pre className="prc-review-thread__hunk">
          <div className="prc-review-thread__hunk-inner prc-review-thread__hunk-inner--gutter">
            {sliced.map((row, i) => {
              const cls = `diff-hunk-line diff-hunk-line--${row.kind}`;
              const prefix = row.kind === 'add' ? '+' : row.kind === 'del' ? '-' : ' ';
              return (
                <div key={i} className={cls}>
                  <span className="diff-hunk-line__gutter">{row.lineNo}</span>
                  <span className="diff-hunk-line__sigil">{prefix}</span>
                  <span className="diff-hunk-line__text">{row.text || ' '}</span>
                </div>
              );
            })}
          </div>
        </pre>
      </div>
    );
  }
  return <FullHunk hunk={hunk} />;
}

function FullHunk({ hunk }: { hunk: string }) {
  const lines = hunk.split('\n');
  // Inner wrapper sized to max(100%, longest-line) so each row's
  // background paints all the way to the right of the longest line —
  // the outer <pre> is the horizontal-scroll viewport. Without the
  // inner wrapper, grid items get capped at the <pre>'s visible width
  // and long lines leave white space on the right of every other row.
  return (
    <pre className="prc-review-thread__hunk">
      <div className="prc-review-thread__hunk-inner">
      {lines.map((line, i) => {
        let cls = 'diff-hunk-line diff-hunk-line--ctx';
        if (line.startsWith('@@')) cls = 'diff-hunk-line diff-hunk-line--head';
        else if (line.startsWith('+')) cls = 'diff-hunk-line diff-hunk-line--add';
        else if (line.startsWith('-')) cls = 'diff-hunk-line diff-hunk-line--del';
        return <div key={i} className={cls}>{line || ' '}</div>;
      })}
      </div>
    </pre>
  );
}

type SlicedRow = { kind: 'add' | 'del' | 'ctx'; lineNo: number; text: string };

/**
 * Walks the unified-diff hunk, tracking the per-side line numbers from
 * the @@ header, and returns just the rows whose target-side line
 * number falls within the given range. Lines on the *other* side
 * (e.g. `-` lines when the comment is on RIGHT) are omitted — they
 * confuse the "this is what I commented on" intent.
 */
export function sliceHunkToRange(hunk: string, range: Range): SlicedRow[] {
  const lines = hunk.split('\n');
  const out: SlicedRow[] = [];
  // We track one-less-than-current so the first ++ lands on the
  // header's a / c value.
  let oldLineNo = 0;
  let newLineNo = 0;
  for (const line of lines) {
    if (line.startsWith('@@')) {
      const m = /^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/.exec(line);
      if (m) {
        oldLineNo = parseInt(m[1], 10) - 1;
        newLineNo = parseInt(m[2], 10) - 1;
      }
      continue;
    }
    // "\ No newline at end of file" — diff metadata, doesn't advance.
    if (line.startsWith('\\')) continue;

    if (line.startsWith('+')) {
      newLineNo++;
      if (range.side === 'RIGHT' && newLineNo >= range.startLine && newLineNo <= range.endLine) {
        out.push({ kind: 'add', lineNo: newLineNo, text: line.slice(1) });
      }
    }
    else if (line.startsWith('-')) {
      oldLineNo++;
      if (range.side === 'LEFT' && oldLineNo >= range.startLine && oldLineNo <= range.endLine) {
        out.push({ kind: 'del', lineNo: oldLineNo, text: line.slice(1) });
      }
    }
    else {
      // Context line — present on both sides. Keep when the comment
      // side's line number is in range.
      newLineNo++;
      oldLineNo++;
      const lineNo = range.side === 'RIGHT' ? newLineNo : oldLineNo;
      if (lineNo >= range.startLine && lineNo <= range.endLine) {
        out.push({ kind: 'ctx', lineNo, text: line.startsWith(' ') ? line.slice(1) : line });
      }
    }
  }
  return out;
}

/**
 * Renders a comment body, replacing GitHub `\`\`\`suggestion ... \`\`\``
 * blocks with a mini-diff that shows the original line as `-` and the
 * suggestion as `+`, GitHub-style. Non-suggestion content renders as
 * normal markdown.
 */
export function CommentBodyWithSuggestions({ body, hunk }: { body: string; hunk: string | null }) {
  // Split the body around suggestion blocks: capturing group keeps the
  // suggestion content so we can render it specially. Markdown outside
  // suggestion blocks goes through marked() unchanged.
  const re = /```suggestion\n([\s\S]*?)```/g;
  const parts: ({ kind: 'md'; text: string } | { kind: 'suggestion'; content: string })[] = [];
  let lastIdx = 0;
  let m: RegExpExecArray | null;
  while ((m = re.exec(body)) !== null) {
    if (m.index > lastIdx) parts.push({ kind: 'md', text: body.slice(lastIdx, m.index) });
    parts.push({ kind: 'suggestion', content: m[1].replace(/\n$/, '') });
    lastIdx = m.index + m[0].length;
  }
  if (lastIdx < body.length) parts.push({ kind: 'md', text: body.slice(lastIdx) });
  if (parts.length === 0) return null;
  return (
    <>
      {parts.map((p, i) => {
        if (p.kind === 'md') {
          if (!p.text.trim()) return null;
          return <MarkdownWithPermalinks key={i} body={p.text} />;
        }
        const oldLine = hunk ? lastTouchedLine(hunk) : '';
        return (
          <div key={i} className="prc-suggestion">
            <div className="prc-suggestion__head">Suggested change</div>
            <pre className="prc-suggestion__diff">
              {oldLine ? <div className="diff-hunk-line diff-hunk-line--del">-{oldLine}</div> : null}
              {p.content.split('\n').map((line, j) => (
                <div key={j} className="diff-hunk-line diff-hunk-line--add">+{line || ' '}</div>
              ))}
            </pre>
          </div>
        );
      })}
    </>
  );
}
