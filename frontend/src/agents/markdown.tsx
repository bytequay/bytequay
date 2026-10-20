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

import React, { memo } from 'react';
import { inline, markdownBlocks } from '../pulls/MarkdownBody';

/** A discussion attaches its footer to the block containing the quoted passage. */
export type MarkdownBlock = { node: React.ReactNode; text: string };

export function renderMarkdownBlocks(text: string, repository?: string | null, preserveLineBreaks = false): MarkdownBlock[] {
  return markdownBlocks(text.replace(/\r\n?/g, '\n'), repository ?? undefined).map((block, key) => {
    const marks = (source: string) => inline(source, String(key), undefined, repository ?? undefined, true);
    switch (block.kind) {
      case 'paragraph': {
        const source = preserveLineBreaks ? block.text : block.text.replaceAll('\n', ' ');
        return { node: <p key={key}>{marks(source)}</p>, text: source };
      }
      case 'heading': return { node: <p key={key}><strong>{marks(block.text)}</strong></p>, text: block.text };
      case 'code': return { node: <pre className="agt-code" key={key}>{block.text}</pre>, text: block.text };
      case 'quote': return { node: <blockquote key={key}>{marks(block.text)}</blockquote>, text: block.text };
      case 'task': return { node: <ul key={key}><li className="agt-md__task"><input type="checkbox" checked={block.checked} disabled
        aria-label={block.text} /><span>{marks(block.text)}</span></li></ul>, text: block.text };
      case 'list': {
        const List = block.ordered ? 'ol' : 'ul';
        return { node: <List key={key} start={block.start}>{block.items.map((item, index) => <li key={index}>{marks(item)}</li>)}</List>, text: block.items.join(' ') };
      }
      case 'table': return {
        node: <div className="agt-md__table" key={key}><table>
          <thead><tr>{block.headers.map((header, cell) => <th key={cell} style={{ textAlign: block.aligns[cell] }}>{marks(header)}</th>)}</tr></thead>
          <tbody>{block.rows.map((row, index) => <tr key={index}>{row.map((cell, column) =>
            <td key={column} style={{ textAlign: block.aligns[column] }}>{marks(cell)}</td>)}</tr>)}</tbody>
        </table></div>, text: [block.headers, ...block.rows].flat().join(' '),
      };
      case 'details': return { node: <details key={key}><summary>{marks(block.summary)}</summary>{markdownContent(block.text, repository)}</details>,
        text: `${block.summary} ${block.text}` };
      case 'alert': return { node: <blockquote key={key}><strong>{block.label}</strong>{markdownContent(block.text, repository)}</blockquote>, text: block.text };
      case 'code-reference': return { node: <p key={key}>{marks(block.reference.href)}</p>, text: block.reference.href };
      case 'rule': return { node: <hr key={key} />, text: '' };
    }
  });
}

// Discussion highlighting walks the rendered children, including nested details and alerts.
function markdownContent(text: string, repository?: string | null, preserveLineBreaks = false): React.JSX.Element {
  return <div className="agt-md">{renderMarkdownBlocks(text, repository, preserveLineBreaks).map(block => block.node)}</div>;
}

const AgentMarkdown = memo(function AgentMarkdown({ text, repository, preserveLineBreaks }: {
  text: string;
  repository?: string | null;
  preserveLineBreaks: boolean;
}): React.JSX.Element {
  return markdownContent(text, repository, preserveLineBreaks);
});

export function renderMarkdown(text: string, repository?: string | null, preserveLineBreaks = false): React.ReactNode {
  return <AgentMarkdown text={text} repository={repository} preserveLineBreaks={preserveLineBreaks} />;
}
