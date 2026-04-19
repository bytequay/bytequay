import { useEffect, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';

/** One row in the autocomplete dropdown. The component is agnostic about
 *  shape — caller decides what to render via {@link Props.renderSuggestion}. */
export type TagSuggestion = { value: string; render: ReactNode };

type Props = {
  value: string[];
  onChange: (next: string[]) => void;
  placeholder?: string;
  /** Lowercase normalisation per tag — defaults to true (GitHub logins are case-insensitive). */
  lowercase?: boolean;
  /** Async resolver for autocomplete suggestions. Called on every draft
   *  change after a debounce. Return [] when the query is too short or
   *  results are empty. The resolver should be cheap — call abort/cancel
   *  semantics aren't supported here, so a tiny debounce + per-query gate
   *  (we ignore stale responses) keeps it from flickering. */
  fetchSuggestions?: (query: string) => Promise<TagSuggestion[]>;
  /** Milliseconds to wait before firing fetchSuggestions. Default 200. */
  debounceMs?: number;
};

/**
 * Chip-style text input. Each existing entry renders as a removable tag;
 * an inline text field at the trailing edge accepts the next one. Commits
 * a tag on Enter / comma / space; Backspace on an empty input pops the
 * last tag (typical Mac/web "remove with one keystroke" behaviour).
 *
 * Used by the team editor for member rosters; structured so it could be
 * reused by any "list of short strings" input later.
 */
function TagInput({ value, onChange, placeholder, lowercase = true, fetchSuggestions, debounceMs = 200 }: Props) {
  const [draft, setDraft] = useState('');
  const [suggestions, setSuggestions] = useState<TagSuggestion[]>([]);
  // -1 means "nothing pre-selected"; user can still Enter to commit the
  // raw draft. ≥0 means "Enter / click commits suggestions[highlighted]".
  const [highlighted, setHighlighted] = useState<number>(-1);
  const [open, setOpen] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const queryRef = useRef<string>('');

  const normalise = (raw: string): string => {
    const t = raw.trim();
    return lowercase ? t.toLowerCase() : t;
  };

  const commit = (raw: string) => {
    const t = normalise(raw);
    if (!t) return;
    if (value.includes(t)) {
      setDraft('');
      setSuggestions([]);
      setOpen(false);
      return;
    }
    onChange([...value, t]);
    setDraft('');
    setSuggestions([]);
    setOpen(false);
    setHighlighted(-1);
  };

  // Debounced async suggestion fetch. queryRef gates stale responses
  // (only the most recent in-flight query gets to update state).
  useEffect(() => {
    if (!fetchSuggestions) return;
    const q = draft.trim();
    queryRef.current = q;
    if (q.length < 2) {
      setSuggestions([]);
      setOpen(false);
      return;
    }
    const handle = setTimeout(async () => {
      try {
        const result = await fetchSuggestions(q);
        if (queryRef.current === q) {
          // Filter out anything already chipped — no point suggesting
          // a member you've already added.
          const filtered = result.filter(s => !value.includes(normalise(s.value)));
          setSuggestions(filtered);
          setOpen(filtered.length > 0);
          setHighlighted(filtered.length > 0 ? 0 : -1);
        }
      }
      catch {
        if (queryRef.current === q) {
          setSuggestions([]);
          setOpen(false);
        }
      }
    }, debounceMs);
    return () => clearTimeout(handle);
  }, [draft, fetchSuggestions, debounceMs, value]);

  const handleKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    // Arrow keys + Enter take priority when the suggestion list is open.
    if (open && suggestions.length > 0) {
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setHighlighted(h => (h + 1) % suggestions.length);
        return;
      }
      if (e.key === 'ArrowUp') {
        e.preventDefault();
        setHighlighted(h => (h <= 0 ? suggestions.length - 1 : h - 1));
        return;
      }
      if (e.key === 'Enter') {
        e.preventDefault();
        const pick = highlighted >= 0 ? suggestions[highlighted] : suggestions[0];
        commit(pick.value);
        return;
      }
      if (e.key === 'Escape') {
        e.preventDefault();
        setOpen(false);
        return;
      }
    }
    if (e.key === 'Enter' || e.key === ',') {
      // Comma always commits the raw draft (handy when pasting "alice,
      // bob"). Enter outside the dropdown also commits raw — keeps the
      // legacy "type a name and hit Enter" behaviour for callers that
      // didn't wire a fetchSuggestions.
      if (draft.trim()) {
        e.preventDefault();
        commit(draft);
      } else if (e.key === 'Enter') {
        e.preventDefault();
      }
    } else if (e.key === ' ' && !fetchSuggestions) {
      // Space-as-separator was helpful before autocomplete, but with a
      // suggestion list the user is more likely to want a literal space
      // mid-typing (e.g. "Alice S" before the dropdown narrows).
      if (draft.trim()) {
        e.preventDefault();
        commit(draft);
      }
    } else if (e.key === 'Backspace' && draft === '' && value.length > 0) {
      e.preventDefault();
      onChange(value.slice(0, -1));
    }
  };

  // When the user pastes a chunk like "alice\nbob\ncarol", split it into
  // multiple tags in one shot.
  const handlePaste = (e: React.ClipboardEvent<HTMLInputElement>) => {
    const pasted = e.clipboardData.getData('text');
    if (/[\s,]/.test(pasted)) {
      e.preventDefault();
      const tokens = pasted.split(/[\s,]+/).map(normalise).filter(Boolean);
      const next = [...value];
      for (const t of tokens) {
        if (!next.includes(t)) next.push(t);
      }
      onChange(next);
      setDraft('');
    }
  };

  const handleBlur = () => {
    // Delay so a click on a suggestion row gets to fire its onMouseDown
    // before the input loses focus.
    setTimeout(() => {
      if (draft.trim()) commit(draft);
      setOpen(false);
    }, 100);
  };

  const removeAt = (idx: number) => {
    const next = value.slice();
    next.splice(idx, 1);
    onChange(next);
    inputRef.current?.focus();
  };

  return (
    <div className="tag-input-wrap">
      <div className="tag-input" onClick={() => inputRef.current?.focus()}>
        {value.map((tag, idx) => (
          <span key={tag} className="tag-input__chip">
            <span className="tag-input__chip-text">{tag}</span>
            <button
              type="button"
              className="tag-input__chip-remove"
              onClick={(e) => { e.stopPropagation(); removeAt(idx); }}
              aria-label={`Remove ${tag}`}
              title={`Remove ${tag}`}
            >
              ×
            </button>
          </span>
        ))}
        <input
          ref={inputRef}
          className="tag-input__field"
          type="text"
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={handleKeyDown}
          onPaste={handlePaste}
          onBlur={handleBlur}
          onFocus={() => { if (suggestions.length > 0) setOpen(true); }}
          placeholder={value.length === 0 ? placeholder : ''}
          aria-autocomplete="list"
          aria-expanded={open}
        />
      </div>
      {open && suggestions.length > 0 && (
        <ul className="tag-input__suggestions" role="listbox">
          {suggestions.map((s, i) => (
            <li
              key={s.value}
              role="option"
              aria-selected={i === highlighted}
              className={`tag-input__suggestion${i === highlighted ? ' tag-input__suggestion--active' : ''}`}
              onMouseDown={(e) => { e.preventDefault(); commit(s.value); }}
              onMouseEnter={() => setHighlighted(i)}
            >
              {s.render}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

export default TagInput;
