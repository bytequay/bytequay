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
import { marked } from 'marked';
import { lastTouchedLine } from './utils';

/**
 * Renders a unified-diff hunk with GitHub-style coloring: green background
 * for added lines (`+`), red for removed (`-`), neutral for context, and
 * a muted blue tint for the `@@` hunk header. Input is the raw string
 * GitHub returns on review-thread.diffHunk.
 */
export function DiffHunk({ hunk }: { hunk: string }) {
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
          return (
            <div
              key={i}
              className="prc-comment-body"
              dangerouslySetInnerHTML={{ __html: marked.parse(p.text, { async: false }) as string }}
            />
          );
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
