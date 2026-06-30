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
import { BacklogFormModal } from './BacklogFormModal';

afterEach(cleanup);

describe('BacklogFormModal', () => {
  it('blocks save until a title is typed, then saves title + default priority', () => {
    const onSave = vi.fn();
    render(<BacklogFormModal onSave={onSave} onClose={() => {}} />);

    expect((screen.getByText('Add item') as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'Parse cost meter' } });
    expect((screen.getByText('Add item') as HTMLButtonElement).disabled).toBe(false);

    fireEvent.click(screen.getByText('Add item'));
    expect(onSave).toHaveBeenCalledWith(
      expect.objectContaining({ title: 'Parse cost meter', priority: 'medium', tags: [] }));
  });

  it('collects tags from the chip input and a high-priority pick', () => {
    const onSave = vi.fn();
    render(<BacklogFormModal onSave={onSave} onClose={() => {}} />);
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'X' } });

    const tagInput = screen.getByPlaceholderText('Add a tag, press Enter');
    fireEvent.change(tagInput, { target: { value: 'ui' } });
    fireEvent.keyDown(tagInput, { key: 'Enter' });
    expect(screen.getByText('ui')).toBeTruthy();

    fireEvent.click(screen.getByText('high'));
    fireEvent.click(screen.getByText('Add item'));
    expect(onSave).toHaveBeenCalledWith(expect.objectContaining({ tags: ['ui'], priority: 'high' }));
  });

  it('cancels via the Cancel button', () => {
    const onClose = vi.fn();
    render(<BacklogFormModal onSave={() => {}} onClose={onClose} />);
    fireEvent.click(screen.getByText('Cancel'));
    expect(onClose).toHaveBeenCalledOnce();
  });
});
