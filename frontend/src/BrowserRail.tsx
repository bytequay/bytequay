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
import { Globe, PanelLeftClose, PanelLeftOpen, Pencil, Plus, Ship, TriangleAlert, X } from 'lucide-react';
import { useRenameDraft } from './renameDraft';
import type { InAppBrowserState, InAppBrowserTab } from './types';

type Props = {
  state: InAppBrowserState;
  expanded: boolean;
  newPageOpen: boolean;
  error: string | null;
  onToggle: () => void;
  onApp: () => void;
  onNewPage: () => void;
  onSelect: (id: string) => void;
  onClose: (id: string) => void;
  onRename: (id: string, name: string) => void;
};

function PageIcon({ tab }: { tab: InAppBrowserTab }) {
  const [failedUrl, setFailedUrl] = useState<string | null>(null);
  return (
    <span className="browser-rail__icon" aria-hidden="true">
      {tab.favicon && tab.favicon !== failedUrl
        ? <img src={tab.favicon} alt="" referrerPolicy="no-referrer" onError={() => setFailedUrl(tab.favicon)} />
        : <Globe size={16} strokeWidth={1.7} />}
      {tab.loading && <span className="browser-rail__loading" />}
    </span>
  );
}

function PageRow({ tab, active, expanded, onSelect, onClose, onRename }: {
  tab: InAppBrowserTab;
  active: boolean;
  expanded: boolean;
  onSelect: (id: string) => void;
  onClose: (id: string) => void;
  onRename: (id: string, name: string) => void;
}) {
  const label = tab.name ?? (tab.title || tab.url);
  const rename = useRenameDraft(label, name => onRename(tab.id, name));
  return (
    <div className={`browser-rail__row${active ? ' is-active' : ''}`}>
      {rename.editing
        ? <span className="browser-rail__page"><PageIcon tab={tab} /><input className="browser-rail__rename" {...rename.inputProps} aria-label="Page name" /></span>
        : <>
          <button type="button" className="browser-rail__page" onClick={() => onSelect(tab.id)}
            title={`${label}\n${tab.url}${tab.pinned ? '\nPinned' : ''}`}
            aria-label={`${label}${tab.pinned ? ' (pinned)' : ''}`}
            aria-current={active ? 'page' : undefined}>
            <PageIcon tab={tab} />
            {expanded && <span className="browser-rail__label">{label}</span>}
          </button>
          {expanded && <button type="button" className="browser-rail__tool" onClick={rename.start}
            title="Rename this page" aria-label={`Rename ${label}`}><Pencil size={13} /></button>}
          {expanded && <button type="button" className="browser-rail__tool browser-rail__close" onClick={() => onClose(tab.id)}
            title={tab.pinned ? 'Minimize page and keep pin' : 'Close page'} aria-label={`${tab.pinned ? 'Minimize' : 'Close'} ${label}`}><X size={14} /></button>}
        </>}
    </div>
  );
}

export default function BrowserRail({ state, expanded, newPageOpen, error, onToggle, onApp, onNewPage, onSelect, onClose, onRename }: Props) {
  const pinned = state.tabs.filter(tab => tab.pinned);
  const unpinned = state.tabs.filter(tab => !tab.pinned);
  const row = (tab: InAppBrowserTab) => (
    <PageRow key={tab.id} tab={tab} active={state.activeTabId === tab.id} expanded={expanded}
      onSelect={onSelect} onClose={onClose} onRename={onRename} />
  );
  return (
    <nav id="browser-rail" className={`browser-rail${expanded ? ' is-expanded' : ''}`} aria-label="Web pages">
      <button type="button" className="browser-rail__action browser-rail__toggle" onClick={onToggle}
        title={expanded ? 'Collapse web pages' : 'Expand web pages'} aria-label={expanded ? 'Collapse web pages' : 'Expand web pages'} aria-expanded={expanded}>
        {expanded ? <PanelLeftClose size={16} strokeWidth={1.7} /> : <PanelLeftOpen size={16} strokeWidth={1.7} />}
        {expanded && <span>Web pages</span>}
      </button>
      <button type="button" className={`browser-rail__action${state.activeTabId === null && !newPageOpen ? ' is-active' : ''}`}
        onClick={onApp} title="Back to ByteQuay" aria-label="Back to ByteQuay" aria-current={state.activeTabId === null && !newPageOpen ? 'page' : undefined}>
        <Ship size={16} strokeWidth={1.7} />{expanded && <span>ByteQuay</span>}
      </button>
      <div className="browser-rail__pages">
        {pinned.length > 0 && <div className="browser-rail__group" role="group" aria-label="Pinned pages">{pinned.map(row)}</div>}
        {pinned.length > 0 && <hr />}
        {unpinned.length > 0 && <div className="browser-rail__group" role="group" aria-label="Open pages">{unpinned.map(row)}</div>}
        <button type="button" className={`browser-rail__action browser-rail__new${newPageOpen ? ' is-active' : ''}`}
          onClick={onNewPage} title="Open a new web page" aria-label="Open a new web page">
          <Plus size={18} strokeWidth={1.7} />{expanded && <span>New page</span>}
        </button>
        {expanded && state.tabs.length === 0 && <p className="browser-rail__empty">Open a page, then pin it here.</p>}
      </div>
      {error && <div className="browser-rail__error" role="alert" title={error}><TriangleAlert size={16} strokeWidth={1.7} />{expanded && <span>{error}</span>}</div>}
    </nav>
  );
}
