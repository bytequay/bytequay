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
import { describe, expect, it } from 'vitest';
import { specCheckDirectory, type ContextSpecCandidateDto, type ContextSpecCheckDto } from './contracts';
import { checkSummary, codeLink, issueUrl, openFindings, specAddress } from './specChecks';

const candidate = (id: string, type: ContextSpecCandidateDto['type'], status: ContextSpecCandidateDto['status'],
  patch: Partial<ContextSpecCandidateDto> = {}): ContextSpecCandidateDto => ({ id, type, predicted: type === 'BUG' ? 'DIVERGES' : type === 'FEATURE_REQUEST' ? 'REFUSES' : 'SKIPS_SHOULD',
  title: `Gap ${id}`, rule: 'MUST x', specUrl: 'https://example.com/spec#x', code: ['a/B.java:1-2'], reasoning: 'why', severity: 2, duplicate: null, status, test: null,
  reason: null, draft: null, dismissed: null, ...patch });
const check = (candidates: ContextSpecCandidateDto[]): ContextSpecCheckDto => ({ id: 'check-1', directory: 'plugin/trino-delta-lake/', baselineSha: 'a'.repeat(40),
  requestKey: 'key', specs: ['https://example.com/spec'],
  checkedAgainst: ['https://example.com/spec'], labels: [],
  state: 'DONE', candidates, covered: [], next: [], reproductions: 2, detail: null, startedAt: '2026-10-01T00:00:00Z', endedAt: '2026-10-01T01:00:00Z' });

describe('spec check report', () => {
  it('counts confirmed gaps by kind, then what the check could not use', () => {
    const reported = candidate('c5', 'BUG', 'ALREADY_REPORTED', { duplicate: { number: 31252, title: 'Report', state: 'open', url: 'https://github.com/o/r/issues/31252' } });
    expect(checkSummary(check([candidate('c1', 'BUG', 'CONFIRMED'), candidate('c2', 'BUG', 'CONFIRMED'), candidate('c3', 'FEATURE_REQUEST', 'CONFIRMED'),
      candidate('c4', 'IMPROVEMENT', 'NOT_REPRODUCED'), reported])))
      .toBe('2 bugs and 1 feature request confirmed · 1 not reproduced · 1 already reported (#31252)');
    expect(checkSummary(check([candidate('c1', 'BUG', 'REFUTED')]))).toBe('No gap confirmed · 1 refuted');
  });

  it('lists only confirmed findings nobody dismissed', () => {
    const value = check([candidate('c1', 'BUG', 'CONFIRMED'), candidate('c2', 'BUG', 'CONFIRMED', { dismissed: 'Filed on GitHub' }),
      candidate('c3', 'BUG', 'NOT_REPRODUCED')]);
    expect(openFindings(value).map(item => item.id)).toEqual(['c1']);
  });
});

describe('spec check directory', () => {
  it('takes a repository directory with or without its slashes and refuses one that escapes it', () => {
    expect(['plugin/trino-iceberg', '/plugin/trino-iceberg/', ' plugin/trino-iceberg// '].map(specCheckDirectory))
      .toEqual(['plugin/trino-iceberg/', 'plugin/trino-iceberg/', 'plugin/trino-iceberg/']);
    expect(['', '/', 'plugin/../core', './plugin', 'plugin//iceberg', 'C:\\plugin', '-plugin'].map(specCheckDirectory)).toEqual([null, null, null, null, null, null, null]);
  });
});

describe('spec check links', () => {
  it('pre-fills GitHub’s form and cuts a body too long for the address', () => {
    const draft = { title: 'Checkpoint keys removes by path only', body: 'Summary\n\n### Specification\n> MUST key by path and deletion vector', labels: ['bug', 'delta-lake'] };
    const short = issueUrl('trinodb/trino', draft);
    const url = new URL(short.url);
    expect(short.cut).toBe(false);
    expect(url.origin + url.pathname).toBe('https://github.com/trinodb/trino/issues/new');
    expect([url.searchParams.get('title'), url.searchParams.get('body'), url.searchParams.get('labels')]).toEqual([draft.title, draft.body, 'bug,delta-lake']);
    const long = issueUrl('trinodb/trino', { ...draft, body: 'x'.repeat(40_000), labels: [] });
    expect(long.cut).toBe(true);
    expect(long.url.length).toBeLessThanOrEqual(7500);
    expect(new URL(long.url).searchParams.get('body')).toMatch(/^x+\n\n… The full report is on the clipboard; paste it over this one\.$/);
    expect(new URL(long.url).searchParams.has('labels')).toBe(false);
  });

  it('links code lines at the check’s baseline and takes only HTTPS spec addresses', () => {
    expect(codeLink('o/r', 'abc', 'core/A.java:12-40')).toBe('https://github.com/o/r/blob/abc/core/A.java#L12-L40');
    expect(codeLink('o/r', 'abc', 'core/A.java:7')).toBe('https://github.com/o/r/blob/abc/core/A.java#L7');
    expect(specAddress(' https://iceberg.apache.org/spec/#manifests ')).toBe('https://iceberg.apache.org/spec/');
    expect([specAddress('http://iceberg.apache.org/spec/'), specAddress('https://example.com/spec?v=2'), specAddress('not a url')]).toEqual([null, null, null]);
  });
});
