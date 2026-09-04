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

import React, { useCallback } from 'react';
import { INTERACTIVE_LANE_ID } from '../settings/model';
import { TaskComposer, useAgentTaskCreation } from './AgentTasksView';
import { TaskWindows } from './TaskCard';
import type { AgentBridgePort } from './model';
import type { AgentTaskDto, ModelOptionDto, ModelProviderDto } from '../types';

/** Starting points that fill the composer; the user edits or sends them as they are. */
const SUGGESTIONS = [
  { icon: '⌕', color: '#8250df', title: 'Explore and understand code', prompt: 'Explore the code and explain how a feature is implemented.' },
  { icon: '◉', color: '#cf222e', title: 'Fix issues and failures', prompt: 'Investigate a failing test and fix the root cause.' },
  { icon: '☑', color: '#1a7f37', title: 'Review code and suggest changes', prompt: 'Review recent changes and suggest safe improvements.' },
] as const;

export interface AgentHomeProps {
  workspaceId: string;
  /** "owner/repo", named in the question the hero asks. */
  repository: string;
  branch?: string;
  bridge?: AgentBridgePort;
  onOpenTask: (taskId: string) => void;
  onOpenSettings?: () => void;
  onShowAll: () => void;
  laneId?: string;
}

export default function AgentHome({
  workspaceId, repository, branch, bridge: explicitBridge, onOpenTask, onOpenSettings, onShowAll, laneId = INTERACTIVE_LANE_ID,
}: AgentHomeProps): React.JSX.Element {
  const live = useCallback((): AgentBridgePort => explicitBridge ?? window.bridge, [explicitBridge]);
  const creation = useAgentTaskCreation({ workspaceId, initialLaneId: laneId, bridge: live, onOpenTask });
  return (
    <AgentHomeView
      repository={repository}
      branch={branch}
      laneId={creation.laneId}
      providers={creation.catalog?.providers ?? []}
      bridge={live}
      tasks={creation.tasks.filter((task) => task.state === 'OPEN')}
      models={creation.models}
      selection={creation.selection}
      draft={creation.draft}
      blocker={creation.blocker}
      onDraftChange={creation.setDraft}
      onSelectProvider={creation.selectProvider}
      onSelectModel={(model, effort) => creation.setSelection({ model, effort })}
      onCreate={() => { void creation.create(); }}
      onOpenTask={onOpenTask}
      onOpenSettings={onOpenSettings}
      onShowAll={onShowAll}
    />
  );
}

export function AgentHomeView({
  repository, branch, laneId, providers, bridge, tasks, models, selection, draft, blocker,
  onDraftChange, onSelectProvider, onSelectModel, onCreate, onOpenTask, onOpenSettings, onShowAll,
}: {
  repository: string;
  branch?: string;
  laneId: string;
  providers: ModelProviderDto[];
  bridge: () => AgentBridgePort;
  /** The open tasks, drawn as windows under the hero. */
  tasks: AgentTaskDto[];
  models: ModelOptionDto[];
  selection: { model: string; effort: string | null } | null;
  draft: string;
  blocker: string | null;
  onDraftChange: (draft: string) => void;
  onSelectProvider: (laneId: string) => void;
  onSelectModel: (model: string, effort: string | null) => void;
  onCreate: () => void;
  onOpenTask: (taskId: string) => void;
  onOpenSettings?: () => void;
  onShowAll: () => void;
}): React.JSX.Element {
  return (
    <div className="agt-tasks-page">
      <div className="agt-tasks-scroll">
        <div className="agt-hero">
          <div className="agt-hero__mark" aria-hidden="true">❋</div>
          <h1>What should the agent do in {repository}?</h1>
          <div className="agt-suggestions">
            {SUGGESTIONS.map((suggestion) => (
              <button type="button" className="agt-suggestion" key={suggestion.title} onClick={() => onDraftChange(suggestion.prompt)}>
                <div className="agt-suggestion__icon" style={{ color: suggestion.color }} aria-hidden="true">{suggestion.icon}</div>
                <div className="agt-suggestion__text">{suggestion.title}</div>
              </button>
            ))}
          </div>
          <div className="agt-open-tasks">
            <div className="agt-open-tasks__head">
              <span className="agt-label">Open tasks · {tasks.length}</span>
              <span className="agt-spacer" />
              <button type="button" className="agt-link" onClick={onShowAll}>
                Show all <kbd className="agt-kbd">{'⇧⌘\\'}</kbd>
              </button>
            </div>
            <div className="agt-windows">
              <TaskWindows tasks={tasks} providers={providers} width={200} bridge={bridge} onOpen={onOpenTask} />
            </div>
          </div>
        </div>
      </div>
      <TaskComposer
        draft={draft}
        blocker={blocker}
        branch={branch}
        laneId={laneId}
        providers={providers}
        models={models}
        selection={selection}
        onDraftChange={onDraftChange}
        onSelectProvider={onSelectProvider}
        onSelectModel={onSelectModel}
        onCreate={onCreate}
        onOpenSettings={onOpenSettings}
      />
    </div>
  );
}
