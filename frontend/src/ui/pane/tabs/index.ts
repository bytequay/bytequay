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

/** V3 right-pane tab contents — one component per tab type. */
export { PlanTabContent } from './PlanTabContent';
export type { PlanSource, PlanStep, PlanSignal, PlanConfidence } from './PlanTabContent';
export { DetailsTabContent } from './DetailsTabContent';
export type { DetailRow, DetailSection } from './DetailsTabContent';
export { PRTabContent, CommentThread } from './PRTabContent';
export type { PRStatus, PRMetaChip, CommentThreadData } from './PRTabContent';
export { TasksTabContent } from './TasksTabContent';
export type { TaskCardData } from './TasksTabContent';
export { BacklogTabContent } from './BacklogTabContent';
export type { BacklogItemData } from './BacklogTabContent';
export { NotificationsTabContent, NotificationRow } from './NotificationsTabContent';
export type { NotifData, NotifIconKind } from './NotificationsTabContent';
