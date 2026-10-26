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
import { flushSync } from 'react-dom';
import { useResizablePane } from '../useResizablePane';
import { createFixtureRoot } from './reactFixture';
import { assert, element, runScenarios } from './harness';

function mount(resizeEdge: 'start' | 'end' = 'end', axis: 'x' | 'y' = 'x') {
  const view = createFixtureRoot();
  let current: Pick<ReturnType<typeof useResizablePane>, 'width' | 'resizing' | 'startResize'>;
  let measurements = 0;
  const maximum = () => { measurements++; return 500; };
  function Harness() {
    current = useResizablePane(300, 200, maximum, resizeEdge, axis);
    return <div onMouseDown={event => current.startResize(event)} />;
  }
  flushSync(() => view.root.render(<Harness />));
  return {
    ...view,
    state: () => current,
    measurements: () => measurements,
    start: (button = 0) => flushSync(() => element('div', view.host).dispatchEvent(new MouseEvent('mousedown', {
      bubbles: true, button, clientX: 300, clientY: 300,
    }))),
  };
}

function move(position: number) {
  flushSync(() => window.dispatchEvent(new MouseEvent('mousemove', { clientX: position, clientY: position })));
}

export async function run() {
  return runScenarios([
    ['keeps size bounds and restores previous body styles after mouseup', () => {
      const view = mount();
      document.body.style.cursor = 'crosshair';
      document.body.style.userSelect = 'text';
      try {
        view.start();
        assert(view.state().resizing && document.body.style.cursor === 'col-resize', 'A primary drag starts resizing');
        move(350);
        assert(view.state().width === 350, 'The pane follows the horizontal drag');
        move(1000);
        assert(view.state().width === 500, 'The maximum width is respected');
        move(0);
        assert(view.state().width === 200, 'The minimum width is respected');
        flushSync(() => window.dispatchEvent(new MouseEvent('mouseup')));
        assert(!view.state().resizing && String(document.body.style.cursor) === 'crosshair'
          && document.body.style.userSelect === 'text', 'Mouseup restores the previous styles');
        move(400);
        assert(view.state().width === 200, 'Mouse movement after release does not resize');
      } finally { view.dispose(); document.body.style.cursor = ''; document.body.style.userSelect = ''; }
    }],
    ['stops a vertical start-edge drag when the window loses focus', () => {
      const view = mount('start', 'y');
      try {
        view.start();
        move(250);
        assert(view.state().width === 350 && document.body.style.cursor === 'row-resize', 'The start edge uses the reverse vertical distance');
        flushSync(() => window.dispatchEvent(new Event('blur')));
        move(200);
        assert(!view.state().resizing && view.state().width === 350, 'Losing focus ends the drag even without mouseup');
        assert(String(document.body.style.cursor) === '' && document.body.style.userSelect === '', 'Blur restores the page styles');
      } finally { view.dispose(); }
    }],
    ['releases listeners and styles when a resizing pane unmounts', () => {
      const view = mount();
      view.start();
      view.dispose();
      move(400);
      assert(view.measurements() === 0, 'An unmounted pane no longer handles mouse movement');
      assert(document.body.style.cursor === '' && document.body.style.userSelect === '', 'Closing a pane releases global drag styles');
    }],
    ['ignores secondary buttons and replaces an unfinished drag', () => {
      const view = mount();
      try {
        view.start(2);
        move(400);
        assert(!view.state().resizing && view.measurements() === 0, 'A context-menu click must not resize');
        view.start();
        view.start();
        move(400);
        assert(view.measurements() === 1 && view.state().width === 400, 'Restarting a drag leaves only one movement listener');
      } finally { view.dispose(); }
      assert(document.body.style.cursor === '' && document.body.style.userSelect === '', 'Restarting a drag does not retain its temporary styles');
    }],
  ]);
}
