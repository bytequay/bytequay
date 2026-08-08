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
import { BacklogTabContent } from './index';

afterEach(cleanup);

describe('BacklogTabContent', () => {
  it('renders the add dropzone + backlog cards and wires Start development', () => {
    const onAddItem = vi.fn();
    const onStart = vi.fn();
    const { container } = render(
      <BacklogTabContent
        items={[{ id: 'b1', title: 'Idea', tags: [{ label: 'ui', color: 'green' }] }]}
        onAddItem={onAddItem}
        onStartDevelopment={onStart}
      />,
    );
    fireEvent.click(container.querySelector('.backlog-add') as HTMLElement);
    expect(onAddItem).toHaveBeenCalledOnce();
    fireEvent.click(screen.getByRole('button', { name: /Start development/ }));
    expect(onStart).toHaveBeenCalledWith('b1');
  });
});
