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
import { createRoot } from 'react-dom/client';
import { flushSync } from 'react-dom';
import { RelatedWorkspaceNavigation, RelatedWorkspaceSettings } from '../RelatedWorkspaces';
import type { WorkspaceDto, WorkspaceRelationshipDto } from '../../types';
import { assert, changeInput, settle } from '../../fixtures/harness';
import '../../css/base.css';
import '../../css/home.css';
import '../../css/settings-window.css';

const workspace = (id: string, repositoryId: number, name: string): WorkspaceDto => ({
  id, name, selectedOwner: 'owner', selectedRepo: id, monitoredOwner: 'apache', monitoredRepo: id,
  selectedRepositoryId: repositoryId, monitoredRepositoryId: repositoryId,
  upstreamRepositoryId: null, upstreamOwner: null, upstreamRepo: null, topology: 'DIRECT', defaultBranch: 'main',
  upstreamDefaultBranch: null, localRepositoryPath: `/managed/${id}`, lastCodeSyncAt: '2026-01-01T00:00:00Z',
  lastMetadataSyncAt: null, displayOrder: 0,
});
const trino = workspace('trino', 1, 'Trino');
const iceberg = workspace('iceberg', 2, 'Apache Iceberg');
const alias = workspace('alias', 1, 'Same monitored project');

export function mount() {
  let links: WorkspaceRelationshipDto[] = [];
  let reject = false;
  Object.defineProperty(window, 'bridge', { configurable: true, value: {
    getWorkspaceRelationships: async () => links,
    listWorkspaces: async () => [trino, iceberg, alias],
    saveWorkspaceRelationship: async (_: string, link: WorkspaceRelationshipDto) => {
      if (reject) throw new Error('Workspace became unavailable');
      links = [...links.filter(item => item.workspaceId !== link.workspaceId), link];
      return links;
    },
    removeWorkspaceRelationship: async (_: string, id: string) => { links = links.filter(item => item.workspaceId !== id); return links; },
  } });
  const host = document.createElement('div');
  host.style.cssText = 'display:flex;max-width:1000px;margin:36px auto;gap:32px';
  document.body.append(host);
  const root = createRoot(host);
  flushSync(() => root.render(<><aside style={{ width: 190 }}><RelatedWorkspaceNavigation workspaceId="trino" /></aside>
    <main className="setw-page" style={{ flex: 1 }}><h1>Repository</h1><RelatedWorkspaceSettings workspace={trino} /></main></>));
  return { host, reject: (value: boolean) => { reject = value; },
    missingCheckout: () => { links = [{ workspaceId: 'iceberg', kind: 'DEPENDENCY', backportByDefault: false, guidance: 'Keep this', unavailableReason: 'Managed checkout missing' }]; window.dispatchEvent(new Event('bytequay:relationships-changed')); },
    unavailable: () => { links = [{ workspaceId: 'missing', kind: 'FORK', backportByDefault: false, guidance: 'Saved guidance' }]; window.dispatchEvent(new Event('bytequay:relationships-changed')); },
    close: () => { flushSync(() => root.unmount()); host.remove(); } };
}

export async function run() {
  const view = mount();
  const button = (label: string) => {
    const found = [...view.host.querySelectorAll('button')].find(item => item.textContent?.trim() === label || item.getAttribute('aria-label') === label);
    assert(found !== undefined && !found.disabled, `Enabled ${label} button`);
    return found;
  };
  const click = async (label: string) => { button(label).click(); await settle(); };
  try {
    await settle();
    assert(view.host.textContent?.includes('No related workspaces'), 'Empty state');
    await click('+ Add related workspace');
    await click('Related workspace');
    const options = [...view.host.querySelectorAll<HTMLButtonElement>('[role="option"]')];
    assert(options.find(item => item.textContent?.includes('Trino'))?.disabled, 'Self disabled');
    assert(options.find(item => item.textContent?.includes('Same monitored project'))?.disabled, 'Same logical project disabled');
    await click('Apache Iceberg');
    assert(button('Dependency').getAttribute('aria-checked') === 'false', 'Type requires an explicit choice');
    await click('Dependency');
    const guidance = view.host.querySelector<HTMLTextAreaElement>('textarea');
    assert(guidance !== null, 'Guidance input');
    changeInput(guidance, 'For connector behavior, read Iceberg. Version is in pom.xml.');
    await settle();
    view.reject(true);
    await click('Add');
    assert(view.host.querySelector('[role="alert"]')?.textContent === 'Workspace became unavailable', 'Server rejection inline');
    assert(view.host.querySelector('textarea')?.value.includes('pom.xml'), 'Failed save keeps guidance');
    view.reject(false);
    await click('Add');
    assert(view.host.querySelector('textarea') === null, 'Successful save closes form');
    assert(view.host.querySelectorAll('a[href="#/workspaces/iceberg/commits"]').length === 2, 'Settings and sidebar link to destination commits');
    assert(view.host.querySelectorAll('a').length === 4, 'Memory and commits links on both surfaces');
    await click('Edit Apache Iceberg');
    await click('Fork');
    const backport = view.host.querySelector<HTMLInputElement>('input[type="checkbox"]');
    assert(backport !== null, 'Fork exposes a default backport source');
    backport.click(); await settle();
    await click('Save');
    assert(view.host.textContent?.includes('Default backport source → Trino'), 'Default backport direction is saved and shown');
    assert(view.host.querySelector('.related-kind')?.textContent === 'Fork', 'Edit updates sidebar');
    await click('Remove Apache Iceberg');
    assert(view.host.querySelector('[aria-label="Related workspaces"]') === null, 'Removed link disappears from sidebar');
    view.missingCheckout(); await settle();
    assert(view.host.querySelectorAll('a').length === 0, 'Missing checkout disables links on both surfaces');
    assert(view.host.textContent?.includes('Keep this'), 'Missing checkout keeps guidance');
    view.unavailable(); await settle();
    assert(view.host.textContent?.includes('Workspace unavailable'), 'Unavailable destination keeps saved relationship visible');
    await click('Remove missing');
    return [{ name: 'settings, server errors, navigation, edit and removal' }];
  } finally { view.close(); }
}
