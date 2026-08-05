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
import { useState } from 'react';
import Avatar from '../Avatar';
import type { EditableCommit } from './commitRewrite';
import type { WorkspaceBranchDto } from './workspaceApi';
import { isToday, useDismissOnOutside } from './WorkspaceRepoUi';

/** Colour per selected commit, reused by the selection chips and the
 *  per-file "which commits touched this" markers so the two read as one
 *  legend. */
export const SELECTION_COLOURS = [
  '#0969da', '#8250df', '#1f883d', '#bf8700', '#cf222e', '#0e7490',
];

export function selectionColour(index: number): string {
  return SELECTION_COLOURS[index % SELECTION_COLOURS.length];
}

/** Day heading for the pushed section. Matches the Commits list's own
 *  grouping so the two surfaces read the same. */
export function dayLabel(iso: string | null): string {
  if (iso === null) return 'Earlier';
  if (isToday(iso)) return 'Today';
  return new Intl.DateTimeFormat(undefined, { weekday: 'long', month: 'short', day: 'numeric' })
    .format(new Date(iso));
}

/** Middle-elides a long path so the interesting tail stays readable. */
export function shortPath(path: string): string {
  const parts = path.split('/');
  return parts.length > 4 ? `${parts[0]}/…/${parts.slice(-2).join('/')}` : path;
}

/**
 * Author filter for the Commits header. Lives beside the search box
 * rather than in the list, so the two filters read as one control — the
 * count-and-chips bar below only appears once one of them is on.
 */
/** One author's share of the loaded history. */
export type CommitAuthorTally = {
  name: string;
  /** GitHub handle for the avatar; see {@link githubHandle}. */
  handle: string;
  count: number;
};

export function CommitAuthorPicker({
  author,
  authors,
  total,
  onPick,
}: {
  author: string;
  /** Most prolific first. */
  authors: CommitAuthorTally[];
  total: number;
  onPick: (author: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const shell = useDismissOnOutside<HTMLSpanElement>(open, () => setOpen(false));
  const choose = (next: string) => { onPick(next); setOpen(false); };
  return (
    <span className="wu-ce-authorpick" ref={shell}>
      <button type="button" className={author === 'all' ? '' : 'is-on'}
        aria-expanded={open} onClick={() => setOpen(value => !value)}>
        <PersonIcon />{author === 'all' ? 'All authors' : author}
        <ChevronIcon />
      </button>
      {open && (
        <div className="wu-ce-authormenu" role="menu">
          <button type="button" role="menuitem"
            className={author === 'all' ? 'is-on' : ''} onClick={() => choose('all')}>
            <span className="wu-author-avatar" aria-hidden>∗</span>
            All authors<i>{total}</i>
          </button>
          {authors.map(tally => (
            <button type="button" role="menuitem" key={tally.name}
              className={author === tally.name ? 'is-on' : ''}
              onClick={() => choose(tally.name)}>
              <Avatar login={tally.handle} size={18} className="wu-ce-avatar" />
              {tally.name}<i>{tally.count}</i>
            </button>
          ))}
        </div>
      )}
    </span>
  );
}

/**
 * Branch picker for the Commits list. Replaces an invisible native
 * <select> overlay: that showed one entry and gave no hint whether more
 * existed, and the design calls for a button here anyway.
 *
 * On a fork the upstream's refs are listed under their own heading, so
 * the same picker walks both repos — reading `upstream/master` is how you
 * find the commits worth cherry-picking down into the fork.
 */
export function CommitBranchPicker({
  branch,
  branches,
  upstreamBranches = [],
  upstreamLabel,
  currentBranch,
  onPick,
}: {
  branch: string;
  branches: WorkspaceBranchDto[];
  /** Qualified `upstream/*` refs; empty for a direct clone. */
  upstreamBranches?: string[];
  /** Heading for the upstream group — the upstream repo's full name. */
  upstreamLabel?: string;
  /** The checked-out branch — the only one history can be rewritten on. */
  currentBranch: string | null;
  onPick: (branch: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const shell = useDismissOnOutside<HTMLSpanElement>(open, () => setOpen(false));
  // Remote-only rows are PR head refs with no local checkout; switching
  // the Commits list to one would show an empty, uneditable history.
  const local = branches.filter(candidate => !candidate.remoteOnly);
  return (
    <span className="wu-ce-authorpick wu-ce-branchpick" ref={shell}>
      <button type="button" aria-expanded={open} aria-label={`Branch: ${branch}`}
        onClick={() => setOpen(value => !value)}>
        <BranchGlyph />{branch}<ChevronIcon />
      </button>
      {open && (
        <div className="wu-ce-authormenu" role="menu">
          {local.map(candidate => (
            <button type="button" role="menuitem" key={candidate.name}
              className={candidate.name === branch ? 'is-on' : ''}
              onClick={() => { onPick(candidate.name); setOpen(false); }}>
              <BranchGlyph />
              <span className="wu-ce-branchname">{candidate.name}</span>
              {candidate.name === currentBranch && <i>checked out</i>}
            </button>
          ))}
          {local.length === 0 && upstreamBranches.length === 0 && (
            <span className="wu-ce-menu-empty" role="status">
              No local branches yet — check one out to see it here.
            </span>
          )}
          {upstreamBranches.length > 0 && (
            <span className="wu-ce-menu-group" role="presentation">
              {upstreamLabel ?? 'Upstream'}
            </span>
          )}
          {upstreamBranches.map(name => (
            <button type="button" role="menuitem" key={name}
              className={name === branch ? 'is-on' : ''}
              onClick={() => { onPick(name); setOpen(false); }}>
              <BranchGlyph />
              <span className="wu-ce-branchname">{name}</span>
            </button>
          ))}
        </div>
      )}
    </span>
  );
}

function BranchGlyph() {
  return (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <circle cx="6" cy="6" r="2.4" /><circle cx="6" cy="18" r="2.4" />
      <circle cx="18" cy="6" r="2.4" />
      <path d="M6 8.5v7" /><path d="M18 8.5a7 7 0 0 1-7 7" />
    </svg>
  );
}

export function ChevronIcon() {
  return (
    <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="m6 9 6 6 6-6" />
    </svg>
  );
}

/**
 * Best guess at the GitHub account behind a commit. GitHub's private
 * commit address — `<id>+<login>@users.noreply.github.com`, or plain
 * `<login>@…` on older accounts — is the only field git carries that
 * names the ACCOUNT rather than a display name, so it wins. Otherwise
 * the author name is used as-is: it's often the handle, and when it
 * isn't, the avatar 404s and {@link Avatar} falls back to the initial.
 */
export function githubHandle(authorName: string, authorEmail: string): string {
  const noreply = /^(?:\d+\+)?([^@\s]+)@users\.noreply\.github\.com$/i
    .exec(authorEmail.trim());
  return noreply === null ? authorName.trim() : noreply[1];
}

/** The commit author's GitHub picture, with the initial as the fallback. */
export function CommitAuthorAvatar({ commit, size }: { commit: EditableCommit; size: number }) {
  return (
    <Avatar
      login={githubHandle(commit.authorName, commit.authorEmail)}
      size={size}
      className="wu-ce-avatar"
    />
  );
}

export function GripIcon() {
  return (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor" aria-hidden>
      <circle cx="9" cy="6" r="1.55" /><circle cx="15" cy="6" r="1.55" />
      <circle cx="9" cy="12" r="1.55" /><circle cx="15" cy="12" r="1.55" />
      <circle cx="9" cy="18" r="1.55" /><circle cx="15" cy="18" r="1.55" />
    </svg>
  );
}

export function PencilIcon() {
  return (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M12 20h9" />
      <path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z" />
    </svg>
  );
}

export function SquashIcon() {
  return (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M8 4h8" /><path d="M8 20h8" /><path d="M12 8v8" /><path d="m9 11 3 3 3-3" />
    </svg>
  );
}

export function UpArrowIcon() {
  return (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M12 19V5" /><path d="m5 12 7-7 7 7" />
    </svg>
  );
}

export function UndoIcon() {
  return (
    <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M3 12a9 9 0 1 1 3 6.7" /><path d="M3 4v8h8" />
    </svg>
  );
}

export function WarnIcon() {
  return (
    <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2.2" strokeLinecap="round" aria-hidden>
      <path d="M12 8v5" /><path d="M12 17h.01" /><circle cx="12" cy="12" r="9" />
    </svg>
  );
}

export function CheckIcon() {
  return (
    <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M5 12.5 10 17.5 19 7" />
    </svg>
  );
}

export function PersonIcon() {
  return (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <circle cx="12" cy="8" r="3.4" /><path d="M5 20a7 7 0 0 1 14 0" />
    </svg>
  );
}
