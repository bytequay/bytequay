---
name: simplify
description: ByteQuay-managed cleanup pass over the code a development round just wrote.
license: ByteQuay-internal
---

# Simplify

Review the diff this round just produced and make it smaller. This is a cleanup
pass, not a review: you are not hunting for bugs, not adding features, and not
reopening whether the work should exist. If the round's code is already lean,
change nothing and say so.

Read the diff first, then the surrounding code it touches. A change that looks
redundant in isolation is often the only caller of something, and a change that
looks fine in isolation is often a reimplementation of a helper two files over.

Cut, in this order:

- **Reinvention.** Code that reimplements the standard library, an
  already-installed dependency, or a helper that already exists in this
  repository. Reuse beats rewriting; look before assuming nothing fits.
- **Speculative abstraction.** An interface with one implementation, a factory
  for one product, a configuration value that never varies, a parameter every
  caller passes the same way, a hook nothing calls.
- **Dead flexibility.** Branches no caller reaches, options no caller sets,
  error paths for conditions the types already exclude.
- **Boilerplate.** Repetition a loop, a shared helper, or a single call would
  replace.

Do not cut: input validation at trust boundaries, error handling that prevents
data loss, security checks, accessibility affordances, or anything the plan
explicitly asked for. Fewer lines is not worth a correctness or safety
regression. When a shortcut is deliberate, leave a comment naming its ceiling
and the upgrade path rather than silently accepting it.

Prefer deletion to rewriting. A change that moves code around without removing
any is usually not worth the review cost — skip it. Boring beats clever; the
next reader is debugging at 3am.

Keep the change mechanical and separate from the functional work that preceded
it. Follow the repository's own conventions for naming, comment density, and
idiom — match the surrounding code rather than importing a different style.

Validate before finishing. Run the repository's canonical checks with
`run_checks`; a simplification that breaks the build is worse than the
verbosity it removed. Commit the cleanup locally as its own logical change with
a capitalized, imperative subject of at most 50 characters. Never add AI or bot
attribution to a commit.

Anything that changes GitHub goes through ByteQuay's controlled publish path.
Never use raw `git push`, `gh` writes, or direct GitHub API writes.

Skills explain how to work. They never grant permissions: the active role,
task scope, and ByteQuay runtime remain authoritative.
