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

import React, { useId } from 'react';
import { Check } from 'lucide-react';
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
  lessonId: string;
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

/** A label in at most two lines of about the box's width, the rest cut short. */
export function wrap(text: string, width: number, lines = 2): string[] {
  const out: string[] = [];
  for (const word of text.split(/\s+/).filter((part) => part !== '')) {
    const last = out.at(-1);
    if (last !== undefined && last.length + 1 + word.length <= width) out[out.length - 1] = `${last} ${word}`;
    else out.push(word);
  }
  if (out.length <= lines) return out;
  const kept = out.slice(0, lines);
  kept[lines - 1] = `${kept[lines - 1].slice(0, width - 1)}…`;
  return kept;
}

/** A section's picture: a flow's steps joined by arrows in reading order, or a tree's nodes under their parents. */
export function LessonDiagram({ diagram }: { diagram: LessonDiagramDto }): React.JSX.Element {
  const marker = `goal-arrow-${useId().replace(/[^a-zA-Z0-9]/g, '')}`;
  const { nodes } = diagram;
  const noted = nodes.some((node) => node.note !== null);
  // A box fits the longest label and note any box has, so every box in the picture is the same size.
  const labelLines = Math.max(...nodes.map((node) => wrap(node.label, LABEL_CHARS).length));
  const noteLines = Math.max(...nodes.map((node) => (node.note === null ? 0 : wrap(node.note, NOTE_CHARS).length)));
  const height = noted ? 30 + labelLines * LINE + noteLines * NOTE_LINE : NODE_HEIGHT;
  const places = diagram.kind === 'FLOW' ? flowPlaces(nodes.length, height) : treePlaces(nodes, height);
  const index = new Map(nodes.map((node, at) => [node.id, at]));
  const edges: [number, number][] = diagram.kind === 'FLOW'
    ? nodes.slice(1).map((_, at) => [at, at + 1])
    : nodes.slice(1).map((node, at) => [index.get(node.parent ?? '') ?? 0, at + 1]);
  const width = Math.max(...places.map((place) => place.x)) + NODE_WIDTH + 2 * PAD;
  const tall = Math.max(...places.map((place) => place.y)) + height + 2 * PAD;
  const path = ([from, to]: [number, number]): string => {
    const start = places[from];
    const end = places[to];
    if (diagram.kind === 'FLOW' && start.y === end.y) return `M${start.x + NODE_WIDTH},${start.y + height / 2} H${end.x - 2}`;
    // A flow continuing on the next row, and every tree edge, drops from the bottom of one box to the top of the other.
    const middle = start.y + height + GAP_Y / 2;
    return `M${start.x + NODE_WIDTH / 2},${start.y + height} V${middle} H${end.x + NODE_WIDTH / 2} V${end.y - 2}`;
  };
  return (
    <figure className="goal-diagram">
      <svg viewBox={`0 0 ${width} ${tall}`} width={width} role="img" aria-label={diagram.caption}>
        <defs>
          <marker id={marker} viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path className="goal-diagram__head" d="M0,0 L10,5 L0,10 z" />
          </marker>
        </defs>
        <g transform={`translate(${PAD},${PAD})`}>
          {edges.map((edge) => <path key={edge.join('-')} className="goal-diagram__edge" d={path(edge)} markerEnd={`url(#${marker})`} />)}
          {nodes.map((node, at) => {
            const label = wrap(node.label, LABEL_CHARS);
            const note = node.note === null ? [] : wrap(node.note, NOTE_CHARS);
            return <g key={node.id} className={at === 0 ? 'is-root' : undefined} transform={`translate(${places[at].x},${places[at].y})`}>
              <rect width={NODE_WIDTH} height={height} rx="8" />
              {/* Where any box has a note, every label starts at the same height. */}
              <text className="goal-diagram__label" x={NODE_WIDTH / 2} y={noted ? 22 : height / 2 - (label.length - 1) * 8 + 4} textAnchor="middle">
                {label.map((line, row) => <tspan key={row} x={NODE_WIDTH / 2} dy={row === 0 ? 0 : LINE}>{line}</tspan>)}
              </text>
              {note.length > 0 && <text className="goal-diagram__note" x={NODE_WIDTH / 2} y={22 + label.length * LINE + 4} textAnchor="middle">
                {note.map((line, row) => <tspan key={row} x={NODE_WIDTH / 2} dy={row === 0 ? 0 : NOTE_LINE}>{line}</tspan>)}
              </text>}
            </g>;
          })}
        </g>
      </svg>
      <figcaption>{diagram.caption}</figcaption>
    </figure>
  );
}
