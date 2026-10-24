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
import { createRoot } from 'react-dom/client';
import { flushSync } from 'react-dom';
import hljs from 'highlight.js/lib/common';
import type { HighlightOptions } from 'highlight.js';
import MarkdownBody from '../MarkdownBody';
import '../../css/base.css';
import '../../css/pulls.css';

const sequence = `sequenceDiagram
  participant Client
  participant OpenApiDescriptionResource
  participant OpenApiDescriptionController
  participant OpenApiDescriptionStorage
  participant S3
  Client->>OpenApiDescriptionResource: POST description request
  OpenApiDescriptionResource->>OpenApiDescriptionController: Upload request and session account ID
  OpenApiDescriptionController->>OpenApiDescriptionStorage: Store description under staged key
  OpenApiDescriptionStorage->>S3: PutObject
  S3-->>OpenApiDescriptionStorage: Store result
  OpenApiDescriptionStorage-->>OpenApiDescriptionController: Checksum
  OpenApiDescriptionController-->>Client: Upload ID
  Client-->>OpenApiDescriptionResource: Receive upload response`;
const fence = (source: string, language = 'mermaid') => `\`\`\`${language}\n${source}\n\`\`\``;
const body = `<details><summary>Walkthrough</summary>\n\n### Sequence Diagram(s)\n\n${fence(sequence)}\n</details>`;

function assert(value: unknown, message: string): asserts value {
  if (!value) throw new Error(message);
}

async function waitFor(check: () => boolean) {
  const start = performance.now();
  while (!check()) {
    if (performance.now() - start > 10_000) throw new Error('Timed out waiting for diagram');
    await new Promise(resolve => setTimeout(resolve, 20));
  }
}

export async function run() {
  document.documentElement.style.colorScheme = 'light';
  const host = document.createElement('div');
  host.style.cssText = 'max-width: 1300px; margin: 20px auto;';
  document.body.append(host);
  const root = createRoot(host);
  const show = (text: string) => flushSync(() => root.render(<MarkdownBody body={text} />));
  show(body);
  await waitFor(() => !!host.querySelector('.md-mermaid img'));
  const details = host.querySelector('details');
  assert(details && !details.open, 'Diagram must render while its disclosure is collapsed');
  details.open = true;
  const image = host.querySelector<HTMLImageElement>('.md-mermaid img');
  assert(image, 'Expected diagram image');
  await image.decode();
  assert(image.naturalWidth > 0 && image.naturalHeight > 0, 'SVG must decode as an image');
  const svg = decodeURIComponent(image.src.split(',', 2)[1]);
  assert(svg.includes('POST description request') && svg.includes('Receive upload response'), 'All sequence messages must survive Markdown rendering');
  assert(svg.includes('OpenApiDescriptionStorage'), 'Participant labels must render');
  assert(image.width <= host.clientWidth && image.height > 200, 'Sequence must fit the available width');
  const light = image.src;
  document.documentElement.style.colorScheme = 'dark';
  await waitFor(() => host.querySelector<HTMLImageElement>('.md-mermaid img')?.src !== light);
  await image.decode();
  assert(image.src !== light, 'Theme changes must regenerate the diagram');
  host.style.width = '360px';
  assert(image.getBoundingClientRect().width <= 360, 'Diagram must fit a narrow pane');
  host.style.width = '';

  show(fence('not a valid diagram') + '\n\n' + fence('flowchart LR\n A[Start] --> B[Finish]', 'MERMAID'));
  await waitFor(() => !!host.querySelector('[role="status"]')?.textContent?.includes('Unable') && !!host.querySelector('img'));
  assert(host.querySelector('pre code')?.textContent === 'not a valid diagram', 'Invalid source must remain readable');
  await host.querySelector<HTMLImageElement>('img')?.decode();
  assert(document.querySelectorAll('body > div').length === 1, 'Temporary rendering containers must be removed after errors');

  show(fence('flowchart LR\n Old --> Result'));
  show(fence('flowchart LR\n Current --> Result'));
  await waitFor(() => !!host.querySelector('img'));
  const flowchart = decodeURIComponent(host.querySelector<HTMLImageElement>('img')?.src ?? '');
  assert(flowchart.includes('Current'), 'Source edits must not display stale diagrams');
  assert(!flowchart.includes('<foreignObject'), 'Flowchart labels must be SVG text, which wraps correctly at any page zoom');

  show(fence('sequenceDiagram\n A->>B: Plan\n Note over B: Count requests; parent plans'));
  await waitFor(() => !!host.querySelector('img') || !!host.querySelector('[role="status"]')?.textContent?.includes('Unable'));
  assert(decodeURIComponent(host.querySelector<HTMLImageElement>('img')?.src ?? '').includes('Count requests; parent plans'),
    'A semicolon in sequence text must stay text rather than end the statement');

  show(fence('const value = 1;', 'typescript'));
  assert(!host.querySelector('.md-mermaid') && !!host.querySelector('code.hljs'), 'Ordinary code must retain highlighting');

  // Review threads pass a new suggestion object on every render; only a changed value may parse again.
  const highlight = hljs.highlight;
  let highlighted = 0;
  const highlights = () => highlighted;
  hljs.highlight = ((code: string, options: HighlightOptions) => {
    highlighted++;
    return highlight(code, options);
  }) as typeof highlight;
  const review = (text: string) => flushSync(() => root.render(<MarkdownBody body={text} suggestion={{ startLine: 1, lines: ['const value = 0;'] }} />));
  review(fence('const value = 1;', 'typescript'));
  review(fence('const value = 1;', 'typescript'));
  assert(highlights() === 1, `Re-rendering the same text must reuse its highlighted code, highlighted ${highlights()} times`);
  review(fence('const value = 2;', 'typescript'));
  assert(highlights() === 2, 'Changed text must be highlighted again');
  hljs.highlight = highlight;
  show(body);
  await waitFor(() => !!host.querySelector('img'));
  const finalDetails = host.querySelector('details');
  assert(finalDetails, 'Expected final disclosure');
  finalDetails.open = true;
  await host.querySelector<HTMLImageElement>('img')?.decode();
  await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
  return { passed: true };
}
