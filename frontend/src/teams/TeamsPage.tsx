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
import { useEffect, useRef, useState } from 'react';
import { LockKeyhole, Pencil, Plus, RefreshCw, Trash2, Users } from 'lucide-react';
import Avatar from '../Avatar';
import { ConfirmDialog } from '../ConfirmDialog';
import { relativeTime } from '../relativeTime';
import { membersOf } from './activity';
import { parseTeamCreateInput } from './contracts';
import TeamActivityList from './TeamActivityList';
import TeamsAttention from './TeamsAttention';
import TeamPeoplePicker from './TeamPeoplePicker';
import type { TeamActivityFilter, TeamCircle, TeamCreateInput, TeamDirectory, TeamMember, TeamMemberInput, TeamPersonStatistics } from './types';
import { GITHUB_ACCOUNT_CHANGED, updateTeams, useTeams } from './useTeams';
import { useTeamActivity } from './useTeamActivity';
import './teams.css';

type Tab = 'attention' | 'activity' | 'people' | 'insights';
const TABS: { id: Tab; label: string }[] = [
  { id: 'attention', label: 'Needs you' }, { id: 'activity', label: 'Activity' },
  { id: 'people', label: 'People' }, { id: 'insights', label: 'Insights' },
];

export default function TeamsPage() {
  const { directory, loading, error: directoryError } = useTeams();
  const [circleId, setCircleId] = useState('');
  const [memberId, setMemberId] = useState<number | undefined>();
  const [tab, setTab] = useState<Tab>('activity');
  const [days, setDays] = useState(7);
  const [period, setPeriod] = useState('7');
  const [limit, setLimit] = useState(100);
  const [filter, setFilter] = useState<TeamActivityFilter>('all');
  const [addingPeople, setAddingPeople] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<TeamCircle | 'new' | null>(null);
  const [removing, setRemoving] = useState<TeamCircle | null>(null);
  const currentAccount = useRef(directory?.accountLogin);
  currentAccount.current = directory?.accountLogin;
  const circle = directory?.circles.find(item => item.id === circleId);
  const circles = circle ? [circle] : directory?.circles ?? [];
  const members = membersOf(circles);
  const member = members.find(item => item.githubId === memberId);
  const selectedMemberId = member?.githubId;
  const activity = useTeamActivity(directory?.accountLogin, { circleId: circle?.id, memberId: selectedMemberId, days, limit, filter });
  const events = activity.snapshot?.events ?? [];

  useEffect(() => {
    const reset = () => { currentAccount.current = undefined; setCircleId(''); setMemberId(undefined); setEditing(null); setRemoving(null); setAddingPeople(false); setError(null); };
    window.addEventListener(GITHUB_ACCOUNT_CHANGED, reset);
    return () => window.removeEventListener(GITHUB_ACCOUNT_CHANGED, reset);
  }, []);

  async function mutate(action: () => Promise<TeamDirectory>, done?: (result: TeamDirectory) => void) {
    if (busy) return;
    const account = currentAccount.current;
    setBusy(true); setError(null);
    try {
      const result = await action();
      if (account !== currentAccount.current || result.accountLogin.toLowerCase() !== account?.toLowerCase()) return;
      updateTeams(result); done?.(result);
    }
    catch (failure) { if (account === currentAccount.current) setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { setBusy(false); }
  }

  function openPerson(id: number) { setMemberId(id); setTab('insights'); setFilter('all'); }
  function inspect(next: TeamActivityFilter) { setFilter(next); setLimit(100); setTab('activity'); }
  const people = activity.snapshot?.people ?? [];

  return <div className="teams-page">
    <header className="teams-heading">
      <div><div className="teams-eyebrow"><LockKeyhole size={12} /> Only you</div><h1>Teams</h1>
        <p>Keep the people you trust close to your work.</p></div>
      <button type="button" className="bq-button bq-button--primary" disabled={directory === null || busy} onClick={() => setEditing('new')}>
        <Plus size={15} /> New circle
      </button>
    </header>
    {(error || directoryError) && <div className="teams-error" role="alert">{error || directoryError}</div>}
    {loading && directory === null && <div className="teams-empty" role="status">Loading your circles…</div>}
    {directory !== null && directory.circles.length === 0 && <div className="teams-welcome">
      <Users size={32} strokeWidth={1.4} /><h2>Your people, in one place</h2>
      <p>Create a private circle for your colleagues or trusted open-source collaborators. Choose people from your saved repository contributors.</p>
      <p>No invitations. No member limit. Your repository access stays the same.</p>
      <button type="button" className="bq-button bq-button--primary" onClick={() => setEditing('new')}>Create your first circle</button>
    </div>}
    {directory !== null && directory.circles.length > 0 && <>
      <div className="teams-controls">
        <label>Circle<select aria-label="Circle" value={circle?.id ?? ''} onChange={event => { setCircleId(event.target.value); setMemberId(undefined); setLimit(100); setAddingPeople(false); }}>
          <option value="">All circles</option>
          {directory.circles.map(item => <option key={item.id} value={item.id}>{item.name} · {item.members.length}</option>)}
        </select></label>
        <label>Person<select aria-label="Person" value={selectedMemberId ?? ''} onChange={event => { setMemberId(event.target.value ? Number(event.target.value) : undefined); setLimit(100); }}>
          <option value="">Everyone</option>
          {members.map(item => <option key={item.githubId} value={item.githubId}>{item.login}</option>)}
        </select></label>
        {tab !== 'attention' && <label>Period<select aria-label="Period" value={period} onChange={event => {
          setPeriod(event.target.value); if (event.target.value !== 'custom') setDays(Number(event.target.value));
        }}><option value="7">Past 7 days</option><option value="30">Past 30 days</option><option value="90">Past 90 days</option><option value="custom">Custom period</option></select></label>}
        {period === 'custom' && tab !== 'attention' && <label>Past days<input aria-label="Past days" type="number" min={1} max={90} value={days}
          onChange={event => { const value = Number(event.target.value); if (Number.isInteger(value) && value >= 1 && value <= 90) setDays(value); }} /></label>}
        <div className="teams-controls-end">
          {circle && <button type="button" className="bq-button" aria-label={`Edit ${circle.name}`} onClick={() => setEditing(circle)}><Pencil size={13} /> Edit circle</button>}
          <button type="button" className="bq-button" disabled={activity.refreshing || members.length === 0} onClick={() => { void activity.refresh(); }}>
            <RefreshCw size={14} className={activity.refreshing ? 'teams-spinning' : undefined} />{activity.refreshing ? 'Refreshing…' : 'Refresh'}
          </button>
        </div>
      </div>
      <div className="teams-scope">{circle?.repositories.length ? circle.repositories.join(' · ') : 'Available activity across repositories'}
        <span>{members.length} {members.length === 1 ? 'person' : 'people'}</span></div>
      {circle?.description && <p className="teams-description">{circle.description}</p>}
      {member && <div className="teams-person-heading"><Avatar login={member.login} avatarUrl={member.avatarUrl} size={42} />
        <div><h2>{member.name || member.login}</h2><span>@{member.login}</span></div>
        <button type="button" className="bq-button" onClick={() => setMemberId(undefined)}>Everyone</button></div>}
      <nav className="teams-tabs" aria-label="Team views">{TABS.map(item => <button key={item.id} type="button" aria-current={tab === item.id ? 'page' : undefined}
        onClick={() => setTab(item.id)}>{item.label}</button>)}</nav>

      {tab === 'attention' && <TeamsAttention accountLogin={directory.accountLogin} circleId={circle?.id} memberId={selectedMemberId} />}
      {tab === 'people' && <>
        {circle ? <div className="teams-add-person">
          <button type="button" className="bq-button" aria-expanded={addingPeople} aria-haspopup="listbox" disabled={busy}
            onClick={() => setAddingPeople(value => !value)}><Plus size={14} /> Add people</button>
          {addingPeople && <TeamPeoplePicker key={`${directory.accountLogin}:${circle.id}`} selected={circle.members}
            knownPeople={membersOf(directory.circles)} busy={busy} lockSelected
            onToggle={person => { void mutate(() => window.bridge.addTeamMember(circle.id, person)); }} />}
          <p>Search contributors from all your saved repositories. They won’t be notified.</p>
        </div> : <p className="teams-muted">Choose a circle to add or remove people.</p>}
        <div className="teams-people">{(member ? [member] : members).map(person => {
          const stats = people.find(item => item.memberId === person.githubId);
          return <div className="teams-person" key={person.githubId}>
            <button type="button" className="teams-person-link" onClick={() => openPerson(person.githubId)}>
              <Avatar login={person.login} avatarUrl={person.avatarUrl} size={34} />
              <span><strong>{person.name || person.login}</strong><small>@{person.login}</small></span>
            </button>
            <span className="teams-person-count">{stats ? `${stats.authoredPrsMerged} PRs merged · ${stats.prsReviewed} reviewed` : 'No recorded activity'}</span>
            {circle && <button type="button" className="bq-button" disabled={busy} aria-label={`Remove ${person.login} from ${circle.name}`}
              onClick={() => { void mutate(() => window.bridge.removeTeamMember(circle.id, person.githubId)); }}><Trash2 size={14} /></button>}
          </div>;
        })}</div>
        {members.length === 0 && <div className="teams-empty">Add your first person to start following their work.</div>}
      </>}
      {tab === 'insights' && <>
        <div className="teams-section-heading"><h2>{member ? 'Contributions' : 'Recorded contributions'}</h2><span>Past {days} days</span></div>
        <div className="teams-stats">
          <Stat label="Authored PRs merged" value={total(people, 'authoredPrsMerged')} onClick={() => inspect('authored-merges')} />
          <Stat label="PRs reviewed by person" value={total(people, 'prsReviewed')} onClick={() => inspect('reviews-performed')} />
          <Stat label="Issues opened" value={total(people, 'issuesOpened')} onClick={() => inspect('issues-opened')} />
          <Stat label="Direct interactions with you" value={total(people, 'directInteractions')} onClick={() => inspect('interactions')} />
        </div>
        <p className="teams-muted">Counts cover recorded activity. Reviews count once per person and pull request; direct interactions count explicit mentions, requests to review, and reviews of each other’s PRs.</p>
        <div className="teams-table-scroll"><table className="teams-table"><thead><tr><th>Person</th><th>PRs opened</th><th>Merged as author</th><th>Merges performed</th><th>Reviewed</th><th>Comments</th><th>With you</th></tr></thead>
          <tbody>{people.map(person => <tr key={person.memberId}><td><button type="button" onClick={() => openPerson(person.memberId)}>{person.login}</button></td>
            <td>{person.prsOpened}</td><td>{person.authoredPrsMerged}</td><td>{person.mergesPerformed}</td><td>{person.prsReviewed}</td><td>{person.comments}</td><td>{person.directInteractions}</td></tr>)}</tbody></table></div>
        {member && people[0] && <div className="teams-shared"><h3>Work you both participated in</h3>
          <p>{people[0].sharedPullRequests} pull requests · {people[0].sharedIssues} issues · {people[0].issuesClosed} issues closed by {member.login}</p>
          <p className="teams-muted">Shared work does not necessarily mean a direct conversation.</p>
          <button type="button" className="bq-button" onClick={() => inspect('all')}>View recorded activity</button></div>}
      </>}
      {tab === 'activity' && <>
        <div className="teams-section-heading"><h2>{member ? `${member.login}’s activity` : 'Team updates'}</h2>
          <select aria-label="Activity type" value={filter} onChange={event => { setFilter(event.target.value as TeamActivityFilter); setLimit(100); }}>
            <option value="all">All updates</option><option value="merged">Merged PRs</option><option value="reviews">Reviews</option><option value="issues">Issues</option><option value="interactions">With you</option>
            <option value="authored-merges">Authored PRs merged</option><option value="reviews-performed">Reviews performed</option><option value="issues-opened">Issues opened</option>
          </select></div>
        {activity.loading ? <div className="teams-empty" role="status">Loading recorded activity…</div>
          : <TeamActivityList events={events} members={members} onPerson={openPerson} />}
        {activity.snapshot?.coverage.hasMoreEvents && <div className="teams-more"><span>Showing the latest {limit} source updates. Statistics include all retained observations in this period.</span>
          {limit < 500 && <button type="button" className="bq-button" onClick={() => setLimit(value => Math.min(500, value + 100))}>Show more</button>}
          {limit >= 500 && <span>Narrow the period or select a person to see more detail.</span>}</div>}
      </>}
      {activity.error && <div className="teams-error" role="alert">{activity.error}</div>}
      {activity.snapshot && <details className="teams-coverage"><summary>
        Observed activity · {activity.snapshot.coverage.lastRefreshedAt ? `Refreshed ${relativeTime(activity.snapshot.coverage.lastRefreshedAt)}` : 'Not refreshed yet'}
      </summary><p>{activity.snapshot.coverage.description}</p>
        <p>{activity.snapshot.coverage.membersScanned} of {activity.snapshot.coverage.memberCount} people and {activity.snapshot.coverage.repositoriesScanned} of {activity.snapshot.coverage.repositoryCount} repositories scanned in the latest pass.</p>
        {activity.snapshot.coverage.observedSince && <p>Observations retained since {new Date(activity.snapshot.coverage.observedSince).toLocaleDateString()}.</p>}
        {activity.snapshot.coverage.errors.map((message, index) => <p key={index} className="teams-error">{message}</p>)}
      </details>}
    </>}
    {editing !== null && <CircleDialog circle={editing === 'new' ? null : editing} knownPeople={membersOf(directory?.circles ?? [])} busy={busy} error={error} onCancel={() => { setEditing(null); setError(null); }}
      onSave={value => {
        try {
          const input = parseTeamCreateInput(value);
          const existing = editing === 'new' ? null : editing;
          void mutate(() => existing ? window.bridge.updateTeam(existing.id, input) : window.bridge.createTeam(input), result => {
            const added = existing ?? result.circles.find(item => !directory?.circles.some(previous => previous.id === item.id));
            if (added) setCircleId(added.id);
            setEditing(null); setTab('people');
          });
        }
        catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)); }
      }} onRemove={editing === 'new' ? undefined : () => { setRemoving(editing); setEditing(null); }} />}
    {removing && <ConfirmDialog title={`Delete ${removing.name}?`} confirmLabel="Delete circle" destructive busy={busy}
      onCancel={() => setRemoving(null)} onConfirm={() => { void mutate(() => window.bridge.removeTeam(removing.id), () => { setRemoving(null); setCircleId(''); }); }}>
      <p>This removes the private circle and its priority preference. People in other circles stay there.</p>
    </ConfirmDialog>}
  </div>;
}

function total(people: TeamPersonStatistics[], field: keyof Omit<TeamPersonStatistics, 'memberId' | 'login'>): number {
  return people.reduce((sum, person) => sum + person[field], 0);
}

function Stat({ label, value, onClick }: { label: string; value: number; onClick: () => void }) {
  return <button type="button" className="teams-stat" onClick={onClick}><strong>{value}</strong><span>{label}</span></button>;
}

function CircleDialog({ circle, knownPeople, busy, error, onSave, onCancel, onRemove }: {
  circle: TeamCircle | null; knownPeople: TeamMember[]; busy: boolean; error: string | null;
  onSave: (input: TeamCreateInput) => void; onCancel: () => void; onRemove?: () => void;
}) {
  const [name, setName] = useState(circle?.name ?? '');
  const [description, setDescription] = useState(circle?.description ?? '');
  const [selectedPeople, setSelectedPeople] = useState<TeamMemberInput[]>([]);
  const [repositories, setRepositories] = useState(circle?.repositories.join('\n') ?? '');
  return <ConfirmDialog title={circle ? 'Edit circle' : 'New private circle'} confirmLabel={circle ? 'Save circle' : 'Create circle'} busy={busy}
    confirmDisabled={!name.trim()} onCancel={onCancel} onConfirm={() => onSave({ name, description, repositories: repositories.split(/[\s,]+/).filter(Boolean), members: selectedPeople })}>
    <div className="teams-circle-form">
      <label>Name<input aria-label="Circle name" maxLength={100} value={name} placeholder="Trusted OSS" onChange={event => setName(event.target.value)} /></label>
      <label>Description <span>(optional)</span><textarea aria-label="Circle description" rows={2} maxLength={2000} value={description}
        placeholder="People I review and collaborate with" onChange={event => setDescription(event.target.value)} /></label>
      {!circle && <div className="teams-circle-people"><div className="teams-circle-people-heading">People <span>{selectedPeople.length} selected</span></div>
        <TeamPeoplePicker selected={selectedPeople} knownPeople={knownPeople} busy={busy}
          onToggle={candidate => setSelectedPeople(people => people.some(person => person.githubId === candidate.githubId)
            ? people.filter(person => person.githubId !== candidate.githubId) : [...people, candidate])} />
        <p>Choose from all your saved repository contributors. You can add more people later.</p></div>}
      <label>Repositories <span>(optional)</span><textarea aria-label="Circle repositories" rows={3} value={repositories} placeholder="trinodb/trino"
        onChange={event => setRepositories(event.target.value)} /></label>
      <p>Leave blank for available activity across repositories, or enter one owner/repository per line. Watch a repository in ByteQuay to collect its internal activity.</p>
      {error && <div className="teams-error" role="alert">{error}</div>}
      {onRemove && <button type="button" className="bq-button teams-danger" disabled={busy} onClick={onRemove}><Trash2 size={13} /> Delete circle</button>}
    </div>
  </ConfirmDialog>;
}
