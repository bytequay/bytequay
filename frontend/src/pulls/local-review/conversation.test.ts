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
import { expect, it } from 'vitest';
import { reviewFixture } from '../agent-review/fixtures/review';
import type { ReviewFindingDto } from '../agent-review/contracts';
import { findingConversation, findingFixRequest, localReviewConversation } from './conversation';
import type { LocalReviewThreadDto } from './types';

it('projects saved fix requests without depending on the current finding title or revision', () => {
  const review = reviewFixture({ sourceTaskId: 'developer' });
  const finding = { ...review.findings[0], revision: 2, title: 'Fix the analyzer\nand preserve ordering' };
  const instruction = 'Please address this finding.\n\nKeep the public API.';
  const message = `${instruction}\n\n${findingConversation(review, finding)}`;
  expect(findingFixRequest(message, 'developer')).toEqual({
    action: 'ByteQuay · Fix the agent review', topic: `#${finding.label} Fix the analyzer and preserve ordering`,
    body: instruction, detail: `${review.task.laneLabel} · ${finding.label} · revision 2`,
    target: { kind: 'finding', findingId: finding.id, runId: review.runs[0].id },
  });
  expect(findingFixRequest(message)).not.toBeNull();
  expect(findingFixRequest(message, 'another-task')).toBeNull();
});

it('links whole-change fixes even when the saved review scope is unavailable', () => {
  const review = reviewFixture({ sourceTaskId: 'developer', number: null, runs: [] });
  const finding: ReviewFindingDto = { ...review.findings[0], path: null, severity: null };
  expect(findingFixRequest(`Fix this.\n\n${findingConversation(review, finding)}`)?.target)
    .toEqual({ kind: 'finding', findingId: finding.id, runId: undefined });
});

it('leaves ordinary user text, copied findings and incomplete review context visible', () => {
  const review = reviewFixture({ sourceTaskId: 'developer' });
  const snapshot = findingConversation(review, review.findings[0]);
  for (const message of ['Fix agent review #F-1 please.', snapshot, `Look at this:\n\n${snapshot.replace('Private ByteQuay', 'Private other')}`,
    `Fix this.\n\n${snapshot.split('## Current finding')[0]}`, `Fix this.\n\n${snapshot.replace('Source development task: developer', '')}`]) {
    expect(findingFixRequest(message)).toBeNull();
  }
});

it('exports the selected finding, retained evidence, limitations and every follow-up without mixing conversations', () => {
  const review = reviewFixture();
  const finding = { ...review.findings[0], state: 'RESOLVED' as const, stateReason: 'Handled elsewhere', outdated: true, revision: 2 };
  review.runs[0] = { ...review.runs[0], coverage: { complete: false, omissions: ['Race not reproduced'],
    warnings: ['Integration test needs credentials'], recordedAt: finding.updatedAt } };
  review.runs.push({ ...review.runs[0], id: 'other-run', ordinal: 2, coverage: { complete: true, omissions: [],
    warnings: ['Unrelated warning'], recordedAt: finding.updatedAt } });
  review.queuedInputIds = ['queued-question'];
  review.messages = [...review.messages, ...Array.from({ length: 25 }, (_, index) => ({
    ...review.messages[index % 2], id: `follow-up-${index}`, findingId: finding.id, body: `Follow-up ${index}\n\n\`\`\`java\ncode\n\`\`\``,
    queuedInputId: index === 24 ? 'queued-question' : null,
  }))];
  const output = findingConversation(review, finding);
  for (const value of [review.repo, String(review.number), finding.path, finding.validatedHeadSha, finding.body,
    'RESOLVED · Outdated', finding.stateReason, 'Finding revision: 1', 'Queued', 'Race not reproduced', 'Integration test needs credentials',
    ...finding.evidence.flatMap(item => [item.ref, item.source, item.provenance, item.excerpt, item.explanation])]) {
    expect(output).toContain(value);
  }
  expect(output).toContain('25 saved messages');
  expect(output.indexOf('Follow-up 0\n')).toBeLessThan(output.indexOf('Follow-up 24\n'));
  expect(output).toContain('> Follow-up 24\n> \n> ```java\n> code\n> ```');
  expect(output).not.toContain(review.messages[0].body);
  expect(output).not.toContain(review.findings[1].body);
  expect(output).not.toContain('Unrelated warning');
});

it('keeps whole-change local findings and nested code fences readable without inventing PRs or lines', () => {
  const review = reviewFixture({ number: null, sourceTaskId: 'developer', runs: [] });
  const finding: ReviewFindingDto = { ...review.findings[0], path: null, side: null, startLine: null, endLine: null, range: null, excerpt: [],
    severity: null, evidence: [{ ...review.findings[0].evidence[0], excerpt: '```java\nexample\n```' }] };
  const output = findingConversation(review, finding);
  expect(output).toContain('Source development task: developer');
  expect(output).toContain('Location: Whole change');
  expect(output).toContain('````\n```java\nexample\n```\n````');
  expect(output).toContain('coverage are unavailable');
  expect(output).not.toMatch(/github.com|Recorded code excerpt|Severity:/);
});

it('exports saved local code and learning discussions with their delivery failures and immutable snapshots', () => {
  const thread: LocalReviewThreadDto = { id: 'thread-1', taskId: 'task-1', taskVersion: 1, workspaceId: 'workspace-1',
    repo: 'owner/repo', number: 4, path: 'src/Example.java', side: 'LEFT', startLine: 2, line: 4, baseSha: 'base', headSha: 'head',
    code: 'old code', resolved: true, version: 1, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
    messages: [{ id: 'message-1', author: 'USER', body: 'Is this needed?', createdAt: '2026-01-01T00:00:00Z', turnId: null,
      queuedInputId: null, messageEventId: null, deliveryState: 'UNSENT', deliveryError: 'Agent unavailable' }] };
  const output = localReviewConversation(thread, true);
  for (const value of ['src/Example.java L2–L4 (LEFT)', 'Base: base', 'Head: head', 'old code',
    'RESOLVED · Outdated', 'Delivery: UNSENT', 'Agent unavailable', 'Is this needed?']) expect(output).toContain(value);
  const learning = localReviewConversation({ ...thread, number: 0, learningAnchor: {
    turnId: 'summary-turn', cardId: 'purpose', title: 'Change intention', body: 'The original explanation',
  } }, false);
  expect(learning).toContain('Source turn: summary-turn');
  expect(learning).toContain('> The original explanation');
  expect(learning).not.toMatch(/github.com|Location:|Recorded code/);
});
