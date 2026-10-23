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

import React from 'react';
import { Archive, ArchiveRestore, Check, ChevronRight, Cherry, CodeXml, Eye, LayoutGrid, PanelLeft, Trash2, Wrench } from 'lucide-react';
import { Card, CardActions, CardAge, Clickable, IconButton, LinkKey, Pill } from '../cards/Card';
import { relativeTime } from '@bytequay/ui/relativeTime';
import { ProviderAvatar, ProviderIcon, providerBrand } from './ProviderIcon';
import { taskArchived, taskBusy } from './taskStatus';
import { TASK_TYPES, type TaskListItem, type TaskListMessage, type TaskType } from './taskList';

const TYPE_ICONS = { dev: CodeXml, review: Eye, cherry: Cherry, ci: Wrench };

/** A task type's icon; "all" is the grid the All tab shows. */
export function TaskTypeIcon({ type, size = 14 }: { type: TaskType | 'all'; size?: number }): React.JSX.Element {
  const Icon = type === 'all' ? LayoutGrid : TYPE_ICONS[type];
  return <Icon size={size} strokeWidth={1.9} aria-hidden="true" />;
}

/** "RP" for Riley Park, or the start of a login when the profile has no name. */
export function viewerInitials(profile: { login: string; name: string | null } | undefined): string {
  const words = profile?.name?.trim().split(/\s+/).filter((word) => word !== '') ?? [];
  if (words.length > 0) return words.slice(0, 2).map((word) => word[0].toUpperCase()).join('');
  return profile === undefined ? 'Y' : profile.login.slice(0, 2).toUpperCase();
}

/** Who said a task's last message: the agent's logo, else a round badge of initials. */
function Speaker({ message, you }: { message: TaskListMessage; you: string }): React.JSX.Element {
  if (message.laneId !== undefined && providerBrand(message.laneId) !== null) return <ProviderAvatar id={message.laneId} size={18} />;
  const mine = message.who === 'You';
  return <span className={`agt-speaker${mine ? ' is-you' : ''}`} aria-hidden="true">{mine ? you : message.who.slice(0, 2).toUpperCase()}</span>;
}

function StatePill({ item }: { item: TaskListItem }): React.JSX.Element {
  return <Pill tone={item.state.tone} spinning={item.state.spinning}>{item.state.label}</Pill>;
}

function Links({ item }: { item: TaskListItem }): React.JSX.Element {
  return <>{item.links.map((link) => <LinkKey key={`${link.source}:${link.key}`} source={link.source} name={link.key} title={link.title} size={13} />)}</>;
}

export type TaskItemHandlers = {
  now: number;
  /** The viewer's initials, for a message the user sent. */
  you?: string;
  /** While anything is selected, activating an agent task toggles it instead of opening it. */
  selecting: boolean;
  checked: boolean;
  onOpen: () => void;
  onToggle?: () => void;
  onArchive?: (archived: boolean) => void;
  onDelete?: () => void;
  /** A review's own conversation, beside its pull request. */
  onOpenWorkspace?: () => void;
};

/**
 * The type chip or tile. For an agent task it is also the selection slot: its icon turns into a
 * checkbox on hover and while anything is selected.
 */
function TypeSlot({ item, checked, onToggle, chip }: {
  item: TaskListItem;
  checked: boolean;
  onToggle?: () => void;
  chip: boolean;
}): React.JSX.Element {
  const label = TASK_TYPES[item.type].label;
  const icon = <TaskTypeIcon type={item.type} size={chip ? 13 : 14} />;
  const className = `${chip ? 'agt-type-chip' : 'agt-type-tile'} is-${item.type}`;
  if (onToggle === undefined) {
    return <span className={className} title={label}>{icon}{chip && label}</span>;
  }
  return (
    <button type="button" className={className} title={label} aria-pressed={checked} aria-label={`${checked ? 'Deselect' : 'Select'} ${item.title}`}
      onClick={(event) => { event.stopPropagation(); onToggle(); }}>
      <span className="agt-type-glyph">{icon}</span>
      <span className="agt-type-check" aria-hidden="true"><Check size={10} strokeWidth={3.2} /></span>
      {chip && label}
    </button>
  );
}

function Actions({ item, onArchive, onDelete, onOpenWorkspace }: Pick<TaskItemHandlers, 'onArchive' | 'onDelete' | 'onOpenWorkspace'> & { item: TaskListItem }): React.JSX.Element | null {
  if (onArchive === undefined && onDelete === undefined && onOpenWorkspace === undefined) return null;
  const archived = item.task !== null && taskArchived(item.task);
  const busy = item.task !== null && taskBusy(item.task);
  return (
    <CardActions>
      {onOpenWorkspace !== undefined && (
        <IconButton label={`View ${item.title} inside workspace`} title="View inside workspace" onClick={onOpenWorkspace}>
          <PanelLeft size={14} aria-hidden="true" />
        </IconButton>
      )}
      {onArchive !== undefined && (
        <IconButton label={`${archived ? 'Restore' : 'Archive'} task: ${item.title}`} title={archived ? 'Restore to Active' : 'Archive'} onClick={() => onArchive(!archived)}>
          {archived ? <ArchiveRestore size={14} aria-hidden="true" /> : <Archive size={14} aria-hidden="true" />}
        </IconButton>
      )}
      {onDelete !== undefined && (
        <IconButton label={`Delete task: ${item.title}`} title={busy ? 'Stop the task before deleting' : 'Delete permanently'} tone="danger" disabled={busy} onClick={onDelete}>
          <Trash2 size={14} aria-hidden="true" />
        </IconButton>
      )}
    </CardActions>
  );
}

/**
 * Today's task as a card: its type and state, the title, why it needs the user, the conversation's
 * last message, and its links with what it changed or the stages a cherry-pick has come through.
 */
export function TaskItemCard({ item, now, you = 'Y', selecting, checked, onOpen, onToggle, onArchive, onDelete, onOpenWorkspace }: TaskItemHandlers & { item: TaskListItem }): React.JSX.Element {
  const open = selecting && onToggle !== undefined ? onToggle : onOpen;
  return (
    <Card className="agt-item-card" selected={checked} label={`${TASK_TYPES[item.type].label}: ${item.title}`} onOpen={open}>
      <div className="agt-item-card__head">
        <TypeSlot item={item} checked={checked} onToggle={onToggle} chip />
        <StatePill item={item} />
        <span className="agt-spacer" />
        {!selecting && <Actions item={item} onArchive={onArchive} onDelete={onDelete} onOpenWorkspace={onOpenWorkspace} />}
        <CardAge at={item.at} now={now} />
      </div>
      <div className="agt-item-card__title">{item.title}</div>
      {item.state.reason !== null && <div className="agt-item-card__needs"><span aria-hidden="true" />{item.state.reason}</div>}
      {item.message !== null && (
        <div className="card-box agt-item-card__message">
          <Speaker message={item.message} you={you} />
          <div className="agt-item-card__said">
            <div className="agt-item-card__who"><span>{item.message.who}</span> · {relativeTime(item.message.at, { suffix: false, now })}</div>
            <div className="agt-item-card__text">{item.message.text}</div>
          </div>
        </div>
      )}
      {item.stages !== null && (
        <div className="agt-item-card__stages" aria-label="Stages">
          {item.stages.map(stage => <span key={stage.stage} className={`is-${stage.state}`} title={`${stage.name}: ${stage.detail}`}>{stage.name}</span>)}
        </div>
      )}
      {(item.links.length > 0 || item.change !== null) && (
        <div className="agt-item-card__foot">
          <Links item={item} />
          {item.change !== null && <span className="agt-item-change">{item.change}</span>}
        </div>
      )}
    </Card>
  );
}

/** An older task as one row: its type, title, the last message, links, state and age. */
export function TaskItemRow({ item, now, selecting, checked, onOpen, onToggle, onArchive, onDelete, onOpenWorkspace }: TaskItemHandlers & { item: TaskListItem }): React.JSX.Element {
  const open = selecting && onToggle !== undefined ? onToggle : onOpen;
  return (
    <Clickable className={`agt-item-row card-row${checked ? ' is-selected' : ''}`} label={`${TASK_TYPES[item.type].label}: ${item.title}`} onOpen={open}>
      <TypeSlot item={item} checked={checked} onToggle={onToggle} chip={false} />
      <span className="agt-item-row__title">{item.title}</span>
      <span className="agt-item-row__said">
        {item.message !== null && <><span className="agt-item-row__who">
          {item.message.laneId !== undefined && <><ProviderIcon id={item.message.laneId} size={12} /> </>}{item.message.who}:</span> {item.message.text}</>}
      </span>
      <Links item={item} />
      {item.change !== null && <span className="agt-item-change">{item.change}</span>}
      <StatePill item={item} />
      <CardAge at={item.at} now={now} />
      <span className="agt-item-row__end">
        {!selecting && <Actions item={item} onArchive={onArchive} onDelete={onDelete} onOpenWorkspace={onOpenWorkspace} />}
        <ChevronRight className="agt-item-row__chev" size={13} aria-hidden="true" />
      </span>
    </Clickable>
  );
}
