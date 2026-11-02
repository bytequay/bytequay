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
import { startWorkbench } from '../startup';
import { attachPart, Parts } from '@codingame/monaco-vscode-views-service-override';
import { assert } from '../../fixtures/harness';
import { testEvidence } from '../testEvidence';
import { getService, ITestService } from '@codingame/monaco-vscode-api';
import type { CallHierarchyIncomingCall, CallHierarchyItem, SymbolInformation } from 'vscode';

export async function run() {
  const { connection } = await window.ideWorkbench.bootstrap();
  const integration = await window.ideWorkbench.request('benchmark.integration');
  const started = performance.now();
  const { runtime } = await startWorkbench(connection);
  const host = document.createElement('div');
  host.style.cssText = 'width:1000px;height:700px'; document.body.append(host);
  attachPart(Parts.EDITOR_PART, host);
  const uri = runtime.api.Uri.joinPath(runtime.workspace, 'module0/src/example/Entry.java');
  const sourceDocument = await runtime.api.workspace.openTextDocument(uri);
  await runtime.api.window.showTextDocument(sourceDocument, { preview: false });
  const position = new runtime.api.Position(3, 11);
  const until = async <T>(read: () => PromiseLike<T>, accept: (value: T) => boolean, label: string): Promise<T> => {
    const deadline = performance.now() + 180_000;
    let last: T;
    do {
      last = await read();
      if (accept(last)) return last;
      await new Promise(resolve => setTimeout(resolve, 500));
    } while (performance.now() < deadline);
    throw new Error(`${label} unavailable: ${JSON.stringify(last)}`);
  };
  const definition = () => runtime.api.commands.executeCommand<{ uri?: { path: string }; targetUri?: { path: string } }[]>('vscode.executeDefinitionProvider', uri, position);
  const locations = await until(definition, value => Array.isArray(value) && value.some(item => (item.uri ?? item.targetUri)?.path.endsWith('/Class0.java') === true), 'Java definition');
  const references = await until(() => runtime.api.commands.executeCommand<unknown[]>('vscode.executeReferenceProvider', uri, position), value => Array.isArray(value) && value.length > 0, 'Java references');
  assert(Array.isArray(references) && references.length > 0, 'Real Java references must resolve');
  const editor = runtime.editors.getActiveCodeEditor();
  assert(editor !== null && editor.getLayoutInfo().width >= 800 && editor.getLayoutInfo().height >= 500, 'The styled native editor must fill the fixture');
  const editorNode = editor.getDomNode();
  assert(editorNode !== null && getComputedStyle(editorNode).position === 'relative', 'The native editor stylesheet must be active');
  await window.ideWorkbench.request('benchmark.phase', 'ready');
  const coldMs = performance.now() - started;
  const warmStarted = performance.now();
  for (let i = 0; i < 20; i++) {
    const found = await definition();
    assert(found?.length > 0, 'Warm definition must resolve');
  }
  const warmMs = performance.now() - warmStarted;
  await window.ideWorkbench.request('benchmark.phase', 'idle');
  await new Promise(resolve => setTimeout(resolve, integration ? 1000 : 70_000));
  let passedTests = 0;
  let symbols = 0;
  let callers = 0;
  if (integration) {
    await window.ideWorkbench.request('benchmark.phase', 'integration');
    const foundSymbols = await runtime.api.commands.executeCommand<SymbolInformation[]>('vscode.executeWorkspaceSymbolProvider', 'Entry');
    assert(foundSymbols.some(symbol => symbol.name === 'Entry'), 'Workspace class search must resolve');
    symbols = foundSymbols.length;
    const classUri = runtime.api.Uri.joinPath(runtime.workspace, 'module0/src/example/Class0.java');
    const hierarchy = await runtime.api.commands.executeCommand<CallHierarchyItem[]>('vscode.prepareCallHierarchy', classUri, new runtime.api.Position(2, 23));
    assert(hierarchy.length > 0, 'Method call hierarchy must resolve');
    const incoming = await runtime.api.commands.executeCommand<CallHierarchyIncomingCall[]>('vscode.provideIncomingCalls', hierarchy[0]);
    assert(incoming.some(call => call.from.uri.path.endsWith('/Entry.java')), 'The caller must identify the generated Entry method');
    callers = incoming.length;
    const testUri = runtime.api.Uri.joinPath(runtime.workspace, 'module0/src/example/EntryTest.java');
    await runtime.api.window.showTextDocument(await runtime.api.workspace.openTextDocument(testUri), { preview: false });
    const tests = await getService(ITestService);
    const discovered = await until(async () => {
      await tests.refreshTests();
      for (const id of tests.collection.rootIds) await tests.collection.expand(id, Infinity);
      return [...tests.collection.all].filter(item => item.item.uri?.path.endsWith('/EntryTest.java') && item.children.size === 0);
    }, value => value.length > 0, 'Java test discovery');
    await tests.runTests({ group: 2, tests: discovered });
    const evidence = await until(async () => testEvidence(runtime.tests.results), value => value.tests.some(group => group.methods.some(method => method.status === 'PASSED')), 'Java Run test');
    assert(!evidence.incomplete && evidence.tests.every(group => group.methods.every(method => method.status === 'PASSED')), 'Java tests must complete successfully');
    passedTests = evidence.tests.reduce((total, group) => total + group.methods.length, 0);
    await until(async () => runtime.executions.run, value => value === null, 'Execution completion');
    assert(runtime.executions.error === null, 'Test execution must finish without a claim error');
  }
  return { coldMs, warmMs, definitions: locations.length, references: references.length, warmQueries: 20, passedTests, symbols, callers };
}
