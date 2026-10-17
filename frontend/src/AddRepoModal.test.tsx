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
import AddRepoModal, { WatchRepositoryConfirmation } from './AddRepoModal';
import type { WatchRepositoryPreviewDto } from './types';
import { runBrowserFixture } from './fixtures/browserFixture';

const upstream = { owner: 'trinodb', repo: 'trino' };
const fork = { owner: 'octocat', repo: 'trino' };
const preview: WatchRepositoryPreviewDto = {
  selectedRepositoryId: 1,
  selectedRepository: upstream,
  visibility: 'public',
  topology: 'FORK',
  recommendedTopology: 'FORK',
  forkModeAvailable: true,
  upstreamRepositoryId: 1,
  upstreamRepository: upstream,
  monitoredRepositoryId: 1,
  monitoredRepository: upstream,
  defaultBranch: 'master',
  developmentBase: 'upstream/master',
  pushRemote: 'origin',
  pushRepository: fork,
  pushWritable: false,
  forkRequired: true,
  pullRequestBase: upstream,
  fingerprint: 'confirmed-routing',
  existingWorkspaceId: null,
};

function confirmation(overrides: Partial<WatchRepositoryPreviewDto> = {}, adding = false) {
  const onWatch = vi.fn();
  const onBack = vi.fn();
  const onOpenWorkspace = vi.fn();
  const value = { ...preview, ...overrides };
  const view = WatchRepositoryConfirmation({ preview: value, topology: value.topology, ready: true, adding,
    onTopologyChange: vi.fn(), onWatch, onBack, onOpenWorkspace });
  const actions = view.props.children[5] as ReactElement<{ children: ReactElement<ButtonHTMLAttributes<HTMLButtonElement>>[] }>;
  return {
    onWatch,
    onBack,
    onOpenWorkspace,
    html: renderToStaticMarkup(view),
    back: actions.props.children[1],
    confirm: actions.props.children[2],
  };
}

describe('watch repository fork consent', () => {
  it('starts Watch again with a routing preview before offering any watch action', () => {
    const onAdded = vi.fn();
    const onOpenWorkspace = vi.fn();
    const html = renderToStaticMarkup(<AddRepoModal initialRepositoryId={1} onAdded={onAdded} onOpenWorkspace={onOpenWorkspace} onClose={vi.fn()} />);

    expect(html).toContain('Checking repository routing…');
    expect(html).not.toContain('Search your GitHub repositories');
    expect(html).not.toContain('modal-preview__confirm');
    expect(onAdded).not.toHaveBeenCalled();
    expect(onOpenWorkspace).not.toHaveBeenCalled();
  });

  it('discloses the exact fork and upstream without creating anything on preview or Back', () => {
    const { html, onWatch, onBack, back, confirm } = confirmation();

    expect(html).toContain('Allow ByteQuay to create octocat/trino as your personal fork of trinodb/trino?');
    expect(html).toContain('>Create fork and watch</button>');
    expect(html).toContain('<span>Followed for updates</span><b>trinodb/trino</b>');
    expect(html).toContain('<span>Work branches push to</span><b>octocat/trino</b>');
    expect(html).toContain('<span>New branches start from</span><b>upstream/master</b>');
    expect(html).toContain('<span>Pull requests open against</span><b>trinodb/trino · master</b>');
    expect(html).not.toContain('You can’t push');
    expect(onWatch).not.toHaveBeenCalled();

    back.props.onClick?.({} as MouseEvent<HTMLButtonElement>);
    expect(onBack).toHaveBeenCalledOnce();
    expect(onWatch).not.toHaveBeenCalled();

    confirm.props.onClick?.({} as MouseEvent<HTMLButtonElement>);
    expect(onWatch).toHaveBeenCalledOnce();
    expect(onWatch).toHaveBeenCalledWith({
      selectedRepositoryId: 1,
      topology: 'FORK',
      previewFingerprint: 'confirmed-routing',
      allowForkCreation: true,
    });
  });

  it.each([
    { selectedRepositoryId: 1, selectedRepository: upstream },
    { selectedRepositoryId: 2, selectedRepository: fork },
  ])('reuses the personal fork when watching $selectedRepository.owner/$selectedRepository.repo', selected => {
    const { html, onWatch, confirm } = confirmation({ ...selected, pushWritable: true, forkRequired: false });

    expect(html).toContain('reuse your existing personal fork octocat/trino');
    expect(html).toContain('class="modal-preview__name">trinodb/trino</span>');
    expect(html).toContain('aria-label="trinodb/trino"');
    expect(html).not.toContain('octocat.png');
    expect(html).toContain('follow trinodb/trino for commits, issues and pull requests');
    expect(html).toContain('>Watch repository</button>');
    expect(html).not.toContain('Create fork and watch');
    expect(onWatch).not.toHaveBeenCalled();

    confirm.props.onClick?.({} as MouseEvent<HTMLButtonElement>);
    expect(onWatch).toHaveBeenCalledOnce();
    expect(onWatch).toHaveBeenCalledWith({
      selectedRepositoryId: selected.selectedRepositoryId,
      topology: 'FORK',
      previewFingerprint: 'confirmed-routing',
      allowForkCreation: false,
    });
  });

  it('keeps repositories created by the user direct and grants no fork permission', () => {
    const { html, onWatch, confirm } = confirmation({
      selectedRepository: fork,
      topology: 'DIRECT',
      recommendedTopology: 'DIRECT',
      forkModeAvailable: false,
      upstreamRepositoryId: null,
      upstreamRepository: null,
      monitoredRepository: fork,
      developmentBase: 'origin/master',
      pushWritable: true,
      forkRequired: false,
      pullRequestBase: fork,
    });

    expect(html).toContain('ByteQuay will clone octocat/trino directly, follow its commits, issues and pull requests, and publish work branches to this repository.');
    expect(html).not.toContain('Personal fork');
    expect(html).toContain('Direct clone requires write permission');
    confirm.props.onClick?.({} as MouseEvent<HTMLButtonElement>);
    expect(onWatch).toHaveBeenCalledWith(expect.objectContaining({ topology: 'DIRECT', allowForkCreation: false }));
  });

  it('opens the existing workspace shared by upstream and fork without another watch command', () => {
    const { html, onWatch, onOpenWorkspace, confirm } = confirmation({
      existingWorkspaceId: 'existing-workspace',
      pushWritable: true,
      forkRequired: false,
    });

    expect(html).toContain('>Open workspace</button>');
    confirm.props.onClick?.({} as MouseEvent<HTMLButtonElement>);
    expect(onOpenWorkspace).toHaveBeenCalledOnce();
    expect(onOpenWorkspace).toHaveBeenCalledWith('existing-workspace');
    expect(onWatch).not.toHaveBeenCalled();
  });

  it('disables both actions while the confirmed watch is in progress', () => {
    const { html, back, confirm } = confirmation({}, true);

    expect(back.props.disabled).toBe(true);
    expect(confirm.props.disabled).toBe(true);
    expect(html).toContain('>Watching…</button>');
  });
});

it('preserves watch progress on background clicks and keeps explicit cancellation in Chromium', async () => {
  const results = await runBrowserFixture<string[]>({
    prefix: 'bytequay-watch-progress-',
    entry: 'src/fixtures/watchRepositoryBrowser.tsx',
    global: 'WatchRepositoryTests',
    styles: true,
    charset: true,
    body: "const results = await window.webContents.executeJavaScript('(async () => [...await WatchRepositoryTests.run(), ...await WatchRepositoryTests.runStrategies()])()');",
  });
  expect(results).toEqual(['background clicks preserve progress', 'explicit cancellation discards a late preview', 'preview caches belong to each repository and dialog',
    'watch from review confirms and refreshes',
    'workflow switching reuses pending and completed previews', 'workflow switching ignores stale previews', 'workflow errors recover without changing consent']);
}, 30_000);
