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
import { CiFailIcon, CiPassIcon } from './atoms';
import type { ChecksGroup, ChecksModel } from './detailModel';

/** The collapsible checks card from the prototype's shared checks section. */

function Chevron({ open }: { open: boolean }) {
  return (
    <span style={{ display: 'inline-flex', color: '#8b949e', transform: open ? 'rotate(90deg)' : 'rotate(0deg)', transition: 'transform 0.15s' }}>
      <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
        <path d="m9 18 6-6-6-6" />
      </svg>
    </span>
  );
}

function RowIcon({ state }: { state: ChecksGroup['rows'][number]['state'] }) {
  switch (state) {
    case 'fail':
      return <span style={{ color: '#cf222e', display: 'inline-flex', flexShrink: 0 }}><CiFailIcon size={13} /></span>;
    case 'prog':
      return <span style={{ width: 13, height: 13, borderRadius: '50%', border: '2px dashed #bf8700', display: 'inline-block', flexShrink: 0, animation: 'pl-spin 1.6s linear infinite' }} />;
    case 'ok':
      return <span style={{ color: '#1a7f37', display: 'inline-flex', flexShrink: 0 }}><CiPassIcon size={14} /></span>;
    case 'skip':
      return (
        <span style={{ color: '#8b949e', display: 'inline-flex', flexShrink: 0 }}>
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round"><path d="M5 12h14" /></svg>
        </span>
      );
  }
}

export default function PullChecksCard({ model }: { model: ChecksModel }) {
  const [open, setOpen] = useState<Record<string, boolean>>({});
  const isOpen = (g: ChecksGroup) => open[g.key] ?? g.defaultOpen;
  return (
    <div style={{ position: 'relative', border: '1px solid #d5dbe1', borderRadius: 10, background: '#fff', margin: '18px 0' }}>
      <div style={{ display: 'flex', gap: 12, padding: '14px 18px', alignItems: 'flex-start' }}>
        {model.state === 'fail' && (
          <span style={{ width: 26, height: 26, borderRadius: '50%', background: '#cf222e', display: 'inline-flex', alignItems: 'center', justifyContent: 'center', color: '#fff', flexShrink: 0 }}>
            <CiFailIcon size={13} />
          </span>
        )}
        {model.state === 'ok' && (
          <span style={{ width: 26, height: 26, borderRadius: '50%', background: '#1f883d', display: 'inline-flex', alignItems: 'center', justifyContent: 'center', color: '#fff', flexShrink: 0 }}>
            <CiPassIcon size={14} />
          </span>
        )}
        {model.state === 'prog' && (
          <span style={{ width: 22, height: 22, borderRadius: '50%', border: '2.5px dashed #bf8700', display: 'inline-block', flexShrink: 0, margin: 2, animation: 'pl-spin 1.6s linear infinite' }} />
        )}
        <span style={{ minWidth: 0 }}>
          <span style={{ display: 'block', fontSize: 14.5, color: '#17191c' }}>{model.title}</span>
          <span style={{ display: 'block', fontSize: 12.5, color: '#8b949e', marginTop: 2 }}>{model.sub}</span>
        </span>
      </div>
      {model.groups.map(g => (
        <div key={g.key} style={{ borderTop: '1px solid #eef1f4' }}>
          <div
            className="pl-hov-btn"
            onClick={() => setOpen(o => ({ ...o, [g.key]: !isOpen(g) }))}
            style={{ display: 'flex', alignItems: 'center', gap: 9, padding: '10px 18px', cursor: 'pointer' }}
          >
            <Chevron open={isOpen(g)} />
            <span style={{ fontSize: 13.5, color: '#17191c' }}>{g.label}</span>
          </div>
          {isOpen(g) && (
            <div style={{ padding: '0 0 8px' }}>
              {g.rows.map(cr => (
                <div key={cr.name} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '6px 18px 6px 46px' }}>
                  <RowIcon state={cr.state} />
                  <span style={{ fontSize: 13, color: '#1f2328', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{cr.name}</span>
                  <span style={{ fontSize: 12, color: '#8b949e' }}>{cr.note}</span>
                  {cr.time !== '' && (
                    <span title={cr.title} style={{ marginLeft: 'auto', paddingLeft: 10, fontSize: 12, color: '#8b949e', whiteSpace: 'nowrap', flexShrink: 0 }}>
                      {cr.time}
                    </span>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>
      ))}
    </div>
  );
}
