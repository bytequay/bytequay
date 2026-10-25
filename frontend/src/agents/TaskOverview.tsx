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

import React, { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { ConfirmDialog } from '../ConfirmDialog';
import { taskArchived } from './taskStatus';
import { TaskWindows } from './TaskCard';
import type { AgentBridgePort } from './model';
import type { AgentTaskDto, ModelProviderDto } from '../types';

interface TaskOverviewProps {
  workspaceId: string;
  bridge?: AgentBridgePort;
  /** The task open behind the overlay, ringed so the user knows where they came from. */
  activeTaskId: string | null;
  onOpenTask: (taskId: string) => void;
  onNewTask: () => void;
  onDismiss: () => void;
}

/** Reads the tasks itself: the overlay is transient, and nothing else on screen owns this list. */
export default function TaskOverview({
  workspaceId, bridge: explicitBridge, activeTaskId, onOpenTask, onNewTask, onDismiss,
}: TaskOverviewProps): React.JSX.Element {
  const live = useCallback((): AgentBridgePort => explicitBridge ?? window.bridge, [explicitBridge]);
  const [tasks, setTasks] = useState<AgentTaskDto[]>([]);
  const [providers, setProviders] = useState<ModelProviderDto[]>([]);
  const [notice, setNotice] = useState<string | null>(null);
  const refresh = useCallback(() => live().listAgentTasks(workspaceId)
    .then((found) => setTasks(found.filter((task) => task.state === 'OPEN' && !taskArchived(task))))
    .catch((failure: unknown) => setNotice(failure instanceof Error ? failure.message : String(failure))), [live, workspaceId]);
  useEffect(() => { void refresh(); }, [refresh]);
  useEffect(() => {
    void live().getModelSettings(workspaceId)
      .then((catalog) => setProviders(catalog.providers))
      .catch(() => {});
  }, [live, workspaceId]);
  const [closing, setClosing] = useState<AgentTaskDto | null>(null);
  const close = useCallback((task: AgentTaskDto) => {
    setClosing(null);
    void live().completeAgentTask(task.id, workspaceId)
      .then(() => refresh())
      .catch((failure: unknown) => setNotice(failure instanceof Error ? failure.message : String(failure)));
  }, [live, refresh, workspaceId]);
  return (
    <>
      <TaskOverviewView
        tasks={tasks}
        providers={providers}
        bridge={live}
        notice={notice}
        activeTaskId={activeTaskId}
        onOpenTask={onOpenTask}
        onCloseTask={setClosing}
        onNewTask={onNewTask}
        onDismiss={onDismiss}
      />
      {closing !== null && (
        <ConfirmDialog title={`Close “${closing.title}”?`} confirmLabel="Close task" onConfirm={() => close(closing)} onCancel={() => setClosing(null)}>
          {/* The card's ✕ is Complete: it closes the task and keeps its result branch, never a discard. */}
          <p>Its clean checkout is removed and the result branch is kept.</p>
        </ConfirmDialog>
      )}
    </>
  );
}

export function TaskOverviewView({ tasks, providers, bridge, notice, activeTaskId, onOpenTask, onCloseTask, onNewTask, onDismiss }: {
  tasks: AgentTaskDto[];
  providers: ModelProviderDto[];
  bridge: () => AgentBridgePort;
  notice: string | null;
  activeTaskId: string | null;
  onOpenTask: (taskId: string) => void;
  onCloseTask: (task: AgentTaskDto) => void;
  onNewTask: () => void;
  onDismiss: () => void;
}): React.JSX.Element {
  const root = useRef<HTMLDivElement>(null);
  const focusedTask = useRef(false);
  const restoreFocus = useRef(false);
  useLayoutEffect(() => {
    const previous = document.activeElement;
    return () => {
      if (restoreFocus.current && previous instanceof HTMLElement && previous.isConnected && !previous.closest('[inert]') && previous.getClientRects().length > 0) previous.focus();
    };
  }, []);
  useLayoutEffect(() => {
    const host = root.current;
    if (!host || focusedTask.current || host.closest('[inert]') || document.querySelector('dialog[open]')) return;
    const focusedDialog = document.activeElement?.closest('[role="dialog"]');
    if (focusedDialog && focusedDialog !== host) return;
    // The first render can precede the task list; move from New task to the active task once it arrives.
    const active = host.querySelector<HTMLButtonElement>('.agt-card.is-active .agt-card__open') ?? host.querySelector<HTMLButtonElement>('.agt-card__open');
    (active ?? host.querySelector<HTMLButtonElement>('.agt-card-new'))?.focus();
    focusedTask.current = active !== null;
  }, [tasks]);
  const dismiss = () => { restoreFocus.current = true; onDismiss(); };
  const onKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
    const host = root.current;
    if (!host || event.defaultPrevented || event.nativeEvent.isComposing || event.metaKey || event.ctrlKey || event.altKey
      || host.closest('[inert]') || document.querySelector('dialog[open]')) return;
    const target = event.target instanceof HTMLElement ? event.target : null;
    if (target?.closest('[role="dialog"]') !== host) return;
    if (event.key === 'Escape') {
      event.preventDefault(); event.stopPropagation(); dismiss();
      return;
    }
    if (target?.closest('input, textarea, select, [contenteditable]')) return;
    if (event.key === 'Tab') {
      const controls = [...host.querySelectorAll<HTMLButtonElement>('.agt-overview__head button, .agt-card__open, .agt-card__close:not(:disabled), .agt-card-new')];
      const index = controls.indexOf(document.activeElement as HTMLButtonElement);
      if (event.shiftKey && index <= 0) { event.preventDefault(); controls.at(-1)?.focus(); }
      else if (!event.shiftKey && index === controls.length - 1) { event.preventDefault(); controls[0]?.focus(); }
      return;
    }
    if (event.shiftKey || !['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)) return;
    const cards = [...host.querySelectorAll<HTMLButtonElement>('.agt-card__open, .agt-card-new')];
    const current = target?.closest('.agt-card')?.querySelector<HTMLButtonElement>('.agt-card__open') ?? target;
    const index = cards.indexOf(current as HTMLButtonElement);
    const step = event.key === 'ArrowLeft' || event.key === 'ArrowUp' ? -1 : 1;
    const next = index < 0 ? 0 : (index + step + cards.length) % cards.length;
    event.preventDefault(); event.stopPropagation(); cards[next]?.focus();
  };
  const running = tasks.filter((task) => task.mutationClaim?.kind === 'TURN').length;
  // A click past the windows leaves the overlay, the same as Escape.
  const dismissOnEmpty = (event: React.MouseEvent): void => { if (event.target === event.currentTarget) dismiss(); };
  return (
    <div ref={root} className="agt-overview" role="dialog" aria-label="All tasks" onClick={dismissOnEmpty} onKeyDown={onKeyDown}>
      <div className="agt-overview__head">
        <h1>Tasks</h1>
        <span className="agt-muted agt-overview__summary">
          {tasks.length} open{running === 0 ? '' : ` · ${running} running`}
        </span>
        <span className="agt-spacer" />
        <button type="button" className="agt-action" title="New task (⇧⌘N)" onClick={onNewTask}><span aria-hidden="true">+ </span>New task</button>
        <button type="button" className="agt-headbtn" aria-label="Close task switcher" title="Close task switcher (Escape)" onClick={dismiss}>×</button>
      </div>
      {notice !== null && <div className="agt-notice agt-overview__notice" role="alert">{notice}</div>}
      <div className="agt-overview__grid" onClick={dismissOnEmpty}>
        <TaskWindows tasks={tasks} providers={providers} width={294} bridge={bridge} activeTaskId={activeTaskId} onOpen={onOpenTask} onClose={onCloseTask} />
        <button type="button" className="agt-card-new" aria-label="New task" title="New task (⇧⌘N)" onClick={onNewTask}>
          <span className="agt-card-new__plus" aria-hidden="true">+</span>
          <span>New task</span>
        </button>
      </div>
      <div className="agt-overview__foot">
        <span><kbd className="agt-kbd">⌃⌘T</kbd> toggle overview</span>
        <span><kbd className="agt-kbd">esc</kbd> or a click outside · back to the current task</span>
        <span><kbd className="agt-kbd">← ↑ ↓ →</kbd> select · <kbd className="agt-kbd">Enter</kbd> open · ✕ closes the task</span>
      </div>
    </div>
  );
}
