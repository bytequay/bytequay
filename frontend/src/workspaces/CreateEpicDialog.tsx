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

import { useEffect, useId, useRef, useState } from 'react';
import { LoaderCircle, LockKeyhole, Search, X } from 'lucide-react';
import Composer from '../Composer';
import { AgentSetupNotice, useAgentAvailability } from '../agents/availability';
import type { EpicDraftDto, EpicDraftMessage, UnifiedWorkItem } from '../workitems/contracts';
import { notifyJiraChanged } from '../jira/contracts';
import MarkdownBody from '../pulls/MarkdownBody';
import { useHostingPresentation } from '../pulls/PullRequestWorkspace';
import { useModalDialog } from '../useModalDialog';
import { createLocalEpic, ticketEpicId } from './epics';
import { notifyIssueDevelopmentChanged } from './issueDevelopment';
import '../css/confirm-dialog.css';
import '../css/create-epic.css';

type Mode = 'talk' | 'manual';
type Source = 'LOCAL' | 'GITHUB' | 'JIRA';
/** A Local item opens by its id; a marked issue or ticket by its Epic identity, when the catalog named it by one. */
export type CreatedEpic = { kind: 'local'; itemId: string } | { kind: 'remote'; epicId: string | null };

const EMPTY: EpicDraftDto = { title: '', description: '', context: '' };
const MAX_LISTED = 50;
const MAX_MESSAGE = 8_000;
// Closing the dialog by accident keeps what was said, until the app quits; creating the Epic or Start over ends it.
const conversations = new Map<string, EpicDraftMessage[]>();

const errorText = (error: unknown) => error instanceof Error ? error.message : String(error);

/**
 * Creates an Epic from Home: talked through with the Development model, which asks until it can draft one and then asks to create it,
 * or made directly as Local work or from one of the workspace's issues or Jira tickets. Nothing here writes to the remote provider.
 */
export default function CreateEpicDialog({ workspaceId, marked, jiraConnected, onClose, onCreated }: {
  workspaceId: string;
  /** The workspace's Epics by identity, which the pickers leave out. */
  marked: ReadonlySet<string>;
  jiraConnected: boolean;
  onClose: () => void;
  onCreated: (created: CreatedEpic) => void;
}) {
  const { server } = useHostingPresentation();
  const issueProvider = server?.provider === 'GITLAB' ? 'GitLab' : 'GitHub';
  const dialog = useModalDialog();
  // Opening a modal moves focus to the dialog itself, away from the field its first view focused while mounting.
  useEffect(() => { dialog.current?.querySelector<HTMLElement>('textarea, input')?.focus(); }, [dialog]);
  const titleId = useId();
  const [mode, setMode] = useState<Mode>('talk');
  const [source, setSource] = useState<Source>('LOCAL');
  const [fields, setFields] = useState<EpicDraftDto>(EMPTY);
  // One id for every attempt: creating is idempotent for the same request, so a retry never makes a second item.
  const [itemId] = useState(() => crypto.randomUUID());
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const createLocal = async (value: EpicDraftDto): Promise<void> => {
    setCreating(true);
    setError(null);
    try {
      await createLocalEpic(workspaceId, { id: itemId, title: value.title.trim(), description: value.description, context: value.context });
      conversations.delete(workspaceId);
      onCreated({ kind: 'local', itemId });
    }
    catch (cause) {
      setError(errorText(cause));
      setCreating(false);
    }
  };

  const markRemote = async (item: UnifiedWorkItem): Promise<void> => {
    const key = item.reference?.key;
    if (key === undefined) return;
    setCreating(true);
    setError(null);
    try {
      if (item.source === 'GITHUB') {
        const number = Number(key.slice(key.lastIndexOf('#') + 1));
        await window.bridge.setWorkspaceIssueEpic(workspaceId, number, true);
        notifyIssueDevelopmentChanged();
        onCreated({ kind: 'remote', epicId: item.id.startsWith('ISSUE:') ? item.id : null });
      }
      else {
        // A ticket's key is connection:cloud:issue.
        const [connectionId, ...rest] = key.split(':');
        const issueId = rest.pop() ?? '';
        const cloudId = rest.join(':');
        await window.bridge.setJiraEpic({ connectionId, cloudId, issueId }, true);
        notifyJiraChanged();
        onCreated({ kind: 'remote', epicId: ticketEpicId({ cloudId, id: issueId }) });
      }
    }
    catch (cause) {
      setError(errorText(cause));
      setCreating(false);
    }
  };

  const sources: [Source, string][] = [['LOCAL', 'Local'], ['GITHUB', `${issueProvider} issue`], ...(jiraConnected ? [['JIRA', 'Jira ticket'] as [Source, string]] : [])];
  return (
    <dialog ref={dialog} className="bq-confirm-dialog create-epic" aria-labelledby={titleId} aria-modal="true" aria-busy={creating}
      onCancel={(event) => {
        event.preventDefault();
        event.stopPropagation();
        if (!creating) onClose();
      }}
      onKeyDown={(event) => event.stopPropagation()}>
      <header className="create-epic__head">
        <h2 id={titleId} className="bq-confirm-dialog__title">Create Epic</h2>
        <button type="button" className="create-epic__close" aria-label="Close Create Epic" title="Close (Esc)" disabled={creating} onClick={onClose}><X size={16} aria-hidden="true" /></button>
      </header>
      <div className="create-epic__tabs" role="group" aria-label="How to create the Epic">
        <button type="button" className={mode === 'talk' ? 'is-active' : ''} aria-pressed={mode === 'talk'} onClick={() => setMode('talk')}>Talk it through</button>
        <button type="button" className={mode === 'manual' ? 'is-active' : ''} aria-pressed={mode === 'manual'} onClick={() => setMode('manual')}>Create yourself</button>
      </div>
      {mode === 'talk'
        ? <Talk workspaceId={workspaceId} busy={creating} onCreate={createLocal} onEdit={(draft) => {
          setFields(draft);
          setSource('LOCAL');
          setMode('manual');
        }} />
        : <>
          <div className="create-epic__sources" role="group" aria-label="Create the Epic from">
            {sources.map(([value, label]) => (
              <button type="button" key={value} className={source === value ? 'is-active' : ''} aria-pressed={source === value} onClick={() => setSource(value)}>{label}</button>
            ))}
          </div>
          {source === 'LOCAL'
            ? <LocalForm fields={fields} busy={creating} onChange={setFields} onSubmit={() => { void createLocal(fields); }} />
            : <Picker workspaceId={workspaceId} issueProvider={issueProvider} source={source} marked={marked} busy={creating} onPick={(item) => { void markRemote(item); }} />}
        </>}
      {error !== null && <div className="create-epic__error" role="alert">{error}</div>}
    </dialog>
  );
}

function Talk({ workspaceId, busy, onCreate, onEdit }: {
  workspaceId: string;
  busy: boolean;
  onCreate: (draft: EpicDraftDto) => Promise<void>;
  onEdit: (draft: EpicDraftDto) => void;
}) {
  const availability = useAgentAvailability(workspaceId);
  const [messages, setMessages] = useState<EpicDraftMessage[]>(() => conversations.get(workspaceId) ?? []);
  // Saved as it changes, never from an effect: creating the Epic clears the conversation, and a later save must not bring it back.
  const update = (next: EpicDraftMessage[]): void => {
    conversations.set(workspaceId, next);
    setMessages(next);
  };
  const [text, setText] = useState('');
  const [waiting, setWaiting] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const alive = useRef(false);
  const end = useRef<HTMLDivElement>(null);
  useEffect(() => {
    alive.current = true;
    return () => { alive.current = false; };
  }, []);
  useEffect(() => { end.current?.scrollIntoView({ block: 'end' }); }, [messages]);

  const ask = async (conversation: EpicDraftMessage[]): Promise<void> => {
    setWaiting(true);
    setFailure(null);
    try {
      const reply = await window.bridge.draftEpic(workspaceId, conversation);
      if (!alive.current) return;
      // A yes in words approves the draft the user saw, never one this reply changed.
      const shown = conversation.findLast((message) => message.draft !== undefined)?.draft;
      update([...conversation, { role: 'AGENT', text: reply.message, ...(reply.draft === null ? {} : { draft: reply.draft }) }]);
      setWaiting(false);
      if (reply.create && shown !== undefined) await onCreate(shown);
    }
    catch (cause) {
      if (!alive.current) return;
      setFailure(errorText(cause));
      setWaiting(false);
    }
  };
  const send = (): void => {
    const words = text.trim();
    if (words === '' || waiting || busy) return;
    const next: EpicDraftMessage[] = [...messages, { role: 'USER', text: words }];
    update(next);
    setText('');
    void ask(next);
  };

  if (!availability.loading && !availability.development) {
    return <div className="create-epic__talk is-unavailable"><AgentSetupNotice workspaceId={workspaceId} /><p className="create-epic__hint">Or choose Create yourself.</p></div>;
  }
  const latest = messages.findLastIndex((message) => message.draft !== undefined);
  // The reply never came: it failed, or the dialog closed while the agent was answering.
  const unanswered = !waiting && messages.at(-1)?.role === 'USER';
  return (
    <div className="create-epic__talk">
      <div className="create-epic__log" role="log" aria-label="Conversation about the Epic">
        {messages.length === 0 && (
          <p className="create-epic__hint">Describe the Epic you have in mind. The agent asks what it needs to know, then drafts the Epic for you to approve.</p>
        )}
        {messages.map((message, index) => {
          const draft = index === latest ? message.draft : undefined;
          return (
            <div key={index} className={`create-epic__message is-${message.role === 'USER' ? 'user' : 'agent'}`}>
              {message.role === 'USER' ? <p>{message.text}</p> : <MarkdownBody body={message.text} />}
              {draft !== undefined && <DraftCard draft={draft} busy={busy || waiting} onCreate={() => { void onCreate(draft); }} onEdit={() => onEdit(draft)} />}
            </div>
          );
        })}
        {waiting && <div className="create-epic__message is-agent is-waiting" role="status"><span className="bq-spinner is-spinning"><LoaderCircle size={14} aria-hidden="true" /></span>Thinking…</div>}
        {unanswered && (
          <div className="create-epic__retry" role={failure === null ? undefined : 'alert'}>
            <span>{failure ?? 'No reply yet.'}</span>
            <button type="button" className="bq-button" disabled={busy} onClick={() => { void ask(messages); }}>Ask again</button>
          </div>
        )}
        <div ref={end} />
      </div>
      <Composer aria-label="Message the agent about the Epic" placeholder={messages.length === 0 ? 'What should this Epic achieve?' : 'Reply to the agent'}
        autoFocus value={text} maxLength={MAX_MESSAGE} onChange={(event) => setText(event.target.value)} busy={waiting} onSubmit={send}
        submitEnabled={text.trim() !== '' && !waiting && !busy} submitLabel="Send to the agent" submitTitle="Send (Enter)">
        {messages.length > 0 && (
          <button type="button" className="create-epic__quiet" disabled={waiting || busy} onClick={() => { update([]); setFailure(null); }}>Start over</button>
        )}
      </Composer>
    </div>
  );
}

function DraftCard({ draft, busy, onCreate, onEdit }: { draft: EpicDraftDto; busy: boolean; onCreate: () => void; onEdit: () => void }) {
  return (
    <section className="create-epic__draft" aria-label={`Draft Epic: ${draft.title}`}>
      <span className="create-epic__label">Draft Epic</span>
      <h3 className="create-epic__draft-title">{draft.title}</h3>
      {draft.description !== '' && <MarkdownBody body={draft.description} className="create-epic__draft-body" />}
      {draft.context !== '' && <>
        <span className="create-epic__label">Context</span>
        <MarkdownBody body={draft.context} className="create-epic__draft-body" />
      </>}
      <div className="create-epic__actions">
        <button type="button" className="bq-button" disabled={busy} onClick={onEdit}>Edit</button>
        <button type="button" className="bq-button bq-button--primary" disabled={busy} onClick={onCreate}>Create Epic</button>
      </div>
    </section>
  );
}

function LocalForm({ fields, busy, onChange, onSubmit }: { fields: EpicDraftDto; busy: boolean; onChange: (fields: EpicDraftDto) => void; onSubmit: () => void }) {
  return (
    <form className="create-epic__form" onSubmit={(event) => {
      event.preventDefault();
      if (!busy && fields.title.trim() !== '') onSubmit();
    }}>
      <label>Title<input className="bq-input" autoFocus required maxLength={500} placeholder="What should this Epic deliver?" value={fields.title}
        onChange={(event) => onChange({ ...fields, title: event.target.value })} /></label>
      <label>Description<textarea className="bq-input" rows={6} maxLength={32_000} value={fields.description} onChange={(event) => onChange({ ...fields, description: event.target.value })}
        placeholder="What it delivers and why, what is in and out of scope, and how to know it is done." /></label>
      <label>Context<textarea className="bq-input" rows={4} maxLength={32_000} value={fields.context} onChange={(event) => onChange({ ...fields, context: event.target.value })}
        placeholder="Background, constraints, links and decisions that planning its work will need." /></label>
      <p className="create-epic__note"><LockKeyhole size={13} aria-hidden="true" />Stays local until you choose Push to remote.</p>
      <div className="create-epic__actions">
        <button type="submit" className="bq-button bq-button--primary" disabled={busy || fields.title.trim() === ''}>{busy ? 'Creating…' : 'Create Epic'}</button>
      </div>
    </form>
  );
}

/** The workspace's open issues or tickets that can become an Epic: not one already, and not work in another Epic. */
function Picker({ workspaceId, issueProvider, source, marked, busy, onPick }: {
  workspaceId: string;
  issueProvider: string;
  source: 'GITHUB' | 'JIRA';
  marked: ReadonlySet<string>;
  busy: boolean;
  onPick: (item: UnifiedWorkItem) => void;
}) {
  const [items, setItems] = useState<UnifiedWorkItem[] | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [query, setQuery] = useState('');
  useEffect(() => {
    let active = true;
    void window.bridge.listUnifiedWorkItems(workspaceId).then((value) => { if (active) setItems(value); })
      .catch((cause) => { if (active) setFailure(errorText(cause)); });
    return () => { active = false; };
  }, [workspaceId]);
  const words = query.trim().toLowerCase();
  const found = (items ?? []).filter((item) => item.source === source && !item.closed && item.reference !== null && !marked.has(item.id)
    && item.work?.isEpic !== true && !item.work?.epicId && (words === '' || `${item.reference.label} ${item.title}`.toLowerCase().includes(words)));
  const issue = source === 'GITHUB';
  return (
    <div className="create-epic__picker">
      <label className="create-epic__search">
        <Search size={14} aria-hidden="true" />
        <input className="bq-input" autoFocus aria-label={issue ? 'Find an open issue' : 'Find a Jira ticket'} placeholder={issue ? 'Find an issue by number or title' : 'Find a ticket by key or title'}
          value={query} onChange={(event) => setQuery(event.target.value)} />
      </label>
      <div className="create-epic__results" role="list" aria-label={issue ? 'Open issues' : 'Jira tickets'}>
        {items === null && failure === null && <p className="create-epic__hint">Loading…</p>}
        {failure !== null && <div className="create-epic__error" role="alert">{failure}</div>}
        {found.slice(0, MAX_LISTED).map((item) => (
          <div role="listitem" className="create-epic__result" key={item.id}>
            <span className="create-epic__ref">{item.reference?.label}</span>
            <span className="create-epic__result-title" title={item.title}>{item.title}</span>
            <button type="button" className="bq-button" disabled={busy} onClick={() => onPick(item)}>Mark as Epic</button>
          </div>
        ))}
        {items !== null && found.length === 0 && (
          <p className="create-epic__hint">{words !== '' ? 'Nothing matches.' : issue ? 'No open issue in this workspace can become an Epic.'
            : 'No Jira ticket in this workspace can become an Epic. Assign a ticket to this workspace in Jira first.'}</p>
        )}
      </div>
      <p className="create-epic__note"><LockKeyhole size={13} aria-hidden="true" />The Epic is local to ByteQuay; the {issue ? `issue on ${issueProvider}` : 'ticket in Jira'} stays unchanged.</p>
    </div>
  );
}
