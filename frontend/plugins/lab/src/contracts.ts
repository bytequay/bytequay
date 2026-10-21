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
/** A task the dashboard asks ByteQuay to open in a repository's task composer, for the user to read and send. */
export type LabTask = { repository: string; text: string };

export type LabBridge = { openLab: () => Promise<string>; onLabTask: (callback: (task: LabTask) => void) => () => void };

export type LabHost = Pick<LabBridge, 'openLab'> & {
  openInAppBrowser: (url: string) => Promise<unknown>;
  openSettings: () => void;
  /** Opens the composer of the workspace that watches the task's repository; false when none does. */
  startTask: (task: LabTask) => Promise<boolean>;
};
