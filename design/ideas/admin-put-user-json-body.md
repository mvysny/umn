# `PUT /admin/users` takes the credentials as a JSON body

Today the admin API creates a user with `PUT /admin/users?username=u&password=p`, so the plaintext
password travels in the URL. URLs are what everything logs: a reverse proxy's access log (Caddy
logs the full URI, see `caddy-terminates-wss.md`), okhttp's `HttpLoggingInterceptor` at `BASIC`
level, browser and tool history. A body is logged only when someone asks for it.

## Proposal

**Server.** If both `username` and `password` query parameters are present, behave as today.
Otherwise read the body as JSON:

```json
{"username": "user", "password": "example"}
```

Both fields are required and non-empty, as now; a missing field, an empty body or a malformed one
answers 400 and creates nobody, and the 400 message describes the JSON body, never the query
parameters: it guides toward the new form. A request carrying both query parameters and a body
takes the query and ignores the body, as old servers do.

The server ignores `Content-Type` entirely. With the parameters missing, the JSON body is the only
thing left to try, so the header could only reject a request the server would understand anyway:
`curl -d` sends `application/x-www-form-urlencoded`. Nothing in `AdminServer` parses form
parameters, so such a body reaches `JavalinGson` intact.

```
curl -i -X PUT localhost:10145/admin/users -d '{"username":"user","password":"example"}'
```

**Client.** `AdminClient.createOrUpdateUser2(username, password)` always sends the JSON body, with
`application/json`. The signatures are identical, so an overload can't tell them apart; hence a new
name. The `2` reads odd, but says "the same operation, newer route" better than `putUser` or
`setUser` would. `createOrUpdateUser` becomes `@Deprecated` in the same release, pointing at it
with `ReplaceWith`. The KDoc of `createOrUpdateUser2` warns loudly, first line, that it needs a
UMN proxy server 0.17+, and so does the deprecation message, so nobody follows the `ReplaceWith`
without seeing it. It never falls back to query parameters on a 400: against an old server it
fails, rather than quietly putting the password back in the URL.

**Compatibility.** The route is additive: every 0.15-era request still means what it meant, so
`D_frozen_0_15_formats` ("the admin REST paths and JSON") holds on the server side. The other
direction doesn't: `createOrUpdateUser2` against a server older than this change sends no query
parameters, and that server answers 400. Aedict-Online's sync server is a 2018 0.13-SNAPSHOT
build (`D_frozen_0_15_formats`), so an Aedict-Online that upgrades its admin client and follows the
deprecation breaks user creation until that server is upgraded too. It fails loudly (400, nobody
created), never silently. The same owner runs both, so the migration is staged: server to 0.17
first, then Aedict-Online onto `createOrUpdateUser2`.

## When implementing

- `AdminServer`: the dispatch above. A body-less PUT with one query parameter now lands on the
  JSON branch and gets the JSON-body message.
- `AdminClientTest`: `createOrUpdateUser2` round-trip, empty password rejected.
- `umn-compat-0.15`'s `AdminTestBattery` runs against the production image as well as HEAD's
  server, so a JSON-body test goes in a HEAD-only battery. `E3` (`?username=x`, no password) keeps
  passing: it now hits the JSON branch with an empty body, still a 4xx.
- Docs: the `PUT /admin/users` row in `umn-proxy-server/README.md`, the curl in `README.md`,
  `umn-proxy-server-adminclient/README.md`; a `CHANGELOG.md` line naming the 0.17+ server
  `createOrUpdateUser2` needs.
- When the idea graduates, the why-nots worth a `D_` entry: why `Content-Type` plays no role, and
  why the client has no fallback.
