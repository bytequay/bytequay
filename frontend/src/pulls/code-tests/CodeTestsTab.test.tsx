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
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { parseAgentTask } from '../../agents/contracts';
import contracts from '../../agents/fixtures/agent-contracts-v1.json';
import type { PullRequestDetailDto } from '../../types';
import { reviewFixture } from '../agent-review/fixtures/review';
import CodeTestsTab, { type CodeTestsBridge } from './CodeTestsTab';

const detail = { repo: 'trinodb/trino', number: 30304 } as PullRequestDetailDto;

function bridge(): CodeTestsBridge {
  return {
    listCodeFiles: vi.fn(),
    readCodeFile: vi.fn(),
    listCodeRuns: vi.fn(),
    stopCodeRun: vi.fn(),
    readCodeRunOutput: vi.fn(),
    openCodeTerminal: vi.fn(),
    writeReviewTerminal: vi.fn(),
    resizeReviewTerminal: vi.fn(),
    onReviewTerminalEvent: vi.fn(),
    listAgentReviews: vi.fn(),
    prepareReviewWorktree: vi.fn(),
    getAgentReview: vi.fn(),
    subscribeAgentReview: vi.fn(),
    getAgentTask: vi.fn(),
  };
}

describe('CodeTestsTab', () => {
  it('asks for a watched repository before anything can be read', () => {
    const html = renderToStaticMarkup(<CodeTestsTab detail={detail} workspaceId={undefined} review={null}
      onReviewChange={() => {}} active ownReviewStream bridge={bridge()} />);
    expect(html).toContain('Code &amp; Tests needs a watched repository');
    expect(html).not.toContain('Start review');
    expect(html).not.toContain('Create worktree to explore');
  });

  it('offers exploration with a memory notice before starting any review', () => {
    const html = renderToStaticMarkup(<CodeTestsTab detail={detail} workspaceId="workspace-1" review={null}
      onReviewChange={() => {}} active ownReviewStream bridge={bridge()} />);
    expect(html).toContain('No working copy yet');
    expect(html).toContain('Create worktree to explore');
    expect(html).toContain('local code server');
    expect(html).toContain('additional memory');
    expect(html).not.toContain('>Start review</button>');
  });

  it('checks for a ready IDE for an active review', () => {
    const html = renderToStaticMarkup(<CodeTestsTab detail={detail} workspaceId="workspace-1" review={reviewFixture()}
      onReviewChange={() => {}} active ownReviewStream bridge={bridge()} />);
    expect(html).toContain('Checking code tools');
    expect(html).toContain('ct-ide-surface');
    expect(html).not.toContain('ct-code');
  });

  it('opens code before a PR exists, including for a workflow task', () => {
    const task = { ...parseAgentTask(contracts.task), interactive: false };
    const html = renderToStaticMarkup(<CodeTestsTab workspaceId="workspace-1" review={null} task={task}
      onReviewChange={() => {}} active ownReviewStream={false} bridge={bridge()} />);
    expect(html).toContain('aria-label="Find file"');
    expect(html).not.toContain('Start review');
  });

  it('styles an unplaced review card like one placed in the code', () => {
    const css = readFileSync(resolve(process.cwd(), 'src/css/code-tests.css'), 'utf8');
    // A descendant selector here reached into the card and restyled its own summary and comment body.
    expect(css).not.toMatch(/\.ct-unplaced (summary|p) \{/);
    expect(css).toMatch(/\.ct-unplaced > summary \{[^}]*list-style: none/);
    expect(css).toMatch(/\.ct-inline-card, \.ct-unplaced__card \{[^}]*font-size: var\(--ui-font-size\)/);
  });

  it('sizes a review card beside code from the one rule the diff uses, not its own', () => {
    const pulls = readFileSync(resolve(process.cwd(), 'src/css/pulls.css'), 'utf8');
    const rule = /:is\(([^)]*)\) :is\(([^)]*)\) \{([^}]*)\}/.exec(pulls.slice(pulls.indexOf('/* Every review card hosted')));
    expect(rule).not.toBeNull();
    // Both Code & Tests hosts size the card through the diff's rule; a second copy is what let them drift apart.
    for (const host of ['.pull-diff-code', '.pull-diff-threads', '.ct-inline-card', '.ct-unplaced__card']) expect(rule?.[1]).toContain(host);
    for (const card of ['.lrc', '.agr-finding']) expect(rule?.[2]).toContain(card);
    expect(rule?.[3]).toMatch(/max-width: 780px/);
    expect(rule?.[3]).toMatch(/margin-left: 0/);
    const agentReview = readFileSync(resolve(process.cwd(), 'src/css/agent-review.css'), 'utf8');
    expect(agentReview).not.toMatch(/\.agr-finding \{[^}]*max-width/);
  });

  it('reads the owning development task\'s working copy instead of any review, with no Start review or Discard', () => {
    const task = { ...parseAgentTask(contracts.task), interactive: true };
    const html = renderToStaticMarkup(<CodeTestsTab detail={detail} workspaceId="workspace-1" review={reviewFixture()} task={task}
      onReviewChange={() => {}} active ownReviewStream bridge={bridge()} />);
    expect(html).toContain('Checking code tools');
    expect(html).not.toContain('Start review');
    expect(html).not.toContain('Discard');
    expect(html).not.toContain('Back to reviews');
  });
});
