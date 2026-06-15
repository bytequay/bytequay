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
import { marked, Renderer } from 'marked';

/** Unified-diff hunk header, e.g. {@code @@ -140,20 +140,16 @@}. Its
 *  presence is the unambiguous signal that a fenced block is a diff
 *  even when the agent fenced it as plain ``` with no language. */
const DIFF_HUNK_RE = /^@@ -\d+(?:,\d+)? \+\d+(?:,\d+)? @@/m;

function isDiffBlock(code: string, infostring: string | undefined): boolean {
  const lang = (infostring ?? '').trim().split(/\s+/)[0].toLowerCase();
  if (lang === 'diff' || lang === 'patch') return true;
  return DIFF_HUNK_RE.test(code);
}

/** Map a diff line to its row class. Order matters: the {@code +++} /
 *  {@code ---} file markers must be caught before the bare {@code +} /
 *  {@code -} add/remove lines. */
function diffLineClass(line: string): string {
  if (line.startsWith('+++') || line.startsWith('---')) return 'bq-diff-meta';
  if (line.startsWith('@@')) return 'bq-diff-hunk';
  if (line.startsWith('+')) return 'bq-diff-add';
  if (line.startsWith('-')) return 'bq-diff-del';
  return 'bq-diff-ctx';
}

function escapeHtml(s: string): string {
  return s
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
}

/** Render a fenced diff as a `<pre class="bq-diff">` of per-line spans
 *  so CSS can paint added / removed / hunk / meta rows the same way the
 *  code-diff page does. Empty lines get a zero-width space so the row
 *  keeps its height. */
function renderDiffBlock(code: string): string {
  const rows = code.replace(/\n$/, '').split('\n').map(line => {
    const content = line.length === 0 ? '​' : escapeHtml(line);
    return `<span class="bq-diff-line ${diffLineClass(line)}">${content}</span>`;
  }).join('');
  return `<pre class="bq-diff"><code class="language-diff">${rows}</code></pre>`;
}

/** Shared renderer that special-cases diff code fences and otherwise
 *  defers to marked's default. Passed per-parse via options so we don't
 *  mutate the global marked singleton. */
function makeChatRenderer(): Renderer {
  const renderer = new Renderer();
  const baseCode = renderer.code.bind(renderer);
  renderer.code = (code: string, infostring: string | undefined, escaped: boolean): string =>
    isDiffBlock(code, infostring) ? renderDiffBlock(code) : baseCode(code, infostring, escaped);
  return renderer;
}

const diffAwareRenderer = makeChatRenderer();

/** Render a markdown string the same way GitHub does for PR comments:
 *  GFM rules (so triple-backtick fenced code blocks become <pre><code>)
 *  AND `breaks: true` so a single newline inside a paragraph becomes a
 *  <br>, matching GitHub's "soft line break" behaviour.
 *
 *  Use this for every PR-body / comment / AI-response render — bare
 *  `marked.parse(text)` calls dropped the breaks option and rendered
 *  fenced blocks like ```sql as inline code, leaving the literal
 *  backticks visible (see docs/mockups/issue/code-block.png).
 *
 *  Strips Windows \r\n → \n up front because some GitHub responses
 *  carry CRLF line endings depending on the user's git config and a
 *  stray \r at the end of a fence line stops marked from matching it.
 *
 *  Post-processes the rendered HTML to wrap bare `@user` mentions and
 *  `#N` issue references in themed chip spans — same treatment
 *  github.com gives them on its own pages.
 */
/**
 * Optional repo context the caller passes when the markdown comes
 * from a known repo. Used by {@link decorateRefsAndMentions} to stamp
 * {@code data-repo-owner} / {@code data-repo-name} on the {@code #N}
 * issue-reference chips so a future click delegate can resolve the
 * reference without re-parsing the surrounding URL.
 */
export type MarkdownRepoContext = { owner: string; repo: string };

export function renderMarkdown(text: string | null | undefined, repoContext?: MarkdownRepoContext): string {
  if (!text) return '';
  const normalised = text.replace(/\r\n/g, '\n');
  const html = marked.parse(
      normalised,
      { gfm: true, breaks: true, async: false, renderer: diffAwareRenderer }) as string;
  return decorateRefsAndMentions(html, repoContext);
}

/**
 * Chat-flavoured variant of {@link renderMarkdown}. Differs in one
 * place: {@code breaks: false}. AI-generated chat text already uses
 * blank lines to mark paragraphs and single newlines as soft wraps
 * inside one — the GitHub-style {@code breaks: true} would render
 * every soft wrap as a {@code <br>} and double the apparent line
 * spacing inside the bubble. Use this for the trunk + task chat
 * surfaces; keep the breaks-on default for PR / issue bodies the
 * user typed by hand on GitHub.
 */
export function renderChatMarkdown(text: string | null | undefined, repoContext?: MarkdownRepoContext): string {
  if (!text) return '';
  const normalised = text
      .replace(/\r\n/g, '\n')
      // Collapse runs of 2+ blank lines down to a single paragraph
      // break — agents occasionally double-blank between paragraphs
      // and marked emits an extra <p></p> in that case.
      .replace(/\n[ \t]*\n[ \t]*\n+/g, '\n\n')
      .trim();
  if (normalised.length === 0) return '';
  const html = marked.parse(
      normalised,
      { gfm: true, breaks: false, async: false, renderer: diffAwareRenderer }) as string;
  // Drop empty paragraphs marked emits for trailing whitespace —
  // they render as a full line of gap thanks to the bubble's
  // line-height and visually look like a missing message.
  const stripped = html
      .replace(/<p>\s*<\/p>/g, '')
      .replace(/<p>(?:\s|&nbsp;|<br\s*\/?>)*<\/p>/g, '');
  return decorateRefsAndMentions(stripped, repoContext);
}

/** Walks the rendered HTML, finds bare `@user` / `#N` tokens in text
 *  nodes, and wraps them in `<span class="md-ref-…">` chips. Skips
 *  text inside `<a>`, `<code>`, `<pre>` and friends so we don't
 *  double-decorate links or break code blocks.
 *
 *  Match rules:
 *  - Mention: `@` followed by GitHub's username charset (alphanum +
 *    hyphen, ≤39 chars), preceded by start-of-string or whitespace
 *    (so `me@example.com` won't match `@example`).
 *  - Issue: `#` followed by 1–8 digits, with the same boundary rule.
 *
 *  No-ops in non-DOM environments (SSR / unit tests without jsdom),
 *  so the renderer stays safe to call from anywhere.
 */
function decorateRefsAndMentions(html: string, repoContext?: MarkdownRepoContext): string {
  if (!html || typeof document === 'undefined') return html;
  if (!html.includes('@') && !html.includes('#')) return html;
  const container = document.createElement('div');
  container.innerHTML = html;
  decorateNode(container, repoContext);
  return container.innerHTML;
}

const SKIP_TAGS = new Set(['A', 'CODE', 'PRE', 'STYLE', 'SCRIPT', 'TEXTAREA']);
const REF_RE = /(?<=^|[\s([])(@[A-Za-z0-9][A-Za-z0-9-]{0,38}|#\d{1,8})(?=$|[\s.,;:!?)\]])/g;

function decorateNode(node: Node, repoContext?: MarkdownRepoContext): void {
  if (node.nodeType === Node.ELEMENT_NODE) {
    if (SKIP_TAGS.has((node as Element).tagName)) return;
    // Snapshot children before mutating — replaceChild during iteration
    // breaks a live NodeList.
    for (const child of Array.from(node.childNodes)) decorateNode(child, repoContext);
    return;
  }
  if (node.nodeType !== Node.TEXT_NODE) return;
  const text = node.textContent ?? '';
  if (!text || (!text.includes('@') && !text.includes('#'))) return;
  const matches = Array.from(text.matchAll(REF_RE));
  if (matches.length === 0) return;
  const frag = document.createDocumentFragment();
  let pos = 0;
  for (const m of matches) {
    const start = m.index ?? 0;
    const end = start + m[0].length;
    if (start > pos) frag.appendChild(document.createTextNode(text.slice(pos, start)));
    const span = document.createElement('span');
    const isIssue = !m[0].startsWith('@');
    span.className = isIssue ? 'md-ref-issue' : 'md-ref-mention';
    span.textContent = m[0];
    // Stamp the originating repo on issue chips so a future click
    // delegate can resolve `#N` without re-parsing the URL the comment
    // came from. Mentions don't need the context (a `@user` reference
    // is global on GitHub).
    if (isIssue && repoContext) {
      span.dataset.repoOwner = repoContext.owner;
      span.dataset.repoName = repoContext.repo;
    }
    frag.appendChild(span);
    pos = end;
  }
  if (pos < text.length) frag.appendChild(document.createTextNode(text.slice(pos)));
  node.parentNode?.replaceChild(frag, node);
}
