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
import { useEffect, useId, useState } from 'react';
import './MermaidDiagram.css';

// Mermaid's configuration is global; keep theme changes and rendering in the same queue.
let rendering = Promise.resolve();

type Diagram = { source: string; image?: string; width?: number; height?: number };

// Agents write ';' in sequence-diagram messages and notes, where Mermaid reads it as the end of a statement.
const escapeSequenceSemicolons = (source: string) => /^\s*sequenceDiagram\b/m.test(source)
  ? source.replace(/^([^:\n]*:)(.*)$/gm, (_, head: string, text: string) => head + text.replaceAll(';', '#59;'))
  : source;

export default function MermaidDiagram({ source }: { source: string }) {
  const id = `mermaid-${useId().replace(/[^a-zA-Z0-9-]/g, '')}`;
  const [diagram, setDiagram] = useState<Diagram>();

  useEffect(() => {
    let cancelled = false;
    let revision = 0;
    const media = matchMedia('(prefers-color-scheme: dark)');
    const render = () => {
      const current = ++revision;
      const scheme = getComputedStyle(document.documentElement).colorScheme;
      const dark = scheme === 'dark' || (scheme !== 'light' && media.matches);
      rendering = rendering.then(async () => {
        if (cancelled || current !== revision) return;
        // Measure outside collapsed <details>, where SVG text otherwise has zero-sized bounds.
        const host = document.createElement('div');
        host.style.cssText = 'position: absolute; visibility: hidden; pointer-events: none;';
        document.body.append(host);
        try {
          const { default: mermaid } = await import('mermaid');
          // Mermaid wraps an HTML label only when its measured width equals the limit exactly, which page zoom
          // breaks and clips long labels; SVG text labels wrap by their measured length instead.
          mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', suppressErrorRendering: true, htmlLabels: false,
            theme: dark ? 'dark' : 'default' });
          const draw = (text: string) => mermaid.render(id, text, host);
          const { svg } = await draw(source).catch((error: unknown) => {
            const escaped = escapeSequenceSemicolons(source);
            if (escaped === source) throw error;
            return draw(escaped);
          });
          const element = new DOMParser().parseFromString(svg, 'image/svg+xml').documentElement;
          const bounds = element.getAttribute('viewBox')?.split(/\s+/).map(Number);
          if (!cancelled && current === revision) setDiagram({ source,
            // An image keeps diagram links and embedded content inert in the app renderer.
            image: `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`,
            width: bounds?.[2], height: bounds?.[3] });
        }
        catch {
          if (!cancelled && current === revision) setDiagram({ source });
        }
        finally { host.remove(); }
      });
    };
    render();
    media.addEventListener('change', render);
    const observer = new MutationObserver(render);
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['style'] });
    return () => { cancelled = true; observer.disconnect(); media.removeEventListener('change', render); };
  }, [id, source]);

  const result = diagram?.source === source ? diagram : undefined;
  return <div className="md-mermaid">
    {result?.image
      ? <img src={result.image} width={result.width} height={result.height} alt="Mermaid diagram" />
      : <div className="md-code-block">
        <span role="status">{result ? 'Unable to render diagram' : 'Rendering diagram…'}</span>
        <pre><code>{source}</code></pre>
      </div>}
  </div>;
}
