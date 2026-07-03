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
import FindBar from './FindBar';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

/** jsdom implements neither; stub both so the bar can drive them. */
function stubFind(found = true) {
  const find = vi.fn().mockReturnValue(found);
  (window as unknown as { find: typeof find }).find = find;
  return find;
}

describe('FindBar', () => {
  it('renders nothing when closed', () => {
    stubFind();
    const { container } = render(<FindBar open={false} onClose={() => {}} />);
    expect(container.firstChild).toBeNull();
  });

  it('searches from the top as the user types', () => {
    const find = stubFind();
    render(<FindBar open onClose={() => {}} />);

    fireEvent.change(screen.getByLabelText('Find in page'), { target: { value: 'hello' } });

    // find(query, caseSensitive, backwards, wrapAround, ...) — forwards search.
    expect(find).toHaveBeenCalledWith('hello', false, false, true, false, false, false);
  });

  it('Enter steps forward, Shift+Enter steps backward', () => {
    const find = stubFind();
    render(<FindBar open onClose={() => {}} />);
    const input = screen.getByLabelText('Find in page');
    fireEvent.change(input, { target: { value: 'x' } });
    find.mockClear();

    fireEvent.keyDown(input, { key: 'Enter' });
    expect(find).toHaveBeenLastCalledWith('x', false, false, true, false, false, false);

    fireEvent.keyDown(input, { key: 'Enter', shiftKey: true });
    expect(find).toHaveBeenLastCalledWith('x', false, true, true, false, false, false);
  });

  it('marks the query red when there is no match', () => {
    stubFind(false);
    render(<FindBar open onClose={() => {}} />);
    const input = screen.getByLabelText('Find in page');

    fireEvent.change(input, { target: { value: 'nope' } });

    expect(input.style.color).toBe('rgb(192, 57, 43)');
  });

  it('Esc closes the bar', () => {
    stubFind();
    const onClose = vi.fn();
    render(<FindBar open onClose={onClose} />);

    fireEvent.keyDown(screen.getByLabelText('Find in page'), { key: 'Escape' });

    expect(onClose).toHaveBeenCalled();
  });
});
