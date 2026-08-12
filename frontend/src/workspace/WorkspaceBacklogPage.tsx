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
import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  workspaceApi,
  type WorkspaceBacklogInput,
  type WorkspaceBacklogItemDto,
  type WorkspaceTrunkDto,
} from './workspaceApi';
import { CreationOriginBadge } from '../ui/CreationOriginBadge';
import { relativeTime } from '../relativeTime';

type BacklogFilter = 'all' | 'open' | 'in-progress' | 'resolved';
type BacklogTrunk = Pick<WorkspaceTrunkDto, 'id' | 'title' | 'description' | 'status' | 'kind'>;

type Props = {
  workspaceId: string;
  threadNames?: Map<string, string>;
  selectedKey?: string;
  onOpenItem?: (key?: string) => void;
  onOpenThread?: (threadId: string) => void;
};

const FILTERS: Array<{ value: BacklogFilter; label: string }> = [
  { value: 'all', label: 'All' },
  { value: 'open', label: 'Open' },
  { value: 'in-progress', label: 'In progress' },
  { value: 'resolved', label: 'Resolved' },
];

export default function WorkspaceBacklogPage({
  workspaceId,
  threadNames = new Map(),
  selectedKey,
  onOpenItem,
  onOpenThread,
}: Props) {
  const [items, setItems] = useState<WorkspaceBacklogItemDto[]>([]);
  const [trunks, setTrunks] = useState<WorkspaceTrunkDto[]>([]);
  const [filter, setFilter] = useState<BacklogFilter>('all');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<WorkspaceBacklogItemDto | null>(null);
  const [creating, setCreating] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [backlog, workspaceTrunks] = await Promise.all([
        workspaceApi.backlog(workspaceId),
        workspaceApi.trunks(workspaceId),
      ]);
      setItems(backlog);
      setTrunks(workspaceTrunks);
      setError(null);
      if (selectedKey !== undefined) {
        setEditing(backlog.find(item => item.key === selectedKey)
          ?? await workspaceApi.backlogItem(workspaceId, selectedKey));
      }
    }
    catch (cause) {
      setError(messageOf(cause, 'Unable to load the backlog.'));
    }
    finally {
      setLoading(false);
    }
  }, [selectedKey, workspaceId]);

  useEffect(() => { void load(); }, [load]);

  useEffect(() => {
    if (selectedKey === undefined && !creating) setEditing(null);
  }, [creating, selectedKey]);

  const visible = useMemo(() => {
    const available = items.filter(item => item.status !== 'discarded');
    if (filter === 'all') return available;
    // shipped/closed are the settled outcomes of a resolved (cut) item — keep
    // them under the "Resolved" tab rather than dropping them once the PR lands.
    if (filter === 'resolved') {
      return available.filter(item =>
        item.status === 'resolved' || item.status === 'shipped' || item.status === 'closed');
    }
    return available.filter(item => item.status === filter);
  }, [filter, items]);
  const backlogNames = useMemo(() => backlogTitleMap(items), [items]);

  const open = (item: WorkspaceBacklogItemDto) => {
    setCreating(false);
    setEditing(item);
    onOpenItem?.(item.key);
  };

  const closeEditor = () => {
    setCreating(false);
    setEditing(null);
    onOpenItem?.(undefined);
  };

  return (
    <section className="wu-backlog">
      <header className="wu-backlog__header">
        <h1>Backlog</h1>
        <span>{items.length} {items.length === 1 ? 'item' : 'items'}</span>
        <div className="wu-backlog__filters" role="tablist" aria-label="Backlog status">
          {FILTERS.map(option => (
            <button
              key={option.value}
              type="button"
              role="tab"
              aria-selected={filter === option.value}
              className={filter === option.value ? 'active' : ''}
              onClick={() => setFilter(option.value)}
            >
              {option.label}
            </button>
          ))}
        </div>
        <button
          type="button"
          className="wu-backlog__add"
          onClick={() => {
            setCreating(true);
            setEditing(null);
            onOpenItem?.(undefined);
          }}
        >
          <PlusIcon /> Add item
        </button>
      </header>

      <div className="wu-backlog__body">
        {error !== null && <div className="wu-state wu-state--error">{error}</div>}
        {error === null && loading && <div className="wu-state">Loading backlog…</div>}
        {error === null && !loading && visible.length === 0 && (
          <div className="wu-state">No {filter === 'all' ? '' : `${filter} `}backlog items.</div>
        )}
        {error === null && !loading && visible.length > 0 && (
          <div className="wu-backlog-list">
            {visible.map(item => (
              <BacklogRow
                key={item.id}
                item={item}
                threadName={threadNames.get(item.threadId)}
                onOpen={() => open(item)}
              />
            ))}
          </div>
        )}
      </div>

      {(editing !== null || creating) && (
        <BacklogEditor
          key={editing?.id ?? 'new'}
          workspaceId={workspaceId}
          item={editing}
          trunks={trunks}
          threadNames={threadNames}
          backlogNames={backlogNames}
          onClose={closeEditor}
          onSaved={async saved => {
            setCreating(false);
            setEditing(saved);
            onOpenItem?.(saved.key);
            await load();
          }}
          onDiscarded={async () => {
            closeEditor();
            await load();
          }}
          onOpenThread={onOpenThread}
        />
      )}
    </section>
  );
}

function BacklogRow({
  item,
  threadName,
  onOpen,
}: {
  item: WorkspaceBacklogItemDto;
  threadName?: string;
  onOpen: () => void;
}) {
  return (
    <article
      className={`wu-backlog-row wu-backlog-row--${item.status}`}
      onClick={onOpen}
      tabIndex={0}
      onKeyDown={event => {
        if (event.key === 'Enter' || event.key === ' ') onOpen();
      }}
    >
      <div className="wu-backlog-row__copy">
        <strong>{item.title}</strong>
        {item.summary.length > 0 && !sameText(item.summary, item.title) && <p>{item.summary}</p>}
        <div className="wu-backlog-row__meta">
          {item.tags.map(tag => <Tag key={tag} value={tag} />)}
          <CreationOriginBadge origin={item.origin} />
          {item.status === 'resolved' || item.status === 'shipped' || item.status === 'closed'
            ? (
              <span>
                {item.status === 'shipped' ? 'shipped' : item.status === 'closed' ? 'closed' : 'resolved'}
                {item.linkedTaskId !== null && <> · <a>→ Task #{taskNumber(item.linkedTaskId)}</a></>}
              </span>
            )
            : (
              <>
                {item.source === 'agent' && (
                  <span className="wu-backlog-row__source">
                    <BranchSourceIcon />
                    {sourceLabel(item.source, threadName)}
                  </span>
                )}
                <span>· {relativeTime(item.createdAt, { suffix: false })}</span>
              </>
            )}
          {item.key.length > 0 && <span className="wu-visually-hidden">{item.key}</span>}
        </div>
      </div>
      <div className="wu-backlog-row__action" onClick={event => event.stopPropagation()}>
        {item.status === 'in-progress' && (
          <span className="wu-backlog-row__exploring"><i />trunk exploring</span>
        )}
        {(item.status === 'resolved' || item.status === 'shipped') && (
          <span className="wu-backlog-row__shipped"><CheckIcon /> Shipped</span>
        )}
        {item.status === 'closed' && (
          <span className="wu-backlog-row__discarded">Closed</span>
        )}
        {item.status === 'discarded' && <span className="wu-backlog-row__discarded">Discarded</span>}
      </div>
    </article>
  );
}

export function BacklogEditor({
  workspaceId,
  item,
  trunks,
  threadNames,
  backlogNames = new Map(),
  fixedTrunkId,
  readOnly = false,
  onClose,
  onSaved,
  onDiscarded,
  onOpenThread,
}: {
  workspaceId: string;
  item: WorkspaceBacklogItemDto | null;
  trunks: BacklogTrunk[];
  threadNames: Map<string, string>;
  backlogNames?: Map<string, string>;
  /** Locks Start development to the trunk hosting this editor. */
  fixedTrunkId?: string;
  /** Shows legacy items that have no workspace key without exposing invalid mutations. */
  readOnly?: boolean;
  onClose: () => void;
  onSaved: (item: WorkspaceBacklogItemDto) => Promise<void>;
  onDiscarded: () => Promise<void>;
  onOpenThread?: (threadId: string) => void;
}) {
  const initialTrunk = fixedTrunkId
    ?? item?.links.find(link => link.type === 'trunk')?.id
    ?? item?.threadId
    ?? trunks[0]?.id
    ?? '';
  const [title, setTitle] = useState(item?.title ?? '');
  const [summary, setSummary] = useState(item?.summary ?? '');
  const [showSummary] = useState(
    item === null || !sameText(item.summary, item.title),
  );
  const [detail, setDetail] = useState(item?.detail ?? '');
  const [impactRisk, setImpactRisk] = useState(item?.impactRisk ?? '');
  const [tags, setTags] = useState(item?.tags ?? []);
  const [tagInput, setTagInput] = useState('');
  const [tagEditor, setTagEditor] = useState(false);
  const [links, setLinks] = useState(item?.links ?? (
    initialTrunk.length === 0 ? [] : [{ type: 'trunk', id: initialTrunk }]
  ));
  const trunkId = initialTrunk;
  const [linkEditor, setLinkEditor] = useState<'trunk' | 'task' | 'backlog' | null>(null);
  const [linkOptions, setLinkOptions] = useState<Array<{ id: string; label: string }>>([]);
  const [knownThreadNames, setKnownThreadNames] = useState(() => new Map(threadNames));
  const [knownBacklogNames, setKnownBacklogNames] = useState(() => new Map(backlogNames));
  const [linksLoading, setLinksLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const persistedSummary = showSummary ? summary.trim() : title.trim();
  const valid = title.trim().length > 0 && persistedSummary.length > 0
    && trunkId.length > 0;
  const linksForTrunk = (selectedTrunkId: string) => [
    ...links.filter(link => link.type !== 'trunk'
      || (link.id !== trunkId && link.id !== selectedTrunkId)),
    { type: 'trunk', id: selectedTrunkId },
  ];

  const input = (): WorkspaceBacklogInput => ({
    trunkId,
    title: title.trim(),
    summary: persistedSummary,
    detail: detail.trim(),
    impactRisk: impactRisk.trim(),
    tags,
    priority: item?.priority ?? 'medium',
    links: linksForTrunk(trunkId),
  });

  const persist = async (): Promise<WorkspaceBacklogItemDto | null> => {
    if (!valid) {
      setError(trunkId.length === 0
        ? 'Create or select a trunk before saving this item.'
        : 'Title and summary are required.');
      return null;
    }
    setBusy(true);
    setError(null);
    try {
      const saved = item === null
        ? await workspaceApi.createBacklogItem(workspaceId, input())
        : await workspaceApi.updateBacklogItem(workspaceId, item.key, input());
      await onSaved(saved);
      return saved;
    }
    catch (cause) {
      setError(messageOf(cause, 'Unable to save this backlog item.'));
      return null;
    }
    finally {
      setBusy(false);
    }
  };

  const discard = async () => {
    if (item === null) {
      onClose();
      return;
    }
    setBusy(true);
    try {
      if (item.status === 'discarded') {
        await workspaceApi.reopenBacklogItem(workspaceId, item.key);
      }
      else {
        await workspaceApi.discardBacklogItem(workspaceId, item.key);
      }
      await onDiscarded();
    }
    catch (cause) {
      setError(messageOf(cause, 'Unable to change the backlog item state.'));
    }
    finally {
      setBusy(false);
    }
  };

  const addTag = () => {
    const value = tagInput.trim().replace(/\s+/g, '-');
    if (value.length > 0 && !tags.includes(value)) setTags(current => [...current, value]);
    setTagInput('');
    setTagEditor(false);
  };

  const addLink = (type: 'trunk' | 'task' | 'backlog', id: string) => {
    if (id.length === 0) return;
    setLinks(current => current.some(link => link.type === type && link.id === id)
      ? current
      : [...current, { type, id }]);
    setLinkEditor(null);
  };

  const openLinkEditor = async (type: 'trunk' | 'task' | 'backlog') => {
    setLinkEditor(type);
    setLinkOptions([]);
    setLinksLoading(true);
    setError(null);
    try {
      if (type === 'trunk') {
        const workspaceTrunks = await workspaceApi.trunks(workspaceId);
        setKnownThreadNames(current => new Map([
          ...current,
          ...workspaceTrunks.map(trunk => [trunk.id, trunk.title] as const),
        ]));
        setLinkOptions(workspaceTrunks
          .filter(trunk => !links.some(link => link.type === 'trunk' && link.id === trunk.id))
          .map(trunk => ({ id: trunk.id, label: trunk.title })));
      }
      else if (type === 'task') {
        const tasks = trunkId.length === 0 ? [] : await window.bridge.listTasksForThread(trunkId);
        setLinkOptions(tasks
          .filter(task => !links.some(link => link.type === 'task' && link.id === task.id))
          .map(task => ({
            id: task.id,
            label: task.name?.trim() || `Task ${task.seq}`,
          })));
      }
      else {
        const backlogItems = await workspaceApi.backlog(workspaceId);
        setKnownBacklogNames(current => new Map([
          ...current,
          ...backlogTitleMap(backlogItems),
        ]));
        setLinkOptions(backlogItems
          .filter(candidate => candidate.id !== item?.id && candidate.key !== item?.key)
          .filter(candidate => !links.some(link => link.type === 'backlog' && link.id === candidate.id))
          .map(candidate => ({ id: candidate.id, label: candidate.title })));
      }
    }
    catch (cause) {
      setLinkEditor(null);
      setError(messageOf(cause, `Unable to load ${
        type === 'trunk' ? 'threads' : type === 'task' ? 'tasks' : 'backlog items'
      }.`));
    }
    finally {
      setLinksLoading(false);
    }
  };

  return (
    <div className="wu-backlog-modal" role="presentation" onMouseDown={onClose}>
      <section
        className="wu-backlog-editor"
        role="dialog"
        aria-modal="true"
        aria-label={item === null ? 'New backlog item' : `Backlog item ${item.key}`}
        onMouseDown={event => event.stopPropagation()}
      >
        <header>
          <BacklogIcon />
          <h2>{item === null ? 'New backlog item' : 'Backlog item'}</h2>
          {item !== null && <span className="wu-backlog-editor__key">{item.key}</span>}
          {item !== null && <span className={`wu-backlog-editor__status ${item.status}`}>{item.status}</span>}
          <span
            className="wu-backlog-editor__close"
            role="button"
            tabIndex={0}
            aria-label="Close"
            onClick={onClose}
            onKeyDown={event => {
              if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault();
                onClose();
              }
            }}
          >
            <CloseIcon />
          </span>
        </header>

        <div className="wu-backlog-editor__body">
          <div className="wu-backlog-editor__form">
            <label>
              <span>Title <b>*</b></span>
              <EditableField
                ariaLabel="Title"
                className="title"
                value={title}
                singleLine
                autoFocus={item === null}
                readOnly={readOnly}
                onChange={setTitle}
              />
            </label>
            {showSummary && (
              <label>
                <span>Summary <b>*</b></span>
                <EditableField
                  ariaLabel="Summary" className="summary" value={summary}
                  readOnly={readOnly} onChange={setSummary}
                />
              </label>
            )}
            <label>
              <span>Detail <em>· markdown</em></span>
              <EditableField
                ariaLabel="Detail" className="detail" value={detail}
                readOnly={readOnly} onChange={setDetail}
              />
            </label>
            <label>
              <span>Impact / Risk</span>
              <EditableField
                ariaLabel="Impact / Risk" className="risk" value={impactRisk}
                readOnly={readOnly} onChange={setImpactRisk}
              />
            </label>
          </div>

          <aside>
            <h3>Tags</h3>
            <div className="wu-backlog-editor__tags">
              {tags.map(tag => (
                <span
                  key={tag}
                  className="wu-backlog-editor__tag-action"
                  role={readOnly ? undefined : 'button'}
                  tabIndex={readOnly ? undefined : 0}
                  title={readOnly ? undefined : `Remove ${tag}`}
                  onClick={readOnly ? undefined : () => setTags(current => current.filter(value => value !== tag))}
                  onKeyDown={event => {
                    if (!readOnly && (event.key === 'Enter' || event.key === ' ')) {
                      event.preventDefault();
                      setTags(current => current.filter(value => value !== tag));
                    }
                  }}
                >
                  <Tag value={tag} />
                </span>
              ))}
              {!readOnly && (tagEditor
                ? (
                  <input
                    value={tagInput}
                    onChange={event => setTagInput(event.target.value)}
                    onBlur={addTag}
                    onKeyDown={event => {
                      if (event.key === 'Enter') addTag();
                      if (event.key === 'Escape') setTagEditor(false);
                    }}
                    autoFocus
                    aria-label="New tag"
                  />
                )
                : (
                  <span
                    className="wu-backlog-editor__add-tag"
                    role="button"
                    tabIndex={0}
                    onClick={() => setTagEditor(true)}
                    onKeyDown={event => {
                      if (event.key === 'Enter' || event.key === ' ') {
                        event.preventDefault();
                        setTagEditor(true);
                      }
                    }}
                  >
                    + tag
                  </span>
                ))}
            </div>

            <h3>Linked items</h3>
            <div className="wu-backlog-editor__links">
              {links.map(link => (
                <div
                  key={`${link.type}-${link.id}`}
                  className={`wu-backlog-editor__link link-${link.type}`}
                  role={link.type === 'trunk' ? 'button' : undefined}
                  tabIndex={link.type === 'trunk' ? 0 : undefined}
                  onClick={() => link.type === 'trunk' && onOpenThread?.(link.id)}
                  onKeyDown={event => {
                    if (link.type === 'trunk' && (event.key === 'Enter' || event.key === ' ')) {
                      event.preventDefault();
                      onOpenThread?.(link.id);
                    }
                  }}
                >
                  <LinkIcon type={link.type} />
                  <span>
                    {linkLabel(link, knownThreadNames, knownBacklogNames)} <em>{linkRelation(link, item)}</em>
                  </span>
                </div>
              ))}
              {!readOnly && (
                <>
                  <div className="wu-backlog-editor__link-actions" aria-label="Add linked item">
                    <button type="button" onClick={() => void openLinkEditor('trunk')}>+ Link thread</button>
                    <button
                      type="button"
                      disabled={trunkId.length === 0}
                      onClick={() => void openLinkEditor('task')}
                    >
                      + Link task
                    </button>
                    <button type="button" onClick={() => void openLinkEditor('backlog')}>
                      + Link other item
                    </button>
                  </div>
                  {linkEditor !== null && (
                    <select
                      value=""
                      disabled={linksLoading}
                      onChange={event => {
                        addLink(linkEditor, event.target.value);
                      }}
                      onBlur={() => setLinkEditor(null)}
                      autoFocus
                      aria-label={`Link ${linkEditor === 'trunk' ? 'thread' : linkEditor === 'task' ? 'task' : 'other item'}`}
                    >
                      <option value="">
                        {linksLoading ? 'Loading…' : `Choose ${linkEditor === 'trunk' ? 'a thread' : linkEditor === 'task' ? 'a task' : 'another item'}…`}
                      </option>
                      {linkOptions.map(option => (
                        <option key={option.id} value={option.id}>{option.label}</option>
                      ))}
                    </select>
                  )}
                </>
              )}
            </div>

            {item !== null && (
              <div className="wu-backlog-editor__audit">
                <span>
                  Created by <CreationOriginBadge origin={item.origin} />
                  {' · '}{threadNames.get(item.threadId) ?? 'trunk'}
                </span>
                <span>Created {relativeTime(item.createdAt)}</span>
              </div>
            )}
          </aside>
        </div>

        {error !== null && <div className="wu-backlog-editor__error">{error}</div>}
        {!readOnly && <footer>
          <button type="button" className="discard" disabled={busy} onClick={() => void discard()}>
            {item?.status === 'discarded' ? 'Reopen' : item === null ? 'Cancel' : 'Discard'}
          </button>
          <span />
          <button type="button" className="save" disabled={busy || !valid} onClick={() => void persist()}>Save</button>
        </footer>}
      </section>
    </div>
  );
}

function EditableField({
  ariaLabel,
  autoFocus = false,
  className,
  onChange,
  readOnly = false,
  singleLine = false,
  value,
}: {
  ariaLabel: string;
  autoFocus?: boolean;
  className: string;
  onChange: (value: string) => void;
  readOnly?: boolean;
  singleLine?: boolean;
  value: string;
}) {
  return (
    <div
      aria-label={ariaLabel}
      aria-readonly={readOnly}
      className={`wu-backlog-editor__control ${className}`}
      contentEditable={!readOnly}
      role="textbox"
      aria-multiline={!singleLine}
      suppressContentEditableWarning
      tabIndex={readOnly ? undefined : 0}
      ref={element => {
        if (!readOnly && autoFocus && element !== null && element.dataset.autofocused === undefined) {
          element.dataset.autofocused = 'true';
          element.focus();
        }
      }}
      onInput={event => onChange(event.currentTarget.textContent ?? '')}
      onKeyDown={event => {
        if (singleLine && event.key === 'Enter') event.preventDefault();
      }}
    >
      {value}
    </div>
  );
}

function Tag({ value }: { value: string }) {
  const tone = tagTone(value);
  return <span className={`wu-backlog-tag ${tone}`}>{value}</span>;
}

function LinkIcon({ type }: { type: string }) {
  if (type === 'trunk') {
    return (
      <svg viewBox="0 0 24 24" aria-hidden>
        <circle cx="6" cy="6" r="2.4" /><circle cx="6" cy="18" r="2.4" />
        <circle cx="18" cy="12" r="2.4" /><path d="m8.3 7.2 7.4 3.8M8.3 16.8l7.4-3.8" />
      </svg>
    );
  }
  if (type === 'task') {
    return <svg viewBox="0 0 24 24" aria-hidden><circle cx="18" cy="18" r="2.6" /><circle cx="6" cy="6" r="2.6" /><path d="M6 21V9a9 9 0 0 0 9 9" /></svg>;
  }
  return <BacklogIcon />;
}

function PlusIcon() {
  return <svg viewBox="0 0 24 24" aria-hidden><path d="M12 5v14M5 12h14" /></svg>;
}

function CheckIcon() {
  return <svg viewBox="0 0 24 24" aria-hidden><path d="M20 6 9 17l-5-5" /></svg>;
}

function BranchSourceIcon() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden>
      <circle cx="6" cy="6" r="2.4" />
      <circle cx="6" cy="18" r="2.4" />
      <circle cx="18" cy="12" r="2.4" />
      <path d="M8.3 7.2 15.7 11M8.3 16.8 15.7 13" />
    </svg>
  );
}

function BacklogIcon() {
  return <svg viewBox="0 0 24 24" aria-hidden><path d="M22 12h-6l-2 3h-4l-2-3H2" /><path d="M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z" /></svg>;
}

function CloseIcon() {
  return <svg viewBox="0 0 24 24" aria-hidden><path d="m6 6 12 12M18 6 6 18" /></svg>;
}

function tagTone(value: string): string {
  const lower = value.toLowerCase();
  if (lower.includes('test') || lower.includes('good-first')) return 'green';
  if (lower.includes('perf') || lower.includes('risk')) return 'amber';
  if (lower.includes('bug') || lower.includes('critical')) return 'red';
  return 'blue';
}

function sourceLabel(source: string, threadName?: string): string {
  if (source === 'agent') return `from ${threadName ?? 'agent'}`;
  if (source === 'user') return 'manual';
  return source;
}

function taskNumber(taskId: string): string {
  return taskId.match(/(?:\.k|task-)(\d+)$/)?.[1] ?? taskId.replace(/^task-/, '');
}

function linkLabel(
  link: { type: string; id: string },
  threadNames: Map<string, string>,
  backlogNames: Map<string, string>,
): string {
  if (link.type === 'trunk') return threadNames.get(link.id) ?? link.id;
  if (link.type === 'task') return `Task #${taskNumber(link.id)}`;
  if (link.type === 'backlog') return backlogNames.get(link.id) ?? 'Backlog item';
  if (link.type === 'issue') return `Issue #${link.id}`;
  if (link.type === 'pr') return `PR #${link.id}`;
  return `${link.type} ${link.id}`;
}

export function backlogTitleMap(
  items: Array<{ id: string; key?: string | null; title: string }>,
): Map<string, string> {
  const names = new Map<string, string>();
  items.forEach(item => {
    names.set(item.id, item.title);
    if (item.key !== null && item.key !== undefined) names.set(item.key, item.title);
  });
  return names;
}

function linkRelation(
  link: { type: string; id: string },
  item: WorkspaceBacklogItemDto | null,
): string {
  if (link.type === 'trunk') return link.id === item?.threadId ? 'thread · origin' : 'linked thread';
  if (link.type === 'task') return 'found during';
  if (link.type === 'backlog') return 'relates to';
  if (item?.source === 'agent') return 'linked by agent';
  return '';
}

function messageOf(cause: unknown, fallback: string): string {
  return cause instanceof Error && cause.message.length > 0 ? cause.message : fallback;
}

function sameText(left: string, right: string): boolean {
  return left.trim().toLocaleLowerCase() === right.trim().toLocaleLowerCase();
}
