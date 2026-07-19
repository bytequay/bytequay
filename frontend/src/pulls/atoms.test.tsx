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
import { cleanup, fireEvent, render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { Av, RepoAv } from './atoms';

afterEach(cleanup);

describe('Av', () => {
  it('loads directly from GitHub avatars and retries when the login changes', () => {
    const { container, rerender } = render(<Av login="missing-user" size={24} />);
    const image = container.querySelector('img');
    expect(image?.getAttribute('src')).toBe('https://avatars.githubusercontent.com/missing-user?s=48');

    if (image === null) throw new Error('Avatar image did not render');
    fireEvent.error(image);
    expect(container.querySelector('img')).toBeNull();

    rerender(<Av login="octocat" size={24} />);
    expect(container.querySelector('img')?.getAttribute('src'))
      .toBe('https://avatars.githubusercontent.com/octocat?s=48');
  });

  it('resolves repository owners through GitHub current-avatar redirects', () => {
    const { container, rerender } = render(<RepoAv repo="trinodb/trino" size={16} />);
    expect(container.querySelector('img')?.getAttribute('src'))
      .toBe('https://github.com/trinodb.png?size=32');

    rerender(<RepoAv repo="acme/widget" size={16} />);
    expect(container.querySelector('img')?.getAttribute('src'))
      .toBe('https://github.com/acme.png?size=32');
  });
});
