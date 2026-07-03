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

/** A finished deployment the inbox can report. */
export type DeployNoticeDto = {
  id: string;
  environment: string;
  repoFullName: string;
  branch: string;
  /** Short commit sha. */
  commit: string;
  durationLabel: string;
  succeeded: boolean;
  /** ISO timestamp the run finished at. */
  finishedAt: string;
};

// TODO(data): no deploy-event source exists yet (neither the
// notifications table nor the GitHub sync carry deployment runs).
// This provider returns nothing until a real source lands; the inbox
// keeps the rendering path alive so wiring it later is data-only.
export function fetchDeployNotices(): Promise<DeployNoticeDto[]> {
  return Promise.resolve([]);
}
