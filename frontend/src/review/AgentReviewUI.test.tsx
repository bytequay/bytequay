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
import { render, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { LocalPRBundle } from '../types/localPr';
import { createAgentReviewFixture, createVerificationStateFixture } from './agentReviewTestData';
import { AgentFindingContent, findingMarkdown, presentFinding } from './AgentEvidence';

function bundle(): LocalPRBundle {
  return {
    pr: {
      id: 'pr-1', taskId: null, branchName: 'feature', baseBranch: 'main', title: 'Preserve missing behavior',
      description: 'Fixture PR', status: 'remote-open', createdAt: 1, pushedAt: 1, remotePrNumber: 42,
      remotePrUrl: 'https://example.test/pr/42', mergedAt: null, closedAt: null, origin: 'external',
      repo: 'acme/widget', author: 'maria', syncedAt: 1, syncedAdditions: 2, syncedDeletions: 1,
      syncedMergeable: true, syncedMergeableState: 'clean', syncedMergeQueueEnabled: false,
      syncedMergeQueueState: null, branchDeletedAt: null,
    },
    commits: [{ id: 'c-1', localPrId: 'pr-1', sha: 'abcdef012345', message: 'change', additions: 2, deletions: 1, authoredAt: 1, pushedAt: 1 }],
    timeline: [], checks: [], comments: [],
  };
}

function fixture() {
  return createAgentReviewFixture(bundle(), [{
    filename: 'src/ChangedFile.ts', status: 'modified', additions: 2, deletions: 1,
    patch: '@@ -3,2 +3,3 @@\n-old\n+new\n context',
  }]);
}

describe('agent review UI', () => {
  it('adds conservative Markdown to legacy finding prose', () => {
    const prose = 'DynamicTrinoCatalog uses ConnectorIdentity.\n\nCould you clarify the intended behavior here? Keep `Session` isolated.';
    expect(findingMarkdown(prose)).toContain('`DynamicTrinoCatalog` uses `ConnectorIdentity`');
    expect(findingMarkdown(prose)).toContain('**Question:** Keep `Session` isolated.');
    expect(findingMarkdown('GitHub links stay prose.')).toBe('GitHub links stay prose.');
  });

  it.each([
    ['verified', /verified/],
    ['partially', /partially verified/],
    ['unknown', /unknown — asks author/],
    ['rejected', /rejected — dropped/],
  ] as const)('renders %s verifier chrome from fixture verification rows', (status, label) => {
    const data = createVerificationStateFixture(fixture(), status);
    const view = presentFinding(data, 'finding-1');
    expect(view).toBeDefined();
    if (view === undefined) return;
    const { container } = render(<AgentFindingContent view={view} body="Finding body" pending />);
    expect(within(container).getByText(label)).toBeTruthy();
  });

  it('derives the confidence ceiling from supporting evidence only', () => {
    const data = fixture();
    data.findings[0] = { ...data.findings[0], verification_status: 'partially' };
    data.evidence = data.evidence.map(row => row.finding_id !== 'finding-1' ? row : {
      ...row,
      strength_class: row.relation === 'SUPPORTS' ? 'E1' : 'E4',
    });
    const view = presentFinding(data, 'finding-1');
    expect(view).toBeDefined();
    if (view === undefined) return;
    const { container } = render(<AgentFindingContent view={view} body="Finding body" />);
    expect(container.querySelector('.agent-finding-chip.confidence')?.textContent).toContain('≤0.45');
  });
});
