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
import '../../css/base.css';

/** The real page over the preload's bridge; main answers the list and shows each page in the real native view. */
export function render(): void {
  document.body.style.margin = '0';
  const host = document.createElement('div');
  host.style.height = '100vh';
  document.body.append(host);
  createRoot(host).render(<ArtifactsPage workspaceId="w1" />);
}
