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
import type { LabHost } from '@bytequay/lab/contracts';
import { openLab as launchLab, takeLabTask } from '@bytequay/lab/renderer';
import { settingsRoute } from './settings/routes';
import { startEpicWork } from './workspaces/epics';

const host: LabHost = {
  openLab: () => window.bridge.openLab(),
  openInAppBrowser: url => window.bridge.openInAppBrowser(url),
  openSettings: () => { window.location.hash = settingsRoute('lab'); },
  startTask: async task => {
    const wanted = task.repository.toLowerCase();
    const workspace = (await window.bridge.listWorkspaces()).find(found => `${found.monitoredOwner}/${found.monitoredRepo}`.toLowerCase() === wanted);
    if (workspace === undefined) return false;
    startEpicWork({ workspaceId: workspace.id, draft: task.text });
    return true;
  },
};

export function openLab(): Promise<void> {
  return launchLab(host);
}

/** Opens each task the Lab dashboard hands over in its repository's composer, where the user reads and sends it. */
export function listenForLabTasks(): () => void {
  return window.bridge.onLabTask(task => { void takeLabTask(host, task); });
}
