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
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import AppSidebar, { type AppPage } from './AppSidebar';
import type { WorkspaceDto } from './types';

const WORKSPACE = {
  id: 'ws-1',
  name: 'trinodb/trino',
  selectedOwner: 'trinodb',
  selectedRepo: 'trino',
  monitoredOwner: 'trinodb',
  monitoredRepo: 'trino',
  localRepositoryPath: '/managed/ws-1/repository',
  topology: 'DIRECT',
} as unknown as WorkspaceDto;

function render(page: AppPage, workspace: WorkspaceDto | null, settings = false) {
  return renderToStaticMarkup(
    <AppSidebar
      page={page}
      onNavigate={vi.fn()}
      canGoBack={false}
      canGoForward={false}
      onBack={vi.fn()}
      onForward={vi.fn()}
      workspace={workspace}
      workspaceTab="home"
      onWorkspaceTab={vi.fn()}
      settings={settings ? {
        section: 'models',
        workspaces: [WORKSPACE],
        workspaceId: 'ws-1',
        onSection: vi.fn(),
        onWorkspace: vi.fn(),
        onExit: vi.fn(),
      } : undefined}
    />,
  );
}

/** Rows are counted by their shared class, which is the point of the shape. */
function rowCount(markup: string): number {
  return markup.split('class="app-sidebar__item').length - 1;
}

describe('app sidebar', () => {
  const app = render('home', null);
  const workspace = render('workspaces', WORKSPACE);
  const settings = render('settings', null, true);

  it('offers Settings from inside a workspace, not only outside one', () => {
    expect(app).toContain('aria-label="Settings"');
    // The workspace tab this replaced left no way into settings from a workspace.
    expect(workspace).toContain('aria-label="Settings"');
    expect(workspace).toContain('aria-label="Application settings"');
  });

  it('keeps its own tabs out of settings and supplies the category nav instead', () => {
    expect(settings).toContain('aria-label="Settings navigation"');
    expect(settings).not.toContain('aria-label="Global navigation"');
    expect(settings).not.toContain('aria-label="Workspace navigation"');
    expect(settings).toContain('aria-label="Back to app"');
  });

  it('renders every shape through the same row, so they share size and markup', () => {
    for (const markup of [app, workspace, settings]) {
      expect(rowCount(markup)).toBeGreaterThan(2);
      // One row shape everywhere: an aria-hidden icon slot plus the collapsible label.
      expect(markup).toContain('<span aria-hidden="true">');
      expect(markup).toContain('class="app-sidebar__label"');
    }
    expect(settings).toContain('class="app-sidebar__item is-active"');
    expect(workspace).toContain('class="app-sidebar__item is-active"');
  });

  it('shares the chrome that makes the boundary draggable', () => {
    for (const markup of [app, workspace, settings]) {
      expect(markup).toContain('class="app-sidebar__resize"');
      expect(markup).toContain('aria-label="Window controls"');
    }
  });
});
