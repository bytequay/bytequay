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
import { useResizablePane } from '../useResizablePane';

export function useTaskPane() {
  return useResizablePane(960, 360, 1600, 'start');
}

export default function TaskPaneDivider({ pane }: { pane: ReturnType<typeof useTaskPane> }): React.JSX.Element {
  return <div className={`agt-panel-resize${pane.resizing ? ' is-resizing' : ''}`}
    role="separator" aria-orientation="vertical" aria-label="Resize side panel" title="Drag to resize the panel"
    onMouseDown={event => {
      // A panel kept mounted but hidden (Tears of time) can come first; measure the one the user sees.
      let panel = event.currentTarget.nextElementSibling;
      while (panel instanceof HTMLElement && panel.hidden) panel = panel.nextElementSibling;
      // CSS can constrain the saved width; dragging starts at the divider the user sees.
      pane.startResize(event, panel?.getBoundingClientRect().width);
    }} />;
}
