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

/** A stage of the open thread's active task, nested under its row so the
 *  user can jump straight to a stage (Plan / Dev / CI Fix / Comments). */
export type StageNavRow = {
  id: string;
  label: string;
  /** Status marker — active stage pulses, closed shows done, etc. */
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

/** The nested stage rows shown under the open thread. */
function StageSubList({ stages, selectedStageId, onOpenStage }: {
  stages: StageNavRow[];
  selectedStageId?: string;
  onOpenStage?: (id: string) => void;
}) {
  return (
    <div className="stage-sublist">
      {stages.map(s => (
        <button
          key={s.id}
          type="button"
          className={s.id === selectedStageId ? 'stage-subitem active' : 'stage-subitem'}
          onClick={() => onOpenStage?.(s.id)}
        >
          <span className="nm">{s.label}</span>
          {s.dot !== undefined && <StatusDot variant={s.dot} />}
        </button>
      ))}
    </div>
  );
}

/** The active task of the open thread — a sub-header above its stages. */
export type TaskNavRow = { id: string; label: string };

/**
 * The workspace's threads in the sidebar — each prefixed by its repo
 * logo so you see which repo it targets at a glance. The selected thread
 * (when one is open) highlights and expands to show its active task's
 * name, and under that the task's stages (Plan / Dev / CI fixing…), so the
 * user can jump straight to the task or one of its stages.
 */
export function ThreadList({
  threads, selectedId, task, stages = [], selectedTaskId, selectedStageId,
  onOpen, onOpenTask, onOpenStage, onNewThread,
}: {
  threads: ThreadRow[];
  selectedId?: string;
  /** The open thread's active task — the sub-header above the stages. */
  task?: TaskNavRow;
  /** Stages of the open thread's active task — nested under the task. */
  stages?: StageNavRow[];
  selectedTaskId?: string;
  selectedStageId?: string;
  onOpen?: (id: string) => void;
  onOpenTask?: (id: string) => void;
  onOpenStage?: (id: string) => void;
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
            {t.id === selectedId && task !== undefined && (
              <button
                type="button"
                className={task.id === selectedTaskId && selectedStageId === undefined
                  ? 'task-subhead active' : 'task-subhead'}
                onClick={() => onOpenTask?.(task.id)}
              >
                <span className="nm">{task.label}</span>
              </button>
            )}
            {t.id === selectedId && stages.length > 0 && (
              <StageSubList stages={stages} selectedStageId={selectedStageId} onOpenStage={onOpenStage} />
            )}
          </Fragment>
        ))}
      </div>
    </div>
  );
}
