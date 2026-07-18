# ByteQuay workspace

ByteQuay is a local-first macOS application for daily developer review and
agent-driven task work. The Electron/React frontend talks only to a local
Spring Boot sidecar. Agent work is scheduled through separate CLI and API
resource lanes; controllers and task services do not launch agents directly.

## Repository map

- `frontend/` — Electron, React 19, TypeScript, Electron Forge, and Vite.
- `backend/` — Java and Spring Boot services, local persistence, provider
  adapters, the agent scheduler, and ByteQuay's MCP/tool boundary.
- `docs/mockups/` — product decisions, feature designs, and visual references.

## Durable boundaries

- Embedded GitHub pages use Electron `WebContentsView`.
- AI output remains local until an explicit user-approved publish action.
- Roles, skills, permissions, tools, and resources are resolved by ByteQuay;
  provider-native repository instruction discovery is not a policy source.
- `WORKSPACE.md` contains provider-neutral architecture only. Task workflows,
  character, permissions, and skill activation belong to ByteQuay's registry.

## Validation

- Backend: run Maven checks from `backend/`.
- Frontend: run npm scripts from `frontend/`.
- Run both development processes with `./dev.sh` from the repository root.
