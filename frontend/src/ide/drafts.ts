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
/* eslint-disable import/no-unresolved -- Pinned text model service. */
import type { IdeRuntime } from './runtime';

export type SourceDraft = { path: string; base: string; text: string };
const KEY = 'bytequay.ide.sourceDrafts';
const RECOVERED = 'bytequay.ide.recoveredDrafts';

export function sourceDrafts(storage: Pick<Storage, 'getItem'>, key = KEY): SourceDraft[] {
  const raw: unknown = JSON.parse(storage.getItem(key) ?? '[]');
  if (!Array.isArray(raw) || raw.some(item => !item || typeof item.path !== 'string' || !item.path || item.path.startsWith('/') || item.path.split('/').some((part: string) => part === '..' || part === '.') || typeof item.base !== 'string' || typeof item.text !== 'string')) {
    throw new Error('Saved editor drafts could not be read. They have been retained for recovery.');
  }
  return raw;
}

/** Paths, rather than ephemeral server authorities, identify drafts across a runtime restart. */
export function trackSourceDrafts(runtime: IdeRuntime, onError: (message: string) => void) {
  const bases = new Map<string, string>();

  const pathOf = (document: { uri: { scheme: string; path: string } }) => document.uri.scheme === 'vscode-remote'
    && document.uri.path.startsWith(runtime.workspace.path + '/') ? document.uri.path.slice(runtime.workspace.path.length + 1) : null;
  const remember = (document: import('vscode').TextDocument) => {
    const path = pathOf(document);
    if (path !== null && !document.isDirty) bases.set(path, document.getText());
  };
  runtime.api.workspace.textDocuments.forEach(remember);
  const opened = runtime.api.workspace.onDidOpenTextDocument(remember);
  const changed = runtime.api.workspace.onDidChangeTextDocument(event => {
    const path = pathOf(event.document);
    if (path === null) return;
    let drafts: Map<string, SourceDraft>;
    try { drafts = new Map(sourceDrafts(localStorage).map(draft => [draft.path, draft])); }
    catch { onError('Saved editor drafts could not be read. Keep this editor open until your changes are saved.'); return; }
    const existing = drafts.get(path);
    if (!event.document.isDirty) { bases.set(path, event.document.getText()); drafts.delete(path); }
    else {
      const base = existing?.base ?? bases.get(path);
      if (base === undefined) { onError(`Could not record the original contents of ${path}. Keep this editor open until saved.`); return; }
      drafts.set(path, { path, base, text: event.document.getText() });
    }
    try { localStorage.setItem(KEY, JSON.stringify([...drafts.values()])); }
    catch { onError('Editor draft storage is full. Save your changes before closing ByteQuay.'); }
  });
  const saved = runtime.api.workspace.onDidSaveTextDocument(document => {
    const path = pathOf(document);
    if (path !== null) {
      bases.set(path, document.getText());
      try { localStorage.setItem(KEY, JSON.stringify(sourceDrafts(localStorage).filter(draft => draft.path !== path))); }
      catch { onError('Could not update the saved draft. Its previous contents have been retained.'); }
    }
  });
  return { dispose: () => { opened.dispose(); changed.dispose(); saved.dispose(); } };
}

export async function restoreSourceDrafts(runtime: IdeRuntime, onConflict: (draft: SourceDraft) => void) {
  sourceDrafts(localStorage, RECOVERED).forEach(onConflict);
  const conflict = (draft: SourceDraft) => {
    const recovered = sourceDrafts(localStorage, RECOVERED);
    if (!recovered.some(item => item.path === draft.path && item.text === draft.text)) {
      localStorage.setItem(RECOVERED, JSON.stringify([...recovered, draft]));
      onConflict(draft);
    }
    localStorage.setItem(KEY, JSON.stringify(sourceDrafts(localStorage).filter(item => item.path !== draft.path)));
  };
  for (const draft of sourceDrafts(localStorage)) {
    const uri = runtime.workspace.with({ path: `${runtime.workspace.path}/${draft.path}` });
    try {
      const document = await runtime.api.workspace.openTextDocument(uri);
      if (document.getText() !== draft.base && document.getText() !== draft.text) { conflict(draft); continue; }
      if (document.getText() === draft.text) {
        if (!document.isDirty) localStorage.setItem(KEY, JSON.stringify(sourceDrafts(localStorage).filter(item => item.path !== draft.path)));
        continue;
      }
      const edit = new runtime.api.WorkspaceEdit();
      edit.replace(document.uri, new runtime.api.Range(document.positionAt(0), document.positionAt(document.getText().length)), draft.text);
      if (!(await runtime.api.workspace.applyEdit(edit))) { conflict(draft); continue; }
      await runtime.api.window.showTextDocument(document, { preview: false, preserveFocus: true });
    }
    catch { conflict(draft); }
  }
}

export function discardRecoveredDraft(draft: SourceDraft) {
  localStorage.setItem(RECOVERED, JSON.stringify(sourceDrafts(localStorage, RECOVERED).filter(item => item.path !== draft.path || item.text !== draft.text)));
}

/** Persist working-copy tabs without the local server's short-lived port. */
export async function restoreNavigation(runtime: IdeRuntime) {
  const saved: unknown = JSON.parse(localStorage.getItem('bytequay.ide.navigation') ?? 'null');
  if (!saved || typeof saved !== 'object' || !('paths' in saved) || !Array.isArray(saved.paths)) return;
  const paths = saved.paths.filter((path): path is string => typeof path === 'string' && path.length > 0 && path.length < 4096
    && !path.startsWith('/') && !path.includes('\0') && !path.split('/').some(part => part === '..' || part === '.')).slice(0, 30);
  for (const path of paths) {
    try { await runtime.api.window.showTextDocument(runtime.workspace.with({ path: `${runtime.workspace.path}/${path}` }), { preview: false, preserveFocus: true }); }
    catch { /* A deleted file must not prevent other tabs or drafts from returning. */ }
  }
  if ('active' in saved && typeof saved.active === 'string' && paths.includes(saved.active)) {
    const editor = await runtime.api.window.showTextDocument(runtime.workspace.with({ path: `${runtime.workspace.path}/${saved.active}` }), { preview: false });
    if ('line' in saved && Number.isSafeInteger(saved.line) && (saved.line as number) >= 0) {
      const line = Math.min(saved.line as number, Math.max(0, editor.document.lineCount - 1));
      editor.selection = new runtime.api.Selection(line, 0, line, 0);
      editor.revealRange(editor.selection);
    }
  }
}

export function trackNavigation(runtime: IdeRuntime, onError: (message: string) => void) {
  const relative = (uri: import('vscode').Uri | undefined) => uri?.scheme === 'vscode-remote' && uri.path.startsWith(runtime.workspace.path + '/')
    ? uri.path.slice(runtime.workspace.path.length + 1) : null;
  const save = () => {
    const paths = runtime.api.window.tabGroups.all.flatMap(group => group.tabs)
      .map(tab => tab.input instanceof runtime.api.TabInputText ? relative(tab.input.uri) : null).filter(path => path !== null).slice(0, 30);
    const active = runtime.api.window.activeTextEditor;
    try { localStorage.setItem('bytequay.ide.navigation', JSON.stringify({ paths, active: relative(active?.document.uri), line: active?.selection.active.line ?? 0 })); }
    catch { onError('Could not save the open tabs for the next editor session.'); }
  };
  const tabs = runtime.api.window.tabGroups.onDidChangeTabs(save);
  const selection = runtime.api.window.onDidChangeTextEditorSelection(save);
  const active = runtime.api.window.onDidChangeActiveTextEditor(save);
  return { dispose: () => { save(); tabs.dispose(); selection.dispose(); active.dispose(); } };
}
