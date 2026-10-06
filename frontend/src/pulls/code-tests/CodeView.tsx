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
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands';
import { javaLanguage } from '@codemirror/lang-java';
import { HighlightStyle, LanguageSupport, StreamLanguage, syntaxHighlighting, syntaxTree } from '@codemirror/language';
import { javascript } from '@codemirror/legacy-modes/mode/javascript';
import { properties } from '@codemirror/legacy-modes/mode/properties';
import { python } from '@codemirror/legacy-modes/mode/python';
import { shell } from '@codemirror/legacy-modes/mode/shell';
import { standardSQL } from '@codemirror/legacy-modes/mode/sql';
import { xml } from '@codemirror/legacy-modes/mode/xml';
import { yaml } from '@codemirror/legacy-modes/mode/yaml';
import {
  closeSearchPanel, findNext, findPrevious, getSearchQuery, openSearchPanel, search, searchPanelOpen, setSearchQuery,
} from '@codemirror/search';
import {
  Annotation, Compartment, EditorState, Prec, RangeSet, StateEffect, StateField, type Extension, type Range, type RangeValue,
} from '@codemirror/state';
import {
  Decoration, type DecorationSet, EditorView, gutter, GutterMarker, gutterLineClass, highlightActiveLine, highlightActiveLineGutter, keymap, lineNumbers,
  ViewPlugin, type ViewUpdate, WidgetType,
} from '@codemirror/view';
import { styleTags, tags } from '@lezer/highlight';
import { gutterChanges, languageFor, type CodeLanguage, type GutterChanges } from './codeModel';
import type { LineChangeDto } from './contracts';
import { javaSemanticSpans, type JavaSemanticKind } from './javaSemantics';

export const javaWithAnnotations = new LanguageSupport(javaLanguage.configure({
  props: [styleTags({
    'MarkerAnnotation Annotation MarkerAnnotation/Identifier Annotation/Identifier MarkerAnnotation/ScopedIdentifier! Annotation/ScopedIdentifier!': tags.annotation,
    'TernaryExpression/:': tags.logicOperator,
  })],
}));

const semanticMarks: Record<JavaSemanticKind, Decoration> = {
  class: Decoration.mark({ class: 'ct-java-class' }),
  interface: Decoration.mark({ class: 'ct-java-interface' }),
  method: Decoration.mark({ class: 'ct-java-method' }),
  staticMethod: Decoration.mark({ class: 'ct-java-static-method' }),
  field: Decoration.mark({ class: 'ct-java-field' }),
  constant: Decoration.mark({ class: 'ct-java-constant' }),
};

function semanticDecorations(view: EditorView) {
  const ranges: Range<Decoration>[] = [];
  for (const { from, to } of view.visibleRanges) {
    for (const span of javaSemanticSpans(view.state, from, to)) ranges.push(semanticMarks[span.kind].range(span.from, span.to));
  }
  return Decoration.set(ranges, true);
}

// Highest precedence makes these marks the innermost spans, so their colour wins over the syntax class on the same token.
const javaSemantics = Prec.highest(ViewPlugin.fromClass(class {
  decorations: DecorationSet;

  constructor(view: EditorView) {
    this.decorations = semanticDecorations(view);
  }

  update(update: ViewUpdate) {
    if (update.docChanged || update.viewportChanged || syntaxTree(update.startState) !== syntaxTree(update.state)) {
      this.decorations = semanticDecorations(update.view);
    }
  }
}, { decorations: plugin => plugin.decorations }));

const LANGUAGES: Record<CodeLanguage, Extension> = {
  java: [javaWithAnnotations, javaSemantics],
  xml: StreamLanguage.define(xml),
  properties: StreamLanguage.define(properties),
  yaml: StreamLanguage.define(yaml),
  shell: StreamLanguage.define(shell),
  sql: StreamLanguage.define(standardSQL),
  javascript: StreamLanguage.define(javascript),
  python: StreamLanguage.define(python),
  text: [],
};

const highlightStyle = HighlightStyle.define([
  { tag: tags.comment, color: '#A0A1A7', fontStyle: 'italic' },
  { tag: [tags.keyword, tags.null, tags.bool, tags.standard(tags.typeName)], color: '#A626A4', fontStyle: 'italic' },
  { tag: [tags.typeName, tags.className], color: '#C18401' },
  { tag: [tags.function(tags.variableName), tags.function(tags.propertyName), tags.annotation, tags.meta], color: '#4078F2' },
  { tag: [tags.string, tags.attributeValue], color: '#50A14F' },
  { tag: [tags.number, tags.atom], color: '#986801' },
  { tag: tags.operator, color: '#0184BC' },
  { tag: [tags.punctuation, tags.derefOperator], color: '#383A42' },
  { tag: [tags.tagName, tags.propertyName], color: '#E45649' },
  { tag: tags.attributeName, color: '#986801' },
]);

const theme = EditorView.theme({
  '&': { height: '100%', fontSize: '12.5px', color: '#383A42', backgroundColor: '#FFFFFF' },
  '&.cm-focused': { outline: 'none' },
  '.cm-scroller': { fontFamily: '"JetBrains Mono", ui-monospace, Menlo, Monaco, monospace', lineHeight: '20px' },
  '.cm-content': { padding: '4px 0 24px', caretColor: '#383A42' },
  '.cm-line': { padding: '0 16px 0 8px' },
  '.cm-activeLine': { backgroundColor: '#F5F5F5' },
  '.cm-gutters': { backgroundColor: '#FFFFFF', border: 'none', color: '#9D9D9F' },
  '.cm-lineNumbers .cm-gutterElement': { minWidth: '36px', padding: '0 6px 0 4px' },
  '.cm-activeLineGutter': { backgroundColor: 'transparent', color: '#383A42' },
  '.ct-java-class': { color: '#C18401' },
  '.ct-java-interface': { color: '#50A14F', fontStyle: 'italic' },
  '.ct-java-method': { color: '#4078F2' },
  '.ct-java-static-method': { color: '#4078F2', fontStyle: 'italic' },
  '.ct-java-field': { color: '#E45649' },
  '.ct-java-constant': { color: '#E45649', fontStyle: 'italic' },
});

const setDestination = StateEffect.define<number | null>();

/** The line a navigation arrived at, tinted until the next navigation. */
const destination = StateField.define<DecorationSet>({
  create: () => Decoration.none,
  update(value, transaction) {
    let next = value.map(transaction.changes);
    for (const effect of transaction.effects) {
      if (!effect.is(setDestination)) continue;
      const line = effect.value;
      next = line === null || line < 1 || line > transaction.state.doc.lines
        ? Decoration.none
        : Decoration.set([Decoration.line({ class: 'ct-code__destination' }).range(transaction.state.doc.line(line).from)]);
    }
    return next;
  },
  provide: field => EditorView.decorations.from(field),
});

const setLink = StateEffect.define<{ from: number; to: number } | null>();

/** The identifier under the pointer while ⌘ or Ctrl is held, drawn as a link. */
const link = StateField.define<DecorationSet>({
  create: () => Decoration.none,
  update(value, transaction) {
    let next = value.map(transaction.changes);
    for (const effect of transaction.effects) {
      if (!effect.is(setLink)) continue;
      const ranges: Range<Decoration>[] = effect.value === null ? [] : [Decoration.mark({ class: 'ct-code__link' }).range(effect.value.from, effect.value.to)];
      next = Decoration.set(ranges);
    }
    return next;
  },
  provide: field => EditorView.decorations.from(field),
});

export type FollowRequest = { name: string; x: number; y: number };

/** A press on a line number, the drag that follows it until release, or Escape during that drag. */
export type CommentGesture =
  | { type: 'start'; line: number; extend: boolean }
  | { type: 'drag'; line: number }
  | { type: 'finish' }
  | { type: 'cancel' };

/** A run affordance on one line: a test method, or the class declaration. */
export type RunMarker = { line: number; method: string | null; label: string; tone: 'idle' | 'pass' | 'fail' | 'running' | 'blocked'; title: string };

class RunGutterMarker extends GutterMarker {
  constructor(readonly marker: RunMarker) {
    super();
  }

  eq(other: RunGutterMarker) {
    return other.marker.tone === this.marker.tone && other.marker.title === this.marker.title && other.marker.method === this.marker.method;
  }

  toDOM() {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = `ct-gutter-run is-${this.marker.tone}`;
    button.title = this.marker.title;
    button.setAttribute('aria-label', this.marker.label);
    button.disabled = this.marker.tone === 'running' || this.marker.tone === 'blocked';
    button.innerHTML = this.marker.tone === 'running'
      ? '<svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" aria-hidden="true" class="ct-spin"><path d="M21 12a9 9 0 1 1-6.219-8.56"/></svg>'
      : '<svg width="11" height="11" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><polygon points="6 3 20 12 6 21 6 3"/></svg>';
    return button;
  }
}

const setRunMarkers = StateEffect.define<readonly RunMarker[]>();

const runMarkers = StateField.define<RangeSet<RunGutterMarker>>({
  create: () => RangeSet.empty,
  update(value, transaction) {
    let next = value.map(transaction.changes);
    for (const effect of transaction.effects) {
      if (!effect.is(setRunMarkers)) continue;
      const doc = transaction.state.doc;
      next = RangeSet.of(effect.value
        .filter(marker => marker.line >= 1 && marker.line <= doc.lines)
        .map(marker => new RunGutterMarker(marker).range(doc.line(marker.line).from)), true);
    }
    return next;
  },
});


type LineRange = { start: number; end: number };

const setCommentRange = StateEffect.define<LineRange | null>();
const selectedLine = Decoration.line({ class: 'ct-code__selected' });

class SelectedGutterMarker extends GutterMarker {
  elementClass = 'ct-code__selected';
}

const selectedGutter = new SelectedGutterMarker();

// ponytail: one mark per line in the range, O(range) per doc change; restrict to view.visibleRanges if huge ranges lag.
function rangeMarks<T extends RangeValue>(state: EditorState, range: LineRange | null, mark: T): RangeSet<T> {
  if (range === null) return RangeSet.empty;
  const marks: Range<T>[] = [];
  for (let line = Math.max(1, range.start); line <= Math.min(range.end, state.doc.lines); line++) marks.push(mark.range(state.doc.line(line).from));
  return RangeSet.of(marks);
}

/** The lines a review comment is being written on, tinted like the diff's selected rows. */
const commentRangeField = StateField.define<LineRange | null>({
  create: () => null,
  update: (value, transaction) => transaction.effects.reduce<LineRange | null>((next, effect) => effect.is(setCommentRange) ? effect.value : next, value),
  // The doc is a dependency so an edit rebuilds positions instead of leaving them past a shortened document.
  provide: field => [
    EditorView.decorations.compute(['doc', field], state => rangeMarks(state, state.field(field), selectedLine)),
    gutterLineClass.compute(['doc', field], state => rangeMarks(state, state.field(field), selectedGutter)),
  ],
});

function runGutter(run: { current: ((marker: RunMarker) => void) | null }) {
  return gutter({
    class: 'ct-run-gutter',
    markers: view => view.state.field(runMarkers),
    initialSpacer: () => new RunGutterMarker({ line: 0, method: null, label: '', tone: 'idle', title: '' }),
    domEventHandlers: {
      mousedown(view, line, event) {
        if (!(event.target instanceof Element) || event.target.closest('.ct-gutter-run:not(:disabled)') === null) return false;
        let found: RunMarker | null = null;
        view.state.field(runMarkers).between(line.from, line.from, (_from, _to, value) => { found = value.marker; });
        if (found === null || run.current === null) return false;
        event.preventDefault();
        run.current(found);
        return true;
      },
    },
  });
}

function followHandlers(follow: { current: ((request: FollowRequest) => void) | null }) {
  const wordAt = (view: EditorView, event: MouseEvent) => {
    const position = view.posAtCoords({ x: event.clientX, y: event.clientY });
    if (position === null) return null;
    const word = view.state.wordAt(position);
    return word === null || !/^[A-Za-z_$][\w$]*$/.test(view.state.sliceDoc(word.from, word.to)) ? null : word;
  };
  const clear = (view: EditorView) => {
    if (view.state.field(link).size > 0) view.dispatch({ effects: setLink.of(null) });
  };
  return EditorView.domEventHandlers({
    mousemove(event, view) {
      if (follow.current === null || !(event.metaKey || event.ctrlKey)) {
        clear(view);
        return false;
      }
      const word = wordAt(view, event);
      const shown = view.state.field(link);
      let same = false;
      shown.between(0, view.state.doc.length, (from, to) => { same = word !== null && from === word.from && to === word.to; });
      if (!same) view.dispatch({ effects: setLink.of(word === null ? null : { from: word.from, to: word.to }) });
      return false;
    },
    mouseleave(_event, view) {
      clear(view);
      return false;
    },
    keyup(event, view) {
      if (!event.metaKey && !event.ctrlKey) clear(view);
      return false;
    },
    mousedown(event, view) {
      // A plain click only places the caret, so source stays typeable.
      if (follow.current === null || !(event.metaKey || event.ctrlKey) || event.button !== 0) return false;
      const word = wordAt(view, event);
      if (word === null) return false;
      event.preventDefault();
      follow.current({ name: view.state.sliceDoc(word.from, word.to), x: event.clientX, y: event.clientY });
      return true;
    },
  });
}

type Handlers = {
  follow: { current: ((request: FollowRequest) => void) | null };
  run: { current: ((marker: RunMarker) => void) | null };
  edit: { current: ((text: string) => void) | null };
  comment: { current: ((gesture: CommentGesture) => void) | null };
  /** The one element a card key's portal renders into, for as long as the key is a card. */
  cards: { host: (key: string, view: EditorView) => HTMLElement };
};

/** A review comment card or composer drawn under a line. */
export type LineCard = { key: string; line: number };

/**
 * Hosts one React card between lines. CodeMirror recreates the widget when its line leaves the viewport; handing it
 * the same element each time keeps the card's portal, and so its composer text and pending actions, mounted.
 */
class CardWidget extends WidgetType {
  constructor(readonly key: string, readonly handlers: Handlers['cards']) {
    super();
  }

  eq(other: CardWidget) {
    return other.key === this.key;
  }

  get estimatedHeight() {
    return 96;
  }

  toDOM(view: EditorView) {
    return this.handlers.host(this.key, view);
  }

  ignoreEvent() {
    return true;
  }
}

const setCards = StateEffect.define<{ cards: readonly LineCard[]; handlers: Handlers['cards'] }>();

const cardField = StateField.define<DecorationSet>({
  create: () => Decoration.none,
  update(value, transaction) {
    let next = value.map(transaction.changes);
    for (const effect of transaction.effects) {
      if (!effect.is(setCards)) continue;
      const doc = transaction.state.doc;
      next = Decoration.set(effect.value.cards
        .filter(card => card.line >= 1 && card.line <= doc.lines)
        .map(card => Decoration.widget({ widget: new CardWidget(card.key, effect.value.handlers), block: true, side: 1 }).range(doc.line(card.line).to)), true);
    }
    return next;
  },
  provide: field => EditorView.decorations.from(field),
});

/** Marks a document replacement that came from props, so it is not reported back as the user's edit. */
const external = Annotation.define<boolean>();
const editability = new Compartment();
const setBaseline = StateEffect.define<string>();

/** The saved text, line by line; a line that differs from it at the same index is an unsaved edit. */
const baseline = StateField.define<readonly string[]>({
  create: () => [],
  update(value, transaction) {
    for (const effect of transaction.effects) {
      if (effect.is(setBaseline)) return effect.value.split('\n');
    }
    return value;
  },
});

const setLineChanges = StateEffect.define<readonly LineChangeDto[]>();

/** How the saved file differs from the pull request's merge base, in saved-file line numbers. */
const baseChanges = StateField.define<GutterChanges>({
  create: () => gutterChanges([]),
  update(value, transaction) {
    for (const effect of transaction.effects) {
      if (effect.is(setLineChanges)) return gutterChanges(effect.value);
    }
    return value;
  },
});

class ChangeMarker extends GutterMarker {
  constructor(readonly className: string) {
    super();
  }

  eq(other: ChangeMarker) {
    return other.className === this.className;
  }

  toDOM() {
    const mark = document.createElement('span');
    mark.className = this.className;
    return mark;
  }
}

const changeGutter = gutter({
  class: 'ct-change-gutter',
  lineMarker(view, block) {
    const saved = view.state.field(baseline);
    const line = view.state.doc.lineAt(block.from);
    if (saved[line.number - 1] !== line.text) return new ChangeMarker('ct-change-mark is-draft');
    // A line equal to the saved line at its index keeps that line's mark, the same test the draft mark uses.
    // ponytail: while a draft shifts lines, a coincidentally equal line (a blank one) can keep another line's mark;
    // map the marks through the edits if that misleads.
    const { lines, deletedAfter } = view.state.field(baseChanges);
    const kind = lines.get(line.number);
    const classes = [
      kind === undefined ? '' : ` is-${kind}`,
      deletedAfter.has(line.number) ? ' is-deleted-below' : '',
      line.number === 1 && deletedAfter.has(0) ? ' is-deleted-above' : '',
    ].join('');
    return classes === '' ? null : new ChangeMarker(`ct-change-mark${classes}`);
  },
  lineMarkerChange: update => update.docChanged
    || update.transactions.some(transaction => transaction.effects.some(effect => effect.is(setBaseline) || effect.is(setLineChanges))),
});

const NO_LINE_CHANGES: readonly LineChangeDto[] = [];

/**
 * The find keys, rather than the package's own `searchKeymap`, which also binds Mod-d, Mod-Shift-l and Mod-Alt-g.
 * A scoped Escape closes only an open panel; with none open it returns false, so the screen's own Escape still runs.
 */
const SEARCH_KEYS = [
  { key: 'Mod-f', run: openSearchPanel, scope: 'editor search-panel' },
  { key: 'Mod-g', run: findNext, shift: findPrevious, preventDefault: true },
  { key: 'F3', run: findNext, shift: findPrevious, preventDefault: true },
  { key: 'Escape', run: closeSearchPanel, scope: 'editor search-panel' },
];

function editable(allowed: boolean) {
  return EditorState.readOnly.of(!allowed);
}

function stateFor(path: string, value: string, saved: string, allowed: boolean, handlers: Handlers) {
  return EditorState.create({
    doc: value,
    extensions: [
      runMarkers,
      runGutter(handlers.run),
      lineNumbers({
        domEventHandlers: {
          mousedown(view, block, event) {
            const report = handlers.comment;
            if (report.current === null || !(event instanceof MouseEvent) || event.button !== 0) return false;
            event.preventDefault();
            let line = view.state.doc.lineAt(block.from).number;
            report.current({ type: 'start', line, extend: event.shiftKey });
            // The press can collapse a composer above, moving lines under a still pointer; only real movement drags.
            let dragging = false;
            // On the window, so the drag keeps extending over the code column and past the editor's edges.
            const move = (moved: MouseEvent) => {
              if (!dragging && Math.hypot(moved.clientX - event.clientX, moved.clientY - event.clientY) <= 3) return;
              dragging = true;
              const next = view.state.doc.lineAt(view.lineBlockAtHeight(moved.clientY - view.documentTop).from).number;
              if (next === line) return;
              line = next;
              report.current?.({ type: 'drag', line });
            };
            const stop = (gesture: CommentGesture) => {
              window.removeEventListener('mousemove', move);
              window.removeEventListener('mouseup', finish);
              window.removeEventListener('blur', finish);
              window.removeEventListener('keydown', cancel, true);
              report.current?.(gesture);
            };
            const finish = () => stop({ type: 'finish' });
            const cancel = (key: KeyboardEvent) => {
              if (key.key !== 'Escape') return;
              // Handled, so the screen's own Escape does not also exit zoom or close the pull request.
              key.preventDefault();
              stop({ type: 'cancel' });
            };
            window.addEventListener('mousemove', move);
            window.addEventListener('mouseup', finish);
            window.addEventListener('blur', finish);
            window.addEventListener('keydown', cancel, true);
            return true;
          },
        },
      }),
      changeGutter,
      highlightActiveLine(),
      highlightActiveLineGutter(),
      editability.of(editable(allowed)),
      history(),
      // CodeMirror renders only the lines in view, so Chromium's find-in-page can never see the rest of the file.
      // The panel hides its replace controls by itself on read-only production source.
      search({ top: true }),
      keymap.of([...defaultKeymap, ...historyKeymap, ...SEARCH_KEYS]),
      LANGUAGES[languageFor(path)],
      syntaxHighlighting(highlightStyle),
      baseline.init(() => saved.split('\n')),
      baseChanges,
      destination,
      commentRangeField,
      cardField,
      link,
      followHandlers(handlers.follow),
      theme,
      EditorView.updateListener.of(update => {
        if (update.docChanged && !update.transactions.some(transaction => transaction.annotation(external))) {
          handlers.edit.current?.(update.state.doc.toString());
        }
      }),
      EditorView.contentAttributes.of({
        'aria-label': allowed ? `${path} source — type to edit` : `${path} source`,
        spellcheck: 'false',
      }),
    ],
  });
}

export default function CodeView({
  path, value, saved, lineChanges = NO_LINE_CHANGES, editable: allowed = false, onEdit, line, token, onFollow, markers = [], onRun, onComment,
  commentRange = null, cards = [], renderCard,
}: {
  path: string;
  /** The text shown: the unsaved draft when there is one, else the saved file. */
  value: string;
  /** The saved file; lines that differ from it are marked in the gutter. */
  saved: string;
  /** How the saved file differs from the pull request's merge base, marked in the gutter like IntelliJ's change markers. */
  lineChanges?: readonly LineChangeDto[];
  editable?: boolean;
  onEdit?: (text: string) => void;
  /** 1-based destination line; 0 shows the top of the file without a highlight. */
  line: number;
  /** Changes on every navigation, so arriving at the same line again still scrolls to it. */
  token: number;
  /** ⌘- or Ctrl-click on an identifier; absent when no symbol data can resolve it. */
  onFollow?: (request: FollowRequest) => void;
  markers?: readonly RunMarker[];
  onRun?: (marker: RunMarker) => void;
  /** A press or drag on line numbers: start a comment range there, or extend the range with Shift. */
  onComment?: (gesture: CommentGesture) => void;
  /** The comment range, tinted like the diff's selected rows. */
  commentRange?: LineRange | null;
  cards?: readonly LineCard[];
  renderCard?: (key: string) => ReactNode;
}) {
  const host = useRef<HTMLDivElement>(null);
  const view = useRef<EditorView | null>(null);
  const follow = useRef<((request: FollowRequest) => void) | null>(onFollow ?? null);
  follow.current = onFollow ?? null;
  const run = useRef<((marker: RunMarker) => void) | null>(onRun ?? null);
  run.current = onRun ?? null;
  const edit = useRef<((text: string) => void) | null>(onEdit ?? null);
  edit.current = onEdit ?? null;
  const comment = useRef<((gesture: CommentGesture) => void) | null>(onComment ?? null);
  comment.current = onComment ?? null;
  const [hosts, setHosts] = useState<ReadonlyMap<string, HTMLElement>>(new Map());
  const cardHosts = useRef(new Map<string, { element: HTMLElement; observer: ResizeObserver }>());
  const cardHandlers = useRef<Handlers['cards']>({
    host: (key, editor) => {
      const known = cardHosts.current.get(key);
      if (known !== undefined) return known.element;
      const element = document.createElement('div');
      element.className = 'ct-inline-card';
      const observer = new ResizeObserver(() => editor.requestMeasure());
      observer.observe(element);
      cardHosts.current.set(key, { element, observer });
      // CodeMirror calls this inside its own update; the portal map changes after that update finishes.
      queueMicrotask(() => setHosts(current => new Map(current).set(key, element)));
      return element;
    },
  });
  const handlers = useRef<Handlers>({ follow, run, edit, comment, cards: cardHandlers.current });
  const shownPath = useRef(path);

  useEffect(() => {
    if (host.current === null) return;
    const created = new EditorView({ parent: host.current, state: stateFor(path, value, saved, allowed, handlers.current) });
    view.current = created;
    const knownHosts = cardHosts.current;
    return () => {
      created.destroy();
      view.current = null;
      for (const { observer } of knownHosts.values()) observer.disconnect();
    };
    // The view is created once; the effects below keep it in step with later props.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    const current = view.current;
    if (current === null) return;
    if (shownPath.current !== path) {
      // Another file: a fresh state, so undo history and language never leak between files. An open find carries
      // over instead, because the window find bar it replaces kept its text across files.
      const finding = searchPanelOpen(current.state) ? getSearchQuery(current.state) : null;
      current.setState(stateFor(path, value, saved, allowed, handlers.current));
      if (finding !== null) {
        openSearchPanel(current);
        current.dispatch({ effects: setSearchQuery.of(finding) });
      }
      shownPath.current = path;
      return;
    }
    // Same file: typing already put `value` in the document; anything else (revert, reload, save) replaces it.
    if (current.state.doc.toString() !== value) {
      current.dispatch({ changes: { from: 0, to: current.state.doc.length, insert: value }, annotations: external.of(true) });
    }
    current.dispatch({ effects: [setBaseline.of(saved), editability.reconfigure(editable(allowed))] });
  }, [path, value, saved, allowed]);

  // After the effect above, so a file switch's fresh state gets the range and the change marks too.
  useEffect(() => {
    view.current?.dispatch({ effects: setCommentRange.of(commentRange) });
  }, [path, commentRange]);

  useEffect(() => {
    view.current?.dispatch({ effects: setLineChanges.of(lineChanges) });
  }, [path, lineChanges]);

  useEffect(() => {
    const current = view.current;
    if (current === null) return;
    const doc = current.state.doc;
    if (line < 1 || line > doc.lines) {
      current.dispatch({ effects: [setDestination.of(null), EditorView.scrollIntoView(0, { y: 'start' })] });
      return;
    }
    const position = doc.line(line).from;
    current.dispatch({
      selection: { anchor: position },
      effects: [setDestination.of(line), EditorView.scrollIntoView(position, { y: 'center' })],
    });
    // Typing must not scroll back to the destination, so the value is deliberately not a dependency.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path, line, token]);

  useEffect(() => {
    view.current?.dispatch({ effects: setRunMarkers.of(markers) });
  }, [path, markers]);

  useEffect(() => {
    const keys = new Set(cards.map(card => card.key));
    const gone = [...cardHosts.current.keys()].filter(key => !keys.has(key));
    for (const key of gone) {
      cardHosts.current.get(key)?.observer.disconnect();
      cardHosts.current.delete(key);
    }
    if (gone.length > 0) {
      setHosts(current => {
        const next = new Map(current);
        for (const key of gone) next.delete(key);
        return next;
      });
    }
    view.current?.dispatch({ effects: setCards.of({ cards, handlers: cardHandlers.current }) });
  }, [path, cards]);

  return (
    <div className={`ct-code${onComment === undefined ? '' : ' is-commentable'}`} ref={host}>
      {renderCard !== undefined && [...hosts].map(([key, element]) => createPortal(renderCard(key), element, key))}
    </div>
  );
}
