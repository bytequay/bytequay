# Trust, data, and safety

ByteQuay is local-first, not offline. Local application state and task execution
coexist with GitHub, selected model providers, and optional integrations that
send requests outside the Mac.

## Desktop boundary

The Spring Boot sidecar binds only to localhost. Electron main owns authenticated
HTTP and event-stream access; the renderer uses typed IPC and does not receive
backend or provider secrets. Renderer requests identify application objects,
not arbitrary filesystem paths.

Embedded web pages use Electron `WebContentsView` and their own website session.
They cannot use the privileged application bridge. Backend credentials and
external-browser cookies are not copied into that browsing session.

One backend process owns the application's SQLite database. Hibernate owns the
current schema; a released-data migration contract must exist before adding
migration-history machinery.

## Authority

Every GitHub write requires a direct user action or an explicitly accepted
automation scope. Model-generated text, repository instructions, a read-only
view, private discussion, or repository relationship cannot grant that authority.

Preview and execution stay bound to the accepted destination and source revision.
Uncertain publication is reconciled instead of blindly repeated. Successful
transport or a model's assertion is not proof that the intended remote action
completed.

Normal worktree cleanup requires stopped writers and verified clean Git state,
uses non-force removal, and preserves the generated branch. Explicit task or
workspace deletion has separate destructive authority and confirmation. Dirty,
mismatched, or unverifiable work is retained for inspection.

## Provider and process boundary

ByteQuay owns task lifecycle and application authorization; provider harnesses
own native execution, sandboxing, and approval mechanisms. These are distinct
protections. Capability and authentication checks precede task allocation.

Managed provider profiles stay outside repository worktrees. ByteQuay validates
effective provider configuration, rejects inherited capabilities or policies
that widen the approved scope, and fails closed when isolation cannot be
established. Stored application
GitHub tokens are not injected into agent environments. Environment filtering
and prompt instructions do not establish complete OS-level isolation from
same-user files or credentials.

Read-only discussions deny repository writes, network access, and approval
requests, and expose no product tools. Guarded review
children may explore source with permitted context tools, memory reads, and
sandboxed shell searches, then report to the root. They cannot save reports,
write memory, use exposed edit tools, or delegate further. Root-only mutations
are checked against the exact root turn. A read-only instruction inside a shared
workspace-write sandbox is not a separate read-only filesystem sandbox.

Provider work completion and process cleanup are separate facts. Cleanup
uncertainty preserves the provider result and runtime identity while retaining
exclusive worktree ownership. Restart recovery never automatically resends
input whose delivery is uncertain.

## Evidence and retained data

SQLite stores durable application and task facts. Task-owned files store bounded
runtime history, output, and attachments; disposable indexes remain separate
from repository contents. The UI renders normalized user-visible events, never
raw provider payloads or hidden reasoning.

An agent review's investigation and validation share a conversation. This is
self-review, not independent verification. Structural checks, source anchors,
and reproducible tests improve evidence but do not prove semantic correctness.
Omitted scope and unsupported checks must stay visible.

Repository memories retain sources and user control. Agents cannot rewrite
user-owned memories. Course and Goal knowledge remain local product data unless
explicitly included in a selected provider request or promoted to repository
context by the user.

Optional CodeGraph exploration is advisory. Its failure cannot block task
execution or cleanup, and a task cannot choose another repository's index.
User-started host terminals and tests have a different trust boundary from
agent tools and must never be advertised as sandboxed by credential filtering.
