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
import App from './App';
import './index.css';

const container = document.getElementById('root');
if (!container) {
    throw new Error('Root element not found');
}

const root = createRoot(container);

async function render(): Promise<void> {
    const frame = (window.location.protocol === 'http:' || window.location.protocol === 'https:')
        ? new URLSearchParams(window.location.search).get('workspaceVisual')
        : null;
    if (frame !== null && frame.length > 0) {
        const [{ default: WorkspaceVisualFixture }, { installWorkspaceVisualBridge }] =
            await Promise.all([
                import('./workspace/WorkspaceVisualFixture'),
                import('./workspace/workspaceVisualFixtureData'),
            ]);
        window.localStorage.clear();
        window.localStorage.setItem('bytequay.workspace.active', 'workspace-bytequay');
        window.localStorage.setItem('bq.rail-width', '250');
        document.documentElement.dataset.workspaceVisualFrame = frame;
        installWorkspaceVisualBridge(frame);
        root.render(<WorkspaceVisualFixture frame={frame} />);
        return;
    }
    root.render(<App />);
}

void render();
