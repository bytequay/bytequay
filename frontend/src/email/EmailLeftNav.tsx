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
import type {
  EmailTagAction,
  EmailTagArchiveEntryDto,
  EmailTagDto,
  EmailThreadMetaDto,
} from '../types';

/**
 * The currently-active left-nav selection. System buckets sit at the
 * top (Inbox / Archived / Ignored) and a per-tag view filters to the
 * threads (or archive entries) that one rule matched.
 */
export type EmailActiveView =
  | { kind: 'inbox' }
  | { kind: 'archived' }
  | { kind: 'ignored' }
  | { kind: 'tag'; tagId: string };

type Props = {
  tags: EmailTagDto[];
  /** The classified inbox listing — already filtered server-side to
   *  drop ARCHIVE threads, so what arrives here is INBOX / FOCUS /
   *  IGNORE only. */
  threads: EmailThreadMetaDto[];
  /** Tag-driven archive log. Drives both the system "Archived" count
   *  and per-archive-tag counts in the nav. */
  archiveEntries: EmailTagArchiveEntryDto[];
  activeView: EmailActiveView;
  onSelect: (view: EmailActiveView) => void;
  onOpenManageRules: () => void;
};

/** Two-section nav: system buckets, then user-defined tag rules.
 *  Counts are computed from props on render — cheap, no caching. */
export default function EmailLeftNav({
  tags, threads, archiveEntries, activeView, onSelect, onOpenManageRules,
}: Props)
{
  const inboxN = threads.filter(t => t.view === 'INBOX' || t.view === 'FOCUS').length;
  const ignoredN = threads.filter(t => t.view === 'IGNORE').length;
  const archivedN = archiveEntries.length;

  return (
    <nav className="email-nav" aria-label="Email folders and tags">
      <ul className="email-nav__list">
        <NavItem
          icon="📥"
          label="Inbox"
          count={inboxN}
          active={activeView.kind === 'inbox'}
          onClick={() => onSelect({ kind: 'inbox' })}
        />
        <NavItem
          icon="📦"
          label="Archived"
          count={archivedN}
          active={activeView.kind === 'archived'}
          onClick={() => onSelect({ kind: 'archived' })}
        />
        <NavItem
          icon="🚫"
          label="Ignored"
          count={ignoredN}
          active={activeView.kind === 'ignored'}
          onClick={() => onSelect({ kind: 'ignored' })}
        />
      </ul>

      {tags.length > 0 && (
        <>
          <div className="email-nav__heading">My tags</div>
          <ul className="email-nav__list">
            {tags.map(tag => (
              <NavItem
                key={tag.id}
                icon={iconForAction(tag.action)}
                label={tag.name}
                count={countForTag(tag, threads, archiveEntries)}
                active={activeView.kind === 'tag' && activeView.tagId === tag.id}
                onClick={() => onSelect({ kind: 'tag', tagId: tag.id })}
              />
            ))}
          </ul>
        </>
      )}

      <div className="email-nav__footer">
        <button
          type="button"
          className="email-nav__manage"
          onClick={onOpenManageRules}
        >
          ⚙ Manage rules
        </button>
      </div>
    </nav>
  );
}

function NavItem({
  icon, label, count, active, onClick,
}: {
  icon: string;
  label: string;
  count: number;
  active: boolean;
  onClick: () => void;
})
{
  return (
    <li>
      <button
        type="button"
        className={`email-nav__item${active ? ' email-nav__item--active' : ''}`}
        onClick={onClick}
        aria-current={active ? 'page' : undefined}
      >
        <span className="email-nav__icon" aria-hidden="true">{icon}</span>
        <span className="email-nav__label">{label}</span>
        <span className="email-nav__count">{count}</span>
      </button>
    </li>
  );
}

function iconForAction(action: EmailTagAction): string
{
  switch (action) {
    case 'FOCUS': return '⭐';
    case 'ARCHIVE': return '📦';
    case 'IGNORE': return '🚫';
  }
}

function countForTag(
  tag: EmailTagDto,
  threads: EmailThreadMetaDto[],
  archiveEntries: EmailTagArchiveEntryDto[],
): number
{
  switch (tag.action) {
    case 'FOCUS':
      return threads.filter(t => t.view === 'FOCUS' && t.matchedTagId === tag.id).length;
    case 'IGNORE':
      return threads.filter(t => t.view === 'IGNORE' && t.matchedTagId === tag.id).length;
    case 'ARCHIVE':
      return archiveEntries.filter(e => e.tagId === tag.id).length;
  }
}
