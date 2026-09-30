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
import { Icon } from './icons';
import { entryTimeKey, filterNotes, gradeLine, kindLabel, noteArea, noteGroups, pageChangedAt, plural, shortDate, type NoteFilter } from './model';
import type { ContextData } from './RepositoryContextPage';

export default function ContextNotes({ data, area, onArea, onOpen }: { data: ContextData; area: string; onArea: (area: string) => void; onOpen: (entryId: string) => void }) {
  const { notebook, times } = data;
  const [kind, setKind] = useState<NoteFilter['kind']>('all');
  const [query, setQuery] = useState('');
  const entries = notebook.entries;
  const filtered = filterNotes(notebook, { kind, area, query });
  const groups = noteGroups(notebook, filtered);
  const available = entries.filter(entry => entry.enabled).length;
  const disabled = entries.length - available;
  const knowledge = entries.filter(entry => entry.kind === 'KNOWLEDGE').length;
  const areas = noteGroups(notebook, entries);
  const selected = areas.find(group => group.area.id === area)?.area ?? null;
  return <div className="rcp-notes" data-screen-label="Notes">
    <nav className="rcp-rail" aria-label="Note areas">
      <div className="rcp-rail__label">AREAS</div>
      <button type="button" className="rcp-rail__item" aria-current={area === 'all'} onClick={() => onArea('all')}>
        <span className="rcp-rail__name">All notes</span><span className="rcp-rail__n">{entries.length}</span></button>
      {areas.map(group => <button type="button" key={group.area.id} className="rcp-rail__item" aria-current={area === group.area.id} onClick={() => onArea(group.area.id)}>
        <span className="rcp-rail__name">{group.area.label}</span><span className="rcp-rail__n">{group.entries.length}</span></button>)}
      <div className="rcp-rail__hint">Areas come from each note’s paths. Several notes can apply to one area; the agent decides which ones fit a task.</div>
    </nav>
    <div className="rcp-notes__main">
      <div className="rcp-notes__head">
        <div><div className="rcp-notes__title">{selected === null ? 'All notes' : selected.label}</div>
          <div className="rcp-notes__sub">{selected !== null && `${filtered.length} shown · `}{available} available to development and review agents
            {disabled > 0 && ` · ${disabled} disabled`}</div></div>
        <span className="rcp-spacer" />
        <div className="rcp-seg" role="tablist" aria-label="Note type">
          {([['all', `All ${entries.length}`], ['KNOWLEDGE', `Knowledge ${knowledge}`], ['MEMORY', `Lessons ${entries.length - knowledge}`]] as const).map(([id, label]) =>
            <button type="button" role="tab" key={id} aria-selected={kind === id} onClick={() => setKind(id)}>{label}</button>)}
        </div>
        <label className="rcp-filter"><Icon name="search" size={13} />
          <input value={query} onChange={event => setQuery(event.target.value)} placeholder="Filter by title, path, text" aria-label="Filter notes" /></label>
      </div>
      {entries.length === 0 && <div className="rcp-empty">No notes yet. Notes appear here as learning runs and development tasks save them.</div>}
      {entries.length > 0 && filtered.length === 0 && <div className="rcp-empty">{query.trim() ? `No notes match “${query.trim()}”.` : 'No notes of this type in this area.'}</div>}
      {groups.map(group => <div className="rcp-group" key={group.area.id}>
        {area === 'all' && <div className="rcp-group__head"><span className="rcp-group__label">{group.area.label}</span>
          {group.area.hint && <span className="rcp-group__hint">{group.area.hint}</span>}<span className="rcp-group__count">· {plural(group.entries.length, 'note')}</span></div>}
        <div className="rcp-list">
          {group.entries.map(entry => {
            const stamp = times.entries[entryTimeKey(entry)];
            const lesson = entry.kind === 'MEMORY';
            return <button type="button" key={entry.id} className={`rcp-note${entry.enabled ? '' : ' is-disabled'}`} onClick={() => onOpen(entry.id)}>
              <span className={`rcp-note__icon${lesson ? ' is-lesson' : ''}`}><Icon name={lesson ? 'lesson' : 'doc'} size={15} /></span>
              <span className="rcp-note__main">
                <span className="rcp-note__line"><span className="rcp-note__title">{entry.title}</span>
                  {!entry.enabled && <span className="rcp-chip rcp-tone--gray">Disabled</span>}
                  {data.disputes?.contested[entry.id] !== undefined && <span className="rcp-chip rcp-tone--amber">Agents disagree</span>}
                  {pageChangedAt(entry, data.changedPages) !== null && <span className="rcp-chip rcp-tone--amber">Page changed</span>}</span>
                <span className="rcp-note__summary">{entry.summary.replaceAll('`', '')}</span>
                <span className="rcp-note__meta"><span className="rcp-note__path">{noteArea(notebook, entry.scopeId).paths.join(' · ')}</span>
                  <span>v{entry.version}{stamp ? ` · ${shortDate(stamp.at, data.now)}` : ''}</span><span>{gradeLine(data.usage, entry, data.now)}</span></span>
              </span>
              <span className={`rcp-kind${lesson ? ' is-lesson' : ''}`}>{kindLabel(entry.kind)}</span>
            </button>;
          })}
        </div>
      </div>)}
    </div>
  </div>;
}
