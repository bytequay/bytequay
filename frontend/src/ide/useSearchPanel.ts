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
import { useEffect } from 'react';
import { flushSync } from 'react-dom';
import type { ICommandService } from '@codingame/monaco-vscode-api';

export function useSearchPanel(commands: ICommandService, reveal: (panel: 'search') => void) {
  useEffect(() => {
    const listener = commands.onWillExecuteCommand(event => {
      if (event.commandId === 'workbench.action.findInFiles' || event.commandId === 'workbench.view.search') {
        // Attach the native view before its command focuses the input, also after returning to Files.
        flushSync(() => reveal('search'));
      }
    });
    return () => listener.dispose();
  }, [commands, reveal]);
}
