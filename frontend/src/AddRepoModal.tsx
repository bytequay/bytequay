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
import type { LocalRepoStatusDto, UserRepoDto, WatchedRepoDto } from './types';
import Avatar from './Avatar';
import MapRepoModal from './repos/AddRepoModal';

type Props = {
  watchedRepos: WatchedRepoDto[];
  // Called after a repo has been both watched AND mapped to a local
  // clone. A watched repo can never exist without a clone, so adding is
  // a single map step — the host re-reads its list from this signal.
  onAdded: (status: LocalRepoStatusDto) => void;
  onClose: () => void;
};

function RepoRow({
  repo,
  isWatched,
  onAdd,
}: {
  repo: UserRepoDto;
  isWatched: boolean;
  onAdd: () => void;
}) {
  return (
    <div className="modal-repo-row">
      <div className="modal-repo-row__info">
        <Avatar login={repo.owner} size={16} className="avatar--repo" />
        <div className="modal-repo-row__text">
          <div className="modal-repo-row__name">{repo.fullName}</div>
          {repo.description && (
            <div className="modal-repo-row__desc">{repo.description}</div>
          )}
          <div className="modal-repo-row__meta">
            {repo.language && <span className="modal-repo-row__lang">{repo.language}</span>}
            {repo.stars > 0 && <span>★ {repo.stars.toLocaleString()}</span>}
          </div>
        </div>
      </div>
      {isWatched ? (
        <span className="modal-repo-row__badge">Watching</span>
      ) : (
        <button
          className="modal-repo-row__add-btn"
          onClick={onAdd}
        >
          Add…
        </button>
      )}
    </div>
  );
}

function AddRepoModal({ watchedRepos, onAdded, onClose }: Props) {
  const [userRepos, setUserRepos] = useState<UserRepoDto[]>([]);
  const [searchResults, setSearchResults] = useState<UserRepoDto[] | null>(null);
  const [loadingUser, setLoadingUser] = useState(true);
  const [searching, setSearching] = useState(false);
  const [query, setQuery] = useState('');
  // The repo the user is mapping a clone for. Picking a repo doesn't
  // watch it directly — it opens the locate/clone step, and the repo is
  // only persisted once that succeeds (every watched repo has a clone).
  const [mapTarget, setMapTarget] = useState<{ owner: string; repo: string } | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const watchedSet = new Set(watchedRepos.map(r => `${r.owner}/${r.repo}`));

  useEffect(() => {
    inputRef.current?.focus();
    window.bridge.getUserRepos()
      .then(setUserRepos)
      .catch(() => {})
      .finally(() => setLoadingUser(false));
  }, []);

  function handleQueryChange(value: string) {
    setQuery(value);
    if (debounceRef.current) clearTimeout(debounceRef.current);
    if (!value.trim()) {
      setSearchResults(null);
      setSearching(false);
      return;
    }
    setSearching(true);
    debounceRef.current = setTimeout(async () => {
      try {
        const results = await window.bridge.searchRepos(value.trim());
        setSearchResults(results);
      } catch {
        setSearchResults([]);
      } finally {
        setSearching(false);
      }
    }, 350);
  }

  function handleAdd(repo: UserRepoDto) {
    setMapTarget({ owner: repo.owner, repo: repo.name });
  }

  const displayRepos = searchResults ?? userRepos;
  const isSearchMode = !!query.trim();

  return (
    <>
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={e => e.stopPropagation()}>
        <div className="modal__header">
          <h2 className="modal__title">Add a repo to watch</h2>
          <button className="modal__close" onClick={onClose}>✕</button>
        </div>
        <div className="modal__search-row">
          <input
            ref={inputRef}
            className="modal__search"
            type="text"
            placeholder="Search any GitHub repo (e.g. facebook/react or react)"
            value={query}
            onChange={e => handleQueryChange(e.target.value)}
          />
        </div>
        {!isSearchMode && (
          <div className="modal__section-label">Your recently updated repos</div>
        )}
        <div className="modal__list">
          {(isSearchMode ? searching : loadingUser) ? (
            <div className="modal__loading">
              {isSearchMode ? 'Searching…' : 'Loading your repos…'}
            </div>
          ) : displayRepos.length === 0 ? (
            <div className="modal__empty">
              {isSearchMode ? `No repos found for "${query}"` : 'No repos found'}
            </div>
          ) : displayRepos.map(repo => (
            <RepoRow
              key={repo.fullName}
              repo={repo}
              isWatched={watchedSet.has(repo.fullName)}
              onAdd={() => handleAdd(repo)}
            />
          ))}
        </div>
      </div>
    </div>
    {mapTarget && (
      <MapRepoModal
        owner={mapTarget.owner}
        repo={mapTarget.repo}
        onClose={() => setMapTarget(null)}
        onMapped={(status) => { setMapTarget(null); onAdded(status); }}
      />
    )}
    </>
  );
}

export default AddRepoModal;
