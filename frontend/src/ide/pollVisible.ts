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
/** Refresh on return to the app, without overlapping slow reads or polling hidden views. */
export function pollVisible(load: () => Promise<void>, interval: number): () => void {
  let stopped = false;
  let running = false;
  const refresh = async () => {
    if (stopped || running || document.visibilityState !== 'visible') return;
    running = true;
    try { await load(); }
    finally { running = false; }
  };
  const update = () => { void refresh(); };
  update();
  const timer = setInterval(update, interval);
  document.addEventListener('visibilitychange', update);
  return () => {
    stopped = true;
    clearInterval(timer);
    document.removeEventListener('visibilitychange', update);
  };
}
