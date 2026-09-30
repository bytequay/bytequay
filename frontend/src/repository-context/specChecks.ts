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
import type { ContextSpecCandidateDto, ContextSpecCheckDto, ContextSpecSourceDto } from './contracts';
import { plural } from './model';

export const MAX_SPECS = 10;
// ponytail: GitHub rejects very long addresses; a longer draft is cut in the form and copied whole to the clipboard.
const MAX_ISSUE_URL = 7500;

export const TYPE_LABEL: Record<ContextSpecCandidateDto['type'], string> = { BUG: 'Bug', FEATURE_REQUEST: 'Feature request', IMPROVEMENT: 'Improvement' };
export const TYPE_TONE: Record<ContextSpecCandidateDto['type'], 'red' | 'purple' | 'blue'> = { BUG: 'red', FEATURE_REQUEST: 'purple', IMPROVEMENT: 'blue' };
export const CHECK_STATE: Record<ContextSpecCheckDto['state'], { label: string; tone: 'blue' | 'green' | 'gray' | 'red' }> = {
  CHECKING: { label: 'Checking', tone: 'blue' }, REPRODUCING: { label: 'Reproducing', tone: 'blue' }, DONE: { label: 'Done', tone: 'green' },
  STOPPED: { label: 'Stopped', tone: 'gray' }, FAILED: { label: 'Failed', tone: 'red' },
};
export const DISMISS_REASONS = ['Filed on GitHub', 'Not a real gap', 'Not worth filing'] as const;

/** A topic's latest check in the current plan; a new plan's topics start without one. */
export function topicCheck(checks: ContextSpecCheckDto[], planId: string, topicId: string): ContextSpecCheckDto | null {
  return checks.find(check => check.planId === planId && check.topicId === topicId) ?? null;
}

/** Confirmed gaps the user has not dismissed: the ones to file. */
export function openFindings(check: ContextSpecCheckDto | null): ContextSpecCandidateDto[] {
  return check === null ? [] : check.candidates.filter(candidate => candidate.status === 'CONFIRMED' && candidate.dismissed === null);
}

/** "2 bugs and 3 feature requests confirmed · 4 not reproduced · 1 already reported (#31252)" */
export function checkSummary(check: ContextSpecCheckDto): string {
  const of = (status: ContextSpecCandidateDto['status']) => check.candidates.filter(candidate => candidate.status === status);
  const confirmed = of('CONFIRMED');
  const kinds = (['BUG', 'FEATURE_REQUEST', 'IMPROVEMENT'] as const).map(type => [type, confirmed.filter(candidate => candidate.type === type).length] as const)
    .filter(([, count]) => count > 0)
    .map(([type, count]) => plural(count, TYPE_LABEL[type].toLowerCase()));
  const reported = of('ALREADY_REPORTED');
  const parts = [kinds.length === 0 ? 'No gap confirmed' : `${kinds.length === 1 ? kinds[0] : `${kinds.slice(0, -1).join(', ')} and ${kinds.at(-1)}`} confirmed`];
  if (of('NOT_REPRODUCED').length > 0) parts.push(`${of('NOT_REPRODUCED').length} not reproduced`);
  if (of('REFUTED').length > 0) parts.push(`${of('REFUTED').length} refuted`);
  if (reported.length > 0) parts.push(`${reported.length} already reported (${reported.slice(0, 3).map(candidate => `#${candidate.duplicate?.number}`).join(', ')})`);
  return parts.join(' · ');
}

/** Specs the drawer selects at first: the documents the code links and the topic's approved documentation, never reference code. */
export function defaultSpecs(sources: ContextSpecSourceDto[]): string[] {
  return sources.filter(source => source.kind === 'DOCUMENT').map(source => source.url).slice(0, MAX_SPECS);
}

/** A spec the user adds: a public HTTPS page without a query; its #section is dropped, since the check plans its own sections. */
export function specAddress(value: string): string | null {
  try {
    const url = new URL(value.trim());
    if (url.protocol !== 'https:' || url.search !== '' || url.username !== '' || url.port !== '') return null;
    url.hash = '';
    return url.toString();
  }
  catch {
    return null;
  }
}

/** A code location as its lines on GitHub at the check's baseline. */
export function codeLink(repository: string, sha: string, location: string): string {
  const match = /^(.+?):(\d+)(?:-(\d+))?$/.exec(location);
  if (match === null) return `https://github.com/${repository}/blob/${sha}/${location}`;
  return `https://github.com/${repository}/blob/${sha}/${match[1]}#L${match[2]}${match[3] === undefined ? '' : `-L${match[3]}`}`;
}

/** GitHub's new-issue form pre-filled with the draft; a body too long for the address is cut there, and cut says so. */
export function issueUrl(repository: string, draft: NonNullable<ContextSpecCandidateDto['draft']>): { url: string; cut: boolean } {
  const address = (body: string) => {
    const query = new URLSearchParams({ title: draft.title, body });
    if (draft.labels.length > 0) query.set('labels', draft.labels.join(','));
    return `https://github.com/${repository}/issues/new?${query.toString()}`;
  };
  if (address(draft.body).length <= MAX_ISSUE_URL) return { url: address(draft.body), cut: false };
  const note = '\n\n… The full report is on the clipboard; paste it over this one.';
  let low = 0;
  let high = draft.body.length;
  while (low < high) {
    const middle = Math.ceil((low + high) / 2);
    if (address(draft.body.slice(0, middle) + note).length <= MAX_ISSUE_URL) low = middle;
    else high = middle - 1;
  }
  return { url: address(draft.body.slice(0, low) + note), cut: true };
}
