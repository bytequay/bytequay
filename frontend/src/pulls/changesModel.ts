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
import { computeGap, type LoadedGap } from '../diffExpand';
import { parseUnifiedDiff, type DiffHunk, type DiffRow } from '../diffParse';
import { buildFileTree, flattenFileTree } from '../fileTree';
import { authorAssociationLabel } from '../pr/utils';

/**
 * Pure view-model mapping for the Changes tab — patch → the DC prototype's
 * diffRows* / treeRows() row shapes (docs/mockups/design/pr-redesign/Pull
 * Requests.dc.html). Parsing and expand math are reused from diffParse /
 * diffExpand / fileTree; this file only reshapes their output into the
 * template's rows.
 */

export type DiffCodeRow = {
  kind: 'code';
  cls: '' | 'add' | 'del';
  oldLn: string;
  newLn: string;
  sign: string;
  text: string;
  /** Comment anchor — RIGHT for added/context rows, LEFT for deletions. */
  side: 'LEFT' | 'RIGHT';
  line: number;
};

export type DiffRowVm =
  | DiffCodeRow
  | { kind: 'exp'; gapIndex: number; text: string }
  | { kind: 'hunk'; text: string };

function codeRow(r: DiffRow): DiffCodeRow {
  const cls = r.kind === 'add' ? 'add' : r.kind === 'del' ? 'del' : '';
  return {
    kind: 'code',
    cls,
    oldLn: r.oldLine === null ? '' : String(r.oldLine),
    newLn: r.newLine === null ? '' : String(r.newLine),
    sign: cls === 'add' ? '+ ' : cls === 'del' ? '− ' : '  ',
    text: r.content,
    side: r.newLine !== null ? 'RIGHT' : 'LEFT',
    line: r.newLine ?? r.oldLine ?? 0,
  };
}

/**
 * The template's row list for one file card: expand bars ("N unmodified
 * lines") for hidden gaps, hunk-header rows, and code rows. Loaded context
 * (from a clicked expand bar) renders in place of its bar. The after-last-hunk
 * gap has no known size without the file blob, so no bar renders there.
 */
export function diffRowsFor(hunks: DiffHunk[], expanded: ReadonlyMap<number, LoadedGap>): DiffRowVm[] {
  const rows: DiffRowVm[] = [];
  for (let g = 0; g <= hunks.length; g++) {
    const gap = computeGap(hunks, g);
    if (gap !== null && gap.newEnd !== null) {
      const loaded = expanded.get(g);
      let hidden = 0;
      const flushHidden = () => {
        if (hidden === 0) return;
        rows.push({ kind: 'exp', gapIndex: g, text: `${hidden} unmodified lines` });
        hidden = 0;
      };
      for (let n = gap.newStart; n <= gap.newEnd; n++) {
        const text = loaded?.get(n);
        if (text !== undefined) {
          flushHidden();
          rows.push({
            kind: 'code', cls: '', sign: '  ', text,
            oldLn: String(n + gap.oldOffset), newLn: String(n),
            side: 'RIGHT', line: n,
          });
        }
        else hidden++;
      }
      flushHidden();
    }
    if (g < hunks.length) {
      rows.push({ kind: 'hunk', text: hunks[g].header });
      for (const r of hunks[g].rows) {
        if (r.kind !== 'hunk-header') rows.push(codeRow(r));
      }
    }
  }
  return rows;
}

/** New-side line range that fully fills a gap; null when the gap is already
 *  gone or its size is unknown (the after-last-hunk gap). */
export function fullGapRange(hunks: DiffHunk[], gapIndex: number): { from: number; to: number } | null {
  const gap = computeGap(hunks, gapIndex);
  if (gap === null || gap.newEnd === null) return null;
  return { from: gap.newStart, to: gap.newEnd };
}

export type ChangesTreeRow = { name: string; path: string; isDir: boolean; pad: number };

/** The template's treeRows(): dirs first (sorted), single-child directory
 *  chains collapsed into one "a/b/c" row, pad = 6 + depth * 12. */
export function changesTreeRows(paths: string[]): ChangesTreeRow[] {
  return flattenFileTree(buildFileTree(paths, p => p), new Set()).map(r => ({
    name: r.name,
    path: r.path,
    isDir: r.kind === 'dir',
    pad: 6 + r.depth * 12,
  }));
}

/** One-line code snippet for a pending-comment card — the diff row the
 *  comment anchors to, or null when the line isn't part of the patch. */
export function snippetRowFor(patch: string | null, side: 'LEFT' | 'RIGHT', line: number): DiffCodeRow | null {
  for (const hunk of parseUnifiedDiff(patch)) {
    for (const r of hunk.rows) {
      if (r.kind === 'hunk-header') continue;
      const match = side === 'RIGHT' ? r.newLine === line : r.oldLine === line;
      if (match) return { ...codeRow(r), side, line };
    }
  }
  return null;
}

/** "…/valuewriter/BinaryValueWriter.java" — the pending card's file label. */
export function lastTwoSegments(path: string): string {
  const segs = path.split('/');
  if (segs.length <= 2) return path;
  return `…/${segs.slice(-2).join('/')}`;
}

/** GitHub authorAssociation → the template's role pill ("Member", …). */
export function assocLabel(association: string | null): string | null {
  return authorAssociationLabel(association);
}
