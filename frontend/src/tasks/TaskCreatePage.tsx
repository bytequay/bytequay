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
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type {
  IssueDto,
  LocalRepoStatusDto,
  PullRequestDto,
  TaskGroupDto,
} from '../types';

/**
 * Full-page create surface for one new task. Lives at
 * {@code nav.view === 'task-create'}, replacing the old modal-based
 * {@code NewTaskDialog}. Layout mirrors
 * {@code docs/mockups/design/tasks/task-create.png}: a centered card
 * over the page background, ← back via the topnav brand, sections
 * for Type / Repo / Pull request / Linked issue / Kind / Group /
 * Title / Initial prompt, footer Cancel + Create & start with the
 * ⌘+↵ shortcut hint.
 *
 * <p>Three fields are persisted server-side as of V60: {@code taskType}
 * ("DEVELOP" / "FIX"), {@code linkedPrNumber}, and
 * {@code linkedIssueNumber}. The PR + Issue dropdowns search the
 * task's repo via the existing {@code getRepoPulls} /
 * {@code getRepoIssues} bridge methods. The "spawned in a fresh
 * worktree" subtitle from the mockup is deliberately omitted until
 * the backend grows worktree support — see
 * {@code followups/task-create-fresh-worktree.md}.
 */
export type TaskCreatePageProps = {
  /** Optional — pre-fills the Group dropdown. Set when the page is
   *  entered from a group page's "+ Add task" button so the new
   *  task lands in that group by default. */
  initialGroupId?: string | null;
  onBack: () => void;
  /** Called after the backend confirms creation. The host typically
   *  navigates to the task list or the task-detail page. */
  onCreated: (taskId: string) => void;
};

type TaskType = 'DEVELOP' | 'FIX';

type Provider = 'claude-code' | 'codex';

export default function TaskCreatePage({
  initialGroupId, onBack, onCreated,
}: TaskCreatePageProps) {
  const [taskType, setTaskType] = useState<TaskType>('DEVELOP');
  const [provider, setProvider] = useState<Provider>('claude-code');
  const [title, setTitle] = useState('');
  const [prompt, setPrompt] = useState('');
  const [groupId, setGroupId] = useState<string>(initialGroupId ?? '');
  const [submitting, setSubmitting] = useState(false);
  const [cloneStatus, setCloneStatus] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Repo selection.
  const [repos, setRepos] = useState<LocalRepoStatusDto[]>([]);
  const [reposError, setReposError] = useState<string | null>(null);
  const [selectedRepoKey, setSelectedRepoKey] = useState<string>('');

  // PR selection (scoped to selected repo).
  const [openPrs, setOpenPrs] = useState<PullRequestDto[] | null>(null);
  const [prsError, setPrsError] = useState<string | null>(null);
  const [linkedPrNumber, setLinkedPrNumber] = useState<number | null>(null);
  const [linkedPrTitle, setLinkedPrTitle] = useState<string>('');
  const [prSearch, setPrSearch] = useState('');
  const [prFocused, setPrFocused] = useState(false);
  // Fallback for the user typing #29206 in a repo whose "open PRs"
  // response is capped — getRepoPulls returns a single page from
  // GitHub, so very old or closed PRs aren't in `openPrs`. When the
  // typed number doesn't match anything local, fire a single
  // getRepoPull(owner, repo, number) and surface the result in the
  // dropdown so the user can still link it. Mirrors the deep-link
  // fallback already wired into the PR detail flow.
  const [extraPr, setExtraPr] = useState<PullRequestDto | null>(null);
  const [extraPrLoading, setExtraPrLoading] = useState(false);
  const [extraPrError, setExtraPrError] = useState<string | null>(null);

  // Issue selection (scoped to selected repo).
  const [openIssues, setOpenIssues] = useState<IssueDto[] | null>(null);
  const [issuesError, setIssuesError] = useState<string | null>(null);
  const [linkedIssueNumber, setLinkedIssueNumber] = useState<number | null>(null);
  const [linkedIssueTitle, setLinkedIssueTitle] = useState<string>('');
  const [issueSearch, setIssueSearch] = useState('');
  const [issueFocused, setIssueFocused] = useState(false);

  // Group dropdown.
  const [groups, setGroups] = useState<TaskGroupDto[]>([]);

  // Fetch repos + groups on mount.
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const list = await window.bridge.listLocalRepos();
        if (cancelled) return;
        setRepos(list);
        const firstMapped = list.find(r => r.localClonePath != null);
        setSelectedRepoKey(repoKey(firstMapped ?? list[0] ?? null));
      }
      catch (e) {
        if (!cancelled) setReposError(e instanceof Error ? e.message : String(e));
      }
    })();
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const gs = await window.bridge.listTaskGroups();
        if (!cancelled) setGroups(gs);
      }
      catch {
        // Groups are optional — silent on error.
      }
    })();
    return () => { cancelled = true; };
  }, []);

  // Re-fetch PRs + issues whenever the selected repo changes. Clears
  // any prior selection that no longer belongs to the new repo.
  useEffect(() => {
    const repo = repos.find(r => repoKey(r) === selectedRepoKey);
    if (repo === undefined) {
      setOpenPrs(null);
      setOpenIssues(null);
      return;
    }
    setLinkedPrNumber(null);
    setLinkedPrTitle('');
    setPrSearch('');
    setLinkedIssueNumber(null);
    setLinkedIssueTitle('');
    setIssueSearch('');

    let cancelled = false;
    setOpenPrs(null);
    setPrsError(null);
    void (async () => {
      try {
        const list = await window.bridge.getRepoPulls(repo.owner, repo.repo);
        if (cancelled) return;
        setOpenPrs(list.filter(p => p.state === 'open' || p.state === null));
      }
      catch (e) {
        if (!cancelled) setPrsError(e instanceof Error ? e.message : String(e));
      }
    })();

    setOpenIssues(null);
    setIssuesError(null);
    void (async () => {
      try {
        const list = await window.bridge.getRepoIssues(repo.owner, repo.repo, 'open');
        if (cancelled) return;
        setOpenIssues(list);
      }
      catch (e) {
        if (!cancelled) setIssuesError(e instanceof Error ? e.message : String(e));
      }
    })();

    return () => { cancelled = true; };
  }, [selectedRepoKey, repos]);

  // Title auto-fill from PR title + type prefix. Only sets the title
  // when the user hasn't already typed one — so editing the title
  // then picking a PR doesn't blow away the manual edit.
  const userEditedTitleRef = useRef(false);
  useEffect(() => {
    if (linkedPrNumber === null || linkedPrTitle === '') return;
    if (userEditedTitleRef.current) return;
    setTitle(`${taskType.toLowerCase()} ${linkedPrTitle}`);
  }, [linkedPrNumber, linkedPrTitle, taskType]);

  function onTitleChange(next: string) {
    userEditedTitleRef.current = next.trim() !== '';
    setTitle(next);
  }

  function pickPr(p: PullRequestDto) {
    setLinkedPrNumber(p.number);
    setLinkedPrTitle(p.title);
    setPrSearch(`#${p.number} ${p.title}`);
    setPrFocused(false);
  }
  function clearPr() {
    setLinkedPrNumber(null);
    setLinkedPrTitle('');
    setPrSearch('');
    userEditedTitleRef.current = false;
  }
  function pickIssue(i: IssueDto) {
    setLinkedIssueNumber(i.number);
    setLinkedIssueTitle(i.title);
    setIssueSearch(`#${i.number} ${i.title}`);
    setIssueFocused(false);
  }
  function clearIssue() {
    setLinkedIssueNumber(null);
    setLinkedIssueTitle('');
    setIssueSearch('');
  }

  const matchingPrs = useMemo(() => {
    const local = filterPrs(openPrs, prSearch, linkedPrNumber);
    if (local.length > 0) return local;
    // Surface the fallback-fetched PR when the local list comes up
    // empty for a numeric query. Hidden once the user clears the
    // search (extraPr only repopulates when the typed number stops
    // matching anything in openPrs).
    if (extraPr !== null && extraPr.number !== linkedPrNumber) {
      return [extraPr];
    }
    return [];
  }, [openPrs, prSearch, linkedPrNumber, extraPr]);
  const matchingIssues = useMemo(() => filterIssues(openIssues, issueSearch, linkedIssueNumber), [openIssues, issueSearch, linkedIssueNumber]);

  // Deep-link fallback for the PR search field. When the user types a
  // number we don't recognise locally, fetch that one PR directly.
  // Debounced so a fast typist doesn't trigger one fetch per
  // keystroke; gated on a numeric query so title searches don't fall
  // through to a 404. Bails immediately if the typed number happens
  // to be in openPrs already, which is the common case.
  useEffect(() => {
    const trimmed = prSearch.trim();
    const numMatch = trimmed.replace(/^#/, '').match(/^\d+$/);
    if (numMatch === null || openPrs === null) {
      setExtraPr(null);
      setExtraPrError(null);
      setExtraPrLoading(false);
      return;
    }
    const num = Number(numMatch[0]);
    if (openPrs.some(p => p.number === num)) {
      setExtraPr(null);
      setExtraPrError(null);
      setExtraPrLoading(false);
      return;
    }
    // Reuse a previous fetch if still relevant.
    if (extraPr !== null && extraPr.number === num) {
      return;
    }
    const repo = repos.find(r => repoKey(r) === selectedRepoKey);
    if (repo === undefined) return;
    let cancelled = false;
    setExtraPrLoading(true);
    setExtraPrError(null);
    const timer = window.setTimeout(async () => {
      try {
        const pr = await window.bridge.getRepoPull(repo.owner, repo.repo, num);
        if (cancelled) return;
        setExtraPr(pr);
      }
      catch (e) {
        if (cancelled) return;
        setExtraPr(null);
        setExtraPrError(e instanceof Error ? e.message : String(e));
      }
      finally {
        if (!cancelled) setExtraPrLoading(false);
      }
    }, 300);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [prSearch, openPrs, selectedRepoKey, repos, extraPr]);

  const selectedRepo = useMemo(
    () => repos.find(r => repoKey(r) === selectedRepoKey) ?? null,
    [repos, selectedRepoKey]);
  const needsClone = selectedRepo !== null && selectedRepo.localClonePath === null;

  const submit = useCallback(async () => {
    if (submitting) return;
    setError(null);

    const trimmedTitle = title.trim();
    const trimmedPrompt = prompt.trim();
    if (!selectedRepo) {
      setError('Pick a repo to work in.');
      return;
    }
    if (trimmedTitle === '') {
      setError(linkedPrNumber !== null
        ? 'Title is empty — fill it in or pick a PR to auto-fill from.'
        : 'Title is required — pick a PR or type one in.');
      return;
    }
    // Initial prompt is optional — the user can start the task and
    // send the first turn from the detail page once the agent boots.

    setSubmitting(true);
    try {
      let workingDir = selectedRepo.localClonePath;
      if (workingDir === null) {
        setCloneStatus(`Cloning ${selectedRepo.owner}/${selectedRepo.repo}…`);
        const destination = await window.bridge.defaultClonePath(
          selectedRepo.owner, selectedRepo.repo);
        const cloned = await window.bridge.cloneRepo(
          selectedRepo.owner, selectedRepo.repo, destination);
        workingDir = cloned.localClonePath;
        setCloneStatus(null);
      }
      if (workingDir === null) {
        throw new Error('Repo has no local path even after cloning.');
      }
      const created = await window.bridge.createTask({
        kind: 'CLI_AGENT',
        provider,
        // Claude Code picks its own model and reports it back through
        // the stream — the detail page surfaces the real value once
        // the session emits its first event.
        model: '',
        title: trimmedTitle,
        workingDir,
        initialPrompt: trimmedPrompt === '' ? undefined : trimmedPrompt,
        initialGroupIds: groupId ? [groupId] : undefined,
        taskType,
        linkedPrNumber: linkedPrNumber ?? undefined,
        linkedIssueNumber: linkedIssueNumber ?? undefined,
      });
      onCreated(created.id);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setCloneStatus(null);
      setSubmitting(false);
    }
  }, [
    submitting, title, prompt, selectedRepo, linkedPrNumber, linkedIssueNumber,
    provider, taskType, groupId, onCreated,
  ]);

  // Esc cancels (unless mid-submit), ⌘/Ctrl+Enter submits.
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape' && !submitting) {
        e.preventDefault();
        onBack();
        return;
      }
      if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
        e.preventDefault();
        void submit();
      }
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onBack, submit, submitting]);

  return (
    <section style={shellStyle}>
      <div style={cardStyle}>
        <header style={headerStyle}>
          <h1 style={titleH1Style}>New task</h1>
          {/* "spawned in a fresh worktree" subtitle deferred — see
              followups/task-create-fresh-worktree.md. */}
        </header>

        <div style={bodyStyle}>
          <Field label="Type" required>
            <div style={segStyle} role="radiogroup" aria-label="Task type">
              <SegBtn
                glyph="🛠"
                label="Develop a feature"
                active={taskType === 'DEVELOP'}
                onClick={() => setTaskType('DEVELOP')}
              />
              <SegBtn
                glyph="🐞"
                label="Fix a bug"
                active={taskType === 'FIX'}
                onClick={() => setTaskType('FIX')}
              />
            </div>
          </Field>

          <Field label="Repo" required hint="watched repos only">
            {reposError !== null && <div style={errorHintStyle}>{reposError}</div>}
            {reposError === null && repos.length === 0 && (
              <div style={mutedHintStyle}>
                No watched repos yet. Add one in the Repos tab, then come back.
              </div>
            )}
            {repos.length > 0 && (
              <select
                value={selectedRepoKey}
                onChange={e => setSelectedRepoKey(e.target.value)}
                style={selectStyle}
              >
                {repos.map(r => (
                  <option key={repoKey(r)} value={repoKey(r)}>
                    {r.owner}/{r.repo}
                    {r.localClonePath === null ? ' (will clone on create)' : ''}
                  </option>
                ))}
              </select>
            )}
          </Field>

          <Field
            label="Pull request"
            hint={prsError !== null
              ? prsError
              : "search the repo's open PRs · or paste #1234"}
          >
            {linkedPrNumber !== null ? (
              <div style={selectedPillStyle}>
                <span style={pillNumStyle}>#{linkedPrNumber}</span>
                <span style={pillTitleStyle}>{linkedPrTitle}</span>
                <button
                  type="button"
                  onClick={clearPr}
                  style={pillCloseStyle}
                  title="Unlink PR"
                  aria-label="Unlink PR"
                >
                  ✕
                </button>
              </div>
            ) : (
              <div style={searchWrapStyle}>
                <input
                  type="text"
                  value={prSearch}
                  onChange={e => setPrSearch(e.target.value)}
                  onFocus={() => setPrFocused(true)}
                  onBlur={() => {
                    // Delay so a click on a dropdown row registers
                    // before the dropdown unmounts on blur.
                    window.setTimeout(() => setPrFocused(false), 120);
                  }}
                  placeholder="Search PRs in this repo, or paste #1234…"
                  style={inputStyle}
                />
                {prFocused && (matchingPrs.length > 0 || prSearch.trim() !== '') && (
                  <PrDropdown
                    prs={matchingPrs}
                    onPick={pickPr}
                    loading={extraPrLoading}
                    error={extraPrError}
                  />
                )}
              </div>
            )}
          </Field>

          <Field
            label="Linked issue"
            hint={issuesError !== null
              ? issuesError
              : "optional · open issues in this repo"}
          >
            {linkedIssueNumber !== null ? (
              <div style={selectedPillStyle}>
                <span style={pillNumStyle}>#{linkedIssueNumber}</span>
                <span style={pillTitleStyle}>{linkedIssueTitle}</span>
                <button
                  type="button"
                  onClick={clearIssue}
                  style={pillCloseStyle}
                  title="Unlink issue"
                  aria-label="Unlink issue"
                >
                  ✕
                </button>
              </div>
            ) : (
              <div style={searchWrapStyle}>
                <input
                  type="text"
                  value={issueSearch}
                  onChange={e => setIssueSearch(e.target.value)}
                  onFocus={() => setIssueFocused(true)}
                  onBlur={() => {
                    window.setTimeout(() => setIssueFocused(false), 120);
                  }}
                  placeholder="Search open issues in this repo…"
                  style={inputStyle}
                />
                {issueFocused && matchingIssues.length > 0 && (
                  <IssueDropdown
                    issues={matchingIssues}
                    onPick={pickIssue}
                  />
                )}
              </div>
            )}
          </Field>

          <Field label="Kind" required>
            <div style={segStyle} role="radiogroup" aria-label="Provider kind">
              <SegBtn
                label="Claude Code"
                active={provider === 'claude-code'}
                onClick={() => setProvider('claude-code')}
              />
              {/* Codex isn't wired end-to-end yet — the option is in
                  place for the visual contract but stays disabled
                  until the backend session for Codex actually runs. */}
              <SegBtn
                label="Codex"
                active={provider === 'codex'}
                onClick={() => setProvider('codex')}
                disabled
                title="Codex provider coming soon"
              />
            </div>
          </Field>

          <Field label="Group" hint="optional">
            <select
              value={groupId}
              onChange={e => setGroupId(e.target.value)}
              style={selectStyle}
            >
              <option value="">— None —</option>
              {groups.map(g => (
                <option key={g.id} value={g.id}>
                  {g.glyph || '•'} {g.name}
                </option>
              ))}
            </select>
          </Field>

          <Field
            label="Title"
            required
            hint={linkedPrNumber !== null
              ? 'auto-filled from the PR · edit to override'
              : 'required — pick a PR to auto-fill'}
          >
            <input
              type="text"
              value={title}
              onChange={e => onTitleChange(e.target.value)}
              placeholder="Add tracing to the order pipeline"
              style={inputStyle}
            />
          </Field>

          <Field label="Initial prompt" hint="optional · you can send the first turn after the agent boots">
            <textarea
              value={prompt}
              onChange={e => setPrompt(e.target.value)}
              placeholder="Describe what you want the agent to do…"
              style={textareaStyle}
              rows={5}
            />
            <div style={promptHintStyle}>
              <span style={kbdStyle}>/</span> commands ·{' '}
              <span style={kbdStyle}>@</span> files ·{' '}
              <span style={kbdStyle}>⌘+↵</span> create &amp; start
            </div>
          </Field>
        </div>

        {error !== null && <div style={errorBoxStyle}>{error}</div>}
        {cloneStatus !== null && (
          <div style={mutedHintStyle} role="status">{cloneStatus}</div>
        )}

        <footer style={footerStyle}>
          <button
            type="button"
            onClick={onBack}
            disabled={submitting}
            style={secondaryBtnStyle}
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={() => void submit()}
            disabled={submitting || repos.length === 0}
            style={primaryBtnStyle}
          >
            {submitting
              ? (needsClone ? 'Cloning + starting…' : 'Starting…')
              : '+ Create & start'}
            <span style={kbdHintStyle}>⌘↵</span>
          </button>
        </footer>
      </div>
    </section>
  );
}

// ─── Subcomponents ──────────────────────────────────────────────────

function Field({
  label, required, hint, children,
}: {
  label: string;
  required?: boolean;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <div style={fieldStyle}>
      <label style={fieldLabelStyle}>
        {label}
        {required && <span style={reqStarStyle}>*</span>}
        {hint !== undefined && hint !== '' && <span style={fieldHintStyle}>{hint}</span>}
      </label>
      {children}
    </div>
  );
}

function SegBtn({
  glyph, label, active, onClick, disabled, title,
}: {
  /** Optional decorative glyph. Today only the Type segment renders
   *  one; the Kind segment uses plain labels per the latest design
   *  pass. */
  glyph?: string;
  label: string;
  active: boolean;
  onClick: () => void;
  disabled?: boolean;
  title?: string;
}) {
  return (
    <button
      type="button"
      role="radio"
      aria-checked={active}
      aria-disabled={disabled}
      disabled={disabled}
      onClick={onClick}
      title={title}
      style={{
        ...segBtnStyle,
        ...(active && !disabled ? segBtnActiveStyle : null),
        ...(disabled ? segBtnDisabledStyle : null),
      }}
    >
      {glyph !== undefined && <span aria-hidden style={segGlyphStyle}>{glyph}</span>}
      <span>{label}</span>
    </button>
  );
}

function PrDropdown({ prs, onPick, loading, error }: {
  prs: PullRequestDto[];
  onPick: (p: PullRequestDto) => void;
  /** True while the deep-link fallback is fetching a typed PR
   *  number that wasn't in the cached local list. */
  loading?: boolean;
  /** Error message from a failed deep-link fetch (typically a 404
   *  for a PR number that doesn't exist in this repo). */
  error?: string | null;
}) {
  if (prs.length === 0) {
    return (
      <div style={dropdownStyle}>
        <div style={dropdownEmptyStyle}>
          {loading
            ? 'Looking it up on GitHub…'
            : (error !== null && error !== undefined
                ? `Couldn't find that PR (${error.length > 80 ? error.slice(0, 77) + '…' : error})`
                : 'No matching open PRs.')}
        </div>
      </div>
    );
  }
  return (
    <div style={dropdownStyle}>
      {prs.map(p => (
        <button
          key={p.number}
          type="button"
          onMouseDown={() => onPick(p)}
          style={dropdownRowStyle}
        >
          <span style={{ ...prStateDotStyle, background: p.draft ? '#8c959f' : '#1a7f37' }} />
          <span style={prNumStyle}>#{p.number}</span>
          <span style={prTitleStyle}>{p.title}</span>
          <span style={{
            ...prBadgeStyle,
            ...(p.draft ? prBadgeDraftStyle : prBadgeOpenStyle),
          }}>
            {p.draft ? 'Draft' : 'Open'}
          </span>
        </button>
      ))}
    </div>
  );
}

function IssueDropdown({ issues, onPick }: {
  issues: IssueDto[];
  onPick: (i: IssueDto) => void;
}) {
  if (issues.length === 0) {
    return (
      <div style={dropdownStyle}>
        <div style={dropdownEmptyStyle}>No matching open issues.</div>
      </div>
    );
  }
  return (
    <div style={dropdownStyle}>
      {issues.map(i => (
        <button
          key={i.number}
          type="button"
          onMouseDown={() => onPick(i)}
          style={dropdownRowStyle}
        >
          <span style={{ ...prStateDotStyle, background: '#d4a72c' }} />
          <span style={prNumStyle}>#{i.number}</span>
          <span style={prTitleStyle}>{i.title}</span>
        </button>
      ))}
    </div>
  );
}

// ─── Helpers ────────────────────────────────────────────────────────

function repoKey(r: LocalRepoStatusDto | null): string {
  if (r === null) return '';
  return `${r.owner}/${r.repo}`;
}

/** Filter PRs by the search input. Accepts a `#1234` paste or a
 *  bare number as a number-match; otherwise case-insensitive title
 *  contains. Skips the already-linked PR (if any) to keep the
 *  dropdown tidy after a pick. Capped at 8 rows. */
function filterPrs(
    prs: PullRequestDto[] | null,
    search: string,
    linked: number | null): PullRequestDto[] {
  if (prs === null) return [];
  const trimmed = search.trim();
  const numMatch = trimmed.replace(/^#/, '').match(/^\d+$/);
  if (numMatch !== null) {
    const num = Number(numMatch[0]);
    return prs
      .filter(p => p.number === num || String(p.number).includes(String(num)))
      .filter(p => p.number !== linked)
      .slice(0, 8);
  }
  if (trimmed === '') {
    return prs.filter(p => p.number !== linked).slice(0, 8);
  }
  const q = trimmed.toLowerCase();
  return prs
    .filter(p => p.title.toLowerCase().includes(q))
    .filter(p => p.number !== linked)
    .slice(0, 8);
}

function filterIssues(
    issues: IssueDto[] | null,
    search: string,
    linked: number | null): IssueDto[] {
  if (issues === null) return [];
  const trimmed = search.trim();
  const numMatch = trimmed.replace(/^#/, '').match(/^\d+$/);
  if (numMatch !== null) {
    const num = Number(numMatch[0]);
    return issues
      .filter(i => i.number === num || String(i.number).includes(String(num)))
      .filter(i => i.number !== linked)
      .slice(0, 8);
  }
  if (trimmed === '') {
    return issues.filter(i => i.number !== linked).slice(0, 8);
  }
  const q = trimmed.toLowerCase();
  return issues
    .filter(i => i.title.toLowerCase().includes(q))
    .filter(i => i.number !== linked)
    .slice(0, 8);
}

// ─── Styles ─────────────────────────────────────────────────────────

const shellStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'center',
  alignItems: 'flex-start',
  padding: '40px 24px',
  minHeight: 'calc(100vh - 56px)',
  background: 'var(--bg-base)',
  overflowY: 'auto',
};
const cardStyle: React.CSSProperties = {
  width: 720,
  maxWidth: '100%',
  background: 'var(--bg-card)',
  border: '1px solid var(--border)',
  borderRadius: 12,
  padding: '24px 32px 20px',
  display: 'flex',
  flexDirection: 'column',
  gap: 16,
  boxShadow: '0 12px 32px rgba(15, 23, 42, 0.08)',
};
const headerStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'baseline',
  justifyContent: 'space-between',
  borderBottom: '1px solid var(--border-hairline)',
  paddingBottom: 12,
};
const titleH1Style: React.CSSProperties = {
  margin: 0,
  fontSize: 20,
  fontWeight: 700,
  color: 'var(--text-1)',
};
const bodyStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 16,
};

const fieldStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
};
const fieldLabelStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'baseline',
  gap: 6,
  fontSize: 11,
  fontWeight: 700,
  color: 'var(--text-3)',
  textTransform: 'uppercase',
  letterSpacing: '0.06em',
};
const reqStarStyle: React.CSSProperties = {
  color: '#d97706',
  fontWeight: 700,
};
const fieldHintStyle: React.CSSProperties = {
  fontWeight: 400,
  color: 'var(--text-3)',
  textTransform: 'none',
  letterSpacing: 0,
  fontSize: 11,
};

const segStyle: React.CSSProperties = {
  display: 'inline-flex',
  gap: 8,
};
const segBtnStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
  padding: '8px 14px',
  background: 'var(--bg-card)',
  border: '1px solid var(--border)',
  borderRadius: 6,
  fontSize: 13,
  color: 'var(--text-2)',
  cursor: 'pointer',
  font: 'inherit',
};
const segBtnActiveStyle: React.CSSProperties = {
  background: 'var(--accent-a10)',
  borderColor: 'var(--accent)',
  color: 'var(--accent)',
  fontWeight: 600,
};
const segBtnDisabledStyle: React.CSSProperties = {
  opacity: 0.45,
  cursor: 'not-allowed',
};
const segGlyphStyle: React.CSSProperties = {
  fontSize: 14,
};

const selectStyle: React.CSSProperties = {
  width: '100%',
  padding: '8px 10px',
  background: 'var(--bg-input)',
  color: 'var(--text-1)',
  border: '1px solid var(--border-input)',
  borderRadius: 6,
  fontSize: 13,
  font: 'inherit',
};
const inputStyle: React.CSSProperties = {
  width: '100%',
  padding: '8px 10px',
  background: 'var(--bg-input)',
  color: 'var(--text-1)',
  border: '1px solid var(--border-input)',
  borderRadius: 6,
  fontSize: 13,
  font: 'inherit',
};
const textareaStyle: React.CSSProperties = {
  ...inputStyle,
  resize: 'vertical',
  minHeight: 110,
  lineHeight: 1.5,
};

const searchWrapStyle: React.CSSProperties = {
  position: 'relative',
};
const dropdownStyle: React.CSSProperties = {
  position: 'absolute',
  top: 'calc(100% + 4px)',
  left: 0,
  right: 0,
  background: 'var(--bg-card)',
  border: '1px solid var(--border)',
  borderRadius: 6,
  boxShadow: '0 8px 20px rgba(15, 23, 42, 0.12)',
  zIndex: 5,
  maxHeight: 280,
  overflowY: 'auto',
  padding: '4px 0',
};
const dropdownRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  width: '100%',
  padding: '8px 12px',
  background: 'transparent',
  border: 'none',
  cursor: 'pointer',
  textAlign: 'left',
  font: 'inherit',
  color: 'var(--text-1)',
  fontSize: 13,
};
const dropdownEmptyStyle: React.CSSProperties = {
  padding: '10px 12px',
  fontSize: 12,
  color: 'var(--text-3)',
};
const prStateDotStyle: React.CSSProperties = {
  width: 8, height: 8,
  borderRadius: '50%',
  flexShrink: 0,
};
const prNumStyle: React.CSSProperties = {
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  fontSize: 12,
  color: 'var(--text-3)',
  flexShrink: 0,
};
const prTitleStyle: React.CSSProperties = {
  flex: 1,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};
const prBadgeStyle: React.CSSProperties = {
  fontSize: 10,
  fontWeight: 700,
  padding: '1px 6px',
  borderRadius: 999,
  textTransform: 'uppercase',
  letterSpacing: '0.04em',
  flexShrink: 0,
};
const prBadgeOpenStyle: React.CSSProperties = {
  background: '#dcfce7',
  color: '#166534',
};
const prBadgeDraftStyle: React.CSSProperties = {
  background: '#e5e7eb',
  color: '#4b5563',
};

const selectedPillStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  padding: '8px 12px',
  background: 'rgba(217, 119, 6, 0.08)',
  border: '1px solid rgba(217, 119, 6, 0.24)',
  borderRadius: 6,
  fontSize: 13,
  color: 'var(--text-1)',
};
const pillNumStyle: React.CSSProperties = {
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  fontSize: 12,
  color: 'var(--text-3)',
  flexShrink: 0,
};
const pillTitleStyle: React.CSSProperties = {
  flex: 1,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};
const pillCloseStyle: React.CSSProperties = {
  width: 20, height: 20,
  border: 'none',
  background: 'transparent',
  color: 'var(--text-3)',
  cursor: 'pointer',
  borderRadius: 4,
  fontSize: 11,
};

const promptHintStyle: React.CSSProperties = {
  marginTop: 6,
  fontSize: 11,
  color: 'var(--text-3)',
};
const kbdStyle: React.CSSProperties = {
  display: 'inline-block',
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  fontSize: 10,
  padding: '1px 4px',
  background: 'var(--bg-elevated)',
  border: '1px solid var(--border-hairline)',
  borderBottomWidth: 2,
  borderRadius: 3,
  color: 'var(--text-2)',
  margin: '0 2px',
};

const errorBoxStyle: React.CSSProperties = {
  padding: '8px 12px',
  background: '#fef2f2',
  color: '#991b1b',
  border: '1px solid #fca5a5',
  borderRadius: 6,
  fontSize: 12,
};
const errorHintStyle: React.CSSProperties = {
  ...errorBoxStyle,
  marginBottom: 4,
};
const mutedHintStyle: React.CSSProperties = {
  fontSize: 12,
  color: 'var(--text-3)',
};

const footerStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'flex-end',
  gap: 10,
  paddingTop: 8,
  borderTop: '1px solid var(--border-hairline)',
};
const secondaryBtnStyle: React.CSSProperties = {
  padding: '8px 16px',
  background: 'var(--bg-btn-secondary)',
  border: '1px solid var(--border-input)',
  borderRadius: 6,
  color: 'var(--text-2)',
  fontSize: 13,
  cursor: 'pointer',
};
const primaryBtnStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 8,
  padding: '8px 16px',
  background: 'var(--accent)',
  border: '1px solid var(--accent)',
  borderRadius: 6,
  color: '#fff',
  fontSize: 13,
  fontWeight: 600,
  cursor: 'pointer',
};
const kbdHintStyle: React.CSSProperties = {
  fontSize: 11,
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  background: 'rgba(255, 255, 255, 0.15)',
  padding: '1px 6px',
  borderRadius: 3,
};
