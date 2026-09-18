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
    searchCodeSymbols: vi.fn(),
    readCodeOutline: vi.fn(),
    listCodeRuns: vi.fn(),
    startCodeRun: vi.fn(),
    stopCodeRun: vi.fn(),
    readCodeRunOutput: vi.fn(),
    saveCodeFile: vi.fn(),
    discardReviewCodeChanges: vi.fn(),
    openCodeTerminal: vi.fn(),
    writeReviewTerminal: vi.fn(),
    resizeReviewTerminal: vi.fn(),
    onReviewTerminalEvent: vi.fn(),
    listAgentReviews: vi.fn(),
    startAgentReview: vi.fn(),
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
  });

  it('offers Start review when the pull request has no working copy yet', () => {
    const html = renderToStaticMarkup(<CodeTestsTab detail={detail} workspaceId="workspace-1" review={null}
      onReviewChange={() => {}} active ownReviewStream bridge={bridge()} />);
    expect(html).toContain('No working copy yet');
    expect(html).toContain('Start review');
  });

  it('reads the review working copy with its file column, filter and history controls', () => {
    const html = renderToStaticMarkup(<CodeTestsTab detail={detail} workspaceId="workspace-1" review={reviewFixture()}
      onReviewChange={() => {}} active ownReviewStream bridge={bridge()} />);
    expect(html).toContain('aria-label="Go to file or symbol"');
    expect(html).not.toContain('>Tests</button>');
    expect(html).toContain('aria-pressed="false" title="Show only test files"');
    expect(html).toContain('aria-label="Back"');
    expect(html).toContain('aria-pressed="false" title="Every file in the repository"><span class="ct-scope__label">All files');
    expect(html).toContain('title="Only files this pull request or this working copy touched"><span class="ct-scope__label">Changed in PR</span><span class="ct-scope__count">');
    expect(html).toContain('Loading files…');
    expect(html).toContain('Open a file from the tree to read it whole.');
  });

  it('truncates the scope labels in a narrow files column instead of pushing its icons out', () => {
    const css = readFileSync(resolve(process.cwd(), 'src/css/code-tests.css'), 'utf8');
    expect(css).toMatch(/\.ct-scope button \{[^}]*min-width: 0/);
    expect(css).toMatch(/\.ct-scope__label \{[^}]*overflow: hidden;[^}]*text-overflow: ellipsis/);
    expect(css).toMatch(/\.ct-scope button\.ct-scope__tests \{[^}]*flex: none/);
    expect(css).toMatch(/\.ct-scope__count \{[^}]*flex: none/);
    expect(css).toMatch(/\.ct-icon-button \{[^}]*flex: none/);
  });

  it('reads the owning development task\'s working copy instead of any review, with no Start review or Discard', () => {
    const task = { ...parseAgentTask(contracts.task), interactive: true };
    const html = renderToStaticMarkup(<CodeTestsTab detail={detail} workspaceId="workspace-1" review={reviewFixture()} task={task}
      onReviewChange={() => {}} active ownReviewStream bridge={bridge()} />);
    expect(html).toContain('aria-label="Go to file or symbol"');
    expect(html).toContain('Loading files…');
    expect(html).not.toContain('Start review');
    expect(html).not.toContain('Discard');
    expect(html).not.toContain('Back to reviews');
  });
});
