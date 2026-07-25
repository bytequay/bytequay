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
import {
  CREDENTIAL_TEMPLATES,
  type CredentialDto,
  type CredentialTemplate,
  type CredentialTestResult,
  type CredentialType,
} from '../../types';
import SettingsPage from '../shared/SettingsPage';
import { CheckIcon, LockIcon, PlusIcon, StarIcon, TrashIcon } from '../shared/icons';
import CredentialEditorModal from './credentials/CredentialEditorModal';

/** The vault groups this surface manages. Each maps to one backend
 *  credential type; rows inside a group are then sectioned by provider. */
type Group = { id: CredentialType; name: string; sub: string; title: string; desc: string; empty: string };

const GROUPS: Group[] = [
  {
    id: 'AI',
    name: 'LLM providers',
    sub: 'Anthropic, OpenAI, DeepSeek…',
    title: 'LLM providers',
    desc: 'Keys the review agent uses to call a model.',
    empty: 'Add a key and ByteQuay will reference it by name from your configs.',
  },
  {
    id: 'ACCOUNT',
    name: 'Git PAT',
    sub: 'Personal access tokens — GitHub',
    title: 'Git PAT',
    desc: 'Tokens ByteQuay uses to read PRs and publish reviews.',
    empty: 'Add your GitHub PAT so the app can sync pull requests and issues.',
  },
];

type RowTestState = { loading: boolean; result: CredentialTestResult | null; error: string | null };

type Props = {
  /** Called the first time a credential is added (any kind). Wired
   *  by the first-run onboarding so adding the user's GitHub PAT here
   *  flips them out of first-run mode. */
  onFirstCredentialAdded?: () => void;
};

function CredentialsPage({ onFirstCredentialAdded }: Props) {
  const [groupId, setGroupId] = useState<CredentialType>('AI');
  const [credentials, setCredentials] = useState<CredentialDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<CredentialDto | 'new' | null>(null);
  const [testStates, setTestStates] = useState<Record<string, RowTestState>>({});

  const group = GROUPS.find(g => g.id === groupId) ?? GROUPS[0];
  const sections = useMemo(
      () => sectionByProvider(credentials.filter(c => c.type === groupId)),
      [credentials, groupId]);

  const load = async () => {
    setLoading(true);
    setError(null);
    setTestStates({});
    try {
      // Fetch the whole vault once so the count badges on the group nav
      // are accurate without per-group refetches.
      setCredentials(await window.bridge.listCredentials());
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, []);

  const handleSave = async (input: {
    type: CredentialType;
    name: string;
    instanceName: string;
    value: string;
    label: string | null;
    notes: string | null;
    setAsDefault: boolean;
    configJson: string | null;
  }) => {
    const wasEmpty = credentials.length === 0;
    // Editing without a fresh secret keeps the existing one — the
    // backend's upsert needs a value, so we only call it when the
    // user actually typed something.
    if (input.value.length > 0) {
      await window.bridge.upsertCredential({
        type: input.type,
        name: input.name,
        instanceName: input.instanceName,
        value: input.value,
        label: input.label,
        notes: input.notes,
        configJson: input.configJson,
      });
    }
    if (input.setAsDefault) {
      await window.bridge.setDefaultCredential(input.type, input.name, input.instanceName);
    }
    setEditing(null);
    await load();
    if (wasEmpty && onFirstCredentialAdded !== undefined) onFirstCredentialAdded();
  };

  const handleDelete = async (cred: CredentialDto) => {
    const label = templateFor(cred)?.displayName ?? `${cred.type.toLowerCase()} / ${cred.name}`;
    if (!confirm(`Delete the stored ${label} ("${cred.instanceName}")?`)) return;
    await window.bridge.deleteCredential(cred.type, cred.name, cred.instanceName);
    await load();
  };

  const handleTest = async (cred: CredentialDto) => {
    setTestStates(s => ({ ...s, [cred.id]: { loading: true, result: null, error: null } }));
    try {
      const result = await window.bridge.testCredential(cred.type, cred.name, cred.instanceName);
      setTestStates(s => ({ ...s, [cred.id]: { loading: false, result, error: null } }));
    } catch (e) {
      setTestStates(s => ({
        ...s,
        [cred.id]: { loading: false, result: null, error: e instanceof Error ? e.message : String(e) },
      }));
    }
  };

  const handleSetDefault = async (cred: CredentialDto) => {
    if (cred.isDefault) return;
    try {
      await window.bridge.setDefaultCredential(cred.type, cred.name, cred.instanceName);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  return (
    <SettingsPage
      title="Credentials"
      width={1000}
      subtitle={
        'One vault for the keys ByteQuay needs. Encrypted at rest on this machine '
        + '(AES-256-GCM) — config files reference rows by name, never the key.'
      }
      action={
        <button className="sv2-btn sv2-btn--dark" type="button" style={{ marginTop: 4 }} onClick={() => setEditing('new')}>
          <PlusIcon size={13} />Add credential
        </button>
      }
    >
      <div className="sv2-cred">
        <div className="sv2-cred__nav">
          {GROUPS.map(g => {
            const count = credentials.filter(c => c.type === g.id).length;
            return (
              <button
                key={g.id}
                type="button"
                className={'sv2-cred__group' + (g.id === groupId ? ' sv2-cred__group--on' : '')}
                onClick={() => { setGroupId(g.id); setTestStates({}); }}
              >
                <span className="sv2-cred__group-top">
                  <span className="sv2-cred__group-name">{g.name}</span>
                  <span className="sv2-cred__count">{count}</span>
                </span>
                <span className="sv2-cred__group-sub">{g.sub}</span>
              </button>
            );
          })}
          <div className="sv2-cred__lock">
            <span style={{ flexShrink: 0, marginTop: 1, display: 'inline-flex' }}><LockIcon size={13} /></span>
            <span>Vault unlocked with your macOS login. Keys never leave this Mac.</span>
          </div>
        </div>

        <div className="sv2-cred__body">
          <div className="sv2-cred__heading">
            <strong>{group.title}</strong>
            <span>{group.desc}</span>
          </div>

          {error !== null && <div className="sv2-error" role="alert">{error}</div>}
          {loading && <div className="sv2-loading">Loading…</div>}

          {!loading && sections.map(section => (
            <div className="sv2-cred__section" key={section.provider}>
              <div className="sv2-cred__section-head">
                {section.provider}
                <span>{section.rows.length} {section.rows.length === 1 ? 'instance' : 'instances'}</span>
              </div>
              {section.rows.map(c => (
                <CredentialRow
                  key={c.id}
                  cred={c}
                  test={testStates[c.id]}
                  onSetDefault={() => { void handleSetDefault(c); }}
                  onTest={() => { void handleTest(c); }}
                  onEdit={() => setEditing(c)}
                  onDelete={() => { void handleDelete(c); }}
                />
              ))}
            </div>
          ))}

          {!loading && sections.length === 0 && error === null && (
            <div className="sv2-empty">
              <div className="sv2-empty__title">Nothing stored in this group yet</div>
              <div className="sv2-empty__body">{group.empty}</div>
              <button className="sv2-btn" type="button" style={{ marginTop: 14 }} onClick={() => setEditing('new')}>
                Add credential
              </button>
            </div>
          )}
        </div>
      </div>

      {editing !== null && (
        <CredentialEditorModal
          filterType={editing === 'new' ? groupId : editing.type}
          existing={editing === 'new' ? undefined : editing}
          onClose={() => setEditing(null)}
          onSave={handleSave}
          onTest={editing === 'new' ? undefined : async () => {
            const cred = editing;
            return window.bridge.testCredential(cred.type, cred.name, cred.instanceName);
          }}
        />
      )}
    </SettingsPage>
  );
}

function CredentialRow({ cred, test, onSetDefault, onTest, onEdit, onDelete }: {
  cred: CredentialDto;
  test: RowTestState | undefined;
  onSetDefault: () => void;
  onTest: () => void;
  onEdit: () => void;
  onDelete: () => void;
}) {
  const testing = test?.loading === true;
  const passed = test?.error === null && test?.result?.ok === true;
  const failed = test !== undefined && !testing && (test.error !== null || test.result?.ok === false);
  const kind = cred.type === 'AI' ? 'API key' : 'PAT';

  return (
    <div className="sv2-cred__row">
      <button
        type="button"
        className={'sv2-cred__star' + (cred.isDefault ? ' sv2-cred__star--on' : '')}
        title={cred.isDefault ? 'Default for this provider' : 'Promote this instance to the default'}
        aria-label={cred.isDefault ? 'Default instance' : 'Make default'}
        onClick={onSetDefault}
      >
        <StarIcon filled={cred.isDefault} />
      </button>

      <div className="sv2-cred__main">
        <div className="sv2-cred__name-row">
          <span className="sv2-cred__name">{cred.instanceName}</span>
          {cred.label !== null && cred.label !== '' && <span className="sv2-cred__chip">{cred.label}</span>}
          <span className={'sv2-cred__kind ' + (cred.type === 'AI' ? 'sv2-cred__kind--key' : 'sv2-cred__kind--pat')}>
            {kind}
          </span>
          {cred.isDefault && <span className="sv2-cred__default">default</span>}
        </div>
        {/* The vault never hands the plaintext back, so the masked preview
            is the whole story — there is nothing to reveal or copy. */}
        <span className="sv2-cred__preview">{cred.preview}</span>
        {cred.notes !== null && cred.notes !== '' && <span className="sv2-cred__note">{cred.notes}</span>}
        <span className="sv2-cred__meta">
          Added {formatTs(cred.createdAt)} · updated {formatTs(cred.updatedAt)}
          {cred.lastUsedAt !== null && ` · last used ${formatTs(cred.lastUsedAt)}`}
        </span>
        {failed && (
          <span className="sv2-cred__result sv2-cred__result--bad">
            {test.error ?? test.result?.message}
          </span>
        )}
      </div>

      <div className="sv2-cred__actions">
        <button
          className={'sv2-btn sv2-btn--sm' + (passed ? ' sv2-btn--ok' : '')}
          type="button"
          disabled={testing}
          onClick={onTest}
        >
          {testing && <span className="sv2-dot-spinner" />}
          {passed && <CheckIcon size={12} />}
          {testing ? 'Testing…' : passed ? 'Valid' : 'Test'}
        </button>
        <button className="sv2-btn sv2-btn--sm" type="button" onClick={onEdit}>Edit</button>
        <button className="sv2-icon-btn" type="button" title="Delete" aria-label="Delete" onClick={onDelete}>
          <TrashIcon size={13} />
        </button>
      </div>
    </div>
  );
}

function templateFor(cred: CredentialDto): CredentialTemplate | undefined {
  return CREDENTIAL_TEMPLATES.find(t => t.type === cred.type && t.name === cred.name);
}

/** Groups a type's rows under their provider heading, default row first. */
function sectionByProvider(rows: CredentialDto[]): { provider: string; rows: CredentialDto[] }[] {
  const map = new Map<string, { provider: string; rows: CredentialDto[] }>();
  for (const c of rows) {
    let entry = map.get(c.name);
    if (entry === undefined) {
      entry = { provider: templateFor(c)?.displayName ?? c.name, rows: [] };
      map.set(c.name, entry);
    }
    entry.rows.push(c);
  }
  for (const entry of map.values()) {
    entry.rows.sort((a, b) => (a.isDefault === b.isDefault ? a.id - b.id : a.isDefault ? -1 : 1));
  }
  return Array.from(map.values());
}

function formatTs(iso: string | null): string {
  if (iso === null || iso === '') return 'never';
  const d = new Date(iso);
  const mins = Math.round((Date.now() - d.getTime()) / 60_000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  if (mins < 1_440) return `${Math.round(mins / 60)}h ago`;
  return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
}

export default CredentialsPage;
