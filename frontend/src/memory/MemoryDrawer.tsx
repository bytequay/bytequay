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
import { useState } from 'react';
import { Quote, Send, TriangleAlert } from 'lucide-react';
import MarkdownBody from '../pulls/MarkdownBody';
import { Icon } from '../repository-context/icons';
import type { MemoryDto } from './contracts';
import { age, KIND_LABEL, memoryFlags, sourceHead, titleRepeated } from './model';

const MAX_TITLE = 80;
const MAX_TEXT_BYTES = 600;
const utf8 = new TextEncoder();

/** One memory: its title and text, where it applies, the sources it rests on with their quotes, and the user's edit, restore and delete. */
export function MemoryDrawer({ memory, busy, error, now, onClose, onSave, onRestore, onKeep, onDelete, onOpen }: {
  memory: MemoryDto; busy: boolean; error: string | null; now: number; onClose: () => void; onSave: (title: string, text: string) => Promise<boolean>; onRestore: () => void; onKeep: () => void;
  onDelete: () => void; onOpen: (url: string) => void;
}) {
  const [draft, setDraft] = useState<{ title: string; text: string } | null>(null);
  const flags = memoryFlags(memory);
  const size = draft === null ? 0 : utf8.encode(draft.text.trim()).length;
  return <>
    <div className="rcp-drawer__head"><div className="rcp-drawer__top">
      <span className="mem-kind">{KIND_LABEL[memory.kind].one}</span>
      <span className="mem-muted">{memory.id}</span>
      {memory.origin.user && <span className="mem-muted">Yours · agents won’t change it</span>}
      <span className="rcp-spacer" />
      <button type="button" className="rcp-close" aria-label="Close" onClick={onClose}><Icon name="close" size={14} /></button>
    </div></div>
    <div className="rcp-drawer__body mem-drawer">
      {error !== null && <div className="rcp-error" role="alert">{error}</div>}
      {draft === null
        ? <>
          {!titleRepeated(memory) && <p className="mem-drawer__title">{memory.title}</p>}
          <p className="mem-drawer__text">{memory.text}</p>
        </>
        : <>
          <label className="mem-edit">Title
            <input disabled={busy} value={draft.title} maxLength={MAX_TITLE} onChange={event => setDraft({ ...draft, title: event.target.value })} />
          </label>
          <label className="mem-edit">{KIND_LABEL[memory.kind].one}
            <textarea disabled={busy} value={draft.text} rows={5} autoFocus onChange={event => setDraft({ ...draft, text: event.target.value })} />
            <span className={`mem-edit__hint${size > MAX_TEXT_BYTES ? ' is-over' : ''}`}>{size} of {MAX_TEXT_BYTES} bytes, one paragraph. Editing makes it yours:
              agents won’t change it.</span>
          </label>
        </>}
      <div className="mem-meta"><span>Applies to</span>
        {memory.scope.length === 0 ? <span className="mem-scope">Repository</span> : memory.scope.map(path => <span key={path} className="mem-scope is-path">{path}</span>)}
      </div>
      <div className="mem-meta"><span>{memory.origin.description}</span>
        <span className="mem-count" title={`Cited ${memory.cited} ${memory.cited === 1 ? 'time' : 'times'} by findings or answers`}><Quote size={13} aria-hidden="true" />
          {memory.cited}</span>
        <span className="mem-count" title={`Delivered to agents ${memory.delivered} ${memory.delivered === 1 ? 'time' : 'times'}`}><Send size={13} aria-hidden="true" />
          {memory.delivered}</span>
        <span>{age(memory.createdAt, now)}</span>
      </div>
      {flags.map(flag => <div key={flag.title} className="mem-flag"><TriangleAlert size={15} aria-hidden="true" />
        <span className="mem-flag__text">{flag.title}: {flag.detail}</span>
        {flag.title === 'Reported wrong' && <button type="button" className="rcp-link" disabled={busy} onClick={onKeep}>Keep as it is</button>}
      </div>)}
      <h3 className="mem-label">Sources · {memory.sources.length}</h3>
      {memory.sources.map((source, index) => {
        const line = sourceHead(source);
        const url = source.url;
        return <div className="mem-source" key={index}>
          <div className="mem-source__head">{line.head}</div>
          {source.quote !== '' && <MarkdownBody className="mem-source__quote" body={source.quote} />}
          {(line.note !== '' || (line.link !== null && url !== null)) && <div className="mem-source__foot"><span>{line.note}</span>
            {line.link !== null && url !== null && <button type="button" className="rcp-link" onClick={() => onOpen(url)}>{line.link}</button>}</div>}
        </div>;
      })}
      {memory.previous !== null && <>
        <h3 className="mem-label">Previous version</h3>
        <div className="mem-previous">
          {!titleRepeated(memory.previous) && <div className="mem-previous__title">{memory.previous.title}</div>}
          {memory.previous.text}
        </div>
        <button type="button" className="rcp-link" disabled={busy || draft !== null} onClick={onRestore}>Restore</button>
      </>}
    </div>
    <div className="rcp-drawer__foot mem-foot">
      {draft === null
        ? <>
          <button type="button" className="rcp-btn rcp-btn--sm" disabled={busy} onClick={() => setDraft({ title: memory.title, text: memory.text })}>Edit</button>
          <button type="button" className="rcp-btn rcp-btn--sm rcp-btn--danger" disabled={busy} onClick={onDelete}>Delete</button>
        </>
        : <>
          <button type="button" className="rcp-btn rcp-btn--primary rcp-btn--sm"
            disabled={busy || draft.title.trim() === '' || draft.text.trim() === '' || size > MAX_TEXT_BYTES}
            onClick={() => { void onSave(draft.title, draft.text).then(saved => { if (saved) setDraft(null); }); }}>Save</button>
          <button type="button" className="rcp-btn rcp-btn--sm" disabled={busy} onClick={() => setDraft(null)}>Cancel</button>
        </>}
    </div>
  </>;
}
