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
import type { ContextSpecCandidateDto, ContextSpecCheckDto, ContextSpecModuleDto, ContextSpecSourceDto } from './contracts';
import { plural } from './model';

export const MAX_SPECS = 30;

/**
 * A request to show one module's spec check, as a Goal card makes it: the check's report, or with a prefill a new check's form filled with
 * the card's pages and reproduction count, which the user may change before starting it.
 */
export type SpecCheckRequest = { directory: string; prefill?: { specs: string[]; reproductions: number } };

/**
 * The linked pages a new check starts with: those the code cites in two sections or three times, which is how a specification shows,
 * and the ones the last check of the directory used. Another engine's source code is only for comparison, so the user adds it.
 */
export function defaultPicks(sources: ContextSpecSourceDto[], previous: ContextSpecCheckDto | null): string[] {
  return sources.filter(source => source.kind === 'PAGE' && (source.sections.length >= 2 || source.links >= 3 || previous?.checkedAgainst.includes(source.url) === true))
    .map(source => source.url);
}

/** A page's short name in a module row: its repository and file on GitHub, else its host and last path part. */
export function pageLabel(url: string): string {
  const { hostname, pathname } = new URL(url);
  const parts = pathname.split('/').filter(Boolean);
  const last = parts.at(-1);
  if (hostname === 'github.com' && parts.length >= 2) return `${parts[0]}/${parts[1]}${parts.length > 2 ? ` · ${last}` : ''}`;
  const host = hostname.replace(/^www\./, '');
  return last === undefined ? host : `${host} · ${last}`;
}

/** The modules whose path or page holds every word of the filter, in the order the backend ranked them. */
export function moduleRows(modules: ContextSpecModuleDto[], filter: string): ContextSpecModuleDto[] {
  const words = filter.trim().toLowerCase().split(/\s+/).filter(Boolean);
  return modules.filter(module => words.every(word => module.directory.toLowerCase().includes(word) || (module.page ?? '').toLowerCase().includes(word)));
}

export const TYPE_LABEL: Record<ContextSpecCandidateDto['type'], string> = { BUG: 'Bug', FEATURE_REQUEST: 'Feature request', IMPROVEMENT: 'Improvement' };
export const TYPE_TONE: Record<ContextSpecCandidateDto['type'], 'red' | 'purple' | 'blue'> = { BUG: 'red', FEATURE_REQUEST: 'purple', IMPROVEMENT: 'blue' };
export const CHECK_STATE: Record<ContextSpecCheckDto['state'], { label: string; tone: 'blue' | 'green' | 'gray' | 'red' }> = {
  CHECKING: { label: 'Checking', tone: 'blue' }, REPRODUCING: { label: 'Reproducing', tone: 'blue' }, DONE: { label: 'Done', tone: 'green' },
  STOPPED: { label: 'Stopped', tone: 'gray' }, FAILED: { label: 'Failed', tone: 'red' },
};
export const DISMISS_REASONS = ['Filed on GitHub', 'Not a real gap', 'Not worth filing'] as const;

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

/** The file a dismissed finding's rule is remembered for: its first code location in the checked directory, else its first. */
export function findingFile(directory: string, code: string[]): string | null {
  const paths = code.map(location => /^(.+?):\d+(?:-\d+)?$/.exec(location)?.[1] ?? location);
  return paths.find(path => path.startsWith(directory)) ?? paths[0] ?? null;
}
