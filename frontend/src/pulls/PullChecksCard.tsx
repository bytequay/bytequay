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
import type { CheckFailureDto } from '../types';
import { CiFailIcon, CiPassIcon } from './atoms';
import { isCiErrorLine } from './detailModel';
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

/** Null until the user first unfolds the row — failure detail is fetched from
 *  GitHub on demand rather than cached, since it's only ever read when someone
 *  opens a failing row. */
type AnnotationState = 'loading' | 'error' | CheckFailureDto;

const annotationBox = {
  margin: '2px 18px 8px 46px',
  padding: '9px 11px',
  border: '1px solid #eef1f4',
  borderRadius: 7,
  background: '#fbfcfd',
} as const;

const monoBlock = {
  margin: '2px 0 0',
  font: '11.5px/1.55 ui-monospace, SFMono-Regular, Menlo, monospace',
  color: '#3f4650', whiteSpace: 'pre-wrap', wordBreak: 'break-word',
} as const;

/** Monospace block with the error lines picked out, so the actual failure
 *  stands out from the surrounding build chatter. One block element per line
 *  so the highlight covers the whole row, not just the glyphs. */
function MonoLines({ text, scroll }: { text: string; scroll?: boolean }) {
  return (
    <pre style={scroll === true ? { ...monoBlock, maxHeight: 260, overflow: 'auto' } : monoBlock}>
      {text.split('\n').map((line, i) => (
        <div key={i} style={isCiErrorLine(line) ? { color: '#cf222e', fontWeight: 600 } : undefined}>
          {line === '' ? ' ' : line}
        </div>
      ))}
    </pre>
  );
}

function Failure({ state }: { state: AnnotationState }) {
  if (state === 'loading') {
    return <div style={annotationBox}><span style={{ fontSize: 12, color: '#8b949e' }}>Loading failure detail…</span></div>;
  }
  if (state === 'error') {
    return <div style={annotationBox}><span style={{ fontSize: 12, color: '#cf222e' }}>Couldn&apos;t load failure detail.</span></div>;
  }
  if (state.annotations.length > 0) {
    return (
      <div style={annotationBox}>
        {state.annotations.map((a, i) => (
          <div key={i} style={{ marginTop: i === 0 ? 0 : 11 }}>
            {a.title !== null && a.title !== '' && (
              <div style={{ fontSize: 12.5, fontWeight: 600, color: '#1f2328' }}>{a.title}</div>
            )}
            {a.message !== null && a.message !== '' && <MonoLines text={a.message} />}
            {a.path !== null && a.path !== '' && (
              <div style={{ marginTop: 3, fontSize: 11.5, color: '#8b949e', wordBreak: 'break-all' }}>
                {a.path}{a.startLine !== null && `#L${a.startLine}`}
              </div>
            )}
          </div>
        ))}
      </div>
    );
  }
  if (state.log !== '') {
    // Log excerpt, so it gets its own scroll rather than stretching the card.
    return (
      <div style={annotationBox}>
        <div style={{ fontSize: 11.5, color: '#8b949e', marginBottom: 5 }}>
          No annotation published — showing the end of the job log.
        </div>
        <MonoLines text={state.log} scroll />
      </div>
    );
  }
  // External CI, or a log GitHub no longer exposes.
  return <div style={annotationBox}><span style={{ fontSize: 12, color: '#8b949e' }}>No failure detail published for this check.</span></div>;
}

function CheckRow({ row, repo }: { row: ChecksGroup['rows'][number]; repo: string }) {
  const [open, setOpen] = useState(false);
  const [annotations, setAnnotations] = useState<AnnotationState | null>(null);
  // Only a failing remote check is worth unfolding: a green one has nothing
  // to explain, and a local run never had a GitHub check-run id to key off.
  const checkRunId = row.state === 'fail' ? row.checkRunId : null;
  const toggle = () => {
    if (checkRunId === null) {
      return;
    }
    const opening = !open;
    setOpen(opening);
    // Refetch on a re-open only after a failure, so a transient error doesn't
    // poison the row until the card remounts.
    if (!opening || (annotations !== null && annotations !== 'error')) {
      return;
    }
    setAnnotations('loading');
    window.bridge.fetchCheckFailure(repo, checkRunId)
      .then(failure => setAnnotations(failure))
      .catch(() => setAnnotations('error'));
  };
  return (
    <>
      <div
        className={checkRunId !== null ? 'pl-hov-btn' : undefined}
        onClick={toggle}
        style={{
          display: 'flex', alignItems: 'center', gap: 10, padding: '6px 18px 6px 24px',
          cursor: checkRunId !== null ? 'pointer' : 'default',
        }}
      >
        {/* Chevron sits in the gutter so the icon column stays put whether or
            not the row expands: 24 + 12 + the 10px gap lands it back at 46. */}
        {checkRunId !== null
          ? <Chevron open={open} />
          : <span style={{ width: 12, flexShrink: 0 }} />}
        <RowIcon state={row.state} />
        <span style={{ fontSize: 13, color: '#1f2328', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{row.name}</span>
        <span style={{ fontSize: 12, color: '#8b949e' }}>{row.note}</span>
        {row.time !== '' && (
          <span title={row.title} style={{ marginLeft: 'auto', paddingLeft: 10, fontSize: 12, color: '#8b949e', whiteSpace: 'nowrap', flexShrink: 0 }}>
            {row.time}
          </span>
        )}
      </div>
      {open && annotations !== null && <Failure state={annotations} />}
    </>
  );
}

export default function PullChecksCard({ model, repo }: { model: ChecksModel; repo: string }) {
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
              {/* Key on the check-run id too: the row now owns fold state, so
                  two matrix legs sharing a name must not share it. */}
              {g.rows.map(cr => <CheckRow key={`${cr.name}:${cr.checkRunId ?? ''}`} row={cr} repo={repo} />)}
            </div>
          )}
        </div>
      ))}
    </div>
  );
}
