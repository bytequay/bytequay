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
import { AskUserQuestionCard } from './AskUserQuestionCard';

afterEach(cleanup);

describe('AskUserQuestionCard', () => {
  it('answers with the picked option id', () => {
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
    expect(onAnswer).toHaveBeenCalledWith('sqlite', undefined);
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
    expect(onAnswer).toHaveBeenCalledWith(undefined, 'bytequay');
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
