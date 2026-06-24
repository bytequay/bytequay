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
import { CIIterationFolder, CILogView, CIPanel } from './index';

afterEach(cleanup);

describe('CIPanel', () => {
  it('renders the current run card with colour-coded checks', () => {
    const { container } = render(
      <CIPanel
        current={{
          title: 'CI run', runId: '#1234', statusLine: 'failing',
          checks: [
            { status: 'pass', name: 'build', duration: '1m' },
            { status: 'fail', name: 'test', duration: '2m' },
          ],
        }}
        groups={[{ key: 'this', label: 'This task', iterations: [] }]}
      />,
    );
    expect(container.querySelector('.ci-check-row .ic.pass')).toBeTruthy();
    expect(container.querySelector('.ci-check-row .ic.fail')).toBeTruthy();
    expect(screen.getByText('#1234')).toBeTruthy();
  });

  it('selects an iteration from a folder (first folder open by default)', () => {
    const onSelectIteration = vi.fn();
    render(
      <CIPanel
        current={{ title: 'CI run', checks: [] }}
        groups={[
          { key: 'this', label: 'This task', iterations: [{ id: 'it1', status: 'fail', name: 'iter 1', timestamp: '2m' }] },
          { key: 'all', label: 'All time', iterations: [] },
        ]}
        onSelectIteration={onSelectIteration}
      />,
    );
    fireEvent.click(screen.getByText('iter 1'));
    expect(onSelectIteration).toHaveBeenCalledWith('it1');
  });
});

describe('CIIterationFolder', () => {
  it('collapses its rows until expanded and shows the count', () => {
    render(
      <CIIterationFolder
        label="Earlier this thread"
        iterations={[{ id: 'a', status: 'pass', name: 'iter a' }]}
      />,
    );
    expect(screen.queryByText('iter a')).toBeNull();
    expect(screen.getByText('1')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /Earlier this thread/ }));
    expect(screen.getByText('iter a')).toBeTruthy();
  });
});

describe('CILogView', () => {
  it('renders colorized children', () => {
    const { container } = render(<CILogView><span className="red">error</span></CILogView>);
    expect(container.querySelector('.ci-log .red')?.textContent).toBe('error');
  });
});
