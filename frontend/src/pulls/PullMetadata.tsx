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
import { Check, User } from 'lucide-react';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { usePopoverDismiss } from '@bytequay/ui/usePopoverDismiss';
import Avatar from '../Avatar';
import { pullRequestAttentionKey } from '../home/attention';
import { recordAttentionAction } from '../home/useAttentionVisit';
import { errorMessage } from '../errorMessage';
import type { PullRequestDetailDto, RepoLabelDto, RepoUserDto } from '../types';
import { displayActor } from './model';
import type { PullDetailUpdater } from './pullDetail';
import { usePullRequestBridge } from './PullRequestWorkspace';
import { mentionCandidates } from './useMentions';

type PeoplePickerKind = 'reviewers' | 'assignees';
type MetadataPickerKind = PeoplePickerKind | 'labels';
type PeoplePickerCandidate = RepoUserDto & { selected: boolean; locked: boolean };

export default function PullMetadata({ detail, contributorsWorkspaceId, onDetailUpdate }: {
  detail: PullRequestDetailDto;
  contributorsWorkspaceId?: string;
  onDetailUpdate: PullDetailUpdater;
}) {
  const bridge = usePullRequestBridge();
  const [open, setOpen] = useState<MetadataPickerKind | null>(null);
  const [repoUsers, setRepoUsers] = useState<RepoUserDto[]>([]);
  const [repoTeams, setRepoTeams] = useState<string[] | null>(null);
  const [repoLabels, setRepoLabels] = useState<RepoLabelDto[]>([]);
  const [loading, setLoading] = useState(false);
  const [updating, setUpdating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState('');
  const key = `${detail.repo.toLowerCase()}#${detail.number}`;
  const [optimisticDetail, setOptimisticDetail] = useState<{
    key: string;
    value: PullRequestDetailDto;
  } | null>(null);
  const optionsRequest = useRef(0);
  const optionsKey = useRef(key);
  const updatingRef = useRef(false);
  const visibleDetail = optimisticDetail?.key === key ? optimisticDetail.value : detail;
  optionsKey.current = key;

  const root = usePopoverDismiss(() => { if (!updatingRef.current) setOpen(null); }, false, open !== null);

  useEffect(() => {
    optionsRequest.current += 1;
    setRepoUsers([]);
    setRepoTeams(null);
    setRepoLabels([]);
    setOptimisticDetail(null);
    setLoading(false);
    setOpen(null);
    setError(null);
  }, [key]);

  const toggle = (kind: MetadataPickerKind) => {
    if (updatingRef.current) return;
    setQuery('');
    setError(null);
    setOpen(current => current === kind ? null : kind);
    const needsUsers = repoUsers.length === 0;
    const needsTeams = kind === 'reviewers' && repoTeams === null;
    if (loading || (kind === 'labels' ? repoLabels.length > 0 : !needsUsers && !needsTeams)) return;
    const requestId = ++optionsRequest.current;
    const requestKey = key;
    setLoading(true);
    const request = kind === 'labels'
      ? bridge.fetchRepoLabels(visibleDetail.repo).then(labels => {
          if (requestId === optionsRequest.current && requestKey === optionsKey.current) setRepoLabels(labels);
        })
      : Promise.all([
          needsUsers && bridge.fetchRepoUsers(visibleDetail.repo, contributorsWorkspaceId).then(users => {
            if (requestId === optionsRequest.current && requestKey === optionsKey.current) setRepoUsers(users);
          }),
          needsTeams && bridge.fetchRepoTeams(visibleDetail.repo).then(teams => {
            if (requestId === optionsRequest.current && requestKey === optionsKey.current) setRepoTeams(teams);
          }),
        ]);
    void request
      .catch(failure => {
        if (requestId === optionsRequest.current && requestKey === optionsKey.current) {
          setError(errorMessage(failure));
        }
      })
      .finally(() => {
        if (requestId === optionsRequest.current && requestKey === optionsKey.current) setLoading(false);
      });
  };

  const update = async (
    optimistic: (current: PullRequestDetailDto) => PullRequestDetailDto,
    rollback: (current: PullRequestDetailDto) => PullRequestDetailDto,
    action: () => Promise<void>,
  ) => {
    if (updatingRef.current) return;
    updatingRef.current = true;
    setUpdating(true);
    setError(null);
    const next = optimistic(visibleDetail);
    setOptimisticDetail({ key, value: next });
    onDetailUpdate(visibleDetail.repo, visibleDetail.number, optimistic);
    try {
      await action();
      recordAttentionAction(pullRequestAttentionKey(visibleDetail), 'update');
      onDetailUpdate(visibleDetail.repo, visibleDetail.number, optimistic);
      setOptimisticDetail(null);
      setOpen(null);
    }
    catch (failure) {
      onDetailUpdate(visibleDetail.repo, visibleDetail.number, rollback);
      setOptimisticDetail(null);
      setError(errorMessage(failure));
    }
    finally {
      updatingRef.current = false;
      setUpdating(false);
    }
  };

  const savePeople = async (kind: PeoplePickerKind, changes: PeoplePickerCandidate[]) => {
    if (updatingRef.current || changes.length === 0) return;
    updatingRef.current = true;
    setUpdating(true);
    setError(null);
    let confirmed = visibleDetail;
    const commit = (apply: (current: PullRequestDetailDto) => PullRequestDetailDto) => {
      confirmed = apply(confirmed);
      if (optionsKey.current === key) setOptimisticDetail({ key, value: confirmed });
      onDetailUpdate(visibleDetail.repo, visibleDetail.number, apply);
      recordAttentionAction(pullRequestAttentionKey(visibleDetail), 'update');
    };
    try {
      if (kind === 'assignees') {
        const removed = new Set(changes.filter(candidate => !candidate.selected).map(candidate => candidate.login.toLowerCase()));
        const next = [...visibleDetail.assignees.filter(login => !removed.has(login.toLowerCase())),
          ...changes.filter(candidate => candidate.selected).map(candidate => candidate.login)];
        await bridge.updatePrMetadata(visibleDetail.repo, visibleDetail.number, next, visibleDetail.labels.map(label => label.name));
        commit(current => ({ ...current, assignees: next }));
      }
      else {
        for (const candidate of changes) {
          await bridge.updatePrReviewer(visibleDetail.repo, visibleDetail.number, candidate.login, candidate.selected);
          // Keep successful writes when a later reviewer fails, so retry only sends the remaining changes.
          commit(current => ({ ...current, reviewers: candidate.selected
            ? current.reviewers.some(reviewer => reviewer.login.toLowerCase() === candidate.login.toLowerCase()) ? current.reviewers
              : [...current.reviewers, { login: candidate.login, avatarUrl: candidate.avatarUrl, state: 'PENDING' }]
            : current.reviewers.filter(reviewer => reviewer.login.toLowerCase() !== candidate.login.toLowerCase() || reviewer.state.toUpperCase() !== 'PENDING'),
          }));
        }
      }
      if (optionsKey.current === key) setOpen(null);
    }
    catch (failure) {
      if (optionsKey.current === key) setError(errorMessage(failure));
    }
    finally {
      if (optionsKey.current === key) setOptimisticDetail(null);
      updatingRef.current = false;
      setUpdating(false);
    }
  };

  return (
    <div className="pr-meta" ref={root}>
      <div className="pr-meta__picker">
        <button type="button" className={`pr-meta__chip${open === 'reviewers' ? ' is-open' : ''}`}
          disabled={updating} aria-expanded={open === 'reviewers'} aria-haspopup="listbox" onClick={() => toggle('reviewers')}>
          <small>Reviewers</small>
          {visibleDetail.reviewers.length === 0 && 'None'}
          {visibleDetail.reviewers.slice(0, visibleDetail.reviewers.length > 3 ? 2 : 3).map(reviewer => (
            <span className="pr-meta__item" key={reviewer.login}>
              <Avatar login={reviewer.login} avatarUrl={reviewer.avatarUrl} size={18} allowGitHubFallback={!reviewer.login.includes('/')} />
              {reviewer.login}
            </span>
          ))}
          {visibleDetail.reviewers.length > 3 && `${visibleDetail.reviewers.length - 2} other reviewers`}
        </button>
        {open === 'reviewers' && <PeoplePicker key={`${key}:reviewers`} detail={visibleDetail} kind="reviewers" loading={loading} query={query}
          repoUsers={[...(repoTeams ?? []).map((login): RepoUserDto => ({ login, avatarUrl: null, bot: false })), ...repoUsers]}
          setQuery={setQuery} updating={updating} error={error} onCancel={() => setOpen(null)}
          onSave={changes => { void savePeople('reviewers', changes); }} />}
      </div>
      <div className="pr-meta__picker">
        <button type="button" className={`pr-meta__chip${open === 'assignees' ? ' is-open' : ''}`}
          disabled={updating} aria-expanded={open === 'assignees'} aria-haspopup="listbox" onClick={() => toggle('assignees')}>
          <User size={15} strokeWidth={1.4} aria-hidden="true" /> <small>Assignees</small>
          {visibleDetail.assignees.slice(0, visibleDetail.assignees.length > 3 ? 2 : 3).map(login => (
            <span className="pr-meta__item" key={login}>
              <Avatar login={login} size={18} />
              {login}
            </span>
          ))}
          {visibleDetail.assignees.length > 3 && `${visibleDetail.assignees.length - 2} other assignees`}
        </button>
        {open === 'assignees' && <PeoplePicker key={`${key}:assignees`} detail={visibleDetail} kind="assignees" loading={loading}
          query={query} repoUsers={repoUsers} setQuery={setQuery} updating={updating} error={error} onCancel={() => setOpen(null)}
          onSave={changes => { void savePeople('assignees', changes); }} />}
      </div>
      <div className="pr-meta__picker">
        <button type="button" className={`pr-meta__chip${open === 'labels' ? ' is-open' : ''}`}
          disabled={updating} aria-expanded={open === 'labels'} aria-haspopup="listbox" onClick={() => toggle('labels')}>
          {visibleDetail.labels.length === 0 && 'No labels'}
          {visibleDetail.labels.slice(0, visibleDetail.labels.length > 3 ? 2 : 3).map(label => (
            <span className="pr-meta__item" key={label.name}>
              <i className="pr-meta__label-dot" aria-hidden="true" style={{ background: labelPalette(label.name, label.color).color }} />
              {label.name}
            </span>
          ))}
          {visibleDetail.labels.length > 3 && `${visibleDetail.labels.length - 2} other labels`}
        </button>
        {open === 'labels' && <LabelPicker labels={repoLabels} selected={visibleDetail.labels.map(label => label.name)}
          loading={loading} query={query} setQuery={setQuery} updating={updating}
          onToggle={label => {
            const previous = visibleDetail.labels;
            const selected = previous.some(value => value.name.toLowerCase() === label.name.toLowerCase());
            const next = selected
              ? previous.filter(value => value.name.toLowerCase() !== label.name.toLowerCase())
              : [...previous, { name: label.name, color: label.color }];
            void update(
              current => ({ ...current, labels: next }),
              current => current.labels === next ? { ...current, labels: previous } : current,
              () => bridge.updatePrMetadata(
                visibleDetail.repo, visibleDetail.number, visibleDetail.assignees, next.map(value => value.name)),
            );
          }} />}
      </div>
      {visibleDetail.milestone && <span><small>Milestone</small> {visibleDetail.milestone}</span>}
      {error && open !== 'reviewers' && open !== 'assignees' && <div className="pr-meta__error" role="alert">Could not update pull request. {error}</div>}
    </div>
  );
}

function PeoplePicker({ detail, kind, loading, query, repoUsers, setQuery, updating, error, onCancel, onSave }: {
  detail: PullRequestDetailDto;
  kind: PeoplePickerKind;
  loading: boolean;
  query: string;
  repoUsers: RepoUserDto[];
  setQuery: (value: string) => void;
  updating: boolean;
  error: string | null;
  onCancel: () => void;
  onSave: (changes: PeoplePickerCandidate[]) => void;
}) {
  const [draft, setDraft] = useState<Map<string, PeoplePickerCandidate>>(new Map());
  const normalized = query.trim().toLowerCase();
  const current = peoplePickerCandidates(detail, repoUsers, kind);
  const currentLogins = new Set(current.map(candidate => candidate.login.toLowerCase()));
  for (const [login, candidate] of draft) {
    if (!currentLogins.has(login)) current.push({ ...candidate, selected: false, locked: false });
  }
  const selection = current.map(candidate => ({ ...candidate,
    selected: candidate.locked ? candidate.selected : draft.get(candidate.login.toLowerCase())?.selected ?? candidate.selected,
  }));
  const changes = selection.filter((candidate, index) => candidate.selected !== current[index].selected);
  const candidates = selection.filter(candidate => candidate.login.toLowerCase().includes(normalized))
    .sort((left, right) => Number(right.selected) - Number(left.selected));
  return <MetadataPicker ariaLabel={kind} placeholder={kind === 'reviewers' ? 'Search users and teams...' : 'Search users...'} query={query} setQuery={setQuery}
    loading={loading} updating={updating} emptyText="No users found" loadingText="Loading repository users…"
    footer={<>
      {error && <div className="pr-people-picker__error" role="alert">{error}</div>}
      <div className="pr-people-picker__actions">
        <span>{selection.filter(candidate => candidate.selected).length} selected</span>
        <button type="button" disabled={updating} onClick={onCancel}>Cancel</button>
        <button type="button" className="is-primary" disabled={updating || changes.length === 0} onClick={() => onSave(changes)}>
          {updating ? 'Saving…' : 'Save changes'}
        </button>
      </div>
    </>}
    options={candidates.map(candidate => ({
      key: candidate.login,
      label: displayActor(candidate.login),
      login: candidate.login,
      avatarUrl: candidate.avatarUrl,
      bot: candidate.bot,
      selected: candidate.selected,
      disabled: candidate.locked,
      disabledReason: candidate.locked ? 'Completed reviews cannot be removed' : undefined,
    }))}
    onToggle={option => {
      const candidate = candidates.find(value => value.login === option.key);
      if (!candidate || candidate.locked) return;
      setDraft(previous => new Map(previous).set(candidate.login.toLowerCase(), { ...candidate, selected: !candidate.selected }));
      setQuery('');
    }} />;
}

function LabelPicker({ labels, selected, loading, query, setQuery, updating, onToggle }: {
  labels: RepoLabelDto[];
  selected: string[];
  loading: boolean;
  query: string;
  setQuery: (value: string) => void;
  updating: boolean;
  onToggle: (label: RepoLabelDto) => void;
}) {
  const selectedNames = new Set(selected.map(label => label.toLowerCase()));
  const candidates = labels.filter(label => label.name.toLowerCase().includes(query.trim().toLowerCase()));
  return <MetadataPicker ariaLabel="labels" placeholder="Search labels..." query={query} setQuery={setQuery}
    loading={loading} updating={updating} emptyText="No labels found" loadingText="Loading repository labels…"
    options={candidates.map(label => ({
      key: label.name,
      label: label.name,
      color: labelPalette(label.name, label.color).color,
      selected: selectedNames.has(label.name.toLowerCase()),
    }))}
    onToggle={option => {
      const label = candidates.find(value => value.name === option.key);
      if (label) onToggle(label);
    }} />;
}

type MetadataPickerOption = {
  key: string;
  label: string;
  login?: string;
  avatarUrl?: string | null;
  color?: string;
  bot?: boolean;
  selected: boolean;
  disabled?: boolean;
  disabledReason?: string;
  /** A second line under the name, such as the circles a person is already in. */
  hint?: string;
};

export const MAX_UNSELECTED_PEOPLE_PICKER_OPTIONS = 100;

export function displayedMetadataPickerOptions(options: MetadataPickerOption[]) {
  let unselectedPeople = 0;
  return options.filter(option => {
    if (!option.login || option.selected) return true;
    unselectedPeople++;
    return unselectedPeople <= MAX_UNSELECTED_PEOPLE_PICKER_OPTIONS;
  });
}

export function MetadataPicker({
  ariaLabel,
  placeholder,
  query,
  setQuery,
  options,
  loading,
  updating,
  loadingText,
  emptyText,
  onToggle,
  footer,
}: {
  ariaLabel: string;
  placeholder: string;
  query: string;
  setQuery: (value: string) => void;
  options: MetadataPickerOption[];
  loading: boolean;
  updating: boolean;
  loadingText: string;
  emptyText: string;
  onToggle: (option: MetadataPickerOption) => void;
  footer?: ReactNode;
}) {
  const search = useRef<HTMLInputElement>(null);
  const displayedOptions = displayedMetadataPickerOptions(options);
  return (
    <div className="pr-people-picker">
      <input ref={search} autoFocus aria-label={`Search ${ariaLabel}`} placeholder={placeholder} value={query}
        onChange={event => setQuery(event.target.value)} />
      <div className="pr-people-picker__list" role="listbox" aria-label={ariaLabel} aria-multiselectable="true">
        {displayedOptions.map(option => (
          <button className={`pr-people-picker__row${option.color ? ' is-label' : ''}`} type="button"
            disabled={updating || option.disabled} aria-label={option.label} aria-selected={option.selected} role="option" key={option.key}
            title={option.disabledReason} onClick={() => { onToggle(option); if (footer) search.current?.focus(); }}>
            <span className="pr-people-picker__check">{option.selected && <Check size={16} strokeWidth={2} aria-hidden="true" />}</span>
            {option.login
              ? <Avatar login={option.login} avatarUrl={option.avatarUrl} size={20} allowGitHubFallback={false} />
              : <i className="pr-meta__label-dot" style={{ background: option.color }} />}
            <span>{option.label}{option.hint && <span className="pr-people-picker__hint">{option.hint}</span>}</span>
            {option.bot && <span className="pr-bot">Bot</span>}
          </button>
        ))}
        {loading && options.length === 0 && <div className="pr-people-picker__empty">{loadingText}</div>}
        {!loading && options.length === 0 && <div className="pr-people-picker__empty">{emptyText}</div>}
      </div>
      {footer}
    </div>
  );
}

export function toggleSelection(values: string[], value: string) {
  const selected = values.some(candidate => candidate.toLowerCase() === value.toLowerCase());
  return selected
    ? values.filter(candidate => candidate.toLowerCase() !== value.toLowerCase())
    : [...values, value];
}

export function peoplePickerCandidates(
  detail: PullRequestDetailDto,
  repoUsers: RepoUserDto[],
  kind: PeoplePickerKind,
): PeoplePickerCandidate[] {
  const participantAvatars = new Map<string, string | null>(mentionCandidates(detail)
    .map(candidate => [candidate.login.toLowerCase(), candidate.avatarUrl] as const));
  const selected = kind === 'reviewers'
    ? detail.reviewers.map(reviewer => ({
      login: reviewer.login,
      avatarUrl: reviewer.avatarUrl,
      locked: reviewer.state.toUpperCase() !== 'PENDING',
    }))
    : detail.assignees.map(login => ({
      login,
      avatarUrl: participantAvatars.get(login.toLowerCase()) ?? null,
      locked: false,
    }));
  const selectedLogins = new Set(selected.map(user => user.login.toLowerCase()));
  const result: PeoplePickerCandidate[] = selected.map(user => {
    const repositoryUser = repoUsers.find(candidate => candidate.login.toLowerCase() === user.login.toLowerCase());
    return {
      ...user,
      avatarUrl: repositoryUser?.avatarUrl ?? null,
      bot: repositoryUser?.bot ?? user.login.endsWith('[bot]'),
      selected: true,
      locked: user.locked,
    };
  });
  for (const user of repoUsers) {
    if (selectedLogins.has(user.login.toLowerCase())) continue;
    result.push({ ...user, selected: false, locked: false });
  }
  return result;
}

export function LabelBadge({ name, color }: { name: string; color: string | null }) {
  const palette = labelPalette(name, color);
  return <span className="pr-label" style={{ background: palette.background, color: palette.color }}>{name}</span>;
}

function labelPalette(name: string, color: string | null) {
  const normalized = name.toLowerCase();
  if (normalized === 'no-release-notes') return { background: 'light-dark(#e3f2b5, var(--dark-green-soft))', color: 'var(--fg-success)' };
  if (['cla-signed', 'docs', 'iceberg'].includes(normalized)) return { background: 'light-dark(#f2e7e7, var(--dark-red-soft))', color: 'light-dark(#8a5551, var(--dark-red))' };
  return { background: `#${color ?? 'eaeef2'}24`, color: 'var(--text-2)' };
}
