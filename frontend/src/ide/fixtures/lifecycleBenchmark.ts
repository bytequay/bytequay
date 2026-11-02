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
import { attachPart, Parts } from '@codingame/monaco-vscode-views-service-override';
import { assert } from '../../fixtures/harness';
import { restoreNavigation, restoreSourceDrafts, trackNavigation, trackSourceDrafts } from '../drafts';
import { idleState } from '../idleState';
import { startWorkbench } from '../startup';

declare global {
  interface Window { benchmarkTiming?: { mark: (name: string, timestamp: number) => void } }
}
const mark = (name: string) => window.benchmarkTiming?.mark(name, performance.timeOrigin + performance.now());
mark('rendererModuleLoaded');

export async function run() {
  mark('rendererRun');
  const { connection } = await window.ideWorkbench.bootstrap();
  mark('bootstrapReady');
  const { runtime } = await startWorkbench(connection);
  mark('runtimeReady');
  const host = document.createElement('div');
  host.style.cssText = 'width:1000px;height:700px'; document.body.append(host);
  attachPart(Parts.EDITOR_PART, host);
  const fail = (message: string) => { throw new Error(message); };
  const drafts = trackSourceDrafts(runtime, fail);
  await restoreSourceDrafts(runtime, () => fail('Unexpected synthetic draft conflict'));
  mark('draftsRestored');
  await restoreNavigation(runtime);
  mark('navigationRestored');
  const uri = runtime.api.Uri.joinPath(runtime.workspace, 'src/example/Entry.java');
  const restoredNavigation = runtime.api.window.activeTextEditor?.document.uri.path === uri.path;
  const navigation = trackNavigation(runtime, fail);
  if (!restoredNavigation) await runtime.api.window.showTextDocument(await runtime.api.workspace.openTextDocument(uri), { preview: false });
  mark('editorReady');
  const verify = async () => {
    mark('navigationQueriesStarted');
    let queryAttempts = 0;
    let definitionReady = false;
    let referencesReady = false;
    const deadline = performance.now() + 120_000;
    do {
      queryAttempts++;
      const definitions = await runtime.api.commands.executeCommand<{ uri?: { path: string }; targetUri?: { path: string } }[]>(
        'vscode.executeDefinitionProvider', uri, new runtime.api.Position(3, 11));
      const hasDefinition = definitions?.some(item => (item.uri ?? item.targetUri)?.path.endsWith('/Class0.java')) === true;
      if (hasDefinition && !definitionReady) { definitionReady = true; mark('firstDefinitionReady'); }
      const references = await runtime.api.commands.executeCommand<unknown[]>('vscode.executeReferenceProvider', uri, new runtime.api.Position(3, 11));
      if (references?.length > 0 && !referencesReady) { referencesReady = true; mark('firstReferencesReady'); }
      if (hasDefinition && references?.length > 0) {
        const editor = runtime.editors.getActiveCodeEditor();
        assert(editor !== null && editor.getLayoutInfo().width >= 800, 'The native editor must retain its layout');
        assert(runtime.api.window.activeTextEditor?.document.uri.path === uri.path, 'Navigation must return to the saved file');
        mark('javaNavigationReady');
        return { definitions: definitions.length, references: references.length, restoredNavigation, queryAttempts, pollWaitMs: (queryAttempts - 1) * 500 };
      }
      await new Promise(resolve => setTimeout(resolve, 500));
    } while (performance.now() < deadline);
    throw new Error('Real Java navigation did not become ready');
  };
  const ready = await verify();
  const inspect = () => ({ state: idleState(runtime), nativeTerminals: runtime.terminals.instances.map(terminal => ({ id: terminal.instanceId, title: terminal.title, processId: terminal.processId })),
    openDocuments: runtime.api.workspace.textDocuments.map(document => ({ path: document.uri.path, dirty: document.isDirty })),
    pendingSave: runtime.saves.pending, starting: runtime.executions.starting, running: runtime.executions.run !== null,
    tests: runtime.tests.results.map(result => ({ completed: result.completedAt !== undefined })) });
  const report = () => window.ideWorkbench.report(idleState(runtime));
  const stopIdle = window.ideWorkbench.onIdleCheck?.(idleCheckId => window.ideWorkbench.report({ ...idleState(runtime), idleCheckId }));
  const timer = setInterval(report, 5_000);
  report();
  window.addEventListener('beforeunload', () => {
    clearInterval(timer); stopIdle?.(); navigation.dispose(); drafts.dispose();
  }, { once: true });
  return { ready, verify, inspect };
}
