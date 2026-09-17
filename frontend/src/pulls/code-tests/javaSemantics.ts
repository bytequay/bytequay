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
import { syntaxTree } from '@codemirror/language';
import type { EditorState } from '@codemirror/state';

type Tree = ReturnType<typeof syntaxTree>;
type SyntaxNode = Tree['topNode'];

export type JavaSemanticKind = 'class' | 'interface' | 'method' | 'staticMethod' | 'field' | 'constant';

export type JavaSemanticSpan = { from: number; to: number; kind: JavaSemanticKind };

type Declarations = {
  classes: Set<string>;
  interfaces: Set<string>;
  typeParameters: Set<string>;
  staticMethods: Set<string>;
  instanceMethods: Set<string>;
  staticImports: Set<string>;
  staticWildcard: boolean;
  fields: Set<string>;
  constants: Set<string>;
  /** Locals of each method, constructor, or lambda, keyed by its start. */
  locals: Map<number, Set<string>>;
  /** Fields of each class or enum body, keyed by its start. */
  members: Map<number, Set<string>>;
};

const JDK_INTERFACES = new Set([
  'List', 'Map', 'Set', 'Collection', 'Iterable', 'Iterator', 'Comparable', 'Comparator', 'Runnable', 'Callable', 'Function',
  'BiFunction', 'Supplier', 'Consumer', 'BiConsumer', 'Predicate', 'Stream', 'CharSequence', 'AutoCloseable', 'Closeable', 'Future',
  'CompletionStage', 'Executor', 'ExecutorService', 'Lock', 'Queue', 'Deque', 'Entry', 'SortedMap', 'NavigableMap',
]);

const CONSTANT_NAME = /^[A-Z][A-Z0-9_]+$/;
const CAPITALISED = /^[A-Z]/;

/** Parents whose Identifier child names a package, annotation, or module rather than a value. */
const NOT_VALUES = new Set([
  'MarkerAnnotation', 'Annotation', 'ScopedIdentifier', 'ImportDeclaration', 'PackageDeclaration', 'ModuleDeclaration', 'ModuleDirective',
  'ReceiverParameter', 'AnnotationTypeDeclaration', 'AnnotationTypeElementDeclaration',
]);

const RECEIVER_PARENTS = new Set(['MethodInvocation', 'FieldAccess', 'MethodReference']);
const SCOPES = new Set(['MethodDeclaration', 'ConstructorDeclaration', 'LambdaExpression']);
const LOCAL_DECLARERS = new Set(['VariableDeclarator', 'FormalParameter', 'InferredParameters', 'LambdaExpression', 'CatchFormalParameter', 'ForSpec', 'Resource']);

// Every edit and every parse step yields a new tree, so a tree identifies one document text.
const declarationsByTree = new WeakMap<Tree, Declarations>();

const hasModifier = (node: SyntaxNode, modifier: string) => node.getChild('Modifiers')?.getChild(modifier) != null;

function declarationsOf(state: EditorState, tree: Tree): Declarations {
  const cached = declarationsByTree.get(tree);
  if (cached !== undefined) return cached;
  const found: Declarations = {
    classes: new Set(), interfaces: new Set(), typeParameters: new Set(), staticMethods: new Set(), instanceMethods: new Set(), staticImports: new Set(),
    staticWildcard: false, fields: new Set(), constants: new Set(), locals: new Map(), members: new Map(),
  };
  const nameOf = (node: SyntaxNode | null | undefined) => (node == null ? '' : state.sliceDoc(node.from, node.to));
  tree.iterate({
    enter: ref => {
      switch (ref.name) {
        case 'ImportDeclaration': {
          const node = ref.node;
          if (node.getChild('static') === null) return false;
          if (node.getChild('Asterisk') !== null) found.staticWildcard = true;
          else found.staticImports.add(nameOf(node.getChild('ScopedIdentifier')?.lastChild));
          return false;
        }
        case 'ClassDeclaration':
        case 'EnumDeclaration':
          found.classes.add(nameOf(ref.node.getChild('Definition')));
          return;
        case 'InterfaceDeclaration':
          found.interfaces.add(nameOf(ref.node.getChild('Definition')));
          return;
        case 'InterfaceTypeList':
          // Whatever a class implements or an interface extends is an interface everywhere in the file.
          for (let child = ref.node.firstChild; child !== null; child = child.nextSibling) {
            let type: SyntaxNode | null = child.name === 'GenericType' ? child.firstChild : child;
            while (type?.name === 'ScopedTypeName') type = type.lastChild;
            if (type?.name === 'TypeName') found.interfaces.add(nameOf(type));
          }
          return;
        case 'TypeParameter':
          found.typeParameters.add(nameOf(ref.node.getChild('Definition')));
          return;
        case 'MethodDeclaration': {
          const node = ref.node;
          (hasModifier(node, 'static') ? found.staticMethods : found.instanceMethods).add(nameOf(node.getChild('Definition')));
          return;
        }
        case 'EnumConstant':
          found.constants.add(nameOf(ref.node.getChild('Definition')));
          return;
        case 'FieldDeclaration':
        case 'ConstantDeclaration': {
          const node = ref.node;
          const constant = ref.name === 'ConstantDeclaration' || (hasModifier(node, 'static') && hasModifier(node, 'final'));
          for (const declarator of node.getChildren('VariableDeclarator')) (constant ? found.constants : found.fields).add(nameOf(declarator.getChild('Definition')));
        }
      }
    },
  });
  declarationsByTree.set(tree, found);
  return found;
}

/**
 * IntelliJ-style semantic kinds for the Java names that overlap `from`..`to`. Names this file cannot resolve are guessed:
 * a capitalised receiver is a type, and an unqualified call is static only when declared or imported static here.
 */
export function javaSemanticSpans(state: EditorState, from: number, to: number): JavaSemanticSpan[] {
  const tree = syntaxTree(state);
  const declared = declarationsOf(state, tree);
  const text = (node: SyntaxNode) => state.sliceDoc(node.from, node.to);

  const localsOf = (scope: SyntaxNode) => {
    const cached = declared.locals.get(scope.from);
    if (cached !== undefined) return cached;
    const names = new Set<string>();
    // ponytail: a local anywhere in the scope shadows the whole scope; block scoping and declaration order are ignored.
    const visit = (node: SyntaxNode) => {
      for (let child = node.firstChild; child !== null; child = child.nextSibling) {
        if (child.name === 'Definition') {
          if (LOCAL_DECLARERS.has(node.name)) names.add(text(child));
        } else if (child.name !== 'LambdaExpression' && child.name !== 'ClassBody') {
          visit(child);
        }
      }
    };
    visit(scope);
    declared.locals.set(scope.from, names);
    return names;
  };

  const fieldsOf = (body: SyntaxNode) => {
    const cached = declared.members.get(body.from);
    if (cached !== undefined) return cached;
    const names = new Set<string>();
    const members = body.name === 'EnumBody' ? body.getChild('EnumBodyDeclarations') : body;
    for (const field of members?.getChildren('FieldDeclaration') ?? []) {
      for (const declarator of field.getChildren('VariableDeclarator')) {
        const definition = declarator.getChild('Definition');
        if (definition !== null) names.add(text(definition));
      }
    }
    declared.members.set(body.from, names);
    return names;
  };

  const shadowed = (node: SyntaxNode, name: string) => {
    for (let scope = node.parent; scope !== null; scope = scope.parent) {
      if ((scope.name === 'ClassBody' || scope.name === 'EnumBody') && fieldsOf(scope).has(name)) return false;
      if (SCOPES.has(scope.name) && localsOf(scope).has(name)) return true;
    }
    return false;
  };

  /** @param local the name can refer to a class this file declares: it is unqualified, or qualified by one of those classes */
  const typeKind = (name: string, local = true): JavaSemanticKind => {
    if (local && declared.classes.has(name)) return 'class';
    return declared.interfaces.has(name) || JDK_INTERFACES.has(name) ? 'interface' : 'class';
  };

  const nameKind = (name: string, receiver: boolean): JavaSemanticKind | null => {
    // All-caps receivers without an underscore are usually types such as UUID or URI, not constants.
    if (CONSTANT_NAME.test(name) && !(receiver && !name.includes('_') && !declared.constants.has(name) && !declared.staticImports.has(name))) return 'constant';
    return CAPITALISED.test(name) ? typeKind(name) : null;
  };

  const valueKind = (node: SyntaxNode, name: string, receiver: boolean): JavaSemanticKind | null => {
    const member = declared.constants.has(name) || declared.fields.has(name);
    if ((member || CONSTANT_NAME.test(name)) && shadowed(node, name)) return null;
    if (member) return declared.constants.has(name) ? 'constant' : 'field';
    return nameKind(name, receiver);
  };

  /** The node a type name belongs to, looking through generic arguments and outer qualifiers. */
  const typeOwner = (node: SyntaxNode) => {
    let current = node;
    for (let parent = current.parent; parent !== null; current = parent, parent = parent.parent) {
      const head = parent.name === 'GenericType' && parent.firstChild?.from === current.from;
      const tail = parent.name === 'ScopedTypeName' && parent.lastChild?.to === current.to;
      if (!head && !tail) return parent.name;
    }
    return null;
  };

  const classify = (node: SyntaxNode): JavaSemanticKind | null => {
    const parent = node.parent;
    if (parent === null) return null;
    const name = text(node);
    if (node.name === 'Definition') {
      switch (parent.name) {
        case 'ClassDeclaration':
        case 'EnumDeclaration':
          return 'class';
        case 'InterfaceDeclaration':
          return 'interface';
        case 'MethodDeclaration':
        case 'ConstructorDeclaration':
          return 'method';
        case 'EnumConstant':
          return 'constant';
        case 'VariableDeclarator': {
          const declaration = parent.parent;
          if (declaration?.name === 'ConstantDeclaration') return 'constant';
          if (declaration?.name !== 'FieldDeclaration') return null;
          return hasModifier(declaration, 'static') && hasModifier(declaration, 'final') ? 'constant' : 'field';
        }
        default:
          return null;
      }
    }
    if (node.name === 'TypeName') {
      if (!CAPITALISED.test(name) || declared.typeParameters.has(name)) return null;
      const owner = typeOwner(node);
      if (owner === 'ObjectCreationExpression') return 'method';
      const qualifier = parent.name === 'ScopedTypeName' && parent.lastChild?.to === node.to && parent.firstChild !== null && parent.firstChild.from !== node.from
        ? parent.firstChild
        : null;
      return owner === 'InterfaceTypeList' ? 'interface' : typeKind(name, qualifier === null || declared.classes.has(text(qualifier)));
    }
    if (node.name !== 'Identifier' || NOT_VALUES.has(parent.name)) return null;
    const first = node.prevSibling === null;
    switch (parent.name) {
      case 'MethodName': {
        const receiver = parent.parent?.firstChild;
        if (receiver == null || receiver.from === parent.from) {
          if (declared.staticMethods.has(name) || declared.staticImports.has(name)) return 'staticMethod';
          return declared.staticWildcard && !declared.instanceMethods.has(name) ? 'staticMethod' : 'method';
        }
        const target = receiver.name === 'FieldAccess' ? receiver.lastChild : receiver;
        const kind = target?.name === 'Identifier' ? classify(target) : null;
        return kind === 'class' || kind === 'interface' ? 'staticMethod' : 'method';
      }
      case 'FieldAccess': {
        if (first) return valueKind(node, name, true);
        const qualifier = parent.firstChild;
        const typeQualified = qualifier !== null && CAPITALISED.test(state.sliceDoc(qualifier.from, qualifier.from + 1));
        if (declared.constants.has(name) || (typeQualified && CONSTANT_NAME.test(name))) return 'constant';
        return nameKind(name, parent.prevSibling === null && RECEIVER_PARENTS.has(parent.parent?.name ?? '')) ?? 'field';
      }
      case 'MethodReference':
        return first ? valueKind(node, name, true) : 'method';
      case 'MethodInvocation':
        return valueKind(node, name, true);
      case 'ElementValuePair':
        return first ? null : valueKind(node, name, false);
      default:
        return valueKind(node, name, false);
    }
  };

  const spans: JavaSemanticSpan[] = [];
  tree.iterate({
    from,
    to,
    enter: ref => {
      if (ref.from >= to || ref.to <= from || (ref.name !== 'Definition' && ref.name !== 'TypeName' && ref.name !== 'Identifier')) return;
      const kind = classify(ref.node);
      if (kind !== null) spans.push({ from: ref.from, to: ref.to, kind });
    },
  });
  return spans;
}
