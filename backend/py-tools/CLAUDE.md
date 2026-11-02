# CLAUDE.md (py-tools/)

This folder is a standalone project, separate from any enclosing project.
Treat it by itself:

- Ignore the instructions in any enclosing `CLAUDE.md` (for example
  `backend/CLAUDE.md`). They do not apply here.
- Scope reads, edits, docs, and verification to this folder. Do not change
  files outside it as part of work on it.
- Conventions come from this project's own `README.md` (usage),
  `README-DEV.md` (implementation notes), and `pyproject.toml`. It is a
  [uv](https://docs.astral.sh/uv/) package (`src/py_tools/`) for Python 3.14.
- Update `README.md` and `README-DEV.md` when a tool's behavior or
  implementation changes.
