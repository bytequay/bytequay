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
 */
export function renderMarkdown(text: string | null | undefined): string {
  if (!text) return '';
  const normalised = text.replace(/\r\n/g, '\n');
  return marked.parse(normalised, { gfm: true, breaks: true, async: false }) as string;
}
