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
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CIStatusPage } from './CIStatusPage';
import { CILogView } from '../ui/fullpage';

afterEach(cleanup);
beforeEach(() => localStorage.clear());

function renderCi(overrides: Partial<Parameters<typeof CIStatusPage>[0]> = {}) {
  return render(
    <CIStatusPage
      sidebar={<aside data-testid="sidebar" />}
      conversation={<div data-testid="conv">feed</div>}
      composer={{ value: '', onChange: () => {}, onSubmit: () => {} }}
      current={{ title: 'CI run', runId: '#1234', statusLine: 'failing', checks: [{ status: 'fail', name: 'test' }] }}
      iterationGroups={[
        { key: 'this', label: 'This task', iterations: [{ id: 'it1', status: 'fail', name: 'iter 1' }] },
      ]}
      log={<CILogView text="build failed" />}
      onBack={() => {}}
      {...overrides}
    />,
  );
}

describe('CIStatusPage', () => {
  it('renders collapsed sidebar, conversation, CI panel, and log', () => {
    renderCi();
    expect(document.querySelector('.shell.sidebar-collapsed')).toBeTruthy();
    expect(screen.getByTestId('conv')).toBeTruthy();
    expect(document.querySelector('.ci-current-card')).toBeTruthy();
    expect(screen.getByText('build failed')).toBeTruthy();
    expect(document.querySelector('.ci-body')?.className).toBe('ci-body');
  });

  it('hides the CI panel and persists the preference', () => {
    renderCi();
    fireEvent.click(screen.getByRole('button', { name: 'Hide CI panel' }));
    expect(document.querySelector('.ci-body.no-panel')).toBeTruthy();
    expect(document.querySelector('.ci-current-card')).toBeNull();
    expect(localStorage.getItem('v3.ci.panelHidden')).toBe('true');
    cleanup();
    renderCi();
    expect(screen.getByRole('button', { name: 'Show CI panel' })).toBeTruthy();
    expect(document.querySelector('.ci-current-card')).toBeNull();
  });

  it('selecting an iteration fires the callback', () => {
    const onSelectIteration = vi.fn();
    renderCi({ onSelectIteration });
    fireEvent.click(screen.getByText('iter 1'));
    expect(onSelectIteration).toHaveBeenCalledWith('it1');
  });

  it('back button fires onBack', () => {
    const onBack = vi.fn();
    renderCi({ onBack });
    fireEvent.click(screen.getByRole('button', { name: 'Back' }));
    expect(onBack).toHaveBeenCalledOnce();
  });
});
