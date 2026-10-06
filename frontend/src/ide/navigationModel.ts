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
import type { BlameSpan } from './contracts';

export function blameAt(spans: readonly BlameSpan[], line: number): BlameSpan | undefined {
  let left = 0;
  let right = spans.length;
  while (left < right) {
    const middle = (left + right) >>> 1;
    if (spans[middle].line <= line) left = middle + 1;
    else right = middle;
  }
  const span = spans[left - 1];
  return span && line < span.line + span.count ? { ...span, line, count: 1, originalLine: span.originalLine + line - span.line } : undefined;
}

export const uncommitted = (span: BlameSpan) => /^0+$/.test(span.commit);
const escapeHtml = (value: string) => value.replace(/[&<>"']/g, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character] ?? character);
export const blameDate = (span: BlameSpan) => new Date(span.authoredAt).toLocaleDateString(undefined, { year: '2-digit', month: '2-digit', day: '2-digit' });

/** Monaco's custom line-number renderer accepts HTML, including repository-controlled author names. */
export function blameLine(spans: readonly BlameSpan[], line: number): string {
  const span = blameAt(spans, line);
  if (!span) return String(line);
  const local = uncommitted(span);
  const hue = Number.parseInt(span.commit.slice(0, 6), 16) % 360;
  const label = local ? 'Uncommitted' : `${blameDate(span)}  ${span.author}`;
  const title = local ? 'Uncommitted changes' : `${span.author} · ${blameDate(span)} · ${span.commit.slice(0, 8)} · ${span.summary}`;
  return `<span class="ide-blame-line${local ? ' is-uncommitted' : ''}" style="--blame-hue:${hue}" title="${escapeHtml(title)}">${escapeHtml(label)}</span><span class="ide-blame-number">${line}</span>`;
}

/** Match the overview's document position, including folded lines and review-card view zones. */
export function overviewLine(fraction: number, scrollHeight: number, lineCount: number, top: (line: number) => number): number {
  const target = Math.max(0, Math.min(1, fraction)) * scrollHeight;
  let left = 1;
  let right = lineCount;
  while (left < right) {
    const middle = Math.ceil((left + right) / 2);
    if (top(middle) <= target) left = middle;
    else right = middle - 1;
  }
  return left;
}
