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
import type { MemoryDto, MemoryKind, MemoryPart, MemorySourceDto } from './contracts';

export type MemoryKindFilter = 'ALL' | MemoryKind;
export type MemorySourceFilter = 'ANY' | 'PR' | 'USER' | 'PAGE';

/** Every page that changes memory tells the sidebar count to re-read it. */
export const MEMORY_CHANGED = 'bytequay:memory-changed';

export const KIND_LABEL: Record<MemoryKind, { one: string; many: string }> = { RULE: { one: 'Rule', many: 'Rules' }, SPEC: { one: 'Spec', many: 'Specs' } };

/** Why a memory needs the user: its latest report, and scope paths gone from the monitored head. A flag never hides the memory. */
export function memoryFlags(memory: MemoryDto): { title: string; detail: string }[] {
  const flags: { title: string; detail: string }[] = [];
  const report = memory.reports.at(-1);
  if (report !== undefined) flags.push({ title: 'Reported wrong', detail: report.reason });
  if (memory.missingScope.length > 0) {
    flags.push({ title: 'Scope gone', detail: `${memory.missingScope.join(', ')} ${memory.missingScope.length === 1 ? 'is' : 'are'} not at the monitored head` });
  }
  return flags;
}

export const flagged = (memory: MemoryDto): boolean => memoryFlags(memory).length > 0;

/** The row's source chip: "#31201 @alice" with "3 PRs", or the page it cites. */
export function sourceChip(memory: MemoryDto): { kind: 'PR' | 'PAGE'; label: string; more: string } {
  const first = memory.sources[0];
  if (first.pullRequest !== null) {
    const pullRequests = new Set(memory.sources.flatMap(source => source.pullRequest === null ? [] : [source.pullRequest.number])).size;
    return { kind: 'PR', label: `#${first.pullRequest.number} @${first.pullRequest.author}`,
      more: pullRequests > 1 ? `${pullRequests} PRs` : first.pullRequest.merged ? '' : 'closed' };
  }
  return { kind: 'PAGE', label: pageLabel(first.url), more: '' };
}

/** "github.com · PROTOCOL.md": the host and the page's last path segment. */
export function pageLabel(url: string): string {
  try {
    const address = new URL(url);
    const name = address.pathname.split('/').filter(Boolean).at(-1);
    return name === undefined ? address.host : `${address.host} · ${decodeURIComponent(name)}`;
  }
  catch {
    return url;
  }
}

const PART_LABEL: Record<MemoryPart, string> = { REVIEW_COMMENT: 'review comment', REVIEW: 'review', COMMENT: 'comment', CLOSE: 'closing comment' };

/** "PR #31201 · review comment · @alice, maintainer · merged", what shows the thread was applied, and the link's label. */
export function sourceHead(source: MemorySourceDto): { head: string; note: string; link: string } {
  const part = source.pullRequest;
  if (part === null) {
    return { head: ['Page', source.url.replace(/^https:\/\//, ''), source.section].filter(value => value !== null && value !== '').join(' · '), note: '', link: 'Open page' };
  }
  const head = `PR #${part.number} · ${PART_LABEL[part.part]} · @${part.author}${part.maintainer ? ', maintainer' : ''} · ${part.merged ? 'merged' : 'closed without merge'}`;
  const note = part.part === 'CLOSE' ? 'A maintainer explained the close'
    : part.resolved ? 'Applied: thread resolved' : part.outdated ? 'Applied: the code under the thread changed' : '';
  return { head, note, link: 'Open on GitHub' };
}

export function matches(memory: MemoryDto, kind: MemoryKindFilter, source: MemorySourceFilter, query: string): boolean {
  if (kind !== 'ALL' && memory.kind !== kind) return false;
  if (source === 'USER' && !memory.origin.user) return false;
  if (source === 'PR' && !memory.sources.some(item => item.pullRequest !== null)) return false;
  if (source === 'PAGE' && !memory.sources.some(item => item.kind === 'PAGE')) return false;
  const needle = query.trim().toLowerCase();
  return needle === '' || [memory.text, ...memory.scope, sourceChip(memory).label].join(' ').toLowerCase().includes(needle);
}

/** "now", "5h", "3d", "2w", "4mo": how long ago, in the list's narrow age column. */
export function age(at: string, now: number): string {
  const hours = Math.max(0, now - Date.parse(at)) / 3_600_000;
  if (Number.isNaN(hours)) return '';
  if (hours < 1) return 'now';
  if (hours < 24) return `${Math.floor(hours)}h`;
  const days = hours / 24;
  if (days < 14) return `${Math.floor(days)}d`;
  if (days < 60) return `${Math.floor(days / 7)}w`;
  return `${Math.floor(days / 30)}mo`;
}

/** "17 KB", rounded up, as the guideline list states a file's size. */
export const kilobytes = (bytes: number): string => `${Math.max(1, Math.ceil(bytes / 1024))} KB`;
