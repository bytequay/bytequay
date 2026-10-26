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
import { createRoot } from 'react-dom/client';
import ArtifactsPage from '../ArtifactsPage';
import { FocusBreakProvider, useFocusBreaks } from '../../focus/FocusBreakProvider';
import { FOCUS_PREFERENCES_KEY } from '../../focus/model';
import '../../css/base.css';

let beginBreak = () => {};
let endBreak = () => {};

function BreakControls() {
  const focus = useFocusBreaks();
  beginBreak = focus.startBreak;
  endBreak = () => focus.updatePreferences({ ...focus.preferences, enabled: false });
  return null;
}

export function startBreak(): void { beginBreak(); }
export function stopBreak(): void { endBreak(); }

/** The real page over the preload's bridge; main answers the list and shows each page in the real native view. */
export function render(): void {
  localStorage.setItem(FOCUS_PREFERENCES_KEY, JSON.stringify({ enabled: true, workMinutes: 15, breakMinutes: 1, companion: 'chubby-cat' }));
  document.body.style.margin = '0';
  const host = document.createElement('div');
  host.style.height = '100vh';
  document.body.append(host);
  createRoot(host).render(<FocusBreakProvider><BreakControls /><ArtifactsPage workspaceId="w1" /></FocusBreakProvider>);
}
