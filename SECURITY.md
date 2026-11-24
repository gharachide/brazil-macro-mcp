# Security policy

## Reporting a vulnerability

Please use GitHub's private vulnerability reporting ("Report a vulnerability" in the
Security tab) instead of a public issue. You can expect an answer within a week.

## Deployment notes

- The server is read-only: it only issues HTTPS GET requests to `api.bcb.gov.br` and
  `olinda.bcb.gov.br`, and it stores nothing but its audit log.
- In HTTP mode it binds to `127.0.0.1` by default. Browser requests whose `Origin` header is
  not in `brazil-macro.http.allowed-origins` are rejected (DNS rebinding protection required by
  the MCP specification).
- For a remote deployment, set `brazil-macro.http.bearer-token` (or `BRAZIL_MACRO_HTTP_BEARER_TOKEN`)
  and terminate TLS in front of the server. For multi-user access, put it behind an OAuth 2.1
  gateway, as described in the MCP authorization specification.
- The audit chain detects edited, deleted or reordered entries. Detecting the removal of the
  newest entries requires recording the chain head (`--verify-audit` prints it) somewhere else.
