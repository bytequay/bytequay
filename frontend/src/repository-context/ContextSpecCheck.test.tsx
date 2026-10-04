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
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { ContextBuildViewDto, ContextSpecCheckDto } from './contracts';
import { SpecCheckDrawer, type SpecCheckActions, type SpecCheckData } from './ContextSpecCheck';

const actions: SpecCheckActions = { start: () => {}, stop: () => {}, dismissFinding: () => {}, openIssue: () => {}, say: () => {} };
const check: ContextSpecCheckDto = { id: 'check-1', directory: 'plugin/trino-iceberg/', baselineSha: 'a'.repeat(40), requestKey: 'key',
  specs: ['https://iceberg.apache.org/spec/'], checkedAgainst: [], labels: [], state: 'CHECKING', candidates: [], covered: [], next: [], reproductions: 0, maxReproductions: 3,
  detail: null, startedAt: '2026-10-02T00:00:00Z', endedAt: null };
const running: ContextBuildViewDto = { buildRequestSequence: 1, canStart: false, build: { kind: 'CHECK', requestKey: 'key', taskId: 'task', checkId: 'check-1', task: null,
  activeTurn: { cancellationRequestedAt: null }, pendingApprovals: 0, outcome: 'RUNNING', detail: null, activity: [] } };
const data = (checks: ContextSpecCheckDto[], build: ContextBuildViewDto): SpecCheckData => ({ workspaceId: 'workspace', repository: 'trinodb/trino', checks, build,
  model: { label: 'Codex · Sol', blocker: null }, now: Date.parse('2026-10-02T01:00:00Z') });

describe('spec check drawer', () => {
  it('lists the repository’s modules to pick from before it lists any page to check against', () => {
    const markup = renderToStaticMarkup(<SpecCheckDrawer data={data([], { buildRequestSequence: 0, canStart: true, build: null })} actions={actions} busy={false}
      directory={null} onClose={() => {}} />);
    expect(markup).toContain('aria-label="Filter modules"');
    expect(markup).toContain('Listing the repository’s modules…');
    expect(markup).not.toContain('Check against spec');
  });

  it('lets the user pick how many candidates a new check reproduces, three by default', () => {
    const markup = renderToStaticMarkup(<SpecCheckDrawer data={data([], { buildRequestSequence: 0, canStart: true, build: null })} actions={actions} busy={false}
      directory="plugin/trino-delta-lake/" onClose={() => {}} />);
    expect(markup).toContain('aria-label="Reproductions"');
    expect(markup).toContain('<option value="3" selected="">3</option><option value="8">8</option>');
    expect(markup).toContain('then up to 3 reproduction turns that write and run tests in its worktree');
  });

  it('starts a check a Goal card asked for with its reproduction count, rounded up to a limit the form offers', () => {
    const markup = renderToStaticMarkup(<SpecCheckDrawer data={data([], { buildRequestSequence: 0, canStart: true, build: null })} actions={actions} busy={false}
      directory="plugin/trino-delta-lake/" prefill={{ specs: ['https://github.com/delta-io/delta/blob/master/PROTOCOL.md'], reproductions: 5 }} onClose={() => {}} />);
    expect(markup).toContain('<option value="3">3</option><option value="8" selected="">8</option>');
    expect(markup).toContain('then up to 8 reproduction turns');
  });

  it('marks the specifications the check proposed because the code does not link them', () => {
    const done: ContextSpecCheckDto = { ...check, state: 'DONE', endedAt: '2026-10-02T00:30:00Z', specs: [],
      checkedAgainst: ['https://iceberg.apache.org/spec/'] };
    const markup = renderToStaticMarkup(<SpecCheckDrawer data={data([done], { buildRequestSequence: 1, canStart: true, build: null })} actions={actions}
      busy={false} directory="plugin/trino-iceberg/" onClose={() => {}} />);
    expect(markup).toContain('Checked against https://iceberg.apache.org/spec/ (proposed by the check).');
  });

  it('shows a running check’s report with Stop and offers no second check while it runs', () => {
    const markup = renderToStaticMarkup(<SpecCheckDrawer data={data([check], running)} actions={actions} busy={false} directory="plugin/trino-iceberg/"
      onClose={() => {}} />);
    expect(markup).toContain('Spec check · plugin/trino-iceberg/');
    expect(markup).toContain('Checking');
    expect(markup).toContain('Stop check');
    expect(markup).not.toContain('WHAT THE CHECK READS');
  });
});
