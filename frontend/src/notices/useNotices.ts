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
import { useEffect, useState } from 'react';
import type { AppNotice, NoticeSnapshot } from './contracts';

/** The task notices the window holds, read once and then as they change. */
export function useNotices(): AppNotice[] {
  const [notices, setNotices] = useState<AppNotice[]>([]);
  useEffect(() => {
    let received = false;
    const show = (state: NoticeSnapshot) => setNotices(state.notices);
    const stop = window.bridge.onAppNotices?.(state => { received = true; show(state); });
    void window.bridge.readAppNotices?.().then(state => { if (!received) show(state); }).catch(() => {});
    return stop;
  }, []);
  return notices;
}
