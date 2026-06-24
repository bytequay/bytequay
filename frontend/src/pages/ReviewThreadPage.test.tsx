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
import { ReviewThreadPage } from './ReviewThreadPage';
import { Callout, Conv, Thought } from '../ui/conv';

afterEach(cleanup);

function renderReview(overrides: Partial<Parameters<typeof ReviewThreadPage>[0]> = {}) {
  return render(
    <ReviewThreadPage
      thread={{ title: 'Review PR #5678' }}
      sidebar={<aside data-testid="sidebar" />}
      conversation={
        <Conv>
          <Thought seconds={9} />
          <Callout>This looks reasonable, but the retry loop can spin.</Callout>
        </Conv>
      }
      prTab={<div data-testid="pr-tab">pr</div>}
      composer={{ value: '', onChange: () => {}, onSubmit: () => {} }}
      onSubmitReview={() => {}}
      {...overrides}
    />,
  );
}

describe('ReviewThreadPage', () => {
  it('starts with the sidebar collapsed and renders the review prose', () => {
    renderReview();
    expect(document.querySelector('.shell.sidebar-collapsed')).toBeTruthy();
    expect(screen.getByText('Thought for 9s')).toBeTruthy();
    expect(screen.getByText(/retry loop can spin/)).toBeTruthy();
    expect(screen.getByTestId('pr-tab')).toBeTruthy();
  });

  it('Submit review CTA is the green variant and fires the callback', () => {
    const onSubmitReview = vi.fn();
    renderReview({ onSubmitReview });
    const btn = screen.getByRole('button', { name: 'Submit review' });
    expect(btn.className).toBe('btn submit');
    fireEvent.click(btn);
    expect(onSubmitReview).toHaveBeenCalledOnce();
  });

  it('shows a submitting state and does not re-fire while submitting', () => {
    const onSubmitReview = vi.fn();
    renderReview({ onSubmitReview, submitting: true });
    fireEvent.click(screen.getByRole('button', { name: 'Submitting…' }));
    expect(onSubmitReview).not.toHaveBeenCalled();
  });

  it('back arrow fires onBack', () => {
    const onBack = vi.fn();
    renderReview({ onBack });
    fireEvent.click(screen.getByRole('button', { name: 'Back' }));
    expect(onBack).toHaveBeenCalledOnce();
  });
});
