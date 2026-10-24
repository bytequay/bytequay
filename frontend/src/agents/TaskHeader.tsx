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
import { ArrowLeft } from 'lucide-react';
import type { ReactNode } from 'react';
import '../css/agents.css';

export default function TaskHeader({ title, status, actions, onBack, preview = false }: {
  title?: ReactNode;
  status?: ReactNode;
  actions?: ReactNode;
  onBack?: () => void;
  preview?: boolean;
}): React.JSX.Element {
  return <header className="agt-screen-head">
    {(onBack !== undefined || preview) && <TaskBackButton onBack={onBack} />}
    <div className="agt-screen-head__title">{title}</div>
    <div className="agt-screen-head__actions">{status}{actions}</div>
  </header>;
}

function TaskBackButton({ onBack }: { onBack?: () => void }): React.JSX.Element {
  const icon = <ArrowLeft aria-hidden="true" />;
  const className = 'agt-headbtn agt-task-back';
  // Overview thumbnails are inside an open button, so their preview cannot be another button.
  if (onBack === undefined) return <span className={className} aria-hidden="true">{icon}</span>;
  return <button type="button" className={className} onClick={onBack} aria-label="Back to tasks" title="Back to tasks">{icon}</button>;
}
