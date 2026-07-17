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
import { Fragment, useState } from 'react';
import { Chev, PrStateIcon, StatusDot, TrunkIcon } from '../primitives';
import type { PrGlyphState, StatusDotVariant } from '../primitives';

/** One thread row in the sidebar: a trunk tile + name + a status dot. */
export type ThreadRow = {
  id: string;
  name: string;
  status: StatusDotVariant;
  flow?: 'build' | 'review';
  attentionCount?: number;
};

/** A single sidebar thread row. {@code onOpen} is also the fold/unfold
 *  trigger — the caller decides whether a click on an already-active
 *  trunk toggles its task list instead of re-navigating. */
export function ThreadListItem({ thread, active = false, showsFoldChevron = false, foldedOpen = true, onOpen }: {
  thread: ThreadRow;
  active?: boolean;
  /** Show the fold chevron (only meaningful when this row is active
   *  and has tasks to fold). */
  showsFoldChevron?: boolean;
  /** Chevron direction: true = ▾ (tasks showing), false = ▸ (folded). */
  foldedOpen?: boolean;
  onOpen?: (id: string) => void;
}) {
  return (
    <div
      className={active ? 'thread-item active' : 'thread-item'}
      role="button"
      tabIndex={0}
      onClick={() => onOpen?.(thread.id)}
      onKeyDown={event => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          onOpen?.(thread.id);
        }
      }}
    >
      <span className="trunk-tile" aria-hidden><TrunkIcon size={12} /></span>
      <span className="nm">{thread.name}</span>
      {!active && thread.attentionCount !== undefined && thread.attentionCount > 0 && (
        <span className="thread-attention-count">{thread.attentionCount}</span>
      )}
      <StatusDot variant={thread.status} />
      {showsFoldChevron && <Chev open={foldedOpen} />}
    </div>
  );
}

/** A task of the open thread — a sub-header row under the thread. A thread
 *  can run several at once, so the rail lists them all. */
export type TaskNavRow = {
  id: string;
  label: string;
  /** Status marker on the right — done once the task is closed/terminal,
   *  active while it runs, sleep when paused. */
  dot?: StatusDotVariant;
  /** PR-state glyph before the name (merged / open / draft), or omitted
   *  until the task has a PR. */
  pr?: PrGlyphState;
};

/**
 * The workspace's threads in the sidebar — each prefixed by a soft accent
 * trunk tile. The selected thread highlights
 * and expands to show **all** its tasks (a thread can run several at once),
 * each a clickable row with its name + status dot + PR glyph. Stage
 * navigation lives on the brain page's live-plan diagram, not here.
 *
 * <p>Clicking the already-active trunk row (the common "I'm already here"
 * click, which would otherwise be a navigation no-op) toggles its task
 * list open/closed instead. Clicking a different trunk always navigates
 * and shows that trunk's tasks — {@code foldedId} only ever hides the
 * one thread it names, so switching away and back can't strand the list
 * in a folded state with no way to reopen it.
 */
export function ThreadList({
  threads, selectedId, tasks = [], selectedTaskId,
  onOpen, onOpenTask, onNewThread, heading = 'Trunks', showActions = true,
}: {
  threads: ThreadRow[];
  selectedId?: string;
  /** The open thread's tasks — sub-header rows under it. */
  tasks?: TaskNavRow[];
  selectedTaskId?: string;
  onOpen?: (id: string) => void;
  onOpenTask?: (id: string) => void;
  onNewThread?: () => void;
  heading?: string;
  showActions?: boolean;
}) {
  const [foldedId, setFoldedId] = useState<string | null>(null);

  return (
    <div className="sb-section">
      <div className="sb-section-h">
        <span className="nm">{heading}</span>
        {showActions && (
          <span className="actions">
            <span role="button" tabIndex={0} aria-label="Filter">⛚</span>
            <span role="button" tabIndex={0} aria-label="New thread" onClick={onNewThread}>+</span>
          </span>
        )}
      </div>
      <div className="thread-list">
        {threads.map(t => {
          const isActive = t.id === selectedId;
          const isFolded = isActive && foldedId === t.id;
          return (
            <Fragment key={t.id}>
              <ThreadListItem
                thread={t}
                active={isActive}
                showsFoldChevron={isActive && tasks.length > 0}
                foldedOpen={!isFolded}
                onOpen={id => {
                  if (id === selectedId) {
                    setFoldedId(prev => (prev === id ? null : id));
                  }
                  else {
                    onOpen?.(id);
                  }
                }}
              />
              {isActive && !isFolded && tasks.map(task => (
                <div
                  key={task.id}
                  className={task.id === selectedTaskId ? 'task-subhead active' : 'task-subhead'}
                  role="button"
                  tabIndex={0}
                  onClick={() => onOpenTask?.(task.id)}
                  onKeyDown={event => {
                    if (event.key === 'Enter' || event.key === ' ') {
                      event.preventDefault();
                      onOpenTask?.(task.id);
                    }
                  }}
                >
                  {/* One leading lifecycle mark before the name: the GitHub
                      PR glyph once a PR exists (open / merged), else the
                      pre-PR dot (green created → amber developing). */}
                  {task.pr !== undefined
                    ? <PrStateIcon state={task.pr} />
                    : task.dot !== undefined && <StatusDot variant={task.dot} />}
                  <span className="nm">{task.label}</span>
                </div>
              ))}
            </Fragment>
          );
        })}
      </div>
    </div>
  );
}
