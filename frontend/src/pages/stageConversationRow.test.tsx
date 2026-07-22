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
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { StageConversationRow } from '../types/brainView';
import { stageFeed, stageRow } from './stageConversationRow';

afterEach(cleanup);

function row(over: Partial<StageConversationRow>): StageConversationRow {
  return {
    id: 'r1', kind: 'tool_call', text: null,
    toolTag: 'Run', toolLabel: 'Bash', toolDetail: null,
    toolResult: null, toolError: null, toolDiff: null, iterationNumber: null,
    ts: '2026-01-01T00:00:00Z', callId: null, images: [], managedSkills: [],
    ...over,
  } as StageConversationRow;
}

describe('stageRow tool_call', () => {
  it('renders a remote pull request creation milestone', () => {
    render(<>{stageRow(row({
      kind: 'pull_request_created',
      pullRequest: {
        branch: 'feature/timeline', baseBranch: 'main', number: 145,
        additions: 12, deletions: 3,
      },
    }))}</>);

    expect(screen.getByText('Pull request created')).toBeTruthy();
    expect(screen.getByText('#145')).toBeTruthy();
    expect(screen.getByText('feature/timeline')).toBeTruthy();
    expect(screen.getByText('main')).toBeTruthy();
    expect(screen.getByText('+12')).toBeTruthy();
    expect(screen.getByText('-3')).toBeTruthy();
  });

  it('renders pull request preparation milestones', () => {
    render(<>{stageRow(row({
      kind: 'pull_request_progress',
      pullRequest: {
        phase: 'starting', branch: 'feature/timeline', baseBranch: 'main',
      },
    }))}</>);

    expect(screen.getByText('Starting pull request')).toBeTruthy();
    expect(screen.getByText('feature/timeline')).toBeTruthy();
    expect(screen.getByText('main')).toBeTruthy();
  });

  it('renders the approved-plan kickoff as a collapsed runtime card, not a user message', () => {
    const text = `The plan for this task has been approved — implement it now.

Intent: Remove the unused effort picker.
Steps:
1. Delete the picker
Validation: Run frontend checks
Push strategy: await_approval`;
    const { container } = render(<>{stageRow(row({ kind: 'user', text }))}</>);

    const card = container.querySelector('.runtime-kickoff-card') as HTMLDetailsElement;
    expect(card).toBeTruthy();
    expect(card.open).toBe(false);
    expect(container.querySelector('.ev--user')).toBeNull();
    expect(screen.getByText('Approved plan')).toBeTruthy();
    expect(screen.getByText('Remove the unused effort picker.')).toBeTruthy();

    fireEvent.click(screen.getByText('Approved plan'));
    expect(card.open).toBe(true);
    expect(container.querySelector('.runtime-kickoff-card__prompt')?.textContent).toBe(text);
  });

  it.each([
    `## Context from prior stages
You are a fresh agent for the CI-fixing stage — you did NOT do the development work.

What this PR set out to do:
Remove the effort picker.

---

CI is failing on the shipped PR chenjian2664/ByteQuay #38.
Failing checks:
  - CI success – Frontend — unit & component tests

First decide whether these failures are caused by this branch's changes.`,
    `CI is failing on the shipped PR chenjian2664/ByteQuay #38.
Failing checks:
  - Frontend tests

First decide whether these failures are caused by this branch's changes.`,
  ])('renders a CI-fix kickoff as a collapsed runtime card', text => {
    const { container } = render(<>{stageRow(row({ kind: 'user', text }))}</>);

    const card = container.querySelector('.runtime-kickoff-card') as HTMLDetailsElement;
    expect(card).toBeTruthy();
    expect(card.open).toBe(false);
    expect(container.querySelector('.ev--user')).toBeNull();
    expect(screen.getByText('CI fix instructions')).toBeTruthy();
    expect(screen.getByText(/chenjian2664\/ByteQuay #38 ·/)).toBeTruthy();

    fireEvent.click(screen.getByText('CI fix instructions'));
    expect(card.open).toBe(true);
    expect(container.querySelector('.runtime-kickoff-card__prompt')?.textContent).toBe(text);
  });

  it('shows runtime-managed skills on user rows', () => {
    render(<>{stageRow(row({
      kind: 'user',
      text: 'implement',
      managedSkills: ['ponytail'],
    }))}</>);

    fireEvent.click(screen.getByText('runtime'));
    expect(screen.getByText('Managed skills: ponytail')).toBeTruthy();
  });

  it('prints the command after the tool name', () => {
    const { container } = render(<>{stageRow(row({ toolDetail: 'mvnd airstyle:format' }))}</>);
    expect(screen.getByText('mvnd airstyle:format')).toBeTruthy();
    expect(container.querySelector('.tool-block')).toBeTruthy();
    // The command is the literal arg span, not the tool label.
    expect(container.querySelector('.tool-arg')?.textContent).toBe('mvnd airstyle:format');
  });

  it('renders the tool block bare — no redundant Agent who-row', () => {
    const { container } = render(<>{stageRow(row({ toolDetail: 'ls -la' }))}</>);
    // No EventRow wrapper (which would add the "Agent" who-row).
    expect(container.querySelector('.ev')).toBeNull();
    expect(screen.queryByText('Agent')).toBeNull();
  });

  it('drops the tool name when it just repeats the tag pill (no "Read Read")', () => {
    const { container } = render(
      <>{stageRow(row({ toolTag: 'Read', toolLabel: 'Read', toolDetail: '/repo/.worktree/x/Foo.java' }))}</>,
    );
    // Pill shows "Read" once; the desc is only the path, not "Read /repo…".
    expect(container.querySelector('.tag')?.textContent).toBe('Read');
    expect(container.querySelector('.desc')?.textContent).toBe('/repo/.worktree/x/Foo.java');
    // Path tags head-truncate so the filename tail survives.
    expect(container.querySelector('.desc--tail')).toBeTruthy();
  });

  it('keeps both name and command when they differ (Run Bash …)', () => {
    const { container } = render(<>{stageRow(row({ toolDetail: 'git status' }))}</>);
    expect(container.querySelector('.tag')?.textContent).toBe('Run');
    expect(container.querySelector('.desc')?.textContent).toBe('Bash git status');
    expect(container.querySelector('.desc--tail')).toBeNull();
  });

  it('falls back to the tool name when there is no command', () => {
    render(<>{stageRow(row({ toolLabel: 'Read', toolDetail: null }))}</>);
    expect(screen.getByText('Read')).toBeTruthy();
  });

  it('never renders a blank line — blank tag/label fall back to Tool / Tool call', () => {
    const { container } = render(
      <>{stageRow(row({ toolTag: '', toolLabel: '', toolDetail: '' }))}</>,
    );
    expect(container.querySelector('.tool-block')).toBeTruthy();
    expect(screen.getByText('Tool')).toBeTruthy();
    expect(screen.getByText('Tool call')).toBeTruthy();
  });
});

describe('stageFeed grouping', () => {
  it('folds consecutive tool calls behind a "Worked for" group; boundaries stay inline', () => {
    const rows = [
      row({ id: 'u1', kind: 'user', text: 'go' }),
      row({ id: 't1', ts: '2026-01-01T00:00:00Z' }),
      row({ id: 't2', ts: '2026-01-01T00:03:12Z' }),
      row({ id: 'a1', kind: 'agent', text: 'done' }),
    ];
    const { container } = render(<>{stageFeed(rows)}</>);
    expect(screen.getByText('Worked for 3m 12s')).toBeTruthy();
    expect(screen.getByText('· 2 steps')).toBeTruthy();
    expect(screen.getByText('done')).toBeTruthy();
    // Folded by default: the tool rows are hidden until the bar is clicked.
    expect(container.querySelector('.tool-block')).toBeNull();
    fireEvent.click(screen.getByText('Worked for 3m 12s'));
    expect(container.querySelectorAll('.tool-block')).toHaveLength(2);
  });

  it('keeps the trailing group open while the stage is live', () => {
    const rows = [row({ id: 't1' }), row({ id: 't2' })];
    const { container } = render(<>{stageFeed(rows, undefined, undefined, true)}</>);
    expect(container.querySelectorAll('.tool-block')).toHaveLength(2);
  });

  it('starts stage-detail groups open but lets the user collapse them', () => {
    const rows = [row({ id: 't1' }), row({ id: 't2' })];
    const { container } = render(<>{stageFeed(rows, undefined, undefined, false, true)}</>);
    const toggle = container.querySelector('.sp-work__bar') as HTMLButtonElement;
    expect(container.querySelectorAll('.tool-block')).toHaveLength(2);
    expect((toggle as HTMLButtonElement).disabled).toBe(false);
    fireEvent.click(toggle);
    expect(container.querySelector('.tool-block')).toBeNull();
  });
});

describe('stageRow permission', () => {
  const perm = () => row({
    kind: 'permission', toolLabel: 'run_shell', text: 'cmd: git status', callId: 'c1',
  });

  it('renders an actionable card whose Approve answers the prompt', () => {
    const onDecide = vi.fn();
    render(<>{stageRow(perm(), onDecide)}</>);
    fireEvent.click(screen.getByText('Approve once'));
    expect(onDecide).toHaveBeenCalledWith('c1', 'ALLOW');
  });

  it('falls back to a static note on a read-only surface (no onDecide)', () => {
    render(<>{stageRow(perm())}</>);
    expect(screen.getByText(/Awaiting approval/)).toBeTruthy();
    expect(screen.queryByText('Approve once')).toBeNull();
  });
});
