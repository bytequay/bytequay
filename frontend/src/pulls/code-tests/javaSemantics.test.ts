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
import { describe, expect, it } from 'vitest';
import { javaLanguage } from '@codemirror/lang-java';
import { syntaxTree } from '@codemirror/language';
import { EditorState } from '@codemirror/state';
import { highlightTree, tagHighlighter, tags } from '@lezer/highlight';
import { javaWithAnnotations } from './CodeView';
import { javaSemanticSpans } from './javaSemantics';

const SOURCE = `package com.bytequay.pulls;

import static java.util.Objects.requireNonNull;

@Service
public class PrTimelineService implements Runnable {
    private static final int MAXIMUM_VALUES = 1000;
    private final PrDetailStore store;
    private String title;

    interface PrDetailStore {
        void recordLocalTimelineEvent(PullRequestRef ref, PrTimelineEvent event);
    }

    public PrTimelineService(PrDetailStore store) {
        this.store = requireNonNull(store, "x");
    }

    public Optional<String> record(PullRequestRef ref, String title, List<String> values, Instant at) {
        store.recordLocalTimelineEvent(ref, new PrTimelineEvent(title, body(values), at));
        values.forEach(value -> checkText(value, MAXIMUM_VALUES));
        return Optional.of(describe(ref));
    }

    private static String body(List<String> values) {
        return String.join(UUID.randomUUID().toString(), values);
    }

    private String describe(PullRequestRef ref) {
        return title + ref;
    }

    @Override
    public void run() {}
}
`;

const TEST_SOURCE = `package io.trino.tests;

import static com.google.common.base.Preconditions.checkArgument;

public class TestQueries<T extends Comparable<T>> extends AbstractTestQueryFramework implements Serializable, Map.Entry<String, T> {
    private static final int LIMIT = 5;
    private final Map<String, Entry> entries = new HashMap<>();
    private int count;

    static final class Entry {}

    interface Source<V> extends Readable, Supplier<V> {}

    @Test
    public void testDays(T value) {
        final int MAX_LOCAL = 3;
        int count = MAX_LOCAL + LIMIT;
        long days = ChronoUnit.DAYS.between(start(), value);
        long millis = TimeUnit.SECONDS.toMillis(count);
        String language = Locale.US.getLanguage();
        UUID id = java.util.UUID.randomUUID();
        checkArgument(days > 0);
        assertQuery("SELECT 1");
        getQueryRunner().execute(helper());
        Runnable task = new Runnable() {
            private int count;

            @Override
            public void run() {
                count++;
            }
        };
    }

    private static int start() {
        return 0;
    }

    private String helper() {
        return "";
    }
}
`;

const WILDCARD_SOURCE = `import static org.assertj.core.api.Assertions.*;

class AssertionsTest {
    void test() {
        assertThat(actual()).isTrue();
    }

    private boolean actual() {
        return true;
    }
}
`;

const state = EditorState.create({ doc: SOURCE, extensions: javaLanguage });

/** Looks up the kind of the `occurrence`-th whole-word use of `name` in `source`, or null when it is plain text. */
function kindsIn(source: string) {
  const sourceState = EditorState.create({ doc: source, extensions: javaLanguage });
  const spans = javaSemanticSpans(sourceState, 0, sourceState.doc.length);
  return (name: string, occurrence = 0) => {
    const at = [...source.matchAll(new RegExp(`\\b${name}\\b`, 'g'))][occurrence]?.index;
    if (at === undefined) throw new Error(`${name} #${occurrence} is not in the snippet`);
    return spans.find(span => span.from === at && span.to === at + name.length)?.kind ?? null;
  };
}

const kind = kindsIn(SOURCE);
const testKind = kindsIn(TEST_SOURCE);

describe('javaSemanticSpans', () => {
  it('parses the whole snippet', () => {
    expect(syntaxTree(state).length).toBe(SOURCE.length);
  });

  it('colours type declarations and references', () => {
    expect(kind('PrTimelineService')).toBe('class');
    expect(kind('Runnable')).toBe('interface');
    expect(kind('PrDetailStore', 0)).toBe('interface');
    expect(kind('PrDetailStore', 1)).toBe('interface');
    expect(kind('PullRequestRef')).toBe('class');
    expect(kind('PrTimelineEvent', 0)).toBe('class');
    expect(kind('Optional', 0)).toBe('class');
    expect(kind('Optional', 1)).toBe('class');
    expect(kind('String', 0)).toBe('class');
    expect(kind('List')).toBe('interface');
    expect(kind('Instant')).toBe('class');
    expect(kind('UUID')).toBe('class');
    expect(kind('Service')).toBeNull();
    expect(kind('Override')).toBeNull();
  });

  it('colours method declarations, calls, and constructor calls', () => {
    expect(kind('PrTimelineService', 1)).toBe('method');
    expect(kind('recordLocalTimelineEvent', 0)).toBe('method');
    expect(kind('recordLocalTimelineEvent', 1)).toBe('method');
    expect(kind('PrTimelineEvent', 1)).toBe('method');
    expect(kind('forEach')).toBe('method');
    expect(kind('describe', 0)).toBe('method');
    expect(kind('describe', 1)).toBe('method');
    expect(kind('body', 1)).toBe('method');
    expect(kind('toString')).toBe('method');
  });

  it('colours static calls by qualifier or by what this file declares', () => {
    expect(kind('requireNonNull', 0)).toBeNull();
    expect(kind('requireNonNull', 1)).toBe('staticMethod');
    expect(kind('checkText')).toBe('method');
    expect(kind('body', 0)).toBe('staticMethod');
    expect(kind('of')).toBe('staticMethod');
    expect(kind('join')).toBe('staticMethod');
    expect(kind('randomUUID')).toBe('staticMethod');
  });

  it('colours unqualified calls as static only when declared or imported static', () => {
    expect(testKind('checkArgument', 1)).toBe('staticMethod');
    expect(testKind('start', 0)).toBe('staticMethod');
    expect(testKind('assertQuery')).toBe('method');
    expect(testKind('getQueryRunner')).toBe('method');
    expect(testKind('helper', 0)).toBe('method');

    const wildcardKind = kindsIn(WILDCARD_SOURCE);
    expect(wildcardKind('assertThat')).toBe('staticMethod');
    expect(wildcardKind('actual', 0)).toBe('method');
    expect(wildcardKind('isTrue')).toBe('method');
  });

  it('colours a constant qualified by a type as a constant with instance calls on it', () => {
    expect(testKind('ChronoUnit')).toBe('class');
    expect(testKind('DAYS')).toBe('constant');
    expect(testKind('between')).toBe('method');
    expect(testKind('SECONDS')).toBe('constant');
    expect(testKind('toMillis')).toBe('method');
    expect(testKind('US')).toBe('constant');
    expect(testKind('getLanguage')).toBe('method');
    expect(testKind('UUID', 1)).toBe('class');
    expect(testKind('randomUUID')).toBe('staticMethod');
  });

  it('colours implemented and extended interfaces, and file-local classes everywhere', () => {
    expect(testKind('AbstractTestQueryFramework')).toBe('class');
    expect(testKind('Serializable')).toBe('interface');
    expect(testKind('Entry', 0)).toBe('interface');
    expect(testKind('Entry', 1)).toBe('class');
    expect(testKind('Entry', 2)).toBe('class');
    expect(testKind('Readable')).toBe('interface');
    expect(testKind('Supplier')).toBe('interface');
  });

  it('keeps an implemented interface, a qualified JDK type, and an imported constant receiver consistent', () => {
    const probe = kindsIn(`import static java.util.concurrent.TimeUnit.SECONDS;
class Cache implements ConnectorFactory {
    private static class Entry {}
    private ConnectorFactory factory;
    void scan(Map<String, Entry> entries) {
        for (Map.Entry<String, Entry> e : entries.entrySet()) {}
        ConnectorFactory f = factory;
        SECONDS.toMillis(1);
    }
}
`);
    expect(probe('ConnectorFactory', 0)).toBe('interface');
    expect(probe('ConnectorFactory', 1)).toBe('interface');
    expect(probe('ConnectorFactory', 2)).toBe('interface');
    expect(probe('Entry', 1)).toBe('class');
    expect(probe('Entry', 2)).toBe('interface');
    expect(probe('Entry', 3)).toBe('class');
    expect(probe('SECONDS', 1)).toBe('constant');
    expect(probe('toMillis')).toBe('method');
  });

  it('leaves type parameters plain', () => {
    expect(testKind('T', 0)).toBeNull();
    expect(testKind('T', 1)).toBeNull();
    expect(testKind('T', 2)).toBeNull();
    expect(testKind('T', 3)).toBeNull();
    expect(testKind('V', 1)).toBeNull();
  });

  it('colours an inner class field used inside a method whose local has the same name', () => {
    expect(testKind('count', 0)).toBe('field');
    expect(testKind('count', 1)).toBeNull();
    expect(testKind('count', 2)).toBeNull();
    expect(testKind('count', 3)).toBe('field');
    expect(testKind('count', 4)).toBe('field');
  });

  it('leaves upper-case locals plain', () => {
    expect(testKind('MAX_LOCAL', 0)).toBeNull();
    expect(testKind('MAX_LOCAL', 1)).toBeNull();
    expect(testKind('LIMIT', 1)).toBe('constant');
  });

  it('colours a qualified annotation name without its arguments', () => {
    const source = '@javax.annotation.Nullable @a.b.Named(value = X.Y) class A {}';
    const annotated: string[] = [];
    const highlighter = tagHighlighter([{ tag: tags.annotation, class: 'annotation' }]);
    highlightTree(javaWithAnnotations.language.parser.parse(source), highlighter, (from, to) => annotated.push(source.slice(from, to)));
    expect(annotated).toEqual(['@javax.annotation.Nullable', '@a.b.Named']);
  });

  it('colours fields and constants unless a parameter shadows them', () => {
    expect(kind('MAXIMUM_VALUES', 0)).toBe('constant');
    expect(kind('MAXIMUM_VALUES', 1)).toBe('constant');
    expect(kind('store', 0)).toBe('field');
    expect(kind('store', 1)).toBeNull();
    expect(kind('store', 2)).toBe('field');
    expect(kind('store', 3)).toBeNull();
    expect(kind('store', 4)).toBe('field');
    expect(kind('title', 0)).toBe('field');
    expect(kind('title', 1)).toBeNull();
    expect(kind('title', 2)).toBeNull();
    expect(kind('title', 3)).toBe('field');
    expect(kind('values', 0)).toBeNull();
    expect(kind('value', 0)).toBeNull();
    expect(kind('ref', 1)).toBeNull();
  });

  it('only returns names inside the requested range', () => {
    const line = state.doc.line(8);
    expect(javaSemanticSpans(state, line.from, line.to).map(span => state.sliceDoc(span.from, span.to))).toEqual(['PrDetailStore', 'store']);
  });
});
