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
import { ChevronDown, ChevronRight, File, FilePlus, FlaskConical, Folder, GitCommitHorizontal } from 'lucide-react';
import type { ReactNode } from 'react';
import type { FileTreeItem } from '../fileTree';
import { fileName, inTestRoot, isTestFile } from './codeModel';
import type { ReviewCodeFileEntryDto } from './contracts';

export default function TreeItems({ items, depth, selected, isOpen, onToggle, onOpen, renderFileIcon }: {
  items: FileTreeItem<ReviewCodeFileEntryDto>[];
  depth: number;
  selected: string | null;
  isOpen: (path: string) => boolean;
  onToggle: (path: string) => void;
  onOpen: (path: string) => void;
  renderFileIcon?: (path: string) => ReactNode;
}) {
  return (
    <ul className="ct-tree">
      {items.map(item => item.kind === 'directory' ? (
        <li key={`directory:${item.path}`}>
          <button type="button" className={`ct-tree__row${inTestRoot(item.path) ? ' is-test-root' : ''}`} style={{ paddingLeft: 8 + depth * 16 }} title={item.path}
            aria-expanded={isOpen(item.path)} onClick={() => onToggle(item.path)}>
            <span className="ct-tree__twisty">
              {isOpen(item.path)
                ? <ChevronDown className="ct-tree__chevron" size={12} strokeWidth={2} aria-hidden="true" />
                : <ChevronRight className="ct-tree__chevron" size={12} strokeWidth={2} aria-hidden="true" />}
            </span>
            <Folder className="ct-tree__icon" size={14} strokeWidth={1.7} aria-hidden="true" />
            <span className={`ct-tree__name${treeNameClass(item.name)}${item.module ? ' is-module' : ''}`}>{item.name}</span>
          </button>
          {isOpen(item.path) && (
            <TreeItems items={item.children} depth={depth + 1} selected={selected} isOpen={isOpen}
              onToggle={onToggle} onOpen={onOpen} renderFileIcon={renderFileIcon} />
          )}
        </li>
      ) : (
        <FileRow key={`file:${item.path}`} entry={item.entry} depth={depth} selected={selected === item.path} onOpen={onOpen} icon={renderFileIcon?.(item.path)} />
      ))}
    </ul>
  );
}

function treeNameClass(name: string) {
  return name.startsWith('.') ? ' is-dot' : '';
}

function FileRow({ entry, depth, selected, onOpen, icon }: {
  entry: ReviewCodeFileEntryDto;
  depth: number;
  selected: boolean;
  onOpen: (path: string) => void;
  icon?: ReactNode;
}) {
  return (
    <li>
      <button type="button" className={`ct-tree__row is-file${isTestFile(entry.path) ? ' is-test-root' : ''}${selected ? ' is-selected' : ''}`} style={{ paddingLeft: 8 + depth * 16 }}
        title={entry.path} aria-current={selected ? 'true' : undefined} onClick={() => onOpen(entry.path)}>
        <span className="ct-tree__twisty" aria-hidden="true" />
        {icon ?? (isTestFile(entry.path)
          ? <FlaskConical className="ct-tree__icon is-test" size={14} strokeWidth={1.7} aria-hidden="true" />
          : <File className="ct-tree__icon" size={14} strokeWidth={1.7} aria-hidden="true" />)}
        <span className={`ct-tree__name${treeNameClass(fileName(entry.path))}`}>{fileName(entry.path).replace(/\.java$/, '')}</span>
        {entry.changedInPr && (
          <span className="ct-tree__marker is-pr" role="img" title="Changed in this pull request" aria-label="Changed in this pull request">
            <GitCommitHorizontal size={12} strokeWidth={1.8} aria-hidden="true" />
          </span>
        )}
        {entry.onlyInWorkingCopy && (
          <span className="ct-tree__marker is-local" role="img" title="Local file in this working copy — not in the pull request"
            aria-label="Local file in this working copy — not in the pull request">
            <FilePlus size={12} strokeWidth={1.8} aria-hidden="true" />
          </span>
        )}
      </button>
    </li>
  );
}
