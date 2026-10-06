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

import React, { useEffect, useId, useRef, useState } from 'react';
import { Check, Expand, Minus, Plus, X } from 'lucide-react';
import MarkdownBody, { linkIssueNumbers } from '../pulls/MarkdownBody';
import type { GoalDto, LessonContentDto, LessonDiagramDto, WorkItemDto } from './contracts';

/** The course's lessons in reading order. */
export function courseLessons(goal: GoalDto): WorkItemDto[] {
  const lessons = new Map(goal.items.map((item) => [item.id, item]));
  return (goal.course?.parts ?? []).flatMap((part) => part.lessons.map((id) => lessons.get(id)).filter((lesson) => lesson !== undefined));
}

/** The book's contents: each part with its chapters, done ones checked, and the open chapter's sections to jump to. */
export function BookContents({ goal, lessonId, sections, onLesson, onSection }: {
  goal: GoalDto;
  /** The open chapter, or null on the course's overview. */
  lessonId: string | null;
  sections: string[];
  onLesson: (lessonId: string) => void;
  onSection: (index: number) => void;
}): React.JSX.Element {
  const lessons = new Map(goal.items.map((item) => [item.id, item]));
  const numbers = new Map(courseLessons(goal).map((lesson, index) => [lesson.id, index + 1]));
  return (
    <nav className="goal-book__contents" aria-label="Contents">
      <div className="goal-book__label">Contents</div>
      {(goal.course?.parts ?? []).map((part, index) => <section key={part.title} className="goal-book__part">
        <div className="goal-book__part-title">Part {index + 1} · {part.title}</div>
        <ol>{part.lessons.map((id) => lessons.get(id)).filter((lesson) => lesson !== undefined).map((lesson) => {
          const current = lesson.id === lessonId;
          return <li key={lesson.id}>
            <button type="button" aria-current={current ? 'page' : undefined} onClick={() => onLesson(lesson.id)}
              className={`goal-book__chapter${current ? ' is-current' : ''}${lesson.state === 'DONE' ? ' is-done' : ''}`}>
              <span className="goal-book__number">{lesson.state === 'DONE' ? <Check size={12} strokeWidth={2.4} aria-label="Done" /> : numbers.get(lesson.id)}</span>
              <span>{lesson.title}</span>
            </button>
            {current && sections.length > 0 && <ol className="goal-book__sections">{sections.map((title, section) => <li key={section}>
              <button type="button" onClick={() => onSection(section)}>{title}</button>
            </li>)}</ol>}
          </li>;
        })}</ol>
      </section>)}
    </nav>
  );
}

/** A chapter's own content: its summary, what to remember, its sections with their diagrams, and its exercise. The lesson asks its check. */
export function ChapterBody({ content, repository }: { content: LessonContentDto; repository: string }): React.JSX.Element {
  return <>
    {content.summary !== null && <p className="goal-chapter__summary">{linkIssueNumbers(content.summary, repository)}</p>}
    {content.invariants.length > 0 && <aside className="goal-chapter__remember" aria-label="Remember">
      <div className="goal-chapter__kicker">Remember</div>
      <ul>{content.invariants.map((rule) => <li key={rule}>{linkIssueNumbers(rule, repository)}</li>)}</ul>
    </aside>}
    {content.sections.map((section, index) => <section key={`${index}:${section.title}`} className="goal-chapter__section" data-section={index}>
      <h2>{section.title}</h2>
      {section.diagram !== null && <LessonDiagram diagram={section.diagram} />}
      <MarkdownBody body={section.body} repository={repository} />
    </section>)}
    {content.explanation !== null && <MarkdownBody className="goal-lesson__explanation" body={content.explanation} repository={repository} />}
    <section className="goal-chapter__try" aria-label="Try it">
      <div className="goal-chapter__kicker">Try it</div>
      <MarkdownBody body={content.exercise} repository={repository} />
      <div className="goal-chapter__kicker">What you should see</div>
      <p>{linkIssueNumbers(content.observe, repository)}</p>
    </section>
  </>;
}

const NODE_WIDTH = 172;
const NODE_HEIGHT = 52;
const LINE = 16;
const NOTE_LINE = 14;
const GAP_X = 48;
const GAP_Y = 44;
const PAD = 8;
// Components: room between columns for an arrow's label, and the band each group's column sits in.
const COLUMN_GAP = 104;
const ZONE_HEAD = 30;
const ZONE_INSET = 12;
// Sequences: room between participants for a message's label, and one row per message.
const SEQUENCE_GAP = 64;
const PARTICIPANT_HEIGHT = 40;
const STEP = 38;
const EDGE_CHAR = 6.2;
/** Steps of a flow per row; a longer flow continues on the next row. */
const ROW = 4;
// ponytail: widths are estimated per character at the diagram's font size; measuring text needs a rendered font.
const LABEL_CHARS = 22;
const NOTE_CHARS = 28;

type Place = { x: number; y: number };

/** Where each step of a flow sits: up to four to a row, left to right. */
export function flowPlaces(count: number, height: number): Place[] {
  return Array.from({ length: count }, (_, index) => ({ x: (index % ROW) * (NODE_WIDTH + GAP_X), y: Math.floor(index / ROW) * (height + GAP_Y) }));
}

/** Where each node of a tree sits: one level per row, every parent centred over its children. */
export function treePlaces(nodes: LessonDiagramDto['nodes'], height: number): Place[] {
  const children = new Map<string, number[]>();
  nodes.forEach((node, index) => {
    if (index > 0 && node.parent !== null) children.set(node.parent, [...(children.get(node.parent) ?? []), index]);
  });
  const places: Place[] = nodes.map(() => ({ x: 0, y: 0 }));
  let leaves = 0;
  const place = (index: number, depth: number): number => {
    const below = (children.get(nodes[index].id) ?? []).map((child) => place(child, depth + 1));
    const x = below.length === 0 ? (leaves++) * (NODE_WIDTH + GAP_X) : (below[0] + below[below.length - 1]) / 2;
    places[index] = { x, y: depth * (height + GAP_Y) };
    return x;
  };
  place(0, 0);
  return places;
}

/** A word longer than a line split where its camel-case parts meet, so a class name breaks between them. */
function pieces(word: string, width: number): string[] {
  if (word.length <= width) return [word];
  const out: string[] = [];
  for (const hump of word.split(/(?<=[a-z0-9])(?=[A-Z])/)) {
    const last = out.at(-1);
    if (last !== undefined && last.length + hump.length <= width) out[out.length - 1] = last + hump;
    else out.push(hump);
  }
  return out;
}

/** A label in at most two lines of about the box's width, the rest cut short. */
export function wrap(text: string, width: number, lines = 2): string[] {
  const out: string[] = [];
  for (const word of text.split(/\s+/).filter((part) => part !== '')) {
    pieces(word, width).forEach((piece, at) => {
      const last = out.at(-1);
      if (at === 0 && last !== undefined && last.length + 1 + piece.length <= width) out[out.length - 1] = `${last} ${piece}`;
      else out.push(piece);
    });
  }
  const cut = (line: string): string => (line.length <= width ? line : `${line.slice(0, width - 1)}…`);
  if (out.length <= lines) return out.map(cut);
  const kept = out.slice(0, lines).map(cut);
  kept[lines - 1] = `${kept[lines - 1].slice(0, width - 1).replace(/…$/, '')}…`;
  return kept;
}

/** How tall every box of a picture is: room for the longest label and note any box has. */
function boxHeight(nodes: LessonDiagramDto['nodes']): number {
  if (!nodes.some((node) => node.note !== null)) return NODE_HEIGHT;
  const labelLines = Math.max(...nodes.map((node) => wrap(node.label, LABEL_CHARS).length));
  const noteLines = Math.max(...nodes.map((node) => (node.note === null ? 0 : wrap(node.note, NOTE_CHARS).length)));
  return 30 + labelLines * LINE + noteLines * NOTE_LINE;
}

/** One box with its label, and its note under it; where any box has a note, every label starts at the same height. */
function Box({ node, place, height, noted, root }: {
  node: LessonDiagramDto['nodes'][number]; place: Place; height: number; noted: boolean; root: boolean;
}): React.JSX.Element {
  const label = wrap(node.label, LABEL_CHARS);
  const note = node.note === null ? [] : wrap(node.note, NOTE_CHARS);
  return (
    <g className={root ? 'is-root' : undefined} transform={`translate(${place.x},${place.y})`}>
      <rect width={NODE_WIDTH} height={height} rx="8" />
      <text className="goal-diagram__label" x={NODE_WIDTH / 2} y={noted ? 22 : height / 2 - (label.length - 1) * 8 + 4} textAnchor="middle">
        {label.map((line, row) => <tspan key={row} x={NODE_WIDTH / 2} dy={row === 0 ? 0 : LINE}>{line}</tspan>)}
      </text>
      {note.length > 0 && <text className="goal-diagram__note" x={NODE_WIDTH / 2} y={22 + label.length * LINE + 4} textAnchor="middle">
        {note.map((line, row) => <tspan key={row} x={NODE_WIDTH / 2} dy={row === 0 ? 0 : NOTE_LINE}>{line}</tspan>)}
      </text>}
    </g>
  );
}

/** Labels wrap without losing the call or data being described. */
function EdgeLabel({ x, y, lines }: { x: number; y: number; lines: string[] }): React.JSX.Element {
  const width = Math.max(...lines.map((line) => line.length)) * EDGE_CHAR + 12;
  return <g className="goal-diagram__edge-label">
    <rect x={x - width / 2} y={y - 9} width={width} height={lines.length * 16 + 2} rx="4" />
    <text x={x} y={y + 4} textAnchor="middle">{lines.map((line, row) => <tspan key={row} x={x} dy={row === 0 ? 0 : 16}>{line}</tspan>)}</text>
  </g>;
}

type Drawing = { width: number; height: number; body: React.ReactNode };

/** A flow's steps joined by arrows in reading order, or a tree's nodes under their parents. */
function drawBoxes(diagram: LessonDiagramDto, arrow: string): Drawing {
  const { nodes } = diagram;
  const noted = nodes.some((node) => node.note !== null);
  const height = boxHeight(nodes);
  const places = diagram.kind === 'FLOW' ? flowPlaces(nodes.length, height) : treePlaces(nodes, height);
  const index = new Map(nodes.map((node, at) => [node.id, at]));
  const edges: [number, number][] = diagram.kind === 'FLOW'
    ? nodes.slice(1).map((_, at) => [at, at + 1])
    : nodes.slice(1).map((node, at) => [index.get(node.parent ?? '') ?? 0, at + 1]);
  const path = ([from, to]: [number, number]): string => {
    const start = places[from];
    const end = places[to];
    if (diagram.kind === 'FLOW' && start.y === end.y) return `M${start.x + NODE_WIDTH},${start.y + height / 2} H${end.x - 2}`;
    // A flow continuing on the next row, and every tree edge, drops from the bottom of one box to the top of the other.
    const middle = start.y + height + GAP_Y / 2;
    return `M${start.x + NODE_WIDTH / 2},${start.y + height} V${middle} H${end.x + NODE_WIDTH / 2} V${end.y - 2}`;
  };
  return {
    width: Math.max(...places.map((place) => place.x)) + NODE_WIDTH + 2 * PAD,
    height: Math.max(...places.map((place) => place.y)) + height + 2 * PAD,
    body: <>
      {edges.map((edge) => <path key={edge.join('-')} className="goal-diagram__edge" d={path(edge)} markerEnd={`url(#${arrow})`} />)}
      {nodes.map((node, at) => <Box key={node.id} node={node} place={places[at]} height={height} noted={noted} root={at === 0} />)}
    </>,
  };
}

/** Where each component sits: one column per group in the order the groups first appear, the group's components down it. */
export function componentPlaces(nodes: LessonDiagramDto['nodes'], height: number, head: number): { places: Place[]; groups: string[] } {
  const groups = [...new Set(nodes.map((node) => node.group ?? ''))];
  const rows = new Map<string, number>();
  const places = nodes.map((node) => {
    const group = node.group ?? '';
    const row = rows.get(group) ?? 0;
    rows.set(group, row + 1);
    return { x: groups.indexOf(group) * (NODE_WIDTH + COLUMN_GAP), y: head + row * (height + GAP_Y) };
  });
  return { places, groups };
}

/** Route each connection through its own gutter and row, outside every component box. */
export function componentLayout(diagram: LessonDiagramDto) {
  const height = boxHeight(diagram.nodes);
  const named = diagram.nodes.some((node) => node.group !== null);
  const { places, groups } = componentPlaces(diagram.nodes, height, named ? ZONE_HEAD : 0);
  const groupWidth = Math.max(NODE_WIDTH + 2 * ZONE_INSET, ...groups.map((group) => group.length * 7 + 24));
  const stride = groupWidth + 32 + diagram.edges.length * 16;
  const index = new Map(diagram.nodes.map((node, at) => [node.id, at]));
  places.forEach((place, at) => {
    place.x = groups.indexOf(diagram.nodes[at].group ?? '') * stride + (groupWidth - NODE_WIDTH) / 2;
  });
  const bottom = Math.max(...places.map((place) => place.y)) + height + ZONE_INSET;
  let row = bottom + 28;
  const ports = new Map<number, number>();
  const port = (node: number): Place => {
    const count = ports.get(node) ?? 0;
    ports.set(node, count + 1);
    const total = diagram.edges.filter((edge) => edge.from === diagram.nodes[node].id).length
      + diagram.edges.filter((edge) => edge.to === diagram.nodes[node].id).length;
    return { x: places[node].x + NODE_WIDTH, y: places[node].y + height * (count + 1) / (total + 1) };
  };
  let width = (groups.length - 1) * stride + groupWidth + 32 + diagram.edges.length * 16;
  const arrows = diagram.edges.map((edge, at) => {
    const from = index.get(edge.from) ?? 0;
    const to = index.get(edge.to) ?? 0;
    const start = port(from);
    const end = port(to);
    const gutter = (node: number, offset: number): number => groups.indexOf(diagram.nodes[node].group ?? '') * stride + groupWidth + 16 + at * 16 + offset;
    const left = gutter(from, 0);
    const right = gutter(to, 8);
    const lines = edge.label === null ? [] : wrap(edge.label, 48, Infinity);
    const labelWidth = Math.max(0, ...lines.map((line) => line.length)) * EDGE_CHAR + 12;
    const x = Math.max(labelWidth / 2, (left + right) / 2);
    const y = row + 16;
    const points = [start, { x: left, y: start.y }, { x: left, y: row }, { x: right, y: row }, { x: right, y: end.y }, { x: end.x + 2, y: end.y }];
    row += Math.max(36, lines.length * 16 + 30);
    width = Math.max(width, x + labelWidth / 2);
    return { points, lines, x, y };
  });
  return { places, groups, groupWidth, stride, bottom, boxHeight: height, arrows, width: width + 2 * PAD, height: row + PAD };
}

/** A system's parts stay in their groups; connections have separate, labelled rows below them. */
function drawComponents(diagram: LessonDiagramDto, arrow: string): Drawing {
  const layout = componentLayout(diagram);
  const noted = diagram.nodes.some((node) => node.note !== null);
  return {
    width: layout.width,
    height: layout.height,
    body: <>
      {layout.groups.map((group, column) => group === '' ? null : <g key={group} className="goal-diagram__zone">
        <rect x={column * layout.stride} y={0} width={layout.groupWidth} height={layout.bottom} rx="10" />
        <text x={column * layout.stride + layout.groupWidth / 2} y={18} textAnchor="middle">{group}</text>
      </g>)}
      {layout.arrows.map(({ points }, at) => <path key={at} className="goal-diagram__edge"
        d={points.map((point, index) => `${index === 0 ? 'M' : 'L'}${point.x},${point.y}`).join(' ')} markerEnd={`url(#${arrow})`} />)}
      {diagram.nodes.map((node, at) => <Box key={node.id} node={node} place={layout.places[at]} height={layout.boxHeight} noted={noted} root={false} />)}
      {layout.arrows.map(({ lines, x, y }, at) => lines.length === 0 ? null : <EdgeLabel key={at} x={x} y={y} lines={lines} />)}
    </>,
  };
}

/** Who calls whom over time: the participants across the top, their lifelines down, and the numbered messages in order. */
function drawSequence(diagram: LessonDiagramDto, arrow: string): Drawing {
  const { nodes, edges } = diagram;
  const index = new Map(nodes.map((node, at) => [node.id, at]));
  const centre = (at: number): number => at * (NODE_WIDTH + SEQUENCE_GAP) + NODE_WIDTH / 2;
  const top = PARTICIPANT_HEIGHT + 24;
  const tall = top + edges.length * STEP + 2 * PAD;
  return {
    width: nodes.length * NODE_WIDTH + (nodes.length - 1) * SEQUENCE_GAP + 2 * PAD,
    height: tall,
    body: <>
      {nodes.map((node, at) => <line key={`life-${node.id}`} className="goal-diagram__lifeline" x1={centre(at)} y1={PARTICIPANT_HEIGHT} x2={centre(at)} y2={tall - 2 * PAD} />)}
      {nodes.map((node, at) => {
        const label = wrap(node.label, LABEL_CHARS);
        return <g key={node.id} transform={`translate(${at * (NODE_WIDTH + SEQUENCE_GAP)},0)`}>
          <rect width={NODE_WIDTH} height={PARTICIPANT_HEIGHT} rx="8" />
          <text className="goal-diagram__label" x={NODE_WIDTH / 2} y={PARTICIPANT_HEIGHT / 2 - (label.length - 1) * 8 + 4} textAnchor="middle">
            {label.map((line, row) => <tspan key={row} x={NODE_WIDTH / 2} dy={row === 0 ? 0 : LINE}>{line}</tspan>)}
          </text>
        </g>;
      })}
      {edges.map((edge, at) => {
        const from = centre(index.get(edge.from) ?? 0);
        const to = centre(index.get(edge.to) ?? 0);
        const y = top + at * STEP;
        const text = edge.label === null ? `${at + 1}` : `${at + 1}. ${edge.label}`;
        if (from === to) {
          return <g key={`message-${at}`}>
            <path className="goal-diagram__edge" d={`M${from},${y - 6} h28 v14 h-26`} markerEnd={`url(#${arrow})`} />
            <text className="goal-diagram__message" x={from + 34} y={y + 4}>{text}</text>
          </g>;
        }
        return <g key={`message-${at}`}>
          <path className="goal-diagram__edge" d={`M${from},${y} H${to + (to > from ? -2 : 2)}`} markerEnd={`url(#${arrow})`} />
          <text className="goal-diagram__message" x={(from + to) / 2} y={y - 6} textAnchor="middle">{text}</text>
        </g>;
      })}
    </>,
  };
}

/** A section's picture, drawn from the boxes and arrows the lesson names; the model never writes markup. */
export function LessonDiagram({ diagram }: { diagram: LessonDiagramDto }): React.JSX.Element {
  const [expanded, setExpanded] = useState(false);
  return (
    <figure className={`goal-diagram is-${diagram.kind.toLowerCase()}`}>
      <DiagramPicture diagram={diagram} />
      <figcaption>{diagram.caption}</figcaption>
      <button type="button" className="goal-add goal-diagram__expand" onClick={() => setExpanded(true)}>
        <Expand size={14} aria-hidden="true" /> Expand diagram
      </button>
      {expanded && <DiagramViewer diagram={diagram} onClose={() => setExpanded(false)} />}
    </figure>
  );
}

function DiagramPicture({ diagram, scale }: { diagram: LessonDiagramDto; scale?: number }): React.JSX.Element {
  const arrow = `goal-arrow-${useId().replace(/[^a-zA-Z0-9]/g, '')}`;
  const drawing = diagram.kind === 'COMPONENTS' ? drawComponents(diagram, arrow) : diagram.kind === 'SEQUENCE' ? drawSequence(diagram, arrow) : drawBoxes(diagram, arrow);
  return (
    <svg viewBox={`0 0 ${drawing.width} ${drawing.height}`} width={drawing.width} role="img" aria-label={diagram.caption}
      style={scale === undefined ? undefined : { width: drawing.width * scale, height: drawing.height * scale, maxWidth: 'none' }}>
      <defs>
        <marker id={arrow} viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
          <path className="goal-diagram__head" d="M0,0 L10,5 L0,10 z" />
        </marker>
      </defs>
      <g transform={`translate(${PAD},${PAD})`}>{drawing.body}</g>
    </svg>
  );
}

function DiagramViewer({ diagram, onClose }: { diagram: LessonDiagramDto; onClose: () => void }): React.JSX.Element {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const canvasRef = useRef<HTMLDivElement>(null);
  const [scale, setScale] = useState<number | undefined>(1);
  useEffect(() => {
    const dialog = dialogRef.current;
    dialog?.showModal();
    return () => dialog?.close();
  }, []);
  const zoom = (factor: number): void => {
    const svg = canvasRef.current?.querySelector('svg');
    if (svg === null || svg === undefined) return;
    const bounds = svg.getBoundingClientRect();
    const fitted = Math.min(bounds.width / svg.viewBox.baseVal.width, bounds.height / svg.viewBox.baseVal.height);
    setScale(Math.max(0.25, Math.min(4, (scale ?? fitted) * factor)));
  };
  return <dialog ref={dialogRef} className="goal-diagram__viewer" aria-label="Expanded diagram"
    onClose={(event) => { if (!event.currentTarget.open) onClose(); }}
    onKeyDown={(event) => event.stopPropagation()}>
    <div className="goal-diagram__toolbar">
      <button type="button" className="goal-add" aria-label="Zoom out" disabled={scale !== undefined && scale <= 0.25} onClick={() => zoom(0.8)}><Minus size={16} /></button>
      <button type="button" className="goal-add" title="Actual size" onClick={() => setScale(1)}>{scale === undefined ? 'Fit' : `${Math.round(scale * 100)}%`}</button>
      <button type="button" className="goal-add" aria-label="Zoom in" disabled={scale !== undefined && scale >= 4} onClick={() => zoom(1.25)}><Plus size={16} /></button>
      <button type="button" className="goal-add" onClick={() => setScale(undefined)}>Fit to window</button>
      <button type="button" className="goal-add goal-diagram__close" aria-label="Close diagram" title="Close (Esc)" autoFocus onClick={() => dialogRef.current?.close()}><X size={16} /></button>
    </div>
    <div ref={canvasRef} className={`goal-diagram__canvas${scale === undefined ? ' is-fit' : ''}`} tabIndex={0} aria-label="Diagram, scroll to explore">
      <DiagramPicture diagram={diagram} scale={scale} />
    </div>
    <p className="goal-diagram__caption">{diagram.caption}</p>
  </dialog>;
}

/**
 * The course before a chapter is chosen: how far the reader is, where to continue, and every part with its chapters and what each
 * teaches. Opening a chapter the first time writes it.
 */
export function CourseOverview({ goal, onLesson }: { goal: GoalDto; onLesson: (lessonId: string) => void }): React.JSX.Element {
  const lessons = new Map(goal.items.map((item) => [item.id, item]));
  const order = courseLessons(goal);
  const numbers = new Map(order.map((lesson, index) => [lesson.id, index + 1]));
  const done = order.filter((lesson) => lesson.state === 'DONE').length;
  const next = order.find((lesson) => lesson.state !== 'DONE');
  return (
    <article className="goal-chapter goal-overview">
      <header className="goal-chapter__head">
        <div className="goal-lesson__part">Course</div>
        <h1 className="goal-chapter__title">{goal.title}</h1>
        <div className="goal-lesson__outcome" title={`${done} of ${order.length} chapters done`}>✓ {done}/{order.length} chapters</div>
      </header>
      {goal.course !== null && goal.course.map !== null && <LessonDiagram diagram={goal.course.map} />}
      {next !== undefined && <div><button type="button" className="goal-add is-primary" onClick={() => onLesson(next.id)}>
        {done === 0 ? 'Start' : 'Continue'}: {next.title}</button></div>}
      {(goal.course?.parts ?? []).map((part, index) => <section key={part.title} className="goal-overview__part" aria-label={part.title}>
        <h2>Part {index + 1} · {part.title}</h2>
        <ol>{part.lessons.map((id) => lessons.get(id)).filter((lesson) => lesson !== undefined).map((lesson) => <li key={lesson.id}>
          <button type="button" className={`goal-overview__chapter${lesson.state === 'DONE' ? ' is-done' : ''}`} onClick={() => onLesson(lesson.id)}>
            <span className="goal-book__number">{lesson.state === 'DONE' ? <Check size={12} strokeWidth={2.4} aria-label="Done" /> : numbers.get(lesson.id)}</span>
            <span className="goal-overview__text"><span className="goal-overview__title">{lesson.title}</span>
              <span className="goal-overview__outcome">{lesson.acceptanceCriteria}</span></span>
          </button>
        </li>)}</ol>
      </section>)}
    </article>
  );
}
