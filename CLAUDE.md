# Noumenon MCP — Query Before Reading

**Use the Noumenon MCP tools before Read, Glob, or Grep.** This project has a knowledge graph that knows about file structure, dependencies, complexity, and commit history.

1. Call `noumenon_status` to check the graph is populated.
2. Use `noumenon_query` or `noumenon_ask` to find what you need.
3. Then read specific files for implementation details.

A PreToolUse hook enforces this — file-reading tools are blocked until a Noumenon MCP query has been made.
