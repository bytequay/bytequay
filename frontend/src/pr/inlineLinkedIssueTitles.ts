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
import type { LinkedIssueDto } from '../types';

/**
 * Walks every {@code <a>} in already-rendered description HTML and, when
 * the link points at a known linked issue (matched by GitHub URL),
 * replaces the link text with {@code #N <title>} — matching GitHub's web
 * treatment where {@code Fixes https://github.com/.../issues/1234} reads
 * as {@code Fixes #1234 Store extended statistics filename} instead of
 * the bare URL.
 *
 * Same-repo only — the backend's resolver doesn't follow cross-repo
 * URLs yet, so anything not in {@code linkedIssues} is left as the
 * markdown rendered it. The {@code <a>} element keeps its href, so
 * clicks still navigate to the issue page.
 */
export function inlineLinkedIssueTitles(html: string, linkedIssues: LinkedIssueDto[]): string {
  if (!html || linkedIssues.length === 0 || typeof document === 'undefined') return html;
  const byUrl = new Map(linkedIssues.map(li => [li.htmlUrl, li]));
  const container = document.createElement('div');
  container.innerHTML = html;
  container.querySelectorAll('a[href]').forEach(a => {
    const href = a.getAttribute('href') ?? '';
    const match = byUrl.get(href);
    if (match) {
      a.textContent = `#${match.number} ${match.title}`;
    }
  });
  return container.innerHTML;
}
