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
import { useEffect, useMemo, useRef, useState } from 'react';
import Avatar from './Avatar';
import type { UserRepoDto, WatchedRepoDto } from './types';

type Props = {
  watchedRepos: WatchedRepoDto[];
  onAdded: (repo: WatchedRepoDto) => void;
  onClose: () => void;
};

export default function AddRepoModal({ watchedRepos, onAdded, onClose }: Props) {
  const [repos, setRepos] = useState<UserRepoDto[]>([]);
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(true);
  const [adding, setAdding] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    inputRef.current?.focus();
    window.bridge.getUserRepos()
      .then(setRepos)
      .catch(e => setError(e instanceof Error ? e.message : String(e)))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    const trimmed = query.trim();
    if (!trimmed) return;
    const timer = window.setTimeout(() => {
      setLoading(true);
      window.bridge.searchRepos(trimmed)
        .then(setRepos)
        .catch(e => setError(e instanceof Error ? e.message : String(e)))
        .finally(() => setLoading(false));
    }, 300);
    return () => window.clearTimeout(timer);
  }, [query]);

  const watched = useMemo(
    () => new Set(watchedRepos.map(repo => `${repo.owner}/${repo.repo}`.toLowerCase())),
    [watchedRepos],
  );

  const add = async (repo: UserRepoDto) => {
    setAdding(repo.fullName);
    setError(null);
    try {
      onAdded(await window.bridge.addWatchedRepo(repo.owner, repo.name));
      onClose();
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setAdding(null);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={event => event.stopPropagation()}>
        <div className="modal__header">
          <h2 className="modal__title">Watch a repository</h2>
          <button className="modal__close" onClick={onClose} type="button">✕</button>
        </div>
        <div className="modal__search-row">
          <input
            ref={inputRef}
            className="modal__search"
            value={query}
            placeholder="Search GitHub repositories"
            onChange={event => setQuery(event.target.value)}
          />
        </div>
        {error && <div className="modal__empty">{error}</div>}
        <div className="modal__list">
          {loading ? <div className="modal__loading">Loading…</div> : repos.map(repo => {
            const isWatched = watched.has(repo.fullName.toLowerCase());
            return (
              <div className="modal-repo-row" key={repo.fullName}>
                <div className="modal-repo-row__info">
                  <Avatar login={repo.owner} size={20} />
                  <div className="modal-repo-row__text">
                    <div className="modal-repo-row__name">{repo.fullName}</div>
                    {repo.description && <div className="modal-repo-row__desc">{repo.description}</div>}
                  </div>
                </div>
                <button
                  className="modal-repo-row__add-btn"
                  type="button"
                  disabled={isWatched || adding === repo.fullName}
                  onClick={() => { void add(repo); }}
                >
                  {isWatched ? 'Watching' : adding === repo.fullName ? 'Adding…' : 'Watch'}
                </button>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
