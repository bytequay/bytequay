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
import { useCallback, useEffect, useState, type ReactNode } from 'react';
import {
  workspaceApi,
  type DistillOperationDto,
  type DistillRunDto,
  type KnowledgeEntryDto,
  type WorkspaceMemoryDto,
} from './workspaceApi';

const audienceOptions = ['plan', 'dev', 'review', 'ci-fix'] as const;

export default function WorkspaceMemoryPage({ workspaceId }: { workspaceId: string }) {
  const visualFrame = typeof document === 'undefined'
    ? undefined
    : document.documentElement.dataset.workspaceVisualFrame;
  const [memory, setMemory] = useState<WorkspaceMemoryDto | null>(null);
  const [preview, setPreview] = useState<DistillRunDto | null>(null);
  const [markdownOpen, setMarkdownOpen] = useState(false);
  const [markdown, setMarkdown] = useState('');
  const [knowledgeEdit, setKnowledgeEdit] = useState<Partial<KnowledgeEntryDto> | null>(null);
  const [loading, setLoading] = useState(true);
  const [acting, setActing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const next = await workspaceApi.memory(workspaceId);
      setMemory(next);
      setMarkdown(next.markdown);
      setError(null);
      const pending = next.distillRuns.find(run => run.status === 'pending');
      if (pending !== undefined) setPreview(pending);
    }
    catch (reason) {
      setError(message(reason));
    }
    finally {
      setLoading(false);
    }
  }, [workspaceId]);

  useEffect(() => { void refresh(); }, [refresh]);

  const startDistill = async () => {
    setActing(true);
    setError(null);
    try {
      const run = await workspaceApi.distillThreads(workspaceId);
      setPreview(run);
      await refresh();
    }
    catch (reason) {
      setError(message(reason));
    }
    finally {
      setActing(false);
    }
  };

  if (loading) return <section className="wu-memory"><div className="wu-body-message">Loading memory…</div></section>;
  if (memory === null) return <section className="wu-memory"><div className="wu-body-message error">{error ?? 'Memory is unavailable.'}</div></section>;

  const usage = Math.min(100, (memory.characters / Math.max(1, memory.characterBudget)) * 100);
  const compact = visualFrame === '3h';
  return (
    <section className={`wu-memory${compact ? ' is-compact' : ''}`}>
      <header className="wu-memory__header">
        <h1>Memory</h1>
        <span className="wu-memory__budget"><i><b style={{ width: `${usage}%` }} /></i>
          {memory.characters.toLocaleString()} / {memory.characterBudget.toLocaleString()}
          {compact && ' chars'}</span>
        <span className="wu-spacer" />
        <button type="button" className="wu-icon-button" onClick={() => setMarkdownOpen(true)}>
          Edit markdown
        </button>
        <button type="button" className="wu-primary-button" disabled={acting}
          onClick={() => { void startDistill(); }}><BrainIcon />{compact ? 'Distill now' : 'Distill…'}</button>
      </header>
      {error !== null && <div className="wu-memory__error">{error}</div>}
      {compact ? (
        <div className="wu-memory-compact">
          <span>Auto-distills every 30 min while threads are active · last run {
            memory.distillRuns[0] === undefined ? 'never' : relativeTime(memory.distillRuns[0].createdAt)
          }</span>
          <article>
            {memory.blocks.map(block => (
              <section key={block.id}>
                <header>
                  <h2>{block.category}</h2>
                  <small className={block.provenance.startsWith('edited') ? 'is-user' : ''}>
                    {!block.provenance.startsWith('edited') && <TrunkSourceIcon />}
                    {block.provenance.startsWith('edited') ? block.provenance : `from ${block.provenance}`}
                  </small>
                </header>
                <p>{renderInlineText(block.body)}</p>
              </section>
            ))}
          </article>
        </div>
      ) : (
        <div className="wu-memory__grid">
          <main className="wu-memory__main">
            {memory.blocks.map(block => (
              <article className="wu-brain-card" key={block.id}>
                <header>
                  <h2>{block.category}</h2>
                  <small><TrunkSourceIcon />from {block.provenance}</small>
                  <button type="button" onClick={() => setMarkdownOpen(true)}
                    aria-label={`Edit ${block.category}`}><PencilIcon /></button>
                </header>
                <p>{renderInlineText(block.body)}</p>
              </article>
            ))}
            {memory.blocks.length === 0 && (
              <div className="wu-memory-empty">
                <strong>No brain blocks yet</strong>
                <span>Distill active trunks or seed from the repository.</span>
                <button type="button" className="wu-icon-button" onClick={() => {
                  setActing(true);
                  void workspaceApi.seedMemory(workspaceId)
                    .then(run => setPreview(run))
                    .catch(reason => setError(message(reason)))
                    .finally(() => setActing(false));
                }}>Seed from repository</button>
              </div>
            )}
            <div className="wu-kb-heading">
              <h2>Knowledge base</h2>
              <span>structured docs agents read at session start</span>
              <button type="button" onClick={() => setKnowledgeEdit({
                title: '', body: '', audience: ['plan', 'dev', 'review', 'ci-fix'],
              })}>+ New entry</button>
            </div>
            {memory.knowledge.map(entry => (
              <article className="wu-kb-card" key={entry.id}>
                <header>
                  <span className={`wu-kb-icon ${entry.audience.length === 1 ? 'review' : 'plan'}`}>
                    {entry.audience.length === 1 ? <BookIcon /> : <ModuleMapIcon />}
                  </span>
                  <h3>{entry.title}</h3>
                  <span className={`wu-kb-audience ${entry.audience.length === 1 ? 'review' : 'plan'}`}>
                    {audienceLabel(entry)}
                  </span>
                  <button type="button" onClick={() => setKnowledgeEdit(entry)}
                    aria-label={`Edit ${entry.title}`}><PencilIcon /></button>
                </header>
                <p>{renderInlineText(entry.body)}</p>
                {provenance(entry) !== '' && <small>{provenance(entry)}</small>}
              </article>
            ))}
          </main>
          <aside className="wu-distill-history">
            <h2>Distill history</h2>
            <div>
              {memory.distillRuns.map(run => (
                <article className={run.status === 'no-changes' ? 'is-muted' : ''} key={run.id}>
                  <header><strong>{relativeTime(run.createdAt)} · {run.trigger}</strong>
                    <span className={historyTone(run)}>{historyLabel(run)}</span></header>
                  <p>{runSummary(run)}</p>
                  {(run.status === 'pending' || run.status === 'applied') && (
                    <footer>
                      {run.status === 'pending' ? (
                      <button type="button" onClick={() => setPreview(run)}>Review changes</button>
                      ) : (
                      <button type="button" onClick={() => setPreview(run)}>View diff · Edit</button>
                      )}
                    </footer>
                  )}
                </article>
              ))}
              {memory.distillRuns.length === 0 && <p className="wu-muted">No distill runs yet.</p>}
            </div>
            <p>Auto-distill every 30 min while threads are active. Every applied run is reversible from its diff.</p>
          </aside>
        </div>
      )}
      {markdownOpen && (
        <EditorModal
          title="Edit memory markdown"
          onClose={() => setMarkdownOpen(false)}
          onSave={() => {
            setActing(true);
            void workspaceApi.saveMemory(workspaceId, markdown)
              .then(next => {
                setMemory(next);
                setMarkdown(next.markdown);
                setMarkdownOpen(false);
              })
              .catch(reason => setError(message(reason)))
              .finally(() => setActing(false));
          }}
          disabled={acting}
        >
          <textarea className="wu-markdown-editor" value={markdown}
            onChange={event => setMarkdown(event.target.value)} />
        </EditorModal>
      )}
      {knowledgeEdit !== null && (
        <KnowledgeEditor
          value={knowledgeEdit}
          disabled={acting}
          onChange={setKnowledgeEdit}
          onClose={() => setKnowledgeEdit(null)}
          onDelete={knowledgeEdit.id === undefined ? undefined : () => {
            setActing(true);
            void workspaceApi.deleteKnowledge(workspaceId, knowledgeEdit.id!)
              .then(() => {
                setKnowledgeEdit(null);
                return refresh();
              })
              .catch(reason => setError(message(reason)))
              .finally(() => setActing(false));
          }}
          onSave={() => {
            setActing(true);
            void workspaceApi.saveKnowledge(workspaceId, {
              title: knowledgeEdit.title ?? '',
              body: knowledgeEdit.body ?? '',
              audience: knowledgeEdit.audience ?? ['plan', 'dev', 'review', 'ci-fix'],
              provenance: knowledgeEdit.provenance ?? { source: 'user' },
            }, knowledgeEdit.id)
              .then(() => {
                setKnowledgeEdit(null);
                return refresh();
              })
              .catch(reason => setError(message(reason)))
              .finally(() => setActing(false));
          }}
        />
      )}
      {preview !== null && (
        <DistillPreview
          run={preview}
          disabled={acting}
          onClose={() => setPreview(null)}
          onChange={setPreview}
          onApply={async operations => {
            setActing(true);
            setError(null);
            try {
              const decided = await workspaceApi.decideDistill(
                workspaceId, preview.id, operations,
              );
              await workspaceApi.applyDistill(workspaceId, decided.id);
              setPreview(null);
              await refresh();
            }
            catch (reason) {
              setError(message(reason));
            }
            finally {
              setActing(false);
            }
          }}
        />
      )}
    </section>
  );
}

function DistillPreview({ run, disabled, onClose, onChange, onApply }: {
  run: DistillRunDto;
  disabled: boolean;
  onClose: () => void;
  onChange: (run: DistillRunDto) => void;
  onApply: (operations: DistillOperationDto[]) => Promise<void>;
}) {
  const update = (id: string, patch: Partial<DistillOperationDto>) => {
    onChange({
      ...run,
      operations: run.operations.map(operation =>
        operation.id === id ? { ...operation, ...patch } : operation),
    });
  };
  const accepted = run.operations.filter(operation =>
    operation.decision === 'accepted' || operation.decision === 'edited').length;
  const settled = run.operations.every(operation => operation.decision !== 'pending');
  return (
    <div className="wu-modal-backdrop">
      <section className="wu-distill-modal" role="dialog" aria-modal="true">
        <header><BrainIcon /><h2>Distill from threads</h2>
          <button type="button" onClick={onClose} aria-label="Close"><CloseIcon /></button></header>
        <main>
          <h3>Sources</h3>
          <div className="wu-distill-sources">
            {sourceRows(run).map(source => (
              <label className={`wu-distill-source${source.disabled ? ' disabled' : ''}`} key={source.label}>
                <input type="checkbox" checked={!source.disabled} disabled={source.disabled} readOnly />
                <strong>{source.label}</strong>
                {source.detail !== '' && <span>{source.detail}</span>}
              </label>
            ))}
          </div>
          {run.sources.length === 0 && <p className="wu-muted">No new source content.</p>}
          <h3>Proposed changes</h3>
          <div className="wu-distill-operations">
            {run.operations.map(operation => (
              <article key={operation.id} className={`${operation.decision} ${operation.action}`}>
                <span>{operation.action === 'add' ? '+' : '~'}</span>
                <div
                  title={operation.decision === 'edited' ? undefined : 'Double-click to edit'}
                  onDoubleClick={() => update(operation.id, { decision: 'edited' })}
                >
                  <strong>{operation.category ?? operation.title ?? 'Knowledge'}:</strong>
                  {operation.decision === 'edited' ? (
                    <textarea value={operation.body ?? ''}
                      onChange={event => update(operation.id, { body: event.target.value })} />
                  ) : ` ${operation.body ?? ''}`}
                </div>
                <footer>
                  <button type="button" onClick={() => update(operation.id, { decision: 'accepted' })}>Accept</button>
                  <button type="button" onClick={() => update(operation.id, { decision: 'skipped' })}>Skip</button>
                </footer>
              </article>
            ))}
            {run.operations.length === 0 && <div className="wu-distill-nochanges">Nothing new to fold.</div>}
          </div>
        </main>
        <footer>
          <span>{accepted} of {run.operations.length} accepted
            {run.characterDelta === undefined ? '' : ` · +${run.characterDelta} chars`}</span>
          <button type="button" className="wu-icon-button" onClick={onClose}>Cancel</button>
          <button type="button" className="wu-primary-button"
            disabled={disabled || (!settled && run.operations.length > 0)}
            onClick={() => { void onApply(run.operations); }}>
            {run.operations.length === 0 ? 'Record no changes' : `Apply ${accepted} changes`}
          </button>
        </footer>
      </section>
    </div>
  );
}

function KnowledgeEditor({ value, disabled, onChange, onClose, onSave, onDelete }: {
  value: Partial<KnowledgeEntryDto>;
  disabled: boolean;
  onChange: (value: Partial<KnowledgeEntryDto>) => void;
  onClose: () => void;
  onSave: () => void;
  onDelete?: () => void;
}) {
  return (
    <EditorModal title={value.id === undefined ? 'New knowledge entry' : 'Edit knowledge entry'}
      onClose={onClose} onSave={onSave} disabled={disabled}>
      <label className="wu-form-field">Title
        <input value={value.title ?? ''} onChange={event => onChange({ ...value, title: event.target.value })} />
      </label>
      <label className="wu-form-field">Body
        <textarea value={value.body ?? ''} onChange={event => onChange({ ...value, body: event.target.value })} />
      </label>
      <fieldset className="wu-audience-field"><legend>Agents that can read this entry</legend>
        {audienceOptions.map(audience => (
          <label key={audience}><input type="checkbox"
            checked={value.audience?.includes(audience) ?? false}
            onChange={event => {
              const current = new Set(value.audience ?? []);
              if (event.target.checked) current.add(audience);
              else current.delete(audience);
              onChange({ ...value, audience: [...current] });
            }} />{audience}</label>
        ))}
      </fieldset>
      {onDelete !== undefined && <button type="button" className="wu-delete-link" onClick={onDelete}>Delete entry</button>}
    </EditorModal>
  );
}

function EditorModal({ title, children, onClose, onSave, disabled }: {
  title: string;
  children: ReactNode;
  onClose: () => void;
  onSave: () => void;
  disabled: boolean;
}) {
  return (
    <div className="wu-modal-backdrop">
      <section className="wu-editor-modal" role="dialog" aria-modal="true">
        <header><h2>{title}</h2><button type="button" onClick={onClose}>×</button></header>
        <main>{children}</main>
        <footer><button type="button" className="wu-icon-button" onClick={onClose}>Cancel</button>
          <button type="button" className="wu-primary-button" disabled={disabled} onClick={onSave}>Save</button></footer>
      </section>
    </div>
  );
}

function historyLabel(run: DistillRunDto): string {
  if (run.status === 'no-changes') return 'no changes';
  if (run.status === 'reverted') return 'reverted';
  if (run.status === 'pending') return 'pending';
  return run.operations.some(operation => operation.decision === 'edited') ? 'edited' : 'applied';
}

function historyTone(run: DistillRunDto): string {
  const label = historyLabel(run);
  if (label === 'applied') return 'applied';
  if (label === 'edited' || label === 'pending') return 'edited';
  return 'no-changes';
}

function runSummary(run: DistillRunDto): ReactNode {
  const fixtureSummary = run.sources.find(source => typeof source.summary === 'string')?.summary;
  if (typeof fixtureSummary === 'string') return renderInlineText(fixtureSummary);
  if (run.operations.length === 0) return 'Scanned active trunks — nothing new to fold';
  const brain = run.operations.filter(operation => operation.target === 'brain').length;
  const kb = run.operations.length - brain;
  return `Folded ${run.sources.length || 'workspace'} source${run.sources.length === 1 ? '' : 's'} → ${brain} brain, ${kb} KB change${kb === 1 ? '' : 's'}`;
}

function sourceRows(run: DistillRunDto): Array<{ label: string; detail: string; disabled: boolean }> {
  const seen = new Set<string>();
  return run.sources.flatMap(source => {
    const label = String(
      source.label
      ?? source.trunkTitle
      ?? source.threadTitle
      ?? source.trunkId
      ?? source.threadId
      ?? source.taskId
      ?? source.prRef
      ?? 'Workspace source',
    );
    if (seen.has(label)) return [];
    seen.add(label);
    return [{
      label,
      detail: String(source.detail ?? ''),
      disabled: source.disabled === true,
    }];
  });
}

function BrainIcon() {
  return (
    <svg className="wu-distill-brain-icon" viewBox="0 0 24 24" aria-hidden>
      <path d="M12 3a4 4 0 0 0-4 4 3.5 3.5 0 0 0-2 6.5A3.5 3.5 0 0 0 9 20a3 3 0 0 0 6 0 3.5 3.5 0 0 0 3-6.5A3.5 3.5 0 0 0 16 7a4 4 0 0 0-4-4Z" />
      <path d="M12 3v18" />
    </svg>
  );
}

function TrunkSourceIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9"
      strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <circle cx="6" cy="6" r="2.4" />
      <circle cx="6" cy="18" r="2.4" />
      <circle cx="18" cy="12" r="2.4" />
      <path d="M8.3 7.2 15.7 11M8.3 16.8 15.7 13" />
    </svg>
  );
}

function PencilIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"
      strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M17 3a2.8 2.8 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z" />
    </svg>
  );
}

function BookIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7"
      strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M4 19.5v-15A2.5 2.5 0 0 1 6.5 2H20v20H6.5a2.5 2.5 0 0 1 0-5H20" />
    </svg>
  );
}

function ModuleMapIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7"
      strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <rect x="3" y="3" width="7" height="7" rx="1.6" />
      <rect x="14" y="3" width="7" height="7" rx="1.6" />
      <rect x="3" y="14" width="7" height="7" rx="1.6" />
      <rect x="14" y="14" width="7" height="7" rx="1.6" />
    </svg>
  );
}

function CloseIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
      strokeLinecap="round" aria-hidden>
      <path d="M18 6 6 18M6 6l12 12" />
    </svg>
  );
}

function audienceLabel(entry: KnowledgeEntryDto): string {
  if (entry.audience.length === 1 && entry.audience[0] === 'review') {
    return 'used by review agent';
  }
  return `used by ${entry.audience.join(' + ')}`;
}

function renderInlineText(text: string): ReactNode {
  return text.split(/(`[^`]+`|\*\*[^*]+\*\*)/g).filter(Boolean).map((part, index) => {
    if (part.startsWith('`') && part.endsWith('`')) {
      return <code key={`${part}-${index}`}>{part.slice(1, -1)}</code>;
    }
    if (part.startsWith('**') && part.endsWith('**')) {
      return <strong key={`${part}-${index}`}>{part.slice(2, -2)}</strong>;
    }
    return part;
  });
}

function provenance(entry: KnowledgeEntryDto): string {
  if (typeof entry.provenance.display === 'string') return entry.provenance.display;
  const source = entry.provenance.source ?? entry.provenance.distillOperation;
  return source === undefined ? 'workspace knowledge' : `from ${String(source)}`;
}

function relativeTime(epochMs: number): string {
  const minutes = Math.max(0, Math.floor((Date.now() - epochMs) / 60_000));
  if (minutes < 1) return 'now';
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  return `${Math.floor(hours / 24)}d ago`;
}

function message(reason: unknown): string {
  return reason instanceof Error ? reason.message : String(reason);
}
