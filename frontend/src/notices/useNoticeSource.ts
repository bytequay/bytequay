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
import { useContext, useEffect, useId } from 'react';
import { LayerCovered } from '../Zoom';

/** Working anywhere in a visible task keeps its notices quiet, including older messages and the editor. */
export function useNoticeSource(taskId: string | null): void {
  const covered = useContext(LayerCovered);
  const key = useId();
  useEffect(() => {
    if (taskId === null || covered || !window.bridge?.setNoticeSource) return;
    void window.bridge.setNoticeSource(key, taskId).catch(() => {});
    return () => { void window.bridge.setNoticeSource(key, null).catch(() => {}); };
  }, [key, taskId, covered]);
}
