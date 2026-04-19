import { useEffect, useState } from 'react';
import type { PullRequestDto } from '../types';
import Avatar from '../Avatar';

/**
 * Reviewers card with add/remove. Pending requests render as chips with a
 * × that calls DELETE on the requested_reviewers endpoint; past reviewers
 * (already submitted a verdict) render below as read-only rows. The "+
 * Add" button reveals a typeahead-driven input where the user picks from
 * GitHub's suggested reviewers (above) or any user-search match (below).
 *
 * `onRefresh` re-pulls the PR detail after each mutation so the synthetic
 * review_requested timeline event GitHub emits shows up immediately.
 */
export function ReviewerEditor({
  pr,
  reviewerVerdicts,
  onRefresh,
}: {
  pr: PullRequestDto;
  reviewerVerdicts: Map<string, string>;
  onRefresh: () => Promise<void>;
}) {
  const [reviewers, setReviewers] = useState<string[]>(pr.requestedReviewers);
  const [adding, setAdding] = useState(false);
  const [newLogin, setNewLogin] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Typeahead state — populated by /api/search/users for any query
  // ≥ 2 chars after a 200ms debounce. Already-requested logins are
  // filtered out so the user can't pick a duplicate. {@code highlight}
  // tracks the active row for keyboard nav (ArrowUp/Down, Enter).
  const [suggestions, setSuggestions] = useState<{ login: string; avatarUrl: string | null; name: string | null }[]>([]);
  const [highlight, setHighlight] = useState(0);
  const [showSuggest, setShowSuggest] = useState(false);
  // GraphQL-derived "suggested reviewers" — github.com surfaces these
  // chips on its conversation page, picked from blame on the touched
  // files plus the requestor's review history. Empty when the GraphQL
  // hop fails (auth/network) — non-essential, hidden then.
  const [suggestedRecs, setSuggestedRecs] = useState<{ login: string; avatarUrl: string | null; name: string | null }[]>([]);

  // Reset when the PR's reviewer list changes externally (sync, switch PR).
  useEffect(() => { setReviewers(pr.requestedReviewers); }, [pr.requestedReviewers]);

  // Fetch GitHub's suggested reviewers once when the user enters the
  // add-flow. Cheap GraphQL call; cache for the duration of this view
  // by guarding on `adding` so re-opens within the same PR session
  // re-fetch (the suggestion set shifts as more reviews land).
  useEffect(() => {
    if (!adding) return;
    let alive = true;
    void window.bridge.getSuggestedReviewers(pr.repo, pr.number)
      .then(rs => {
        if (!alive) return;
        setSuggestedRecs(
          rs.filter(r => r.login && !reviewers.includes(r.login))
            .map(r => ({ login: r.login, avatarUrl: r.avatarUrl, name: r.name })),
        );
      })
      .catch(() => { if (alive) setSuggestedRecs([]); });
    return () => { alive = false; };
  }, [adding, pr.repo, pr.number, reviewers]);

  // Debounced GitHub user search. We rebuild the timer on every keystroke,
  // and an `alive` flag drops responses that arrive after the user has
  // typed something newer (so a slow request doesn't overwrite a fresher
  // one). Queries < 2 chars short-circuit to an empty list — same gate
  // the team-editor uses, which matches the backend's own minimum.
  useEffect(() => {
    if (!adding) return;
    const q = newLogin.trim();
    if (q.length < 2) {
      setSuggestions([]);
      return;
    }
    let alive = true;
    const t = setTimeout(() => {
      void window.bridge.searchUsers(q)
        .then(matches => {
          if (!alive) return;
          const filtered = matches
            .filter(m => m.login && !reviewers.includes(m.login))
            .slice(0, 8);
          setSuggestions(filtered);
          setHighlight(0);
        })
        .catch(() => { if (alive) setSuggestions([]); });
    }, 200);
    return () => { alive = false; clearTimeout(t); };
  }, [newLogin, adding, reviewers]);

  const remove = async (login: string) => {
    setPending(true); setError(null);
    try {
      await window.bridge.removeRequestedReviewer(pr.repo, pr.number, login);
      setReviewers(prev => prev.filter(r => r !== login));
      void onRefresh().catch(() => { /* best-effort */ });
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setPending(false);
    }
  };

  const add = async (loginOverride?: string) => {
    const login = (loginOverride ?? newLogin).trim();
    if (!login) return;
    setPending(true); setError(null);
    try {
      await window.bridge.addRequestedReviewer(pr.repo, pr.number, login);
      setReviewers(prev => prev.includes(login) ? prev : [...prev, login]);
      setNewLogin('');
      setSuggestions([]);
      setShowSuggest(false);
      setAdding(false);
      void onRefresh().catch(() => { /* best-effort */ });
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setPending(false);
    }
  };

  const past = Array.from(reviewerVerdicts.entries()).filter(([login]) => !reviewers.includes(login));
  const empty = reviewers.length === 0 && past.length === 0;

  return (
    <>
      {empty && !adding && <div className="prc-meta-empty">No reviewers requested.</div>}
      {reviewers.map(login => (
        <div key={login} className="prc-reviewer-row">
          <Avatar login={login} size={20} />
          <span className="prc-reviewer-name">{login}</span>
          <span className="prc-reviewer-status prc-reviewer-status--pending">pending</span>
          <button
            type="button"
            className="prc-reviewer-remove"
            onClick={() => remove(login)}
            disabled={pending}
            title={`Remove ${login} from reviewers`}
            aria-label={`Remove ${login}`}
          >
            ×
          </button>
        </div>
      ))}
      {past.map(([login, verdict]) => (
        <div key={login} className="prc-reviewer-row">
          <Avatar login={login} size={20} />
          <span className="prc-reviewer-name">{login}</span>
          <span className={`prc-reviewer-status prc-reviewer-status--${verdict.toLowerCase()}`}>
            {verdict.replace(/_/g, ' ').toLowerCase()}
          </span>
        </div>
      ))}
      {adding && suggestedRecs.length > 0 && (
        <div className="prc-reviewer-suggested-row">
          <span className="prc-reviewer-suggested-row__label">Suggested:</span>
          {suggestedRecs.slice(0, 5).map(r => (
            <button
              key={r.login}
              type="button"
              className="prc-reviewer-suggested-chip"
              onClick={() => { void add(r.login); }}
              disabled={pending}
              title={r.name ? `${r.login} — ${r.name}` : `Add ${r.login} as a reviewer`}
            >
              {r.avatarUrl
                ? <img className="prc-reviewer-suggested-chip__avatar" src={r.avatarUrl} alt="" width={16} height={16} />
                : <span className="prc-reviewer-suggested-chip__avatar prc-reviewer-suggested-chip__avatar--fallback">{r.login.charAt(0).toUpperCase()}</span>}
              <span className="prc-reviewer-suggested-chip__login">{r.login}</span>
            </button>
          ))}
        </div>
      )}
      {adding ? (
        <div className="prc-reviewer-add-row">
          <div className="prc-reviewer-add-typeahead">
            <input
              className="prc-reviewer-add-input"
              type="text"
              value={newLogin}
              onChange={e => { setNewLogin(e.target.value); setShowSuggest(true); }}
              onFocus={() => setShowSuggest(true)}
              // Defer hide so a click on a suggestion fires before the
              // popover unmounts.
              onBlur={() => { setTimeout(() => setShowSuggest(false), 120); }}
              placeholder="Type 2+ chars to search GitHub users"
              disabled={pending}
              autoFocus
              autoComplete="off"
              spellCheck={false}
              aria-autocomplete="list"
              aria-expanded={showSuggest && suggestions.length > 0}
              onKeyDown={e => {
                if (e.key === 'ArrowDown' && suggestions.length > 0) {
                  e.preventDefault();
                  setShowSuggest(true);
                  setHighlight(h => Math.min(suggestions.length - 1, h + 1));
                } else if (e.key === 'ArrowUp' && suggestions.length > 0) {
                  e.preventDefault();
                  setHighlight(h => Math.max(0, h - 1));
                } else if (e.key === 'Enter') {
                  e.preventDefault();
                  const picked = showSuggest && suggestions[highlight];
                  void add(picked ? picked.login : undefined);
                } else if (e.key === 'Escape') {
                  if (showSuggest && suggestions.length > 0) {
                    setShowSuggest(false);
                  } else {
                    setAdding(false);
                    setNewLogin('');
                  }
                }
              }}
            />
            {showSuggest && suggestions.length > 0 && (
              <div className="prc-reviewer-suggest" role="listbox">
                {suggestions.map((m, i) => (
                  <button
                    key={m.login}
                    type="button"
                    role="option"
                    aria-selected={i === highlight}
                    className={`prc-reviewer-suggest__row${i === highlight ? ' prc-reviewer-suggest__row--active' : ''}`}
                    onMouseEnter={() => setHighlight(i)}
                    // onMouseDown so we beat the input's onBlur, which
                    // would otherwise hide the popover before click fires.
                    onMouseDown={e => { e.preventDefault(); void add(m.login); }}
                  >
                    <span className="user-suggestion">
                      {m.avatarUrl
                        ? <img className="user-suggestion__avatar" src={m.avatarUrl} alt="" width={20} height={20} />
                        : <span className="user-suggestion__avatar user-suggestion__avatar--fallback">{m.login.charAt(0).toUpperCase()}</span>}
                      <span className="user-suggestion__login">{m.login}</span>
                      {m.name && <span className="user-suggestion__name">{m.name}</span>}
                    </span>
                  </button>
                ))}
              </div>
            )}
          </div>
          <button
            type="button"
            className="button button--primary prc-reviewer-add-btn"
            onClick={() => { void add(); }}
            disabled={pending || !newLogin.trim()}
          >
            {pending ? '…' : 'Add'}
          </button>
          <button
            type="button"
            className="prc-reviewer-cancel"
            onClick={() => { setAdding(false); setNewLogin(''); setError(null); setSuggestions([]); }}
            disabled={pending}
          >
            Cancel
          </button>
        </div>
      ) : (
        <button
          type="button"
          className="prc-reviewer-add"
          onClick={() => setAdding(true)}
          disabled={pending}
        >
          + Add reviewer
        </button>
      )}
      {error && <div className="prc-reviewer-error">{error}</div>}
    </>
  );
}
