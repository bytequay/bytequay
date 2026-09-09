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
import { useState, type ReactElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import Avatar from './Avatar';
import RepoAvatar from './RepoAvatar';

type ImageEvents = { onError: () => void; onLoad: () => void };

function AvatarAfterFailure({ replacement }: { replacement: Parameters<typeof Avatar>[0] }) {
  const [failed, setFailed] = useState(false);
  // Keep the avatar's hooks on the same renderer while exercising an image event and new props.
  const view = Avatar(failed ? replacement : { login: 'alice' });
  if (!failed) {
    (view as ReactElement<ImageEvents>).props.onError();
    setFailed(true);
  }
  return view;
}

function RepositoryAfterImageEvent({ owner, event }: { owner: string; event: keyof ImageEvents }) {
  const [triggered, setTriggered] = useState(false);
  const view = RepoAvatar({ owner: triggered ? owner : 'alice', repo: 'project', size: 24 });
  if (!triggered) {
    const image = view.props.children[1] as ReactElement<ImageEvents>;
    image.props[event]();
    setTriggered(true);
  }
  return view;
}

describe('avatar image state', () => {
  it('keeps a failed user image as a same-sized fallback', () => {
    const html = renderToStaticMarkup(<AvatarAfterFailure replacement={{ login: 'alice' }} />);

    expect(html).toContain('avatar--fallback');
    expect(html).toContain('width:20px;height:20px');
    expect(html).toContain('aria-label="alice" role="img">A</span>');
    expect(html).not.toContain('<img');
  });

  it.each([
    [{ login: 'bob' }, 'https://github.com/bob.png?size=40'],
    [{ login: 'alice', avatarUrl: 'https://avatars.githubusercontent.com/u/1' }, 'https://avatars.githubusercontent.com/u/1'],
  ])('tries the current user image after the previous source failed', (replacement, source) => {
    const html = renderToStaticMarkup(<AvatarAfterFailure replacement={replacement} />);

    expect(html).toContain(`src="${source}"`);
    expect(html).not.toContain('avatar--fallback');
  });

  it('keeps a failed repository image as its owner initial', () => {
    const html = renderToStaticMarkup(<RepositoryAfterImageEvent owner="alice" event="onError" />);

    expect(html).toContain('aria-label="alice/project"');
    expect(html).toContain('>A</span>');
    expect(html).not.toContain('<img');
  });

  it.each(['onError', 'onLoad'] as const)('loads a new repository image after the previous image %s', event => {
    const html = renderToStaticMarkup(<RepositoryAfterImageEvent owner="upstream" event={event} />);

    expect(html).toContain('aria-label="upstream/project"');
    expect(html).toContain('src="https://github.com/upstream.png?size=48"');
    expect(html).toContain('>U<img');
    expect(html).toContain('opacity:0');
  });
});
