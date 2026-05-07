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
export function renderMarkdown(text: string | null | undefined): string {
  if (!text) return '';
  const normalised = text.replace(/\r\n/g, '\n');
  const html = marked.parse(normalised, { gfm: true, breaks: true, async: false }) as string;
  return decorateRefsAndMentions(html);
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
function decorateRefsAndMentions(html: string): string {
  if (!html || typeof document === 'undefined') return html;
  if (!html.includes('@') && !html.includes('#')) return html;
  const container = document.createElement('div');
  container.innerHTML = html;
  decorateNode(container);
  return container.innerHTML;
}

const SKIP_TAGS = new Set(['A', 'CODE', 'PRE', 'STYLE', 'SCRIPT', 'TEXTAREA']);
const REF_RE = /(?<=^|[\s(\[])(@[A-Za-z0-9][A-Za-z0-9-]{0,38}|#\d{1,8})(?=$|[\s.,;:!?)\]])/g;

function decorateNode(node: Node): void {
  if (node.nodeType === Node.ELEMENT_NODE) {
    if (SKIP_TAGS.has((node as Element).tagName)) return;
    // Snapshot children before mutating — replaceChild during iteration
    // breaks a live NodeList.
    for (const child of Array.from(node.childNodes)) decorateNode(child);
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
    span.className = m[0].startsWith('@') ? 'md-ref-mention' : 'md-ref-issue';
    span.textContent = m[0];
    frag.appendChild(span);
    pos = end;
  }
  if (pos < text.length) frag.appendChild(document.createTextNode(text.slice(pos)));
  node.parentNode?.replaceChild(frag, node);
}
