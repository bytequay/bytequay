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
import { Fragment } from 'react';
import { Logo, StatusDot } from '../primitives';
import type { LogoColor, StatusDotVariant } from '../primitives';

/** One thread row in the sidebar: its repo logo + name + a status dot. */
export type ThreadRow = {
  id: string;
  /** Repo monogram + colour (which repo the thread targets). */
  initials: string;
  color: LogoColor;
  name: string;
  status: StatusDotVariant;
};

/** A task of the open thread, nested under its row so the user can jump
 *  straight to a task (its branch / name + run status). */
export type TaskNavRow = {
  id: string;
  label: string;
  /** Status marker — running pulses, completed shows done, etc. */
  dot?: StatusDotVariant;
};

/** A single sidebar thread row. */
export function ThreadListItem({ thread, active = false, onOpen }: {
  thread: ThreadRow;
  active?: boolean;
  onOpen?: (id: string) => void;
}) {
  return (
    <button
      type="button"
      className={active ? 'thread-item active' : 'thread-item'}
      onClick={() => onOpen?.(thread.id)}
    >
      <Logo initials={thread.initials} color={thread.color} size="sm" />
      <span className="nm">{thread.name}</span>
      <StatusDot variant={thread.status} />
    </button>
  );
}

/** The nested task rows shown under the open thread. */
function TaskSubList({ tasks, selectedTaskId, onOpenTask }: {
  tasks: TaskNavRow[];
  selectedTaskId?: string;
  onOpenTask?: (id: string) => void;
}) {
  return (
    <div className="task-sublist">
      {tasks.map(t => (
        <button
          key={t.id}
          type="button"
          className={t.id === selectedTaskId ? 'task-subitem active' : 'task-subitem'}
          onClick={() => onOpenTask?.(t.id)}
        >
          <span className="nm">{t.label}</span>
          {t.dot !== undefined && <StatusDot variant={t.dot} />}
        </button>
      ))}
    </div>
  );
}

/**
 * The workspace's threads in the sidebar — each prefixed by its repo
 * logo so you see which repo it targets at a glance. The selected thread
 * (when one is open) highlights and expands to show its tasks, so the
 * user can jump straight to a task.
 */
export function ThreadList({
  threads, selectedId, tasks = [], selectedTaskId, onOpen, onOpenTask, onNewThread,
}: {
  threads: ThreadRow[];
  selectedId?: string;
  /** Tasks of the open thread — rendered nested under the selected row.
   *  Empty when no thread is open or it has no tasks yet. */
  tasks?: TaskNavRow[];
  selectedTaskId?: string;
  onOpen?: (id: string) => void;
  onOpenTask?: (id: string) => void;
  onNewThread?: () => void;
}) {
  return (
    <div className="sb-section" style={{ paddingTop: 8 }}>
      <div className="sb-section-h">
        <span className="nm">Threads</span>
        <span className="actions">
          <span role="button" tabIndex={0} aria-label="Filter">⛚</span>
          <span role="button" tabIndex={0} aria-label="New thread" onClick={onNewThread}>+</span>
        </span>
      </div>
      <div className="thread-list">
        {threads.map(t => (
          <Fragment key={t.id}>
            <ThreadListItem thread={t} active={t.id === selectedId} onOpen={onOpen} />
            {t.id === selectedId && tasks.length > 0 && (
              <TaskSubList tasks={tasks} selectedTaskId={selectedTaskId} onOpenTask={onOpenTask} />
            )}
          </Fragment>
        ))}
      </div>
    </div>
  );
}
