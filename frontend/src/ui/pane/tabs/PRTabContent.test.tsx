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
import { PRTabContent } from './PRTabContent';

afterEach(cleanup);

describe('PRTabContent — stage PR tab', () => {
  it('renders the section header, branch flow, and a passing check card', () => {
    render(
      <PRTabContent
        title="Add cost-meter card"
        prNumber={145}
        status="open"
        statusLabel="Open · ready for review"
        headBranch="feat/cost-meter"
        baseBranch="main"
        checks={{ passed: 4, failed: 0, pending: 0, total: 4 }}
      />,
    );
    expect(screen.getByText('Add cost-meter card')).toBeTruthy();
    expect(screen.getByText('#145')).toBeTruthy();
    expect(screen.getByText('feat/cost-meter')).toBeTruthy();
    expect(screen.getByText('All checks have passed')).toBeTruthy();
    expect(screen.getByText(/4 passed/)).toBeTruthy();
  });

  it('summarizes failing checks', () => {
    render(
      <PRTabContent
        status="open"
        statusLabel="Open"
        checks={{ passed: 2, failed: 1, pending: 1, total: 4 }}
      />,
    );
    expect(screen.getByText('1 check failing')).toBeTruthy();
    expect(screen.getByText(/2 passed · 1 failed · 1 running/)).toBeTruthy();
  });

  it('renders review threads under a custom header with the agent reply', () => {
    render(
      <PRTabContent
        status="open"
        statusLabel="Open"
        threadsHeader="Open threads · 1"
        threads={[{
          id: 't1',
          author: 'jack.chen',
          file: 'CostMeterCard.tsx:42',
          status: 'open',
          body: 'Can we cap the bar count at 10?',
          reply: { src: 'BOT', text: 'Capped at 10 with a +N marker.' },
        }]}
      />,
    );
    expect(screen.getByText('Open threads · 1')).toBeTruthy();
    expect(screen.getByText('CostMeterCard.tsx:42')).toBeTruthy();
    expect(screen.getByText('Can we cap the bar count at 10?')).toBeTruthy();
    expect(screen.getByText('BOT')).toBeTruthy();
    expect(screen.getByText('Capped at 10 with a +N marker.')).toBeTruthy();
  });

  it('shows the add-comment box and fires onAddComment', () => {
    const onAddComment = vi.fn();
    render(
      <PRTabContent
        status="open"
        statusLabel="Open"
        commentValue="looks good"
        onCommentChange={() => {}}
        onAddComment={onAddComment}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Comment' }));
    expect(onAddComment).toHaveBeenCalledOnce();
  });
});
