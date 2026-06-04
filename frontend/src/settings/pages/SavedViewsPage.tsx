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
import { useCallback, useEffect, useState } from 'react';
import type { SavedViewDto } from '../../types';

/** User-authored concept catalog. v1 is a small CRUD page that
 *  lets the user name + define a filter (kind=FILTER by default);
 *  the entries land in the concept registry as USER-scoped specs
 *  and the agent's list_terms / lookup_term tools surface them
 *  alongside the workspace and APP-scoped seeds. */
function SavedViewsPage() {
  const [views, setViews] = useState<SavedViewDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [name, setName] = useState('');
  const [definition, setDefinition] = useState('');
  const [aka, setAka] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const load = useCallback(async () => {
    try {
      const rows = await window.bridge.listSavedViews();
      setViews(rows);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const trimmedName = name.trim();
  const trimmedDef = definition.trim();
  const canSubmit = trimmedName.length > 0 && trimmedDef.length > 0 && !submitting;

  const handleCreate = async () => {
    if (!canSubmit) return;
    setSubmitting(true);
    setError(null);
    try {
      const akaList = aka
        .split(',')
        .map(s => s.trim())
        .filter(s => s.length > 0);
      await window.bridge.createSavedView({
        name: trimmedName,
        kind: 'FILTER',
        definition: trimmedDef,
        aka: akaList,
      });
      setName('');
      setDefinition('');
      setAka('');
      await load();
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setSubmitting(false);
    }
  };

  const handleDelete = async (target: string) => {
    setError(null);
    try {
      await window.bridge.deleteSavedView(target);
      await load();
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  return (
    <section className="settings-page" aria-labelledby="saved-views-heading">
      <header className="settings-page__head">
        <h2 id="saved-views-heading">Saved views</h2>
        <p className="settings-page__lede">
          Name a filter once — agents and tools resolve it consistently. Each
          entry shows up under <code>list_terms</code> and is selectable wherever
          a filter param accepts a concept.
        </p>
      </header>

      {error && (
        <div className="settings-page__error" role="alert">
          {error}
        </div>
      )}

      <form
        className="saved-views-form"
        onSubmit={ev => { ev.preventDefault(); void handleCreate(); }}
      >
        <label className="saved-views-form__field">
          <span>Name</span>
          <input
            type="text"
            value={name}
            onChange={ev => setName(ev.target.value)}
            placeholder="e.g. shippable"
            spellCheck={false}
            autoComplete="off"
          />
          <small>Lowercase letters, digits, dashes or underscores. 2–48 characters.</small>
        </label>

        <label className="saved-views-form__field">
          <span>Definition</span>
          <textarea
            value={definition}
            onChange={ev => setDefinition(ev.target.value)}
            placeholder="One sentence describing what this filter means."
            rows={3}
          />
        </label>

        <label className="saved-views-form__field">
          <span>Aliases (optional)</span>
          <input
            type="text"
            value={aka}
            onChange={ev => setAka(ev.target.value)}
            placeholder="ready, mergeable"
            spellCheck={false}
            autoComplete="off"
          />
          <small>Comma-separated synonyms; agents resolve them to the canonical name.</small>
        </label>

        <div className="saved-views-form__actions">
          <button type="submit" disabled={!canSubmit}>
            {submitting ? 'Saving…' : 'Save view'}
          </button>
        </div>
      </form>

      <h3>Your saved views</h3>
      {views === null && <div className="settings-page__placeholder">Loading…</div>}
      {views !== null && views.length === 0 && (
        <div className="settings-page__placeholder">
          No saved views yet. The first one you add will show up here.
        </div>
      )}
      {views !== null && views.length > 0 && (
        <ul className="saved-views-list">
          {views.map(view => (
            <li key={view.name} className="saved-views-list__item">
              <div>
                <div className="saved-views-list__name">{view.name}</div>
                <div className="saved-views-list__def">{view.definition}</div>
                {view.aka.length > 0 && (
                  <div className="saved-views-list__aka">
                    aka: {view.aka.join(', ')}
                  </div>
                )}
              </div>
              <button
                type="button"
                onClick={() => void handleDelete(view.name)}
                className="saved-views-list__delete"
              >
                Delete
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

export default SavedViewsPage;
