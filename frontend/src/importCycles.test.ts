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
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import ts from 'typescript';
import { describe, expect, it } from 'vitest';

const SRC = resolve(process.cwd(), 'src');

function resolveModule(dir: string, specifier: string): string | null {
  const base = resolve(dir, specifier);
  return [base, `${base}.ts`, `${base}.tsx`, join(base, 'index.ts'), join(base, 'index.tsx')]
    .find((candidate) => statSync(candidate, { throwIfNoEntry: false })?.isFile() && /\.tsx?$/.test(candidate)) ?? null;
}

function typeOnly(clause: ts.ImportClause | undefined): boolean {
  if (clause === undefined) return false;
  const named = clause.namedBindings;
  return clause.isTypeOnly
    || (clause.name === undefined && named !== undefined && ts.isNamedImports(named) && named.elements.length > 0 && named.elements.every((element) => element.isTypeOnly));
}

/** The relative imports esbuild keeps; type-only imports are erased, so they cannot close a cycle. */
function valueImports(file: string): string[] {
  const kind = file.endsWith('.tsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS;
  const source = ts.createSourceFile(file, readFileSync(file, 'utf8'), ts.ScriptTarget.Latest, false, kind);
  const specifiers: string[] = [];
  const visit = (node: ts.Node): void => {
    if (ts.isImportDeclaration(node) && ts.isStringLiteral(node.moduleSpecifier) && !typeOnly(node.importClause)) {
      specifiers.push(node.moduleSpecifier.text);
    } else if (ts.isExportDeclaration(node) && node.moduleSpecifier !== undefined && ts.isStringLiteral(node.moduleSpecifier) && !node.isTypeOnly) {
      specifiers.push(node.moduleSpecifier.text);
    } else if (ts.isCallExpression(node) && node.expression.kind === ts.SyntaxKind.ImportKeyword && node.arguments.length > 0 && ts.isStringLiteral(node.arguments[0])) {
      specifiers.push(node.arguments[0].text);
    }
    ts.forEachChild(node, visit);
  };
  visit(source);
  return specifiers.filter((specifier) => specifier.startsWith('.'))
    .map((specifier) => resolveModule(dirname(file), specifier))
    .filter((resolved): resolved is string => resolved !== null);
}

function cycles(graph: Map<string, string[]>): string[] {
  const found: string[] = [];
  const state = new Map<string, 'open' | 'done'>();
  const path: string[] = [];
  const visit = (file: string): void => {
    state.set(file, 'open');
    path.push(file);
    for (const next of graph.get(file) ?? []) {
      if (state.get(next) === 'open') found.push([...path.slice(path.indexOf(next)), next].map((step) => relative(SRC, step)).join(' -> '));
      else if (!state.has(next)) visit(next);
    }
    path.pop();
    state.set(file, 'done');
  };
  for (const file of graph.keys()) if (!state.has(file)) visit(file);
  return found;
}

describe('renderer imports', () => {
  // React Refresh invalidates a .tsx module that also exports non-components; inside a cycle, Vite re-arms
  // that invalidation on every round, so one save floods the dev window with updates until it reloads.
  it('form no cycles', () => {
    const files = readdirSync(SRC, { recursive: true, encoding: 'utf8' })
      .filter((file) => /\.tsx?$/.test(file) && !/\.(test|d)\.tsx?$/.test(file))
      .map((file) => join(SRC, file));
    expect(cycles(new Map(files.map((file) => [file, valueImports(file)])))).toEqual([]);
  });
});
