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
import { BackButton } from './BackButton';

afterEach(cleanup);

describe('BackButton', () => {
  it('renders "← back to {label}"', () => {
    render(<BackButton label="PR #5678" onClick={() => {}} />);
    expect(screen.getByText('← back to PR #5678')).toBeTruthy();
  });

  it('fires onClick', () => {
    const onClick = vi.fn();
    render(<BackButton label="Threads" onClick={onClick} />);
    fireEvent.click(screen.getByRole('button'));
    expect(onClick).toHaveBeenCalledOnce();
  });
});
