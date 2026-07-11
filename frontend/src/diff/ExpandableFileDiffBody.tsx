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
import type { DiffFileDto } from '../types';
import { computeFetchRange, type Gap, type LoadedGap } from '../diffExpand';
import { FileDiffBody, type FileDiffBodyProps } from './DiffFileList';

export type FetchFileBlob = (path: string) => Promise<{ lines: string[] }>;

export type ExpandableFileDiffBodyProps = {
  file: DiffFileDto;
  fetchFileBlob?: FetchFileBlob;
  onExpandedChange?: (expanded: Map<number, LoadedGap>) => void;
} & Omit<FileDiffBodyProps, 'file' | 'expanded' | 'expandLoading' | 'onExpandClick'>;

/**
 * Adds "expand unchanged lines" state around the shared single-file diff
 * renderer. The data source stays injected so remote PRs, local PRs, and
 * task worktrees can fetch file content from their own APIs.
 */
export function ExpandableFileDiffBody({
  file,
  fetchFileBlob,
  onExpandedChange,
  ...bodyProps
}: ExpandableFileDiffBodyProps) {
  const [expanded, setExpanded] = useState<Map<number, LoadedGap>>(new Map());
  const [expandLoading, setExpandLoading] = useState<Set<string>>(new Set());
  const [expandError, setExpandError] = useState<string | null>(null);

  useEffect(() => {
    const empty = new Map<number, LoadedGap>();
    setExpanded(empty);
    setExpandError(null);
  }, [file.patch, file.filename]);

  useEffect(() => {
    onExpandedChange?.(expanded);
  }, [expanded, onExpandedChange]);

  const onExpandClick = fetchFileBlob === undefined ? undefined : async (gap: Gap, direction: 'up' | 'down' | 'all') => {
    const loaded = expanded.get(gap.index) ?? new Map<number, string>();
    const range = direction === 'all' && gap.newEnd !== null
      ? { from: gap.newStart, to: gap.newEnd }
      : direction === 'all'
        ? null
        : computeFetchRange(gap, loaded, direction);
    if (!range) return;
    const key = `${gap.index}:${direction}`;
    if (expandLoading.has(key)) return;
    setExpandLoading(s => new Set(s).add(key));
    setExpandError(null);
    try {
      const blob = await fetchFileBlob(file.filename);
      const next = new Map(loaded);
      for (let n = range.from; n <= range.to; n++) {
        if (n - 1 < blob.lines.length) next.set(n, blob.lines[n - 1]);
      }
      setExpanded(prev => {
        const out = new Map(prev);
        out.set(gap.index, next);
        return out;
      });
    }
    catch (e) {
      setExpandError(e instanceof Error ? e.message : 'Expand failed.');
    }
    finally {
      setExpandLoading(s => {
        const out = new Set(s);
        out.delete(key);
        return out;
      });
    }
  };

  return (
    <>
      {expandError && <div className="diff-expand-error" role="alert">{expandError}</div>}
      <FileDiffBody
        {...bodyProps}
        file={file}
        expanded={expanded}
        expandLoading={expandLoading}
        onExpandClick={onExpandClick}
      />
    </>
  );
}
