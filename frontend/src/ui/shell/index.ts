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

/** V3 Layer 2 shell — the single sidebar + top bar + main + composer
 *  pattern composed by every surface. */
export { Shell } from './Shell';
export { Main } from './Main';
export { Composer } from './Composer';
export { RunMenu } from './RunMenu';
export {
  Sidebar, TrafficLights, SidebarNav, ThreadsSection, ClosedFolder, SidebarFooter, SidebarToggleBar,
} from './Sidebar';
export type { SidebarNavKey } from './Sidebar';
export { ThreadItem, TaskItem, StageItem } from './SidebarTree';
export {
  TopBar, NavArrows, TopBarTitle, CrumbSep, CtxChip, CreatedChip, Grow, StageChips, TopBarButton, BackBtn,
} from './TopBar';
export type { StageChip } from './TopBar';
export { useSidebarCollapsed } from './useSidebarCollapsed';
export { usePersistentToggle } from './usePersistentToggle';
