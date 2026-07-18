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
import type { PullRequestMetadataChoicesDto } from '../types';
import { Av } from './atoms';
import { reviewerLogins } from './detailModel';
import type { PullRow } from './model';

type PickerKind = 'reviewers' | 'assignees' | 'labels';
type Choice = { value: string; color?: string | null };

function PersonSummary({ values }: { values: string[] }) {
  const avatarOnly = values.length > 3;
  return values.map(login => (
    <span key={login} title={avatarOnly ? login : undefined} className="pl-meta-chip__person">
      <Av login={login} size={18} />
      {!avatarOnly && <span>{login}</span>}
    </span>
  ));
}

function PersonIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="12" cy="8" r="4" />
      <path d="M4 21a8 8 0 0 1 16 0" />
    </svg>
  );
}

function CheckIcon() {
  return <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2"><path d="m4 12 5 5L20 6" /></svg>;
}

export default function PullMetadataBar({ row }: { row: PullRow }) {
  const rootRef = useRef<HTMLDivElement>(null);
  const [open, setOpen] = useState<PickerKind | null>(null);
  const [query, setQuery] = useState('');
  const [data, setData] = useState<PullRequestMetadataChoicesDto | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<Set<string>>(new Set());
  const [reviewers, setReviewers] = useState(() => reviewerLogins(row));
  const [assignees, setAssignees] = useState<string[]>([]);
  const [labels, setLabels] = useState(() => row.dto.labels);

  useEffect(() => {
    if (open === null || data !== null) return;
    let alive = true;
    setLoading(true);
    setError(null);
    void window.bridge.getPullRequestMetadataChoices(row.repo, row.num)
      .then(result => {
        if (!alive) return;
        setData(result);
        setAssignees(result.assignees);
        setLabels(result.selectedLabels);
      })
      .catch(reason => { if (alive) setError(reason instanceof Error ? reason.message : String(reason)); })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, [data, open, row.num, row.repo]);

  useEffect(() => {
    if (open === null) return;
    const close = (event: MouseEvent) => {
      if (rootRef.current !== null && !rootRef.current.contains(event.target as Node)) setOpen(null);
    };
    const escape = (event: KeyboardEvent) => { if (event.key === 'Escape') setOpen(null); };
    document.addEventListener('mousedown', close);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('mousedown', close);
      document.removeEventListener('keydown', escape);
    };
  }, [open]);

  const selected = open === 'reviewers' ? reviewers : open === 'assignees' ? assignees : labels;
  const choices = useMemo<Choice[]>(() => {
    if (open === 'labels') {
      const colors = new Map((data?.labels ?? []).map(label => [label.name, label.color]));
      for (const label of row.dto.labels) if (!colors.has(label)) colors.set(label, row.dto.labelColors?.[label]);
      return [...colors].map(([value, color]) => ({ value, color }));
    }
    const values = new Set((data?.users ?? []).map(user => user.login));
    for (const login of open === 'reviewers' ? reviewers : assignees) values.add(login);
    return [...values].map(value => ({ value }));
  }, [assignees, data, open, reviewers, row.dto.labelColors, row.dto.labels]);
  const visible = choices
    .filter(choice => choice.value.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()))
    .sort((a, b) => Number(selected.includes(b.value)) - Number(selected.includes(a.value)) || a.value.localeCompare(b.value));

  const toggle = async (value: string) => {
    if (open === null || busy.has(value)) return;
    const wasSelected = selected.includes(value);
    setBusy(current => new Set(current).add(value));
    setError(null);
    try {
      if (open === 'reviewers') {
        await (wasSelected
          ? window.bridge.removeRequestedReviewer(row.repo, row.num, value)
          : window.bridge.addRequestedReviewer(row.repo, row.num, value));
        setReviewers(current => wasSelected ? current.filter(item => item !== value) : [...current, value]);
      }
      else if (open === 'assignees') {
        await window.bridge.setPullRequestAssignee(row.repo, row.num, value, !wasSelected);
        setAssignees(current => wasSelected ? current.filter(item => item !== value) : [...current, value]);
      }
      else {
        await window.bridge.setPullRequestLabel(row.repo, row.num, value, !wasSelected);
        setLabels(current => wasSelected ? current.filter(item => item !== value) : [...current, value]);
      }
    }
    catch (reason) {
      setError(reason instanceof Error ? reason.message : String(reason));
    }
    finally {
      setBusy(current => { const next = new Set(current); next.delete(value); return next; });
    }
  };

  const picker = (kind: PickerKind) => (
    <span className="pl-meta-picker">
      <button
        type="button"
        className={`pl-meta-chip${open === kind ? ' pl-meta-chip--open' : ''}`}
        onClick={() => { setOpen(current => current === kind ? null : kind); setQuery(''); setError(null); }}
        aria-haspopup="listbox"
        aria-expanded={open === kind}
      >
        {kind === 'reviewers' && <><span>Reviewers</span><PersonSummary values={reviewers} /></>}
        {kind === 'assignees' && <><PersonIcon /><span>Assignees</span><PersonSummary values={assignees} /></>}
        {kind === 'labels' && <><span className="pl-meta-chip__label-dot" style={{ background: labels.length > 0 ? `#${row.dto.labelColors?.[labels[0]] ?? '9b5b55'}` : '#d8dde3' }} /><span>{labels.length} {labels.length === 1 ? 'label' : 'labels'}</span></>}
      </button>
      {open === kind && (
        <div className="pl-meta-popover">
          <input
            autoFocus
            value={query}
            onChange={event => setQuery(event.target.value)}
            className="pl-meta-popover__search"
            placeholder={kind === 'labels' ? 'Search labels…' : 'Search users…'}
            aria-label={kind === 'labels' ? 'Search labels' : `Search ${kind}`}
          />
          <div className="pl-meta-popover__list" role="listbox" aria-multiselectable="true">
            {loading && <div className="pl-meta-popover__message">Loading…</div>}
            {!loading && visible.map(choice => {
              const checked = selected.includes(choice.value);
              return (
                <button
                  type="button"
                  role="option"
                  aria-selected={checked}
                  key={choice.value}
                  className={`pl-meta-option${checked ? ' pl-meta-option--selected' : ''}`}
                  onClick={() => { void toggle(choice.value); }}
                  disabled={busy.has(choice.value)}
                >
                  <span className="pl-meta-option__check">{checked && <CheckIcon />}</span>
                  {kind === 'labels'
                    ? <span className="pl-meta-option__dot" style={{ background: `#${choice.color ?? '9b5b55'}` }} />
                    : <Av login={choice.value} size={20} square={/\[bot\]$/i.test(choice.value)} />}
                  <span className="pl-meta-option__name">{choice.value}</span>
                  {/\[bot\]$|copilot/i.test(choice.value) && <span className="pl-meta-option__bot">Bot</span>}
                  {busy.has(choice.value) && <span className="pl-meta-option__busy">…</span>}
                </button>
              );
            })}
            {!loading && visible.length === 0 && <div className="pl-meta-popover__message">No matches</div>}
          </div>
          {error !== null && <div className="pl-meta-popover__error" role="alert">{error}</div>}
        </div>
      )}
    </span>
  );

  return <div ref={rootRef} className="pl-meta-bar">{picker('reviewers')}{picker('assignees')}{picker('labels')}</div>;
}
