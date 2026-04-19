/*
 * Minimal unified-diff parser.
 *
 * We consume the `patch` string GitHub returns per file (already just the
 * hunk body — GitHub strips the `--- a/file` / `+++ b/file` header) and
 * produce a list of hunks with typed rows suitable for rendering.
 *
 * Purposefully tiny — if we want wrapped/word diffs or rename detection
 * we'll bring in `react-diff-view` in Phase 2c slice D. For now this is
 * enough for the read-only viewer.
 */

export type DiffRowKind = 'context' | 'add' | 'del' | 'hunk-header';

export type DiffRow = {
  kind: DiffRowKind;
  /** 1-based line number in the old file, null for additions and hunk headers */
  oldLine: number | null;
  /** 1-based line number in the new file, null for deletions and hunk headers */
  newLine: number | null;
  /** The source line content without its leading +/-/space sigil */
  content: string;
};

export type DiffHunk = {
  header: string;
  oldStart: number;
  oldCount: number;
  newStart: number;
  newCount: number;
  rows: DiffRow[];
};

const HUNK_HEADER = /^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@(.*)$/;

export function parseUnifiedDiff(patch: string | null | undefined): DiffHunk[] {
  if (!patch) return [];
  const lines = patch.split('\n');
  const hunks: DiffHunk[] = [];
  let current: DiffHunk | null = null;
  let oldLine = 0;
  let newLine = 0;

  for (const line of lines) {
    const m = HUNK_HEADER.exec(line);
    if (m) {
      current = {
        header: line,
        oldStart: Number(m[1]),
        oldCount: m[2] ? Number(m[2]) : 1,
        newStart: Number(m[3]),
        newCount: m[4] ? Number(m[4]) : 1,
        rows: [
          { kind: 'hunk-header', oldLine: null, newLine: null, content: m[5].trim() || line },
        ],
      };
      oldLine = current.oldStart;
      newLine = current.newStart;
      hunks.push(current);
      continue;
    }
    if (!current) continue;

    const prefix = line.charAt(0);
    const rest = line.slice(1);
    if (prefix === '+') {
      current.rows.push({ kind: 'add', oldLine: null, newLine: newLine++, content: rest });
    } else if (prefix === '-') {
      current.rows.push({ kind: 'del', oldLine: oldLine++, newLine: null, content: rest });
    } else if (prefix === ' ' || prefix === '') {
      current.rows.push({ kind: 'context', oldLine: oldLine++, newLine: newLine++, content: rest });
    }
    // '\' rows (e.g. "\ No newline at end of file") — skip silently.
  }
  return hunks;
}
