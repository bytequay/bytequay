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
import type { ContextBuildViewDto } from '../repository-context/contracts';
import type { SpecCheckData } from '../repository-context/ContextSpecCheck';
import type { HarvestLastRunDto, MemoryDto, MemoryPageDto } from './contracts';
import { LearnCard } from './MemoryPage';

const memory: MemoryDto = {
  id: 'm31', kind: 'RULE', title: 'Connector session properties', text: 'When a connector adds a session property, give it a catalog property too.',
  scope: ['plugin/'],
  sources: [{ kind: 'PULL_REQUEST', url: 'https://github.com/owner/repository/pull/31188', quote: 'Please add a catalog property too.', at: '2026-10-01T00:00:00Z',
    section: null, pullRequest: { number: 31188, part: 'REVIEW_COMMENT', author: 'alice', maintainer: true, merged: true, resolved: true, outdated: false },
    user: null, command: null }],
  origin: { user: false, description: 'Learn from my PRs', taskId: 'task' }, version: 1, previous: null, delivered: 0, cited: 0, reports: [], missingScope: [],
  createdAt: '2026-10-02T00:00:00Z', updatedAt: '2026-10-02T00:00:00Z',
};
const idle: ContextBuildViewDto = { buildRequestSequence: 3, canStart: true, build: null };
const page = (last: HarvestLastRunDto | null, cursor: number | null = null): MemoryPageDto => ({ memories: [memory], switches: { reviews: true, development: true },
  guidelines: [], head: null, harvest: { cursor: cursor === null ? null : { number: cursor, closedAt: '2026-10-01T00:00:00Z' }, last } });
const data = (build: ContextBuildViewDto): SpecCheckData => ({ workspaceId: 'w', repository: 'owner/repository', checks: [], build, now: 0,
  model: { label: 'Codex · Sol', blocker: null } });
const last = (outcome: HarvestLastRunDto['outcome']): HarvestLastRunDto => ({ harvestId: 'h', label: 'Learn from my PRs', startedAt: '2026-10-02T00:00:00Z',
  endedAt: null, pulls: 12, threads: 31, saved: ['m31'], seenAgain: 2, replaced: 1, outcome });
const render = (value: MemoryPageDto, build: ContextBuildViewDto) => renderToStaticMarkup(<LearnCard page={value} data={data(build)} busy={false}
  onLearn={() => {}} onSearch={async () => []} onStop={() => {}} onDelete={() => {}} onOpen={() => {}} />);

describe('LearnCard', () => {
  it('offers Learn from my PRs and says how far earlier harvests read', () => {
    expect(render(page(null), idle)).toContain('Reads the newest closed pull requests you wrote or reviewed, up to 30');
    const markup = render(page(null, 31201), idle);
    expect(markup).toContain('Learned through #31201');
    expect(markup).toContain('Learn from my PRs</button>');
    expect(markup).toContain('Starts 1 run · reads the maintainer threads it finds and saves rules · Codex · Sol');
    expect(markup).toContain('Learn from one closed pull request<input type="search"');
    expect(markup).toContain('placeholder="Number, link or title words"');
  });

  it('lists what the last harvest saved, with Delete, and its counts', () => {
    const markup = render(page(last('DONE'), 31240), idle);
    expect(markup).toContain('Learn from my PRs read 31 maintainer threads in 12 pull requests');
    expect(markup).toContain(' · 1 new');
    expect(markup).toContain(' · 2 seen again');
    expect(markup).toContain(' · 1 replaced');
    expect(markup).toContain('>Connector session properties</button>');
    expect(markup).toContain('>Delete</button>');
    expect(render(page(last('NOTHING')), idle)).toContain('found no new maintainer threads in 12 pull requests');
    expect(render(page(last('FAILED')), idle)).toContain('did not finish; what it saved is kept');
  });

  it('follows a running harvest with its activity and Stop', () => {
    const running: ContextBuildViewDto = { buildRequestSequence: 4, canStart: false, build: { kind: 'HARVEST', requestKey: 'k', taskId: 'task', checkId: 'h',
      task: null, activeTurn: { cancellationRequestedAt: null }, pendingApprovals: 0, outcome: 'RUNNING', detail: null,
      activity: [{ at: '2026-10-02T00:00:00Z', text: 'Saved m31 · When a connector adds a session property', tone: 'DONE' }] } };
    const markup = render(page(last('RUNNING')), running);
    expect(markup).toContain('reading 31 maintainer threads in 12 pull requests');
    expect(markup).toContain('Saved m31');
    expect(markup).toContain('>Stop</button>');
    expect(markup).not.toContain('Learn from my PRs</button>');
    expect(markup).not.toContain('Learn from one closed pull request');
  });
});
