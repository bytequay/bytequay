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

/** Shared style tokens for the workspace dialogs (new-thread,
 *  new-workspace). Same calm visual language as the rest of the
 *  shell — glass card on a soft backdrop, purple accent buttons,
 *  ~140ms transitions. */

export const WS_DIALOG_OVERLAY: React.CSSProperties = {
  position: 'fixed',
  inset: 0,
  background: 'rgba(31, 27, 46, 0.18)',
  backdropFilter: 'blur(4px)',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  zIndex: 100,
};

export const WS_DIALOG_PANEL: React.CSSProperties = {
  width: 520,
  maxWidth: 'calc(100vw - 40px)',
  maxHeight: 'calc(100vh - 60px)',
  overflowY: 'auto',
  background: 'rgba(255, 255, 255, 0.96)',
  border: '1px solid var(--ws-card-border)',
  borderRadius: 14,
  boxShadow: '0 18px 60px rgba(67, 56, 202, 0.25), 0 4px 12px rgba(0,0,0,0.08)',
  padding: '18px 20px',
  color: 'var(--ws-text-1)',
};

export const dialogStyles = {
  header: {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 12,
  } as React.CSSProperties,
  title: {
    margin: 0,
    fontSize: 16,
    fontWeight: 700,
    letterSpacing: '-0.01em',
    display: 'flex',
    alignItems: 'center',
    gap: 10,
  } as React.CSSProperties,
  workspaceChip: {
    fontSize: 11,
    fontWeight: 600,
    padding: '2px 8px',
    borderRadius: 999,
    background: 'var(--ws-accent-soft)',
    color: 'var(--ws-accent-deep)',
  } as React.CSSProperties,
  closeBtn: {
    border: 'none',
    background: 'transparent',
    color: 'var(--ws-text-3)',
    cursor: 'pointer',
    fontSize: 14,
    padding: 4,
  } as React.CSSProperties,
  textarea: {
    width: '100%',
    minHeight: 90,
    padding: 12,
    fontSize: 13,
    lineHeight: 1.5,
    border: '1px solid var(--ws-card-border)',
    borderRadius: 8,
    resize: 'vertical',
    fontFamily: 'inherit',
    boxSizing: 'border-box',
    background: 'rgba(255, 255, 255, 0.9)',
    color: 'var(--ws-text-1)',
  } as React.CSSProperties,
  input: {
    width: '100%',
    padding: '8px 10px',
    fontSize: 13,
    border: '1px solid var(--ws-card-border)',
    borderRadius: 8,
    background: 'rgba(255, 255, 255, 0.9)',
    color: 'var(--ws-text-1)',
    boxSizing: 'border-box',
  } as React.CSSProperties,
  chipRow: {
    display: 'flex',
    gap: 6,
    marginTop: 10,
    flexWrap: 'wrap',
  } as React.CSSProperties,
  chip: {
    padding: '4px 10px',
    fontSize: 11,
    border: '1px solid var(--ws-card-border)',
    borderRadius: 999,
    background: 'rgba(255, 255, 255, 0.85)',
    color: 'var(--ws-text-3)',
    cursor: 'pointer',
  } as React.CSSProperties,
  helperRow: {
    marginTop: 8,
    fontSize: 11,
    color: 'var(--ws-text-3)',
  } as React.CSSProperties,
  modeSectionLabel: {
    marginTop: 16,
    marginBottom: 8,
    fontSize: 10,
    fontWeight: 600,
    textTransform: 'uppercase',
    letterSpacing: '0.06em',
    color: 'var(--ws-text-3)',
  } as React.CSSProperties,
  modeRow: {
    display: 'grid',
    gridTemplateColumns: '1fr 1fr',
    gap: 8,
  } as React.CSSProperties,
  modeCard: (active: boolean): React.CSSProperties => ({
    textAlign: 'left',
    padding: 10,
    borderRadius: 10,
    border: active
        ? '2px solid var(--ws-accent)'
        : '1px solid var(--ws-card-border)',
    background: active ? 'var(--ws-accent-soft)' : '#fff',
    cursor: 'pointer',
    transition: 'border-color var(--ws-fast), background var(--ws-fast)',
  }),
  modeCardLabel: {
    fontSize: 12,
    fontWeight: 600,
    color: 'var(--ws-text-1)',
  } as React.CSSProperties,
  modeCardDesc: {
    marginTop: 4,
    fontSize: 11,
    color: 'var(--ws-text-3)',
    lineHeight: 1.4,
  } as React.CSSProperties,
  fieldLabel: {
    fontSize: 10,
    fontWeight: 600,
    textTransform: 'uppercase',
    letterSpacing: '0.06em',
    color: 'var(--ws-text-3)',
    marginBottom: 4,
    display: 'block',
  } as React.CSSProperties,
  field: {
    marginTop: 12,
  } as React.CSSProperties,
  footer: {
    marginTop: 18,
    paddingTop: 10,
    borderTop: '1px solid var(--ws-card-border)',
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    gap: 12,
  } as React.CSSProperties,
  footerNote: {
    fontSize: 10,
    color: 'var(--ws-text-3)',
    lineHeight: 1.4,
    flex: 1,
  } as React.CSSProperties,
  footerButtons: {
    display: 'flex',
    gap: 6,
    flexShrink: 0,
  } as React.CSSProperties,
  secondaryBtn: {
    padding: '7px 13px',
    fontSize: 12,
    border: '1px solid var(--ws-card-border)',
    borderRadius: 8,
    background: '#fff',
    color: 'var(--ws-text-2)',
    cursor: 'pointer',
  } as React.CSSProperties,
  primaryBtn: {
    padding: '7px 13px',
    fontSize: 12,
    fontWeight: 600,
    border: 'none',
    borderRadius: 8,
    background: 'var(--ws-accent)',
    color: '#fff',
    cursor: 'pointer',
    transition: 'background var(--ws-fast)',
  } as React.CSSProperties,
  primaryBtnDisabled: {
    padding: '7px 13px',
    fontSize: 12,
    fontWeight: 600,
    border: 'none',
    borderRadius: 8,
    background: 'rgba(124, 58, 237, 0.4)',
    color: '#fff',
    cursor: 'not-allowed',
  } as React.CSSProperties,
};
