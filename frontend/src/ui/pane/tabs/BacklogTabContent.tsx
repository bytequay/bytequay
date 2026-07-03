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
import { Card } from '../../conv';
import type { CardTag } from '../../conv';

/** Backlog item data for a card in the Backlog tab. */
export type BacklogItemData = {
  id: string;
  title: string;
  body?: string;
  tags?: CardTag[];
  createdLabel?: string;
  /** Set once "Start development" has cut a task from this item. */
  started?: boolean;
  /** Item is not-to-proceed (Dropped): shows a Reopen action, not Start. */
  dropped?: boolean;
  linkedTaskLabel?: string;
};

/**
 * The Backlog tab (trunk only) — a JIRA-like parking lot. Each item is
 * the unified {@link Card} in its backlog variant (tags + bright-orange
 * "Start development →" CTA); started items stay listed, faded, with a
 * link to the task they cut. A dashed "add item" dropzone sits on top.
 */
export function BacklogTabContent(
  { items, onAddItem, onStartDevelopment, onDrop, onReopen, onOpenItem, onOpenLinked }: {
  items: BacklogItemData[];
  onAddItem?: () => void;
  onStartDevelopment?: (id: string) => void;
  /** Marks an item not-to-proceed — the per-item Drop button. */
  onDrop?: (id: string) => void;
  /** Restores a dropped item to created — the per-item Reopen button. */
  onReopen?: (id: string) => void;
  onOpenItem?: (id: string) => void;
  onOpenLinked?: (id: string) => void;
}) {
  return (
    <>
      {onAddItem !== undefined && (
        <button type="button" className="backlog-add" onClick={onAddItem}>
          <span className="ic" aria-hidden>＋</span>Add a backlog item
        </button>
      )}
      {items.map(item => (
        <Card
          key={item.id}
          kind="backlog"
          title={item.title}
          body={item.body}
          tags={item.tags}
          createdLabel={item.createdLabel}
          started={item.started}
          dropped={item.dropped}
          linkedTaskLabel={item.linkedTaskLabel}
          onClick={onOpenItem !== undefined ? () => onOpenItem(item.id) : undefined}
          onStartDevelopment={onStartDevelopment !== undefined ? () => onStartDevelopment(item.id) : undefined}
          onDrop={onDrop !== undefined ? () => onDrop(item.id) : undefined}
          onReopen={onReopen !== undefined ? () => onReopen(item.id) : undefined}
          onOpenLinked={onOpenLinked !== undefined ? () => onOpenLinked(item.id) : undefined}
        />
      ))}
    </>
  );
}
