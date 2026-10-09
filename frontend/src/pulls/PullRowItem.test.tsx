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
import type { ButtonHTMLAttributes, MouseEvent, ReactElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import PullRowItem from './PullRowItem';
import type { PullRow } from './model';

const row: PullRow = {
  id: 1,
  repo: 'owner/repo',
  num: 42,
  title: 'Fix the build',
  author: 'someone-else',
  time: '1h',
  kind: 'open',
  labels: [],
  labelColors: null,
  status: 'failing',
  additions: 1,
  deletions: 0,
  comments: 2,
  review: null,
  monitored: false,
};

describe('pull request monitoring control', () => {
  it('toggles monitoring separately from opening or selecting the card', () => {
    const onPick = vi.fn();
    const onMonitor = vi.fn();
    const card = PullRowItem({ row, wide: false, selected: false, onPick, onMonitor });
    const monitor = card.props.children[1] as ReactElement<ButtonHTMLAttributes<HTMLButtonElement>>;

    monitor.props.onClick?.({} as MouseEvent<HTMLButtonElement>);

    expect(onMonitor).toHaveBeenCalledOnce();
    expect(onPick).not.toHaveBeenCalled();
    const markup = renderToStaticMarkup(card);
    expect(markup).toMatch(/<\/button><button[^>]*class="pull-card__monitor"/);
    expect(markup).toContain('aria-label="Monitor PR: owner/repo #42" aria-pressed="false"');
    expect(markup).toContain('lucide-bell');
    expect(markup).not.toContain('lucide-loader-circle');
  });

  it.each([false, true])('shows saving feedback and preserves the persisted monitor state (%s) in either layout', monitored => {
    for (const wide of [false, true]) {
      const markup = renderToStaticMarkup(
        <PullRowItem row={{ ...row, monitored }} wide={wide} selected monitoring onPick={() => {}} onMonitor={() => {}} />,
      );

      expect(markup).toContain(`class="pull-card__monitor${monitored ? ' is-active' : ''}"`);
      expect(markup).toContain('title="Saving PR monitoring…"');
      expect(markup).toContain('aria-label="Saving PR monitoring…: owner/repo #42"');
      expect(markup).toContain(`aria-pressed="${monitored}" aria-busy="true" disabled=""`);
      expect(markup).toContain('lucide-loader-circle');
      expect(markup).not.toContain('lucide-bell');
    }
  });

  it.each([false, true])('disables monitoring a merged PR in either layout (%s)', wide => {
    const markup = renderToStaticMarkup(
      <PullRowItem row={{ ...row, kind: 'merged' }} wide={wide} selected={false} onPick={() => {}} onMonitor={() => {}} />,
    );

    expect(markup).toContain('title="Merged PRs cannot be monitored"');
    expect(markup).toContain('aria-pressed="false" aria-busy="false" disabled=""');
  });
});
