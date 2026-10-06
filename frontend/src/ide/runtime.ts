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
/* eslint-disable import/no-unresolved -- The pinned modular packages export these paths; Vite and TypeScript resolve them. */
import { initialize, getService, ICodeEditorService, ITestResultService, IDebugService, IViewDescriptorService, ITerminalService, IPaneCompositePartService, IExtensionService } from '@codingame/monaco-vscode-api';
import { registerExtension, ExtensionHostKind } from '@codingame/monaco-vscode-api/extensions';
import { IWorkbenchExtensionEnablementService } from '@codingame/monaco-vscode-api/vscode/vs/workbench/services/extensionManagement/common/extensionManagement.service';
import { SyncDescriptor } from '@codingame/monaco-vscode-api/vscode/vs/platform/instantiation/common/descriptors';
import { URI } from '@codingame/monaco-vscode-api/vscode/vs/base/common/uri';
import getViews from '@codingame/monaco-vscode-views-service-override';
import getConfiguration, { initUserConfiguration } from '@codingame/monaco-vscode-configuration-service-override';
import getModel from '@codingame/monaco-vscode-model-service-override';
import getStorage from '@codingame/monaco-vscode-storage-service-override';
import getTheme from '@codingame/monaco-vscode-theme-service-override';
import getTextmate from '@codingame/monaco-vscode-textmate-service-override';
import getLanguages from '@codingame/monaco-vscode-languages-service-override';
import getKeys, { initUserKeybindings, updateUserKeybindings } from '@codingame/monaco-vscode-keybindings-service-override';
import getExplorer from '@codingame/monaco-vscode-explorer-service-override';
import getTesting from '@codingame/monaco-vscode-testing-service-override';
import getDebug from '@codingame/monaco-vscode-debug-service-override';
import getTasks from '@codingame/monaco-vscode-task-service-override';
import getTerminal from '@codingame/monaco-vscode-terminal-service-override';
import getRemote from '@codingame/monaco-vscode-remote-agent-service-override';
import getSearch from '@codingame/monaco-vscode-search-service-override';
import getScm from '@codingame/monaco-vscode-scm-service-override';
import EditorWorker from '@codingame/monaco-vscode-api/workers/editor.worker?worker';
import TextmateWorker from '@codingame/monaco-vscode-textmate-service-override/worker?worker';
import '@codingame/monaco-vscode-theme-defaults-default-extension';
import '@codingame/monaco-vscode-java-default-extension';
import '@codingame/monaco-vscode-go-default-extension';
import '@codingame/monaco-vscode-rust-default-extension';
import '@codingame/monaco-vscode-javascript-default-extension';
import 'vscode/localExtensionHost';
import { coordinateSaves } from './save';
import { coordinateExecutions } from './executions';
import { ExtensionPolicy } from './extensionPolicy';
import manifest from './runtimeManifest.json';
import light from './ideaLight.json';
import dark from './darcula.json';
import type { IdeConnection } from './contracts';

const ideaKeys = [
    { key: 'cmd+b', command: 'editor.action.revealDefinition', when: 'editorTextFocus' },
    { key: 'alt+f7', command: 'editor.action.referenceSearch.trigger', when: 'editorTextFocus' },
    { key: 'ctrl+alt+h', command: 'editor.showCallHierarchy', when: 'editorTextFocus' },
    { key: 'cmd+o', command: 'workbench.action.showAllSymbols' },
    { key: 'cmd+shift+o', command: 'workbench.action.quickOpen' },
    { key: 'cmd+f12', command: 'workbench.action.gotoSymbol' },
];

export async function chooseKeymap(name: string) {
  await updateUserKeybindings(JSON.stringify(name === 'IDEA' ? ideaKeys : []));
  localStorage.setItem('bytequay.ide.keymap', name);
}

export type IdeRuntime = Awaited<ReturnType<typeof startRuntime>>;

export async function startRuntime(connection: IdeConnection, container: HTMLElement) {
  window.MonacoEnvironment = { getWorker: (_id, label) => label === 'TextMateWorker' ? new TextmateWorker() : new EditorWorker() };
  const workspace = URI.from({ scheme: 'vscode-remote', authority: connection.authority, path: connection.root });
  const theme = localStorage.getItem('bytequay.ide.theme') ?? 'ByteQuay IDEA Light';
  await initUserConfiguration(JSON.stringify({
    'workbench.colorTheme': theme,
    'workbench.activityBar.location': 'hidden', 'workbench.statusBar.visible': false,
    'workbench.startupEditor': 'none', 'workbench.editor.restoreViewState': true,
    'editor.minimap.enabled': false, 'editor.fontSize': 13, 'editor.fontFamily': 'Menlo, monospace',
    'telemetry.telemetryLevel': 'off', 'java.autobuild.enabled': true,
    'java.configuration.updateBuildConfiguration': 'automatic', 'java.trace.server': 'off',
    'go.showWelcome': false, 'go.toolsManagement.autoUpdate': false, 'go.toolsManagement.checkForUpdates': 'off',
    'rust-analyzer.testExplorer': true, 'extensions.autoUpdate': false, 'extensions.autoCheckUpdates': false,
    'testing.saveBeforeTest': false, 'debug.saveBeforeStart': 'none', 'task.saveBeforeRun': 'never',
    'task.allowAutomaticTasks': 'off', 'task.reconnection': false,
    'git.enabled': false, 'scm.graph.pageSize': 100, 'terminal.integrated.enablePersistentSessions': false,
  }));
  await initUserKeybindings(JSON.stringify(localStorage.getItem('bytequay.ide.keymap') === 'VS Code' ? [] : ideaKeys));
  const extension = registerExtension({
    name: 'workbench', publisher: 'bytequay', version: '1.0.0', engines: { vscode: '*' }, enabledApiProposals: ['scmHistoryProvider'],
    contributes: { themes: [
      { id: 'ByteQuay IDEA Light', label: 'ByteQuay IDEA Light', uiTheme: 'vs', path: './ideaLight.json' },
      { id: 'ByteQuay Darcula', label: 'ByteQuay Darcula', uiTheme: 'vs-dark', path: './darcula.json' },
    ] },
  }, ExtensionHostKind.LocalProcess, { system: true });
  extension.registerFileUrl('ideaLight.json', `data:application/json,${encodeURIComponent(JSON.stringify(light))}`);
  extension.registerFileUrl('darcula.json', `data:application/json,${encodeURIComponent(JSON.stringify(dark))}`);
  const versions = Object.fromEntries([
    ...manifest.extensions.map(item => [item.id, item.version]),
    ...['theme-defaults', 'java', 'go', 'rust', 'javascript'].map(name => [`vscode.${name}`, '10.0.0']),
    ['bytequay.workbench', '1.0.0'],
  ]);
  await initialize({
    [IWorkbenchExtensionEnablementService.toString()]: new SyncDescriptor(ExtensionPolicy, [versions]),
    ...getConfiguration(), ...getModel(), ...getStorage(), ...getViews(), ...getTheme(), ...getTextmate(),
    ...getLanguages(), ...getKeys(), ...getExplorer(), ...getTesting(), ...getDebug(), ...getTerminal(), ...getTasks(), ...getSearch(), ...getScm(),
    ...getRemote({ scanRemoteExtensions: true }),
  }, container, {
    resourceUriProvider: uri => URI.from({ scheme: 'https', authority: 'ide-assets.bytequay.invalid', path: uri.path }),
    remoteAuthority: connection.authority, connectionToken: connection.connectionToken,
    productConfiguration: { commit: connection.commit, quality: 'stable' },
    workspaceProvider: { workspace: { folderUri: workspace }, trusted: true, open: async () => false },
  });
  await coordinateSaves(connection);
  const api = await extension.getApi();
  await (await getService(IExtensionService)).whenInstalledExtensionsRegistered();
  const views = await getService(IViewDescriptorService);
  for (const id of ['workbench.view.extension.test', 'workbench.view.debug']) {
    const view = views.getViewContainerById(id);
    if (view) views.moveViewContainerToLocation(view, 1, undefined, 'ByteQuay');
  }
  return {
    api, workspace, views, executions: await coordinateExecutions(api, connection.root, connection.authority),
    editors: await getService(ICodeEditorService),
    tests: await getService(ITestResultService),
    debug: await getService(IDebugService),
    terminals: await getService(ITerminalService),
    panels: await getService(IPaneCompositePartService),
  };
}
