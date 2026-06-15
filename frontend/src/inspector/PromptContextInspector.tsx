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
import { useCallback, useEffect, useMemo, useState } from 'react';
import type { AssembledContextDto } from '../types';
import { MarkdownProse } from '../threads/MarkdownProse';
import {
  DARK_JSON_THEME,
  JSON_PRE_STYLE,
  LIGHT_JSON_THEME,
  extractJsonName,
  highlightJson,
  prettyJson,
} from './prettyJson';

type Section = AssembledContextDto['sections'][number];
type SectionKind = Section['kind'];
type Wire = AssembledContextDto['wire'];

type Props = {
  scope: 'TRUNK' | 'TASK';
  /** Thread id; required for both scopes. */
  threadId: string;
  /** Task id; required for TASK scope, ignored for TRUNK. */
  taskId?: string;
  /** Fires when the user closes the inspector — Esc, the close
   *  button, or backdrop click. The host page unmounts in
   *  response. */
  onClose: () => void;
};

/** Default-active section. Long stacked "All" pages are a UX
 *  failure — Brain is the most informative single panel and the
 *  one users open the inspector for. */
const DEFAULT_KIND: SectionKind = 'BRAIN';

/** Color stripe per section kind — mirrors the spec mockup so the
 *  left-nav dots, the section header bar, and the inline
 *  /* … *​/ labels in the full-request view all match. */
const SECTION_COLOUR: Record<SectionKind, string> = {
  TOOLS: '#4a3aff',
  ROLE: '#8b2db8',
  BRAIN: '#0e6c4f',
  CONCEPT_PREAMBLE: '#b56f00',
  SKILL_MANIFEST: '#c43e8b',
  MEMORY: '#1f5fbf',
  HISTORY: '#5b5b78',
  NEW_TURN: '#000',
};

function PromptContextInspector({ scope, threadId, taskId, onClose }: Props) {
  const [context, setContext] = useState<AssembledContextDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [activeKind, setActiveKind] = useState<SectionKind>(DEFAULT_KIND);
  const [view, setView] = useState<'section' | 'wire'>('section');
  const [copied, setCopied] = useState(false);
  // The provenance chip list can run to hundreds of entries (one per
  // folded history message), which otherwise buries the section body.
  // Collapsed by default; expand to audit individual sources.
  const [sourcesExpanded, setSourcesExpanded] = useState(false);

  const load = useCallback(async () => {
    setError(null);
    try {
      const fresh = scope === 'TRUNK'
        ? await window.bridge.getThreadContext(threadId)
        : await window.bridge.getTaskContext(threadId, taskId!);
      setContext(fresh);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [scope, threadId, taskId]);

  useEffect(() => { void load(); }, [load]);

  // Re-collapse the provenance list when switching sections so a 200-chip
  // history pane doesn't carry its expanded state onto the next section.
  useEffect(() => { setSourcesExpanded(false); }, [activeKind]);

  // Esc to close. ⌘. is wired at the host page level so the
  // shortcut still works when the inspector hasn't mounted yet.
  useEffect(() => {
    const onKey = (ev: KeyboardEvent) => {
      if (ev.key === 'Escape') {
        onClose();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const activeSection = useMemo(() => {
    if (context === null) return null;
    return context.sections.find(s => s.kind === activeKind) ?? null;
  }, [context, activeKind]);

  const wireNodes = useMemo(() => {
    if (context === null) return null;
    return renderWire(context);
  }, [context]);

  const handleCopy = async () => {
    if (context === null) return;
    try {
      await navigator.clipboard.writeText(JSON.stringify(context.wire, null, 2));
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1500);
    }
    catch {
      // Clipboard write can fail on a sandbox / permissions issue;
      // fall back to a textarea select.
      const textarea = document.createElement('textarea');
      textarea.value = JSON.stringify(context.wire, null, 2);
      document.body.appendChild(textarea);
      textarea.select();
      document.execCommand('copy');
      document.body.removeChild(textarea);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1500);
    }
  };

  return (
    <div style={backdropStyle} onClick={onClose} role="presentation">
      <aside
        style={drawerStyle}
        onClick={ev => ev.stopPropagation()}
        aria-label="Prompt context inspector"
      >
        <header style={headerStyle}>
          <div>
            <div style={headerTitleStyle}>Prompt context</div>
            <div style={headerSubtitleStyle}>
              {scope === 'TRUNK' ? 'Trunk turn' : 'Task turn'} ·{' '}
              {context !== null ? `${context.meta.totalTokens.toLocaleString()} tokens` : '—'}
              {context !== null && context.meta.cacheHitPredicted && ' · prefix cache predicted'}
            </div>
          </div>
          <div style={headerActionsStyle}>
            <div style={segmentedControlStyle} role="tablist" aria-label="View">
              <button
                type="button"
                role="tab"
                aria-selected={view === 'section'}
                style={view === 'section' ? segmentActiveStyle : segmentStyle}
                onClick={() => setView('section')}
              >
                Sections
              </button>
              <button
                type="button"
                role="tab"
                aria-selected={view === 'wire'}
                style={view === 'wire' ? segmentActiveStyle : segmentStyle}
                onClick={() => setView('wire')}
                title="See the full assembled prompt that would be sent"
              >
                <span aria-hidden style={{ marginRight: 4 }}>⇄</span>
                Full request
              </button>
            </div>
            <button type="button" style={toggleStyle} onClick={() => void load()}>
              Refresh
            </button>
            <button type="button" style={toggleStyle} onClick={() => void handleCopy()}>
              {copied ? 'Copied!' : 'Copy as JSON'}
            </button>
            <button type="button" style={closeStyle} onClick={onClose} aria-label="Close">
              ×
            </button>
          </div>
        </header>

        {error !== null && <div style={errorStyle} role="alert">{error}</div>}

        {context === null && error === null && (
          <div style={placeholderStyle}>Loading…</div>
        )}

        {context !== null && view === 'wire' && (
          <div style={wireContainerStyle}>
            <div style={bannerStyle}>
              This is a view, not a send. Nothing is dispatched. The provider
              serialises top-to-bottom: tools → system → messages. The order
              must be byte-stable or the prefix cache breaks.
            </div>
            <pre style={wirePreStyle}>{wireNodes}</pre>
          </div>
        )}

        {context !== null && view === 'section' && (
          <div style={splitStyle}>
            <nav style={navStyle} aria-label="Section list">
              {context.sections.map(section => {
                const active = section.kind === activeKind;
                return (
                  <button
                    key={section.kind}
                    type="button"
                    onClick={() => setActiveKind(section.kind)}
                    style={active ? activeNavItemStyle : navItemStyle}
                  >
                    <span
                      style={{
                        ...dotStyle,
                        background: SECTION_COLOUR[section.kind],
                      }}
                      aria-hidden
                    />
                    <span style={navLabelStyle}>{section.label}</span>
                    <span style={navTokenStyle}>{section.tokenCount.toLocaleString()}</span>
                  </button>
                );
              })}
              {/* Views divider — the wire view is reachable from the
                  header toggle too; surfacing it here as well so the
                  user scanning the section list doesn't have to look
                  up to find it. */}
              <div style={navDividerStyle} aria-hidden>Views</div>
              <button
                type="button"
                onClick={() => setView('wire')}
                style={navItemStyle}
                title="See the full assembled prompt that would be sent (read-only)"
              >
                <span style={{ ...dotStyle, background: '#222' }} aria-hidden />
                <span style={navLabelStyle}>⇄ Full request</span>
              </button>
            </nav>
            <section style={mainStyle} aria-live="polite">
              {activeSection !== null && (
                <>
                  <div style={mainHeaderStyle}>
                    <div>
                      <strong>{activeSection.label}</strong>
                      <span style={mainHeaderMetaStyle}>
                        {' · '}{activeSection.tokenCount.toLocaleString()} tokens
                        {activeSection.sources.length > 0
                          && ` · ${activeSection.sources.length} source${activeSection.sources.length === 1 ? '' : 's'}`}
                      </span>
                    </div>
                  </div>
                  <div style={sectionBodyContainerStyle}>
                    <SectionBody section={activeSection} wire={context.wire} />
                  </div>
                  {activeSection.sources.length > 0 && (
                    <div style={provenanceRowStyle} aria-label="Provenance">
                      <button
                        type="button"
                        onClick={() => setSourcesExpanded(e => !e)}
                        style={provenanceToggleStyle}
                        aria-expanded={sourcesExpanded}
                        title={'The original messages / items that were assembled '
                          + 'into this section of the prompt'}
                      >
                        <span style={provenanceCaretStyle}>{sourcesExpanded ? '▾' : '▸'}</span>
                        <span style={provenanceLabelStyle}>Sources</span>
                        <span style={provenanceCountStyle}>{activeSection.sources.length}</span>
                      </button>
                      {sourcesExpanded && (
                        <div style={provenanceChipsStyle}>
                          {activeSection.sources.map((src, idx) => {
                            const label = `${src.kind}:${src.label}`;
                            if (src.href !== null) {
                              return (
                                <a
                                  key={idx}
                                  href={src.href}
                                  style={provenanceChipLinkStyle}
                                  title={src.href}
                                  onClick={ev => {
                                    // Internal route — don't let the renderer
                                    // try to navigate away from the app shell.
                                    ev.preventDefault();
                                    window.location.hash = src.href!;
                                  }}
                                >
                                  {label}
                                </a>
                              );
                            }
                            return (
                              <span key={idx} style={provenanceChipStyle}>{label}</span>
                            );
                          })}
                        </div>
                      )}
                    </div>
                  )}
                </>
              )}
            </section>
          </div>
        )}
      </aside>
    </div>
  );
}

/** Renders one section's body using the format that matches its
 *  content type: markdown sections through {@link MarkdownProse},
 *  tool / history / new-turn JSON through {@link prettyJson} +
 *  {@link highlightJson}. The empty-body path stays as a single
 *  centred "(empty)" message so the user can tell the difference
 *  between "the axis ran and produced nothing" and "the axis
 *  hasn't loaded yet". */
function SectionBody({ section, wire }: { section: Section; wire: Wire }) {
  if (section.kind === 'TOOLS') {
    if (wire.tools.length === 0) return <EmptyBody />;
    return (
      <div style={cardListStyle}>
        {wire.tools.map((tool, idx) => (
          <JsonCard key={idx} body={tool} fallbackTitle={`tool #${idx + 1}`} />
        ))}
      </div>
    );
  }
  if (section.kind === 'HISTORY') {
    if (wire.historyMessages.length === 0) return <EmptyBody />;
    return (
      <div style={cardListStyle}>
        {wire.historyMessages.map((msg, idx) => (
          <JsonCard key={idx} body={msg} fallbackTitle={`message #${idx + 1}`} />
        ))}
      </div>
    );
  }
  if (section.kind === 'NEW_TURN') {
    if (wire.newTurn.length === 0) return <EmptyBody />;
    return (
      <div style={cardListStyle}>
        <JsonCard body={wire.newTurn} fallbackTitle="this turn" />
      </div>
    );
  }
  // Role, brain, concept preamble, skill manifest, memory items —
  // all markdown. MarkdownProse handles headings, lists, code spans,
  // and back-link chips; we wrap in a padded panel so the body
  // doesn't run flush against the section header.
  if (section.body.length === 0) return <EmptyBody />;
  return (
    <div style={markdownPanelStyle}>
      <MarkdownProse text={section.body} variant="card" />
    </div>
  );
}

function EmptyBody() {
  return <div style={emptyBodyStyle}>(empty for this turn)</div>;
}

/** One pretty-printed JSON payload — a tool definition, a history
 *  message, or the new-turn payload. Shows a small header with the
 *  JSON's {@code name} field if present (tool name, message role)
 *  so the user can scan a stack of cards without parsing each one. */
function JsonCard({ body, fallbackTitle }: { body: string; fallbackTitle: string }) {
  const pretty = prettyJson(body);
  const title = extractJsonName(body) ?? fallbackTitle;
  return (
    <div style={jsonCardStyle}>
      <div style={jsonCardHeaderStyle}>{title}</div>
      <pre style={{ ...JSON_PRE_STYLE, padding: 10 }}>
        {highlightJson(pretty, LIGHT_JSON_THEME)}
      </pre>
    </div>
  );
}

/** Full-request view: provider-shape JSON with inline section
 *  markers. Each wire chunk is parsed + re-emitted with stable
 *  indentation so the brackets line up and the highlighted tokens
 *  pick up the dark theme of the surrounding code-block. */
function renderWire(context: AssembledContextDto): React.ReactNode {
  const tools = context.wire.tools;
  const systemBlocks = context.wire.systemBlocks;
  const history = context.wire.historyMessages;
  const newTurn = context.wire.newTurn;
  const systemLabels = [
    '② role',
    '③ brain',
    '④ concepts',
    '⑤ skills',
    '⑥ memory',
  ];
  const parts: React.ReactNode[] = [];
  let key = 0;

  const push = (s: string) => parts.push(s);
  const pushHi = (s: string) => parts.push(
      <span key={key++}>{highlightJson(s, DARK_JSON_THEME)}</span>);

  push('{\n');
  parts.push(<span key={key++} style={commentStyle}>{'  /* ① tools */\n'}</span>);
  push('  "tools": [\n');
  tools.forEach((tool, idx) => {
    const pretty = indentBlock(prettyJson(tool), 4);
    pushHi(pretty);
    push(idx < tools.length - 1 ? ',\n' : '\n');
  });
  push('  ],\n');
  push('  "messages": [\n');
  systemBlocks.forEach((block, idx) => {
    const label = systemLabels[idx] ?? 'system';
    parts.push(
      <span key={key++} style={commentStyle}>{`    /* ${label} */\n`}</span>);
    const msg = JSON.stringify({ role: 'system', content: block }, null, 2);
    pushHi(indentBlock(msg, 4));
    push(idx < systemBlocks.length - 1 || history.length > 0 || newTurn.length > 0
        ? ',\n' : '\n');
  });
  if (history.length > 0) {
    parts.push(
      <span key={key++} style={commentStyle}>{'    /* ⑦ history */\n'}</span>);
    history.forEach((msg, idx) => {
      pushHi(indentBlock(prettyJson(msg), 4));
      const more = idx < history.length - 1 || newTurn.length > 0;
      push(more ? ',\n' : '\n');
    });
  }
  if (newTurn.length > 0) {
    parts.push(
      <span key={key++} style={commentStyle}>{'    /* ⑧ this turn */\n'}</span>);
    pushHi(indentBlock(prettyJson(newTurn), 4));
    push('\n');
  }
  push('  ]\n');
  push('}\n');
  return parts;
}

function indentBlock(body: string, spaces: number): string {
  const pad = ' '.repeat(spaces);
  return body.split('\n').map(line => pad + line).join('\n');
}

const commentStyle: React.CSSProperties = {
  color: '#7a8290',
  fontStyle: 'italic',
};

const backdropStyle: React.CSSProperties = {
  position: 'fixed',
  top: 0,
  left: 0,
  right: 0,
  bottom: 0,
  background: 'rgba(0,0,0,0.45)',
  zIndex: 9999,
  display: 'flex',
  justifyContent: 'flex-end',
};

const drawerStyle: React.CSSProperties = {
  width: 'min(880px, 92vw)',
  height: '100vh',
  background: 'white',
  borderLeft: '1px solid #d8d8e0',
  display: 'flex',
  flexDirection: 'column',
  fontSize: 13,
};

const headerStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'flex-start',
  padding: 12,
  borderBottom: '1px solid #e2e2e8',
};

const headerTitleStyle: React.CSSProperties = {
  fontWeight: 600,
  fontSize: 14,
};

const headerSubtitleStyle: React.CSSProperties = {
  fontSize: 11,
  color: '#6b6b78',
  marginTop: 2,
};

const headerActionsStyle: React.CSSProperties = {
  display: 'flex',
  gap: 6,
  alignItems: 'center',
};

const toggleStyle: React.CSSProperties = {
  fontSize: 11,
  padding: '4px 8px',
  background: 'white',
  border: '1px solid #c8c8d0',
  borderRadius: 4,
  cursor: 'pointer',
};

const activeToggleStyle: React.CSSProperties = {
  ...toggleStyle,
  background: '#222',
  color: 'white',
  borderColor: '#222',
};

/** Segmented control wrapping the Sections / Full request pair —
 *  one bordered pill with two halves so the user reads them as a
 *  view switcher instead of two unrelated buttons. */
const segmentedControlStyle: React.CSSProperties = {
  display: 'inline-flex',
  border: '1px solid #c8c8d0',
  borderRadius: 6,
  overflow: 'hidden',
  marginRight: 4,
};

const segmentStyle: React.CSSProperties = {
  padding: '5px 12px',
  fontSize: 11,
  fontWeight: 600,
  background: 'white',
  color: '#3b3b48',
  border: 'none',
  cursor: 'pointer',
};

const segmentActiveStyle: React.CSSProperties = {
  ...segmentStyle,
  background: '#222',
  color: 'white',
};

const closeStyle: React.CSSProperties = {
  ...toggleStyle,
  background: 'transparent',
  border: 'none',
  fontSize: 18,
  fontWeight: 600,
  width: 28,
  height: 28,
};

const errorStyle: React.CSSProperties = {
  padding: 10,
  background: '#fff0f0',
  color: '#c62828',
  fontSize: 12,
};

const placeholderStyle: React.CSSProperties = {
  padding: 24,
  textAlign: 'center',
  color: '#6b6b78',
};

const splitStyle: React.CSSProperties = {
  display: 'flex',
  flex: 1,
  minHeight: 0,
};

const navStyle: React.CSSProperties = {
  width: 200,
  borderRight: '1px solid #e2e2e8',
  overflowY: 'auto',
  padding: 6,
  display: 'flex',
  flexDirection: 'column',
  gap: 2,
};

const navItemStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  padding: '6px 8px',
  background: 'transparent',
  border: 'none',
  borderRadius: 4,
  cursor: 'pointer',
  fontSize: 12,
  textAlign: 'left',
  width: '100%',
};

const activeNavItemStyle: React.CSSProperties = {
  ...navItemStyle,
  background: '#eef0fd',
};

const navDividerStyle: React.CSSProperties = {
  marginTop: 10,
  padding: '4px 8px 2px',
  fontSize: 9,
  fontWeight: 700,
  textTransform: 'uppercase',
  letterSpacing: 0.6,
  color: '#9090a0',
  borderTop: '1px solid #e2e2e8',
};

const dotStyle: React.CSSProperties = {
  display: 'inline-block',
  width: 8,
  height: 8,
  borderRadius: 4,
  flexShrink: 0,
};

const navLabelStyle: React.CSSProperties = {
  flex: 1,
};

const navTokenStyle: React.CSSProperties = {
  fontSize: 10,
  color: '#6b6b78',
};

const mainStyle: React.CSSProperties = {
  flex: 1,
  display: 'flex',
  flexDirection: 'column',
  minHeight: 0,
};

const mainHeaderStyle: React.CSSProperties = {
  padding: '10px 14px',
  borderBottom: '1px solid #e2e2e8',
  fontSize: 13,
};

const mainHeaderMetaStyle: React.CSSProperties = {
  color: '#6b6b78',
  fontWeight: 400,
  fontSize: 11,
};

const sectionBodyContainerStyle: React.CSSProperties = {
  flex: 1,
  overflow: 'auto',
  background: '#fafafd',
  padding: 14,
  minHeight: 0,
};

const markdownPanelStyle: React.CSSProperties = {
  background: 'white',
  border: '1px solid #e2e2e8',
  borderRadius: 6,
  padding: '8px 14px',
};

const cardListStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 8,
};

const jsonCardStyle: React.CSSProperties = {
  background: 'white',
  border: '1px solid #e2e2e8',
  borderRadius: 6,
  overflow: 'hidden',
};

const jsonCardHeaderStyle: React.CSSProperties = {
  padding: '6px 10px',
  background: '#f0f0f6',
  borderBottom: '1px solid #e2e2e8',
  fontSize: 11,
  fontWeight: 600,
  color: '#3b3b48',
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
};

const emptyBodyStyle: React.CSSProperties = {
  padding: 24,
  textAlign: 'center',
  color: '#9090a0',
  fontStyle: 'italic',
  fontSize: 12,
};

const wireContainerStyle: React.CSSProperties = {
  flex: 1,
  display: 'flex',
  flexDirection: 'column',
  minHeight: 0,
};

const bannerStyle: React.CSSProperties = {
  padding: '10px 14px',
  background: '#fff7d6',
  borderBottom: '1px solid #efc34f',
  fontSize: 12,
  color: '#5e4400',
};

const wirePreStyle: React.CSSProperties = {
  margin: 0,
  padding: 14,
  flex: 1,
  overflow: 'auto',
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  fontSize: 12,
  lineHeight: 1.45,
  whiteSpace: 'pre',
  background: '#1e1e26',
  color: '#dadae0',
};

const provenanceRowStyle: React.CSSProperties = {
  // Pinned below the section body; never grows to eat the body. The
  // chip list (when expanded) scrolls inside its own capped container.
  flexShrink: 0,
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
  padding: '8px 14px',
  borderTop: '1px solid #e2e2e8',
  background: '#f4f4fa',
  fontSize: 11,
};

const provenanceToggleStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
  alignSelf: 'flex-start',
  padding: 0,
  background: 'none',
  border: 'none',
  cursor: 'pointer',
};

const provenanceCaretStyle: React.CSSProperties = {
  color: '#6b6b78',
  fontSize: 10,
  width: 10,
};

const provenanceCountStyle: React.CSSProperties = {
  padding: '0 6px',
  borderRadius: 999,
  background: '#e2e2ea',
  color: '#4b4b58',
  fontSize: 10,
  fontWeight: 600,
};

const provenanceChipsStyle: React.CSSProperties = {
  display: 'flex',
  flexWrap: 'wrap',
  gap: 6,
  // Cap so an enormous source list scrolls instead of overrunning the
  // section body. ~6 rows of chips, then scroll.
  maxHeight: 160,
  overflowY: 'auto',
};

const provenanceLabelStyle: React.CSSProperties = {
  textTransform: 'uppercase',
  letterSpacing: 0.4,
  color: '#6b6b78',
  fontSize: 10,
};

const provenanceChipStyle: React.CSSProperties = {
  display: 'inline-block',
  padding: '2px 8px',
  border: '1px solid #c8c8d0',
  background: 'white',
  borderRadius: 4,
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  color: '#3b3b48',
};

const provenanceChipLinkStyle: React.CSSProperties = {
  ...provenanceChipStyle,
  cursor: 'pointer',
  textDecoration: 'none',
  color: '#1f5fbf',
};

export default PromptContextInspector;
