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
import { describe, expect, it } from 'vitest';
import type { IExtension } from '@codingame/monaco-vscode-api/vscode/vs/platform/extensions/common/extensions';
// eslint-disable-next-line import/no-unresolved -- This is the pinned package's exported path, resolved by this test and tsc.
import { EnablementState } from '@codingame/monaco-vscode-api/vscode/vs/workbench/services/extensionManagement/common/extensionManagement';
import { ExtensionPolicy } from './extensionPolicy';

function extension(id: string, version = '1.0.0', dependencies: string[] = []): IExtension {
  return {
    identifier: { id }, isValid: true,
    manifest: { version, extensionDependencies: dependencies },
  } as IExtension;
}

describe('curated IDE extensions', () => {
  const policy = () => new ExtensionPolicy({ 'bytequay.review': '1.0.0', 'redhat.java': '1.0.0' });

  it('excludes bundled repository mutation and authentication extensions', () => {
    const service = policy();
    const installed = [extension('vscode.git'), extension('vscode.github'),
      extension('vscode.github-authentication'), extension('redhat.java')];
    expect(installed.filter(item => service.isEnabled(item)).map(item => item.identifier.id))
      .toEqual(['redhat.java']);
  });

  it('rejects an unpinned upgrade, invalid manifest, or unapproved dependency', () => {
    const service = policy();
    expect(service.isEnabled(extension('redhat.java', '2.0.0'))).toBe(false);
    expect(service.isEnabled({ ...extension('redhat.java'), isValid: false })).toBe(false);
    expect(service.isEnabled(extension('bytequay.review', '1.0.0', ['vscode.git']))).toBe(false);
    expect(service.isEnabled(extension('ByteQuay.Review', '1.0.0', ['redhat.java']))).toBe(true);
  });

  it('cannot be widened through the workbench enablement API', async () => {
    const service = policy();
    expect(service.canChangeEnablement()).toBe(false);
    expect(service.canChangeWorkspaceEnablement()).toBe(false);
    expect(service.isEnabledEnablementState(EnablementState.EnabledGlobally)).toBe(false);
    await expect(service.setEnablement()).rejects.toThrow('managed with the IDE runtime bundle');
    expect(service.isEnabled(extension('vscode.git'))).toBe(false);
  });
});
