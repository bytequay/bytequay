# Follow-ups

## Scope pull-request CI to affected areas

CI currently runs the complete backend and frontend checks for every pull
request. Add coarse change detection so frontend-only changes skip backend
work and backend-only changes skip frontend work.

Keep the workflow itself running for every pull request and expose one stable
required result, including for documentation-only changes. Changes to CI or
shared build configuration must run every affected check.

Keep full backend reactor verification as the default. Add dependency-aware
backend module selection only if measured CI duration justifies it; selection
must include downstream dependents, especially `app`, and fall back to the
full backend suite when impact cannot be determined safely. Do not introduce
Trino's GIB or dynamic matrix machinery for the current six-module reactor.
