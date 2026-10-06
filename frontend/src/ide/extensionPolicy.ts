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
// eslint-disable-next-line import/no-unresolved -- ESLint's legacy resolver misses package exports; tsc and the runtime build resolve this path.
import { Event } from '@codingame/monaco-vscode-api/vscode/vs/base/common/event';
import type { IExtension } from '@codingame/monaco-vscode-api/vscode/vs/platform/extensions/common/extensions';
// eslint-disable-next-line import/no-unresolved -- This is the pinned package's exported path, validated by tsc and the runtime build.
import { EnablementState } from '@codingame/monaco-vscode-api/vscode/vs/workbench/services/extensionManagement/common/extensionManagement';
import type { IWorkbenchExtensionEnablementService } from '@codingame/monaco-vscode-api/vscode/vs/workbench/services/extensionManagement/common/extensionManagement.service';

// The modular runtime's default enablement service enables everything, including bundled Git/GitHub commands.
// Settings such as disabledExtensions do not enforce a curated bundle without this service.
export class ExtensionPolicy implements IWorkbenchExtensionEnablementService {
  readonly _serviceBrand: undefined = undefined;
  readonly onEnablementChanged = Event.None;
  private extensions: IExtension[] = [];

  constructor(private readonly versions: Readonly<Record<string, string>>) {}

  getEnablementState(extension: IExtension): EnablementState {
    if (!extension.isValid) return EnablementState.DisabledByInvalidExtension;
    const id = extension.identifier.id.toLowerCase();
    if (this.versions[id] !== extension.manifest.version) return EnablementState.DisabledByAllowlist;
    if (extension.manifest.extensionDependencies?.some(dependency => !this.versions[dependency.toLowerCase()])) {
      return EnablementState.DisabledByExtensionDependency;
    }
    return EnablementState.EnabledByEnvironment;
  }

  getEnablementStates(extensions: IExtension[]): EnablementState[] {
    this.extensions = extensions;
    return extensions.map(extension => this.getEnablementState(extension));
  }

  getDependenciesEnablementStates(extension: IExtension): [IExtension, EnablementState][] {
    const dependencies = new Set(extension.manifest.extensionDependencies?.map(id => id.toLowerCase()));
    return this.extensions.filter(item => dependencies.has(item.identifier.id.toLowerCase()))
      .map(item => [item, this.getEnablementState(item)]);
  }

  isEnabled(extension: IExtension): boolean {
    return this.isEnabledEnablementState(this.getEnablementState(extension));
  }

  isEnabledEnablementState(state: EnablementState): boolean {
    return state === EnablementState.EnabledByEnvironment;
  }

  isDisabledGlobally(extension: IExtension): boolean {
    return !this.isEnabled(extension);
  }

  canChangeEnablement(): boolean { return false; }
  canChangeWorkspaceEnablement(): boolean { return false; }

  async setEnablement(): Promise<boolean[]> {
    throw new Error('ByteQuay extensions are managed with the IDE runtime bundle.');
  }

  async updateExtensionsEnablementsWhenWorkspaceTrustChanges(): Promise<void> {
    // Runtime ownership is enforced by ByteQuay; workspace settings cannot change the installed extension set.
  }
}
