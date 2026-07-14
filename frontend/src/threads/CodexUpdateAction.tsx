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
import { useEffect, useState } from 'react';

export function isCodexUpdateRequired(message: string): boolean {
  return /requires a newer version of Codex/i.test(message);
}

/** Action shown only for Codex's explicit incompatible-CLI failure. */
export function CodexUpdateAction({
  message,
  onUpdated,
}: {
  message: string;
  onUpdated?: () => Promise<void>;
}) {
  const required = isCodexUpdateRequired(message);
  const [version, setVersion] = useState<string | null>(null);
  const [updating, setUpdating] = useState(false);
  const [status, setStatus] = useState<string | null>(null);

  useEffect(() => {
    if (!required) return;
    let cancelled = false;
    void window.bridge.getCodexCliVersion()
      .then(info => { if (!cancelled) setVersion(info.version); })
      .catch(() => { if (!cancelled) setVersion('unknown'); });
    return () => { cancelled = true; };
  }, [required]);

  if (!required) return null;

  const update = async () => {
    if (updating) return;
    const from = version === null ? '' : ` (installed: ${version})`;
    if (!window.confirm(`ByteQuay will run \`codex update\`${from}. Continue?`)) return;
    setUpdating(true);
    setStatus(null);
    try {
      const result = await window.bridge.updateCodexCli();
      setVersion(result.version);
      if (onUpdated !== undefined) {
        await onUpdated();
        setStatus(`Updated to ${result.version}; retry queued.`);
      }
      else {
        setStatus(`Updated to ${result.version}. Resume the thread to retry.`);
      }
    }
    catch (e) {
      setStatus(e instanceof Error ? e.message : String(e));
    }
    finally {
      setUpdating(false);
    }
  };

  return (
    <div style={wrapStyle}>
      <span>Installed Codex CLI: {version ?? 'checking…'}</span>
      <button type="button" onClick={() => { void update(); }} disabled={updating} style={buttonStyle}>
        {updating ? 'Updating…' : onUpdated === undefined ? 'Update Codex CLI' : 'Update & retry'}
      </button>
      {status !== null && <span role="status">{status}</span>}
    </div>
  );
}

const wrapStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 8,
  marginTop: 8, fontSize: 12,
};

const buttonStyle: React.CSSProperties = {
  border: '1px solid currentColor', borderRadius: 5,
  background: 'transparent', color: 'inherit', cursor: 'pointer',
  padding: '4px 8px', fontWeight: 700,
};
