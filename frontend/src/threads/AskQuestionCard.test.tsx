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
import { AskQuestionCard } from './AskQuestionCard';
import { findPendingAskQuestion } from './askQuestion';
import type { ThreadMessageDto } from '../types';

afterEach(cleanup);

const SINGLE = {
  questions: [{
    question: 'How should Phase E handle the branch policy?',
    header: 'branch policy',
    multiSelect: false,
    options: [
      { label: 'Build the pr.head primitive now', description: 'Extend WorktreeService…' },
      { label: 'Ship on default-base worktree now', description: 'Spawn the build…' },
      { label: 'Author-mode fetches head', description: 'comment-only…' },
    ],
  }],
};

describe('AskQuestionCard (interactive)', () => {
  it('lets the user pick several options and sends the joined labels', () => {
    const onAnswer = vi.fn();
    const multi = {
      questions: [{
        question: 'Pick features',
        options: [{ label: 'A' }, { label: 'B' }, { label: 'C' }],
      }],
    };
    render(<AskQuestionCard input={multi} onAnswer={onAnswer} />);
    fireEvent.click(screen.getByText('B'));
    fireEvent.click(screen.getByText('C'));
    expect(onAnswer).not.toHaveBeenCalled();      // waits for an explicit Send
    fireEvent.click(screen.getByText('Send →'));
    expect(onAnswer).toHaveBeenCalledWith('B, C');
  });

  it('toggles with the keyboard (Space) and sends with Cmd/Ctrl+Enter', () => {
    const onAnswer = vi.fn();
    render(<AskQuestionCard input={SINGLE} onAnswer={onAnswer} />);
    const box = screen.getByRole('listbox');
    fireEvent.keyDown(box, { key: 'ArrowDown' }); // cursor 0 → 1
    fireEvent.keyDown(box, { key: ' ' });         // toggle option 1
    fireEvent.keyDown(box, { key: 'Enter', ctrlKey: true });
    expect(onAnswer).toHaveBeenCalledWith('Ship on default-base worktree now');
  });

  it('supports a free-text reply with no option selected', () => {
    const onAnswer = vi.fn();
    render(<AskQuestionCard input={SINGLE} onAnswer={onAnswer} />);
    fireEvent.change(screen.getByLabelText('Custom reply'), {
      target: { value: 'neither — look for a different nit' },
    });
    fireEvent.click(screen.getByText('Send →'));
    expect(onAnswer).toHaveBeenCalledWith('neither — look for a different nit');
  });

  it('combines a selected option with free text', () => {
    const onAnswer = vi.fn();
    const q = { questions: [{ question: 'pick', options: [{ label: 'A' }, { label: 'B' }] }] };
    render(<AskQuestionCard input={q} onAnswer={onAnswer} />);
    fireEvent.click(screen.getByText('A'));
    fireEvent.change(screen.getByLabelText('Custom reply'), { target: { value: 'and watch perf' } });
    fireEvent.click(screen.getByText('Send →'));
    expect(onAnswer).toHaveBeenCalledWith('A — and watch perf');
  });

  it('disables Send until something is chosen or typed', () => {
    render(<AskQuestionCard input={SINGLE} onAnswer={vi.fn()} />);
    expect((screen.getByText('Send →') as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByText('Build the pr.head primitive now'));
    expect((screen.getByText('Send →') as HTMLButtonElement).disabled).toBe(false);
  });

  it('static mode (no onAnswer) points the user at the chat input', () => {
    render(<AskQuestionCard input={SINGLE} />);
    expect(screen.getByText(/Reply via the chat input below/)).toBeTruthy();
    expect(screen.queryByRole('listbox')).toBeNull();
  });
});

describe('findPendingAskQuestion', () => {
  const ask = (seq: number): ThreadMessageDto => msg(seq, 'tool', 'tool_call',
    JSON.stringify({ callId: `c-${seq}`, toolName: 'AskUserQuestion', input: SINGLE }));
  const reply = (seq: number): ThreadMessageDto => msg(seq, 'user', 'text', '{"text":"ok"}');

  it('returns the latest unanswered AskUserQuestion', () => {
    expect(findPendingAskQuestion([reply(1), ask(2)])?.callId).toBe('c-2');
  });

  it('returns null once a user reply lands after the question', () => {
    expect(findPendingAskQuestion([ask(1), reply(2)])).toBeNull();
  });

  it('returns null when no question was asked', () => {
    expect(findPendingAskQuestion([reply(1)])).toBeNull();
  });

  function msg(seq: number, role: string, type: string, content: string): ThreadMessageDto {
    return {
      id: `m-${seq}`, threadId: 't', taskId: null, seq, role, type,
      contentJson: content, durationMs: null, tokensIn: null, tokensOut: null,
      costUsdMilli: null, ts: new Date(Date.UTC(2026, 0, 1, 0, 0, seq)).toISOString(),
    };
  }
});
