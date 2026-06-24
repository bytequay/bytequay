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

/**
 * The workspace's threads in the sidebar — each prefixed by its repo
 * logo so you see which repo it targets at a glance. The selected thread
 * (when one is open) highlights.
 */
export function ThreadList({ threads, selectedId, onOpen, onNewThread }: {
  threads: ThreadRow[];
  selectedId?: string;
  onOpen?: (id: string) => void;
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
          <ThreadListItem key={t.id} thread={t} active={t.id === selectedId} onOpen={onOpen} />
        ))}
      </div>
    </div>
  );
}
