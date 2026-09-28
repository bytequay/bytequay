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
import { useEffect, useState } from 'react';
import { MetadataPicker } from '../pulls/PullOverview';
import type { RepositoryMetadataDto } from '../types';
import type { TeamMember, TeamMemberInput } from './types';

export default function TeamPeoplePicker({ selected, knownPeople, busy, onToggle, lockSelected = false }: {
  selected: TeamMemberInput[];
  knownPeople: TeamMember[];
  busy: boolean;
  onToggle: (member: TeamMemberInput) => void;
  lockSelected?: boolean;
}) {
  const [contributors, setContributors] = useState<RepositoryMetadataDto['contributors']>([]);
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    let current = true;
    setLoading(true); setError(null);
    window.bridge.fetchTeamContributors().then(value => { if (current) setContributors(value); })
      .catch(failure => { if (current) setError(failure instanceof Error ? failure.message : String(failure)); })
      .finally(() => { if (current) setLoading(false); });
    return () => { current = false; };
  }, [retry]);
  const normalized = query.trim().replace(/^@/, '').toLowerCase();
  const selectedIds = new Set(selected.map(person => person.githubId));
  const names = new Map(knownPeople.map(person => [person.githubId, person.name]));
  const options = contributors.map(person => {
    const name = names.get(person.id);
    const selected = selectedIds.has(person.id);
    return { key: String(person.id), login: person.login, avatarUrl: person.avatarUrl, bot: person.bot,
      label: name ? `${name} · @${person.login}` : `@${person.login}`, selected,
      disabled: lockSelected && selected, disabledReason: lockSelected && selected ? 'Already in this circle' : undefined };
  }).filter(person => person.label.toLowerCase().includes(normalized));
  return <div className="teams-people-picker">
    <MetadataPicker ariaLabel="contributors" placeholder="Search repository contributors…" query={query} setQuery={setQuery}
      options={options} loading={loading} updating={busy} loadingText="Loading saved contributors…"
      emptyText={query.trim() ? 'No matching contributors' : 'No saved contributors yet. Sync a watched repository to load its people.'}
      onToggle={option => {
        const person = contributors.find(person => person.id === Number(option.key));
        if (person) onToggle({ githubId: person.id, login: person.login });
      }} />
    {error && <div className="teams-error" role="alert">{error} <button type="button" className="bq-button" onClick={() => setRetry(value => value + 1)}>Retry</button></div>}
  </div>;
}
