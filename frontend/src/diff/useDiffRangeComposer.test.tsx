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
import { afterEach, describe, expect, it } from 'vitest';
import { useDiffRangeComposer } from './useDiffRangeComposer';

afterEach(cleanup);

function Harness() {
  const {
    composer,
    closeComposer,
    handleRowClick,
    onRowPointerDown,
    onRowPointerEnter,
    isInRange,
  } = useDiffRangeComposer();

  return (
    <div>
      <div data-testid="composer">
        {composer === null
          ? 'none'
          : `${composer.file}:${composer.side}:${composer.line}:${composer.startLine ?? ''}:${composer.startSide ?? ''}`}
      </div>
      {[1, 2, 3].map(line => (
        <button
          key={line}
          type="button"
          data-testid={`row-${line}`}
          className={isInRange({ file: 'src/foo.ts', side: 'RIGHT', line }) ? 'in-range' : ''}
          onClick={(e) => handleRowClick({ file: 'src/foo.ts', side: 'RIGHT', line }, e.shiftKey)}
          onPointerDown={() => onRowPointerDown({ file: 'src/foo.ts', side: 'RIGHT', line })}
          onPointerEnter={() => onRowPointerEnter({ file: 'src/foo.ts', side: 'RIGHT', line })}
        >
          line {line}
        </button>
      ))}
      <button type="button" onClick={closeComposer}>Cancel</button>
    </div>
  );
}

describe('useDiffRangeComposer', () => {
  it('opens a single-line composer on click', () => {
    render(<Harness />);

    fireEvent.click(screen.getByTestId('row-2'));

    expect(screen.getByTestId('composer').textContent).toBe('src/foo.ts:RIGHT:2::');
    expect(screen.getByTestId('row-2').className).toBe('in-range');
  });

  it('extends a same-side range on shift-click', () => {
    render(<Harness />);

    fireEvent.click(screen.getByTestId('row-1'));
    fireEvent.click(screen.getByTestId('row-3'), { shiftKey: true });

    expect(screen.getByTestId('composer').textContent).toBe('src/foo.ts:RIGHT:3:1:RIGHT');
    expect(screen.getByTestId('row-1').className).toBe('in-range');
    expect(screen.getByTestId('row-2').className).toBe('in-range');
    expect(screen.getByTestId('row-3').className).toBe('in-range');
  });

  it('keeps a drag range after the synthetic click that follows pointerup', () => {
    render(<Harness />);

    fireEvent.pointerDown(screen.getByTestId('row-1'));
    fireEvent.pointerEnter(screen.getByTestId('row-3'));
    fireEvent.pointerUp(window);
    fireEvent.click(screen.getByTestId('row-1'));

    expect(screen.getByTestId('composer').textContent).toBe('src/foo.ts:RIGHT:3:1:RIGHT');
  });

  it('clears the composer and range on cancel', () => {
    render(<Harness />);

    fireEvent.click(screen.getByTestId('row-2'));
    fireEvent.click(screen.getByText('Cancel'));

    expect(screen.getByTestId('composer').textContent).toBe('none');
    expect(screen.getByTestId('row-2').className).toBe('');
  });
});
