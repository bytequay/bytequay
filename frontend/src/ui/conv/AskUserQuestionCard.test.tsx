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
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AskUserQuestionCard } from './AskUserQuestionCard';

beforeEach(() => { vi.useFakeTimers(); });
afterEach(() => { cleanup(); vi.useRealTimers(); });

/** Run out the undo window so the pending answer posts. */
const runUndoWindow = () => act(() => { vi.advanceTimersByTime(5000); });

describe('AskUserQuestionCard', () => {
  it('answers with the picked option id after the undo window', () => {
    const onAnswer = vi.fn();
    render(
      <AskUserQuestionCard
        question="Which database?"
        context="picking storage"
        options={[{ id: 'sqlite', label: 'SQLite' }, { id: 'pg', label: 'Postgres', extra: 'remote' }]}
        allowFreeForm
        onAnswer={onAnswer}
      />,
    );

    expect(screen.getByText('Which database?')).toBeTruthy();
    expect(screen.getByText('picking storage')).toBeTruthy();
    expect(screen.getByText('remote')).toBeTruthy();

    fireEvent.click(screen.getByText('SQLite'));
    expect(onAnswer).not.toHaveBeenCalled();
    expect(screen.getByText('Answer sent to agent')).toBeTruthy();

    runUndoWindow();
    expect(onAnswer).toHaveBeenCalledWith('sqlite', undefined);
  });

  it('undo cancels the pending answer and restores the options', () => {
    const onAnswer = vi.fn();
    render(
      <AskUserQuestionCard
        question="Cut this task?"
        options={[{ id: 'go', label: 'Go ahead' }]}
        allowFreeForm
        onAnswer={onAnswer}
      />,
    );

    fireEvent.click(screen.getByText('Go ahead'));
    fireEvent.click(screen.getByText('Undo'));
    runUndoWindow();

    expect(onAnswer).not.toHaveBeenCalled();
    expect(screen.getByText('Go ahead')).toBeTruthy();
  });

  it('flushes a pending answer when the card unmounts mid-window', () => {
    const onAnswer = vi.fn();
    const { unmount } = render(
      <AskUserQuestionCard
        question="Cut this task?"
        options={[{ id: 'go', label: 'Go ahead' }]}
        allowFreeForm={false}
        onAnswer={onAnswer}
      />,
    );

    fireEvent.click(screen.getByText('Go ahead'));
    unmount();
    expect(onAnswer).toHaveBeenCalledWith('go', undefined);
  });

  it('answers with free-form text and trims it', () => {
    const onAnswer = vi.fn();
    render(
      <AskUserQuestionCard
        question="Name it?"
        options={[]}
        allowFreeForm
        onAnswer={onAnswer}
      />,
    );

    fireEvent.change(screen.getByLabelText('Free-form answer'), { target: { value: '  bytequay  ' } });
    fireEvent.click(screen.getByText('Send'));
    runUndoWindow();
    expect(onAnswer).toHaveBeenCalledWith(undefined, 'bytequay');
  });

  it('renders markdown context and escaped newlines', () => {
    const { container } = render(
      <AskUserQuestionCard
        question="Proceed?"
        context={'First **important** point.\\n\\nRun `mvn test`.'}
        options={[]}
        allowFreeForm={false}
        onAnswer={vi.fn()}
      />,
    );

    const context = container.querySelector('.ask-question-card__ctx');
    expect(context?.querySelector('strong')?.textContent).toBe('important');
    expect(context?.querySelector('code')?.textContent).toBe('mvn test');
    expect(context?.textContent).not.toContain('\\n');
  });

  it('numbers the card when several are open', () => {
    render(
      <AskUserQuestionCard
        question="Second?"
        options={[{ id: 'a', label: 'A' }]}
        allowFreeForm={false}
        index={2}
        total={3}
        onAnswer={vi.fn()}
      />,
    );
    expect(screen.getByText('Question 2 of 3')).toBeTruthy();
  });
});
