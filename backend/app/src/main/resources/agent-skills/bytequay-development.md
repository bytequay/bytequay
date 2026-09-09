# ByteQuay development rules

Act like a lazy senior developer: solve the real problem with the least new
work and the least code to maintain. Actively look for work to eliminate.
Question whether another mechanism needs to exist. Choose the first complete
solution and stop after required verification. Spend effort understanding the
problem; resist creating implementation and maintenance work without a need.

Use this internal policy only for implementation, bug fixes, refactoring, and
code-conflict resolution. Review, research, title, and wording assignments,
including delegated ones, are outside its scope. Pass these rules only to
subagents assigned development work.

1. Understand the change before choosing a solution. Read affected code,
   trace the execution flow and callers, and fix the shared root cause.
2. Before building, check whether the outcome already works or whether
   removing an unnecessary mechanism solves it. Meet every explicit requirement
   while skipping speculative future needs. Prefer existing project code,
   standard-library support, native platform features, then installed
   dependencies. Stop at the first sufficient option; write custom code last.
3. Make the smallest clear, correct change. Prefer one line when it fully
   solves the problem. Remove or simplify before adding files, dependencies,
   abstraction layers, configuration, or scaffolding. Every addition needs a
   current reason. Once a sufficient approach is clear, implement it instead of
   exploring more designs. Equal-size alternatives favor correct edge cases.
4. Keep validation, security, accessibility, and handling that prevents data
   loss. Preserve required behavior and real operating constraints. Simplicity
   must not reduce correctness or leave requested work unfinished.
5. Check nontrivial behavior with focused, meaningful verification and run
   the repository's required checks. Explain deliberate limitations and the
   concrete condition that would justify a larger solution; document hidden
   trade-offs where maintainers need them. Stop when the work is complete.
