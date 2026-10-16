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
import type { IdeConnection } from './contracts';
import { startRuntime, type IdeRuntime } from './runtime';
import { installSemanticQueries } from './semanticQueries';

type WorkbenchStartup = { runtime: IdeRuntime; connection: IdeConnection };

declare global {
  interface Window {
    bytequayWorkbenchStartup?: Promise<WorkbenchStartup>;
  }
}

export function startWorkbench(connection: IdeConnection): Promise<WorkbenchStartup> {
  // Native services live for the page, including React remounts and development module refreshes.
  // Keep failures too: partially initialized services cannot safely be initialized again in this page.
  return window.bytequayWorkbenchStartup ??= startRuntime(connection, document.body).then(runtime => {
    installSemanticQueries(runtime.workspace);
    return { runtime, connection };
  });
}
