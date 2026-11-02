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
import type { IdeRuntime } from './runtime';
import type { IdeStateReport } from './contracts';

/** Shared by the visible workbench and agent-created query runtimes without a mounted workbench. */
export function idleState(runtime: IdeRuntime, restoring = false): IdeStateReport {
  const privateDrafts = [...document.querySelectorAll('textarea')].filter(node => !node.classList.contains('inputarea') && node.value.trim()).length;
  return {
    dirty: runtime.api.workspace.textDocuments.filter(document => document.isDirty || runtime.saves.isDirty(document.uri)).length + privateDrafts,
    busy: restoring || runtime.saves.pending || runtime.executions.starting || runtime.executions.run !== null || runtime.terminals.instances.length > 0
      || runtime.api.debug.activeDebugSession !== undefined || runtime.tests.results.some(result => result.completedAt === undefined),
  };
}
