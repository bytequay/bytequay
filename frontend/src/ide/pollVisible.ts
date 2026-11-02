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
/** Query runtimes stay unthrottled, so native presentation must supplement the Page Visibility API. */
export function observeVisibility(listener: (visible: boolean) => void): () => void {
  const workbench = typeof window === 'undefined' ? undefined : window.ideWorkbench;
  let presented = workbench === undefined;
  const update = () => listener(presented && document.visibilityState === 'visible');
  const stop = workbench?.onVisibility(visible => { presented = visible; update(); });
  document.addEventListener('visibilitychange', update);
  update();
  return () => { stop?.(); document.removeEventListener('visibilitychange', update); };
}

/** Refresh on return to the app, without overlapping slow reads or polling hidden views. */
export function pollVisible(load: () => Promise<void>, interval: number): () => void {
  let stopped = false;
  let running = false;
  let visible = false;
  let timer: ReturnType<typeof setInterval> | undefined;
  const refresh = async () => {
    if (stopped || running || !visible) return;
    running = true;
    try { await load(); }
    finally { running = false; }
  };
  const update = () => { void refresh(); };
  const stopVisibility = observeVisibility(value => {
    if (visible === value) { update(); return; }
    visible = value;
    clearInterval(timer);
    timer = undefined;
    if (visible) { update(); timer = setInterval(update, interval); }
  });
  return () => {
    stopped = true;
    clearInterval(timer);
    stopVisibility();
  };
}
