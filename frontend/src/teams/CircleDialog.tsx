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
import { useEffect, useRef, useState, type KeyboardEvent } from 'react';
import { Check, CircleAlert, LoaderCircle, TriangleAlert, X } from 'lucide-react';
import Avatar from '../Avatar';
import { useModalDialog } from '../useModalDialog';
import { remoteErrorMessage } from '../remoteError';
import type { RepositoryMetadataDto } from '../types';
import { people } from './activity';
import { CIRCLE_COLORS, CIRCLE_SHAPES, type CircleAvatar, type TeamCircle, type TeamCreateInput } from './types';
import '../css/confirm-dialog.css';

type Contributor = RepositoryMetadataDto['contributors'][number];
type Selected = { githubId: number; login: string; avatarUrl: string };
const SHOWN = 50;

/** A circle's avatar: a plain block of the color and shape chosen for it. */
export function CircleIcon({ avatar, size = 'medium' }: { avatar: CircleAvatar; size?: 'dot' | 'small' | 'medium' | 'large' }) {
  return <span className={`cir-avatar is-${size} is-${avatar.color} is-${avatar.shape}`} aria-hidden="true" />;
}

/** A new circle starts on the first color no other circle uses, so circles stay easy to tell apart. */
function firstFreeAvatar(circles: TeamCircle[]): CircleAvatar {
  const color = CIRCLE_COLORS.find(candidate => !circles.some(circle => circle.avatar.color === candidate)) ?? CIRCLE_COLORS[circles.length % CIRCLE_COLORS.length];
  return { color, shape: 'square' };
}

/** The selected person a failed save names, so the dialog can mark them and offer to remove them. */
export function unverifiedPerson(error: string | null, selected: { login: string }[]): string | null {
  if (error === null) return null;
  return selected.find(person => error.toLowerCase().startsWith(`${person.login.toLowerCase()} `))?.login ?? null;
}

export default function CircleDialog({ circle, initialName = '', circles, busy, error, onSave, onCancel, onClearError, onDelete, onManagePeople }: {
  /** Absent for a new circle. */
  circle: TeamCircle | null;
  initialName?: string;
  circles: TeamCircle[];
  busy: boolean;
  error: string | null;
  onSave: (input: TeamCreateInput) => void;
  onCancel: () => void;
  onClearError: () => void;
  onDelete?: () => void;
  onManagePeople?: () => void;
}) {
  const nameInput = useRef<HTMLInputElement>(null);
  const dialog = useModalDialog(nameInput);
  const [name, setName] = useState(circle?.name ?? initialName);
  const [nameError, setNameError] = useState(false);
  const [description, setDescription] = useState(circle?.description ?? '');
  const [avatar, setAvatar] = useState<CircleAvatar>(() => circle?.avatar ?? firstFreeAvatar(circles));
  const [selected, setSelected] = useState<Selected[]>([]);
  const [query, setQuery] = useState('');
  const [focus, setFocus] = useState(0);
  const [repositories, setRepositories] = useState<string[]>(circle?.repositories ?? []);
  const [draft, setDraft] = useState('');
  const [repositoryError, setRepositoryError] = useState<string | null>(null);
  const [contributors, setContributors] = useState<Contributor[] | null>(null);
  const [contributorError, setContributorError] = useState<string | null>(null);
  const [watched, setWatched] = useState<string[] | null>(null);
  const cancel = useRef(onCancel);
  cancel.current = onCancel;

  useEffect(() => {
    let current = true;
    if (circle === null) {
      Promise.resolve().then(() => window.bridge.fetchTeamContributors())
        .then(value => { if (current) setContributors(value); })
        .catch((failure: unknown) => { if (current) { setContributors([]); setContributorError(remoteErrorMessage(failure)); } });
    }
    Promise.resolve().then(() => window.bridge.listWorkspaces())
      .then(workspaces => { if (current) setWatched([...new Set(workspaces.map(workspace => `${workspace.monitoredOwner}/${workspace.monitoredRepo}`.toLowerCase()))].sort()); })
      .catch(() => { /* Suggestions are optional; typing a repository still works. */ });
    return () => { current = false; };
  }, [circle]);

  const known = new Map(circles.flatMap(item => item.members.map(member => [member.githubId, member] as const)));
  const normalized = query.trim().replace(/^@/, '').toLowerCase();
  const options = (contributors ?? []).filter(person => !normalized || person.login.toLowerCase().includes(normalized)
    || known.get(person.id)?.name.toLowerCase().includes(normalized)).sort((left, right) => left.login.localeCompare(right.login)).slice(0, SHOWN);
  const active = Math.min(focus, Math.max(0, options.length - 1));
  const bad = unverifiedPerson(error, selected);

  function toggle(person: Contributor) {
    setSelected(current => current.some(item => item.githubId === person.id) ? current.filter(item => item.githubId !== person.id)
      : [...current, { githubId: person.id, login: person.login, avatarUrl: person.avatarUrl }]);
    if (error !== null) onClearError();
  }
  function remove(githubId: number) {
    setSelected(current => current.filter(item => item.githubId !== githubId));
    if (error !== null) onClearError();
  }
  function pickerKey(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      setFocus(Math.max(0, Math.min(options.length - 1, active + (event.key === 'ArrowDown' ? 1 : -1))));
    }
    else if (event.key === 'Enter') { event.preventDefault(); if (options[active]) toggle(options[active]); }
    else if (event.key === 'Backspace' && query === '' && selected.length > 0) remove(selected[selected.length - 1].githubId);
  }
  /** The typed repository, normalized, or null after naming what is wrong with it. */
  function typedRepository(value: string): string | null {
    const repository = value.trim().replace(/^https?:\/\/github\.com\//i, '').replace(/\/+$/, '').toLowerCase();
    if (/^[\w.-]+\/[\w.-]+$/.test(repository)) return repository;
    setRepositoryError('Use owner/repository, for example octo-org/octo-repo.');
    return null;
  }
  function addRepository(value = draft) {
    const repository = value.trim() === '' ? null : typedRepository(value);
    if (repository === null) return;
    setRepositories(current => current.includes(repository) ? current : [...current, repository]);
    setDraft(''); setRepositoryError(null);
  }
  function repositoryKey(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'Enter' || event.key === ',') { event.preventDefault(); addRepository(); }
    else if (event.key === 'Backspace' && draft === '' && repositories.length > 0) setRepositories(current => current.slice(0, -1));
  }
  function save() {
    if (!name.trim()) { setNameError(true); nameInput.current?.focus(); return; }
    // A repository still being typed is part of the scope the user sees.
    const pending = draft.trim() === '' ? null : typedRepository(draft);
    if (draft.trim() !== '' && pending === null) return;
    onSave({ name, description, avatar, repositories: pending === null || repositories.includes(pending) ? repositories : [...repositories, pending],
      members: selected.map(({ githubId, login }) => ({ githubId, login })) });
  }
  const suggestions = (watched ?? []).filter(repository => !repositories.includes(repository) && (!draft.trim() || repository.includes(draft.trim().toLowerCase()))).slice(0, 5);
  const title = circle === null ? 'New circle' : 'Edit circle';

  return <dialog ref={dialog} className="bq-confirm-dialog cir-dialog" aria-label={title} aria-modal="true" aria-busy={busy}
    onKeyDown={event => event.stopPropagation()}
    onCancel={event => {
      event.preventDefault();
      // Escape clears a people search before it closes the dialog.
      if (query !== '') setQuery('');
      else if (!busy) cancel.current();
    }}>
    <div className="cir-dialog__head">
      <div className="cir-stack"><h2 className="bq-confirm-dialog__title">{title}</h2><span className="cir-muted">Private to you. People aren’t invited or notified.</span></div>
      <button type="button" className="cir-iconbtn" aria-label="Close" disabled={busy} onClick={onCancel}><X size={16} aria-hidden="true" /></button>
    </div>
    <div className="cir-dialog__body">
      <label className="cir-field">Name
        <input ref={nameInput} className={nameError ? 'is-invalid' : undefined} aria-label="Circle name" aria-invalid={nameError} maxLength={100} value={name}
          placeholder="Trusted OSS" onChange={event => { setName(event.target.value); setNameError(false); }} />
        {nameError && <span className="cir-field__error">Give the circle a name.</span>}
      </label>
      <div className="cir-field">
        <span>Avatar</span>
        <div className="cir-avatar-field">
          <CircleIcon avatar={avatar} size="large" />
          <div className="cir-swatches" role="group" aria-label="Avatar color">
            {CIRCLE_COLORS.map(color => {
              const label = `${color.charAt(0).toUpperCase()}${color.slice(1)}`;
              return <button type="button" key={color} className={`cir-swatch is-${color}`} aria-pressed={avatar.color === color} aria-label={label} title={label}
                onClick={() => setAvatar(current => ({ ...current, color }))} />;
            })}
          </div>
          <div className="atn-filters" role="group" aria-label="Avatar shape">
            {CIRCLE_SHAPES.map(shape => <button type="button" key={shape} aria-pressed={avatar.shape === shape}
              className={`atn-filter${avatar.shape === shape ? ' is-active' : ''}`} onClick={() => setAvatar(current => ({ ...current, shape }))}>
              {shape === 'square' ? 'Square' : 'Circle'}
            </button>)}
          </div>
        </div>
      </div>
      <label className="cir-field"><span>Description <span className="cir-muted">· optional</span></span>
        <textarea aria-label="Circle description" rows={2} maxLength={2000} value={description} placeholder="People I review and collaborate with"
          onChange={event => setDescription(event.target.value)} />
      </label>

      {circle === null ? <div className="cir-field">
        <span>People <span className="cir-muted">{selected.length > 0 ? `${selected.length} selected` : 'Optional — add people now or later'}</span></span>
        <div className="cir-tokens">
          {selected.map(person => <span key={person.githubId} className={`cir-token${person.login === bad ? ' is-bad' : ''}`}>
            <Avatar login={person.login} avatarUrl={person.avatarUrl} size={18} /><span className="cir-ellipsis">{person.login}</span>
            <button type="button" className="cir-token__x" aria-label={`Remove ${person.login}`} onClick={() => remove(person.githubId)}><X size={12} strokeWidth={2.2} aria-hidden="true" /></button>
          </span>)}
          <input value={query} placeholder="Type a name or username to filter" aria-label="Filter cached contributors" aria-autocomplete="list"
            onChange={event => { setQuery(event.target.value); setFocus(0); }} onKeyDown={pickerKey} />
        </div>
        <div className="cir-picker" role="listbox" aria-label="Cached contributors" aria-multiselectable="true">
          {options.map((person, index) => {
            const on = selected.some(item => item.githubId === person.id);
            const circlesOf = circles.filter(item => item.members.some(member => member.githubId === person.id)).map(item => item.name);
            const display = known.get(person.id)?.name;
            const label = display && display !== person.login ? `${display} · @${person.login}` : `@${person.login}`;
            return <button type="button" role="option" aria-selected={on} aria-label={label} key={person.id} tabIndex={-1}
              className={`pr-people-picker__row cir-option${index === active ? ' is-active' : ''}`}
              onMouseDown={event => event.preventDefault()} onClick={() => { setFocus(index); toggle(person); }}>
              <span className="pr-people-picker__check">{on && <Check size={16} strokeWidth={2} aria-hidden="true" />}</span>
              <Avatar login={person.login} avatarUrl={person.avatarUrl} size={20} allowGitHubFallback={false} />
              <span className="cir-ellipsis">{label}{person.bot && <span className="pr-bot">Bot</span>}</span>
              <span className="cir-muted">{circlesOf.length > 0 ? `In ${circlesOf[0]}${circlesOf.length > 1 ? ` +${circlesOf.length - 1}` : ''}` : ''}</span>
            </button>;
          })}
          {contributors === null && <div className="pr-people-picker__empty">Loading cached contributors…</div>}
          {contributors !== null && options.length === 0 && (contributors.length === 0
            ? <div className="cir-picker__empty"><strong>No cached contributors yet</strong>
              <span>{contributorError ?? 'ByteQuay only searches people it has already seen in your watched repositories. Watch a repository and let it sync, or create the circle now and add people later.'}</span></div>
            : <div className="cir-picker__empty"><strong>No contributors match “{query}”</strong>
              <span>Search covers usernames and display names of contributors cached across your watched repositories. It doesn’t search GitHub.</span></div>)}
        </div>
        <span className="cir-help">From contributors cached across your watched repositories. Typing only filters — use ↑ ↓ and Return, or click, to select.</span>
      </div> : <div className="cir-people-note">
        <span><span className="cir-strong">People</span> · {circle.members.length > 0 ? `${people(circle.members.length)} · ` : 'No people yet · '}added and removed from Members</span>
        <button type="button" className="bq-button" onClick={onManagePeople}>Manage people</button>
      </div>}

      <div className="cir-field">
        <span>Repositories <span className="cir-muted">· optional</span></span>
        <div className="cir-tokens">
          {repositories.map(repository => {
            const unwatched = watched !== null && !watched.includes(repository);
            return <span key={repository} className={`cir-token is-repo${unwatched ? ' is-warn' : ''}`}
              title={unwatched ? 'Not a watched repository — only its public activity can be collected' : undefined}>
              {unwatched && <TriangleAlert size={12} aria-hidden="true" />}{repository}
              <button type="button" className="cir-token__x" aria-label={`Remove ${repository}`} onClick={() => setRepositories(current => current.filter(item => item !== repository))}>
                <X size={12} strokeWidth={2.2} aria-hidden="true" />
              </button>
            </span>;
          })}
          <input className="cir-mono" value={draft} placeholder="owner/repository" aria-label="Add repository to scope"
            onChange={event => { setDraft(event.target.value); setRepositoryError(null); }} onKeyDown={repositoryKey} />
        </div>
        {suggestions.length > 0 && (draft.trim() !== '' || repositories.length === 0) && <div className="cir-suggestions">
          <span className="cir-muted">Watched:</span>
          {suggestions.map(repository => <button type="button" key={repository} className="cir-suggestion" onMouseDown={event => event.preventDefault()}
            onClick={() => addRepository(repository)}>{repository}</button>)}
        </div>}
        {repositoryError && <span className="cir-field__error">{repositoryError}</span>}
        <span className="cir-help">Leave empty to include activity from any repository. Scope only filters — it doesn’t watch repositories or change access.</span>
      </div>

      {error !== null && <div className="cir-alert" role="alert">
        <CircleAlert size={15} aria-hidden="true" />
        <div><span className="cir-strong">Circle not saved.</span> {error}{bad !== null ? '. Nothing was saved. Remove them and try again, or re-add them after the repository syncs.' : ''}
          {bad !== null && <div><button type="button" className="bq-button" onClick={() => remove(selected.find(person => person.login === bad)?.githubId ?? 0)}>Remove {bad}</button></div>}
        </div>
      </div>}
    </div>
    <div className="cir-dialog__foot">
      {onDelete && <button type="button" className="bq-button bq-button--ghost cir-danger" disabled={busy} onClick={onDelete}>Delete circle</button>}
      <span className="cir-spacer" />
      <button type="button" className="bq-button" disabled={busy} onClick={onCancel}>Cancel</button>
      <button type="button" className="bq-button bq-button--primary" disabled={busy} onClick={save}>
        {busy && <LoaderCircle size={14} className="bq-confirm-dialog__spinner" aria-hidden="true" />}
        {busy ? circle === null ? 'Creating…' : 'Saving…' : circle === null ? 'Create circle' : 'Save changes'}
      </button>
    </div>
  </dialog>;
}
