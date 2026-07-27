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
import { useEffect, useMemo, useState } from 'react';
import type { Ds4StateDto, WorkModelOptionsDto } from '../types';
import { logoColorFor, monogram } from '../pages/useWorkspaceNav';
import { Logo } from '../ui/primitives';
import { WS_DIALOG_OVERLAY, WS_DIALOG_PANEL, dialogStyles } from './dialogStyles';
import { type AgentChoice, choiceGlyph, choiceText, choicesFrom, normalizeChoice } from './agentChoices';
import {
  type DirectoryScopeOverviewDto,
  type WorkspaceRepositoryDto,
  type WorkspaceSettingsDto,
  workspaceApi,
} from './workspaceApi';

type Props = {
  /** Close without taking any action — fired on Cancel and the
   *  backdrop. */
  onClose: () => void;
  /** Open the freshly-created thread in the detail view. The dialog
   *  fires this once createTask returns; the parent owns navigation. */
  onCreated: (threadId: string) => void;
  /** Pre-pin the new thread into a group when the dialog is opened
   *  from a group's `+ Add` button. Defaults to no group. */
  initialGroupId?: string;
  /** Active workspace's id — the trunk lands here, and both the repo
   *  and the inherited agent config are read off it. */
  workspaceId: string;
  /** Active workspace's display name — surfaces on the dialog chip
   *  + the "inheriting X defaults" hint so a trunk created from
   *  workspace X doesn't show "ByteQuay" everywhere. */
  workspaceName: string;
};

/** The four session kinds a trunk spawns, in the order the workspace
 *  Agents tab lists them. `audience` is the wire key shared with the
 *  backend's SessionAudience and the workspace `providers` map. */
const KINDS = [
  { audience: 'plan', chip: 'plan', desc: 'Deep reasoning for specs & plans', fg: '#8250df', bg: 'rgba(130,80,223,0.10)' },
  { audience: 'dev', chip: 'dev', desc: 'Code writing & tests', fg: '#0969da', bg: 'rgba(9,105,218,0.10)' },
  { audience: 'review', chip: 'review', desc: 'PR review rounds', fg: '#1a7f37', bg: 'rgba(45,164,78,0.14)' },
  { audience: 'ci-fix', chip: 'ci fix', desc: 'Cheap loops on red builds', fg: '#cf222e', bg: 'rgba(207,34,46,0.10)' },
] as const;

/** What the backend falls back to when the workspace configured no
 *  engine at all (WorkModelCatalog's first CLI agent). Kept in sync by
 *  hand — a mismatch only mislabels the row, it can't change what runs. */
const CURATED_DEFAULT = 'cli:claude-code';

/**
 * Workspace-scoped new-trunk modal: a short name, an optional remark,
 * code-area selection, and an AGENTS table showing which engine each
 * session kind will run on.
 *
 * <p>The repo is fixed by the workspace (one workspace, one repo), so
 * it reads as a caption rather than a picker. The agent rows inherit
 * the workspace Agents config and can be pinned per session kind for
 * this trunk only — the pins ride along on the create call and the
 * resolver honours them for every session under the trunk. With no
 * agent available at all, creation is blocked up front rather than
 * failing after submit.
 */
function NewThreadDialog({ onClose, onCreated, initialGroupId, workspaceId, workspaceName }: Props) {
  const wsLabel = workspaceName.length > 0 ? workspaceName : 'Workspace';
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [repoName, setRepoName] = useState<string | null>(null);
  const [providers, setProviders] = useState<Record<string, string> | null>(null);
  const [options, setOptions] = useState<WorkModelOptionsDto | null>(null);
  const [localAi, setLocalAi] = useState<Ds4StateDto | null>(null);
  const [codeAreas, setCodeAreas] = useState<DirectoryScopeOverviewDto | null>(null);
  const [selectedCodeArea, setSelectedCodeArea] = useState<string | null>(null);
  const [approvingCodeArea, setApprovingCodeArea] = useState<string | null>(null);
  /** Per-audience pins for this trunk only. An absent key inherits. */
  const [pins, setPins] = useState<Record<string, string>>({});
  const [openKind, setOpenKind] = useState<string | null>(null);
  const [checking, setChecking] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    void Promise.all([
      workspaceApi.repository(workspaceId).catch((): WorkspaceRepositoryDto | null => null),
      workspaceApi.settings(workspaceId).catch((): WorkspaceSettingsDto | null => null),
      workspaceApi.workModelOptions(),
      window.bridge.getDs4Status(),
      workspaceApi.directoryScopeSuggestions(workspaceId)
        .catch((): DirectoryScopeOverviewDto | null => null),
    ])
      .then(([repository, settings, modelOptions, ds4, scopes]) => {
        if (cancelled) return;
        setRepoName(repository?.fullName ?? null);
        setProviders(settings?.providers ?? {});
        setOptions(modelOptions);
        setLocalAi(ds4.state);
        setCodeAreas(scopes);
      })
      .catch(reason => {
        if (!cancelled) setError(reason instanceof Error ? reason.message : String(reason));
      });
    return () => { cancelled = true; };
  }, [workspaceId]);

  const choices = useMemo(() => choicesFrom(options, localAi), [localAi, options]);
  const loading = options === null || providers === null;
  // Nothing installed, authed, or keyed: every kind would resolve to an
  // agent that can't run, so the create is refused up front.
  const blocked = !loading && choices.every(choice => choice.disabled);
  const trimmedName = name.trim();
  const nameWordCount = wordCount(trimmedName);
  const nameInvalid = trimmedName.length === 0 || nameWordCount > 7;

  /** The engine the workspace says this kind runs on: the kind's own
   *  pick, else the workspace default, else the curated fallback. */
  const workspacePick = (audience: string): string => {
    const own = providers?.[audience];
    const fallback = providers?.default;
    const raw = own !== undefined && own.trim() !== '' ? own : fallback;
    return raw === undefined || raw.trim() === '' ? CURATED_DEFAULT : normalizeChoice(raw);
  };

  const recheck = async () => {
    setChecking(true);
    try {
      const [nextOptions, ds4] = await Promise.all([
        workspaceApi.refreshWorkModelOptions(),
        window.bridge.getDs4Status(),
      ]);
      setOptions(nextOptions);
      setLocalAi(ds4.state);
    }
    catch (reason) {
      setError(reason instanceof Error ? reason.message : String(reason));
    }
    finally {
      setChecking(false);
    }
  };

  const openAgentSettings = () => {
    window.location.hash = `#/workspace/${encodeURIComponent(workspaceId)}/settings/agents`;
    onClose();
  };

  const selectCodeArea = async (path: string, decisionState: string) => {
    if (approvingCodeArea !== null) return;
    if (decisionState === 'approved') {
      setSelectedCodeArea(path);
      return;
    }
    setApprovingCodeArea(path);
    setError(null);
    try {
      await workspaceApi.decideDirectoryScope(workspaceId, path, 'approved');
      setCodeAreas(current => current === null ? null : {
        ...current,
        suggestions: current.suggestions.map(suggestion =>
          suggestion.paths[0] === path
            ? { ...suggestion, decisionState: 'approved' }
            : suggestion),
      });
      setSelectedCodeArea(path);
    }
    catch (reason) {
      setError(reason instanceof Error ? reason.message : String(reason));
    }
    finally {
      setApprovingCodeArea(null);
    }
  };

  const handleSubmit = async () => {
    const trimmedDescription = description.trim();
    if (submitting || blocked || approvingCodeArea !== null || nameInvalid) return;
    setSubmitting(true);
    setError(null);
    try {
      // 0-Task thread: no workingDir, no branchName. The short name is
      // the title; description is a persisted remark, not a queued turn.
      // No engine on the request beyond the pins: the backend stamps
      // the thread from the workspace's plan engine otherwise. Sending
      // one here would only put a second, drifting answer on the row.
      const created = await window.bridge.createTask({
        kind: 'CLI_AGENT',
        workspaceId,
        title: trimmedName,
        description: trimmedDescription === '' ? undefined : trimmedDescription,
        initialGroupIds: initialGroupId !== undefined ? [initialGroupId] : undefined,
        engines: Object.keys(pins).length === 0 ? undefined : pins,
      });
      if (selectedCodeArea !== null) {
        // Scope is advisory metadata. A failure must not strand or
        // duplicate the trunk that was already created successfully.
        try {
          await workspaceApi.assignDirectoryScope(workspaceId, created.id, selectedCodeArea);
        }
        catch (reason) {
          console.warn('Could not attach the selected code area', reason);
        }
      }
      onCreated(created.id);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setSubmitting(false);
    }
  };

  const pinCount = Object.keys(pins).length;

  return (
    <div
      style={WS_DIALOG_OVERLAY}
      onClick={onClose}
      role="presentation"
    >
      <div
        style={WS_DIALOG_PANEL}
        role="dialog"
        aria-modal="true"
        aria-labelledby="new-thread-title"
        onClick={e => e.stopPropagation()}
      >
        <header style={dialogStyles.header}>
          <h2 id="new-thread-title" style={dialogStyles.title}>
            New trunk
            <span style={{ ...dialogStyles.workspaceChip, display: 'inline-flex', alignItems: 'center', gap: 6 }}>
              <Logo initials={monogram(wsLabel).toUpperCase()} color={logoColorFor(wsLabel)} size="sm" />
              {wsLabel}
            </span>
          </h2>
          <button
            type="button"
            style={dialogStyles.closeBtn}
            onClick={onClose}
            aria-label="Close"
          >
            ✕
          </button>
        </header>

        <section style={trunkDefinitionStyle} aria-labelledby="trunk-definition-title">
          <h3 id="trunk-definition-title" style={trunkDefinitionTitleStyle}>
            What&apos;s a trunk?
          </h3>
          <p style={trunkDefinitionBodyStyle}>
            A trunk is a focused, long-lived workspace for developing one component or system.
            Keep it scoped instead of mixing unrelated development.
          </p>
          <p style={trunkDefinitionFutureStyle}>
            Map it to a stable area—such as Core engine or Iceberg connector. Each trunk will
            have its own memory and decision markers in the future.
          </p>
        </section>

        <div style={repoLineStyle}>
          <span aria-hidden>📕</span>
          <span>{repoName ?? '—'}</span>
          <span style={repoLineMutedStyle}>· set by the workspace</span>
        </div>

        <div style={dialogStyles.field}>
          <label htmlFor="trunk-name" style={dialogStyles.fieldLabel}>Trunk name</label>
          <input
            id="trunk-name"
            value={name}
            onChange={event => setName(event.target.value)}
            placeholder="e.g. Core engine"
            style={{
              ...dialogStyles.input,
              borderColor: nameWordCount > 7 ? '#cf222e' : 'var(--ws-card-border)',
            }}
            aria-invalid={nameWordCount > 7}
            aria-describedby="trunk-name-help"
          />
          <div id="trunk-name-help" style={fieldHelpStyle(nameWordCount > 7)}>
            <span>{nameWordCount > 7 ? 'Use 7 words or fewer.' : 'Required · 7 words maximum'}</span>
            <span>{nameWordCount}/7 words</span>
          </div>
        </div>

        {codeAreas !== null && (
          <section style={codeAreaSectionStyle} aria-labelledby="code-area-label">
            <div style={codeAreaHeadingStyle}>
              <span id="code-area-label">CODE AREA <span style={codeAreaOptionalStyle}>(optional)</span></span>
              {!codeAreas.historyReady && (
                <span style={codeAreaProgressStyle}>
                  {codeAreas.requiredAnalyzedPrCount === 0
                    ? 'Learning project history'
                    : `Learning · ${codeAreas.analyzedPrCount}/${codeAreas.requiredAnalyzedPrCount} analyzed PRs`}
                </span>
              )}
            </div>
            <div style={codeAreaChoicesStyle}>
              <button
                type="button"
                aria-pressed={selectedCodeArea === null}
                disabled={approvingCodeArea !== null}
                style={codeAreaChoiceStyle(selectedCodeArea === null)}
                onClick={() => setSelectedCodeArea(null)}
              >
                <span style={codeAreaRadioStyle(selectedCodeArea === null)} aria-hidden />
                <span>
                  <span style={codeAreaNameStyle}>Entire repository</span>
                  <span style={codeAreaDetailStyle}>Default · shared changes remain in view</span>
                </span>
              </button>
              {codeAreas.suggestions
                .filter(suggestion => suggestion.decisionState !== 'rejected')
                .map(suggestion => {
                  const path = suggestion.paths[0];
                  if (path === undefined) return null;
                  const selected = selectedCodeArea === path;
                  const approving = approvingCodeArea === path;
                  return (
                    <button
                      key={path}
                      type="button"
                      aria-pressed={selected}
                      disabled={approvingCodeArea !== null}
                      style={codeAreaChoiceStyle(selected)}
                      title={suggestion.rationale}
                      onClick={() => { void selectCodeArea(path, suggestion.decisionState); }}
                    >
                      <span style={codeAreaRadioStyle(selected)} aria-hidden />
                      <span>
                        <span style={codeAreaNameStyle}>{path}</span>
                        <span style={codeAreaDetailStyle}>
                          {suggestion.evidencePrCount} analyzed PRs
                          {suggestion.decisionState === 'pending'
                            ? ` · ${approving ? 'Approving…' : 'Approve & use'}`
                            : ' · Approved'}
                        </span>
                      </span>
                    </button>
                  );
                })}
            </div>
          </section>
        )}

        <div style={dialogStyles.field}>
          <label htmlFor="trunk-description" style={dialogStyles.fieldLabel}>
            Description <span style={codeAreaOptionalStyle}>(optional)</span>
          </label>
          <textarea
            id="trunk-description"
            value={description}
            onChange={event => setDescription(event.target.value)}
            placeholder="Add a remark about this trunk"
            style={dialogStyles.textarea}
          />
          <div style={fieldHelpStyle(false)}>Shown when the trunk name is hovered.</div>
        </div>

        <div style={dialogStyles.chipRow}>
          <button type="button" style={dialogStyles.chip} disabled>📎 Add files</button>
          <button type="button" style={dialogStyles.chip} disabled>↗ Reference a PR</button>
          <button type="button" style={dialogStyles.chip} disabled>✦ Skills</button>
        </div>

        <div style={trunkHintRowStyle}>
          <span style={trunkHintBulletStyle} aria-hidden>●</span>
          <span style={trunkHintTextStyle}>
            Plan here, steer your wild horse.
          </span>
        </div>

        <div style={agentsHeaderStyle}>
          <span style={agentsLabelStyle}>AGENTS</span>
          <span style={{ ...agentsNoteStyle, color: blocked ? '#cf222e' : pinCount > 0 ? '#9a6700' : 'var(--ws-text-4)' }}>
            {blocked
              ? 'nothing to inherit — workspace has none configured'
              : loading
                ? 'reading workspace defaults…'
                : pinCount === 0
                  ? `inheriting ${wsLabel} defaults`
                  : `${pinCount} overridden for this trunk`}
          </span>
          <span style={{ flex: 1 }} />
          {pinCount > 0 && !blocked && (
            <button
              type="button"
              style={agentsActionStyle}
              title="Drop this trunk's overrides"
              onClick={() => { setPins({}); setOpenKind(null); }}
            >
              ↺ Reset
            </button>
          )}
          <button
            type="button"
            style={agentsActionStyle}
            title="Re-check availability"
            disabled={checking}
            onClick={() => { void recheck(); }}
          >
            ↻ {checking ? 'Checking…' : 'Check'}
          </button>
        </div>

        <div style={{ ...agentsTableStyle, background: blocked ? 'rgba(0,0,0,0.015)' : 'transparent' }}>
          {KINDS.map((kind, index) => (
            <AgentRow
              key={kind.audience}
              kind={kind}
              first={index === 0}
              blocked={blocked}
              choices={choices}
              defaultChoice={workspacePick(kind.audience)}
              pinned={pins[kind.audience]}
              menuOpen={openKind === kind.audience}
              onToggleMenu={() => setOpenKind(openKind === kind.audience ? null : kind.audience)}
              onPick={value => {
                setPins(current => {
                  const next = { ...current };
                  if (value === null || value === workspacePick(kind.audience)) delete next[kind.audience];
                  else next[kind.audience] = value;
                  return next;
                });
                setOpenKind(null);
              }}
              onManage={openAgentSettings}
            />
          ))}
        </div>

        {blocked && (
          <div style={blockedBannerStyle}>
            <span style={{ color: '#cf222e' }} aria-hidden>⚠</span>
            <div>
              <div style={blockedTitleStyle}>No agents in {wsLabel}</div>
              <div style={blockedBodyStyle}>
                A trunk can&apos;t be created without at least one agent — every
                session kind resolves to nothing. Install a CLI agent or add an API key in
                the workspace&apos;s Agents settings, then check again.
              </div>
              <div style={blockedActionsStyle}>
                <button type="button" style={dialogStyles.secondaryBtn} onClick={openAgentSettings}>
                  Open Agents settings
                </button>
                <button type="button" style={dialogStyles.secondaryBtn} disabled={checking}
                  onClick={() => { void recheck(); }}>
                  {checking ? 'Checking…' : 'Check again'}
                </button>
              </div>
            </div>
          </div>
        )}

        {error !== null && (
          <div style={errorBannerStyle}>{error}</div>
        )}

        <footer style={dialogStyles.footer}>
          <div style={{ ...dialogStyles.footerNote, color: blocked ? '#cf222e' : undefined }}>
            {blocked
              ? "Can't create — no agent to run the first session."
              : <>Lands on the trunk · inherits {wsLabel}&apos;s memory &amp; skills</>}
          </div>
          <div style={dialogStyles.footerButtons}>
            <button type="button" style={dialogStyles.secondaryBtn} onClick={onClose}>
              Cancel
            </button>
            <button
              type="button"
              style={dialogStyles.primaryBtn}
              onClick={() => { void handleSubmit(); }}
              disabled={submitting || loading || blocked || approvingCodeArea !== null || nameInvalid}
              title={blocked
                ? 'Add an agent in workspace settings first'
                : nameInvalid
                  ? 'Enter a trunk name of 7 words or fewer'
                  : 'Create the trunk'}
            >
              {submitting ? 'Creating…' : 'Create trunk'} <span style={{ marginLeft: 4 }}>⏎</span>
            </button>
          </div>
        </footer>
      </div>
    </div>
  );
}

function wordCount(value: string): number {
  return value === '' ? 0 : value.split(/\s+/).length;
}

/** One session kind: what it is, which engine it lands on, and the
 *  picker that pins a different one for this trunk. */
function AgentRow({
  kind, first, blocked, choices, defaultChoice, pinned, menuOpen, onToggleMenu, onPick, onManage,
}: {
  kind: (typeof KINDS)[number];
  first: boolean;
  blocked: boolean;
  choices: AgentChoice[];
  defaultChoice: string;
  pinned: string | undefined;
  menuOpen: boolean;
  onToggleMenu: () => void;
  onPick: (value: string | null) => void;
  onManage: () => void;
}) {
  const effective = pinned ?? defaultChoice;
  const choice = choices.find(row => row.value === effective);
  const label = choice === undefined ? effective : choice.label;
  const defaultLabel = choices.find(row => row.value === defaultChoice)?.label ?? defaultChoice;

  return (
    <div style={{
      ...agentRowStyle,
      borderTop: first ? 'none' : '1px solid var(--ws-card-border)',
      zIndex: menuOpen ? 2 : 1,
    }}
    >
      <span style={{ ...kindChipStyle, color: kind.fg, background: kind.bg }}>{kind.chip}</span>
      <span style={agentRowTextStyle}>
        <span style={{ color: blocked ? 'var(--ws-text-4)' : 'var(--ws-text-1)' }}>{kind.desc}</span>
        <span style={{ fontSize: 10, color: blocked ? '#cf222e' : pinned !== undefined ? '#9a6700' : 'var(--ws-text-4)' }}>
          {blocked
            ? 'no agent can serve this kind'
            : pinned !== undefined
              ? `this trunk only — workspace default is ${defaultLabel}`
              : 'from workspace defaults'}
        </span>
      </span>
      {pinned !== undefined && !blocked && (
        <span style={overrideChipStyle}>
          override
          <button
            type="button"
            style={overrideChipClearStyle}
            title="Back to workspace default"
            aria-label={`Clear ${kind.chip} override`}
            onClick={() => onPick(null)}
          >
            ✕
          </button>
        </span>
      )}
      <span style={{ position: 'relative', flexShrink: 0 }}>
        <button
          type="button"
          style={{ ...agentPillStyle, borderStyle: blocked ? 'dashed' : 'solid', cursor: blocked ? 'not-allowed' : 'pointer' }}
          disabled={blocked}
          aria-haspopup="listbox"
          aria-expanded={menuOpen}
          title={blocked
            ? 'No agent installed for this workspace'
            : 'Override the workspace agent for this trunk'}
          onClick={onToggleMenu}
        >
          <span style={{ ...agentGlyphStyle, opacity: blocked ? 0.4 : 1 }}>
            {blocked ? '·' : choiceGlyph(effective)}
          </span>
          <span style={agentPillNameStyle}>{blocked ? 'none available' : label}</span>
          <span style={{ color: 'var(--ws-text-4)', fontSize: 9 }}>▾</span>
        </button>
        {menuOpen && (
          <>
            <div style={pickerScrimStyle} onClick={onToggleMenu} />
            <ul style={pickerMenuStyle} role="listbox">
              {choices.map(row => (
                <li key={row.value}>
                  <button
                    type="button"
                    style={pickerItemStyle(row.value === effective, row.disabled === true)}
                    disabled={row.disabled}
                    onClick={() => onPick(row.value)}
                  >
                    <span style={agentGlyphStyle}>{choiceGlyph(row.value)}</span>
                    <span style={pickerItemTitleStyle}>{choiceText(row)}</span>
                    {row.value === defaultChoice && (
                      <span style={pickerItemBadgeStyle}>default</span>
                    )}
                    {row.value === effective && <span style={{ color: 'var(--ws-accent)' }}>✓</span>}
                  </button>
                </li>
              ))}
              <li>
                <button type="button" style={pickerItemStyle(false, false)} onClick={onManage}>
                  <span style={pickerItemTitleStyle}>⚙ Manage workspace agents</span>
                </button>
              </li>
            </ul>
          </>
        )}
      </span>
    </div>
  );
}

const repoLineStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  fontSize: 11,
  color: 'var(--ws-text-3)',
};

const trunkDefinitionStyle: React.CSSProperties = {
  marginBottom: 12,
  padding: '10px 12px',
  border: '1px solid var(--ws-card-border)',
  borderRadius: 10,
  background: 'var(--ws-accent-soft)',
};

const trunkDefinitionTitleStyle: React.CSSProperties = {
  margin: 0,
  color: 'var(--ws-text-1)',
  fontSize: 12,
  fontWeight: 700,
};

const trunkDefinitionBodyStyle: React.CSSProperties = {
  margin: '5px 0 0',
  color: 'var(--ws-text-2)',
  fontSize: 11,
  lineHeight: 1.45,
};

const trunkDefinitionFutureStyle: React.CSSProperties = {
  margin: '4px 0 0',
  color: 'var(--ws-text-3)',
  fontSize: 10.5,
  lineHeight: 1.45,
};

const repoLineMutedStyle: React.CSSProperties = {
  color: 'var(--ws-text-4)',
};

function fieldHelpStyle(error: boolean): React.CSSProperties {
  return {
    display: 'flex',
    justifyContent: 'space-between',
    gap: 8,
    marginTop: 4,
    color: error ? '#cf222e' : 'var(--ws-text-4)',
    fontSize: 10,
  };
}

const codeAreaSectionStyle: React.CSSProperties = {
  marginTop: 12,
};

const codeAreaHeadingStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  fontSize: 10,
  letterSpacing: '0.08em',
  color: 'var(--ws-text-3)',
};

const codeAreaOptionalStyle: React.CSSProperties = {
  letterSpacing: 0,
  color: 'var(--ws-text-4)',
};

const codeAreaProgressStyle: React.CSSProperties = {
  marginLeft: 'auto',
  letterSpacing: 0,
  color: 'var(--ws-text-4)',
};

const codeAreaChoicesStyle: React.CSSProperties = {
  display: 'flex',
  gap: 7,
  marginTop: 6,
  overflowX: 'auto',
  paddingBottom: 2,
};

function codeAreaChoiceStyle(selected: boolean): React.CSSProperties {
  return {
    display: 'flex',
    alignItems: 'center',
    gap: 7,
    minWidth: 155,
    padding: '7px 9px',
    border: `1px solid ${selected ? 'var(--ws-accent)' : 'var(--ws-card-border)'}`,
    borderRadius: 8,
    background: selected ? 'var(--ws-accent-soft)' : 'rgba(255,255,255,0.7)',
    color: 'var(--ws-text-1)',
    cursor: 'pointer',
    textAlign: 'left',
    fontFamily: 'inherit',
    flexShrink: 0,
  };
}

function codeAreaRadioStyle(selected: boolean): React.CSSProperties {
  return {
    width: 9,
    height: 9,
    borderRadius: '50%',
    border: `1px solid ${selected ? 'var(--ws-accent)' : 'var(--ws-text-4)'}`,
    background: selected ? 'var(--ws-accent)' : 'transparent',
    boxShadow: selected ? 'inset 0 0 0 2px white' : 'none',
    flexShrink: 0,
  };
}

const codeAreaNameStyle: React.CSSProperties = {
  display: 'block',
  fontSize: 11,
  fontWeight: 600,
};

const codeAreaDetailStyle: React.CSSProperties = {
  display: 'block',
  marginTop: 1,
  fontSize: 9.5,
  color: 'var(--ws-text-4)',
  whiteSpace: 'nowrap',
};

const pickerScrimStyle: React.CSSProperties = {
  position: 'fixed',
  inset: 0,
  zIndex: 1,
  background: 'transparent',
};

const pickerMenuStyle: React.CSSProperties = {
  position: 'absolute',
  zIndex: 2,
  top: 'calc(100% + 6px)',
  right: 0,
  minWidth: 250,
  margin: 0,
  padding: 6,
  listStyle: 'none',
  background: '#fff',
  border: '1px solid rgba(0,0,0,0.10)',
  borderRadius: 10,
  boxShadow: '0 12px 28px rgba(0,0,0,0.18)',
  maxHeight: 240,
  overflowY: 'auto',
};

function pickerItemStyle(active: boolean, disabled: boolean): React.CSSProperties {
  return {
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    width: '100%',
    padding: '6px 8px',
    border: 'none',
    background: active ? 'var(--ws-accent-soft)' : 'transparent',
    color: disabled ? 'var(--ws-text-4)' : 'var(--ws-text-1)',
    fontSize: 11,
    borderRadius: 6,
    cursor: disabled ? 'not-allowed' : 'pointer',
    textAlign: 'left',
    fontFamily: 'inherit',
  };
}

const pickerItemTitleStyle: React.CSSProperties = {
  flex: 1,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};

const pickerItemBadgeStyle: React.CSSProperties = {
  fontSize: 9,
  letterSpacing: '0.04em',
  padding: '1px 6px',
  background: 'rgba(0,0,0,0.06)',
  borderRadius: 999,
  color: 'var(--ws-text-4)',
  flexShrink: 0,
};

const errorBannerStyle: React.CSSProperties = {
  marginTop: 10,
  padding: '8px 12px',
  fontSize: 11,
  color: '#991b1b',
  background: '#fee2e2',
  border: '1px solid #fecaca',
  borderRadius: 8,
};

const trunkHintRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'flex-start',
  gap: 8,
  marginTop: 14,
  fontSize: 11,
  color: 'var(--ws-text-3)',
  lineHeight: 1.55,
};

const trunkHintBulletStyle: React.CSSProperties = {
  color: 'var(--ws-accent)',
  fontSize: 10,
  marginTop: 2,
  flexShrink: 0,
};

const trunkHintTextStyle: React.CSSProperties = {
  flex: 1,
};

const agentsHeaderStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  marginTop: 16,
};

const agentsLabelStyle: React.CSSProperties = {
  fontSize: 10,
  letterSpacing: '0.09em',
  color: 'var(--ws-text-3)',
};

const agentsNoteStyle: React.CSSProperties = {
  fontSize: 10,
};

const agentsActionStyle: React.CSSProperties = {
  padding: '2px 8px',
  fontSize: 10,
  border: '1px solid var(--ws-card-border)',
  borderRadius: 7,
  background: 'rgba(255,255,255,0.86)',
  color: 'var(--ws-text-3)',
  cursor: 'pointer',
  fontFamily: 'inherit',
};

const agentsTableStyle: React.CSSProperties = {
  marginTop: 8,
  border: '1px solid var(--ws-card-border)',
  borderRadius: 10,
};

const agentRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 10,
  padding: '8px 10px',
  position: 'relative',
};

const kindChipStyle: React.CSSProperties = {
  width: 52,
  textAlign: 'center',
  padding: '2px 0',
  borderRadius: 999,
  fontSize: 9.5,
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  flexShrink: 0,
};

const agentRowTextStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  display: 'flex',
  flexDirection: 'column',
  gap: 1,
  fontSize: 12,
  overflow: 'hidden',
};

const overrideChipStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 3,
  fontSize: 9,
  color: '#9a6700',
  background: '#fff8c5',
  border: '1px solid rgba(212,167,44,0.4)',
  borderRadius: 999,
  padding: '1px 3px 1px 7px',
  flexShrink: 0,
};

const overrideChipClearStyle: React.CSSProperties = {
  border: 'none',
  background: 'transparent',
  color: '#9a6700',
  cursor: 'pointer',
  fontSize: 9,
  padding: '0 2px',
  fontFamily: 'inherit',
};

const agentPillStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
  width: 170,
  padding: '4px 7px',
  border: '1px solid var(--ws-card-border)',
  borderRadius: 8,
  background: 'rgba(255,255,255,0.86)',
  color: 'var(--ws-text-1)',
  fontSize: 11,
  fontFamily: 'inherit',
};

const agentGlyphStyle: React.CSSProperties = {
  width: 16,
  height: 16,
  borderRadius: 5,
  background: 'var(--ws-accent-soft)',
  color: 'var(--ws-accent-deep)',
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  fontSize: 9,
  flexShrink: 0,
};

const agentPillNameStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  textAlign: 'left',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};

const blockedBannerStyle: React.CSSProperties = {
  display: 'flex',
  gap: 10,
  marginTop: 10,
  padding: '10px 12px',
  border: '1px solid rgba(207,34,46,0.28)',
  background: '#fff8f7',
  borderRadius: 10,
};

const blockedTitleStyle: React.CSSProperties = {
  fontSize: 12,
  color: 'var(--ws-text-1)',
};

const blockedBodyStyle: React.CSSProperties = {
  marginTop: 6,
  fontSize: 11,
  color: 'var(--ws-text-3)',
  lineHeight: 1.5,
};

const blockedActionsStyle: React.CSSProperties = {
  display: 'flex',
  gap: 7,
  marginTop: 8,
};

export default NewThreadDialog;
