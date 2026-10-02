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
  specs: ['https://iceberg.apache.org/spec/'], checkedAgainst: [], labels: [], state: 'CHECKING', candidates: [], covered: [], next: [], reproductions: 0,
  detail: null, startedAt: '2026-10-02T00:00:00Z', endedAt: null };
const running: ContextBuildViewDto = { buildRequestSequence: 1, canStart: false, build: { requestKey: 'key', taskId: 'task', checkId: 'check-1', task: null,
  activeTurn: { cancellationRequestedAt: null }, pendingApprovals: 0, outcome: 'RUNNING', detail: null, activity: [] } };
const data = (checks: ContextSpecCheckDto[], build: ContextBuildViewDto): SpecCheckData => ({ workspaceId: 'workspace', repository: 'trinodb/trino', checks, build,
  model: { label: 'Codex · Sol', blocker: null }, now: Date.parse('2026-10-02T01:00:00Z') });

describe('spec check drawer', () => {
  it('asks for a directory before it lists any page to check against', () => {
    const markup = renderToStaticMarkup(<SpecCheckDrawer data={data([], { buildRequestSequence: 0, canStart: true, build: null })} actions={actions} busy={false}
      directory={null} onClose={() => {}} />);
    expect(markup).toContain('aria-label="Directory"');
    expect(markup).toContain('Find pages');
    expect(markup).not.toContain('Check against spec');
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
