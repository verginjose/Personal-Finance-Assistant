# Security audit — Personal Finance Assistant

Static review of the gateway, auth, command, query, and OCR services, plus the frontend and the Kubernetes edge. This is a source review, not a live penetration test. Dates refer to the tree as of 8 November 2026.

**Result:** 4 high, 5 medium, 3 low.

A signed-in user can write transactions into another account, push OCR notifications at another account, and run script in the app origin. Script in the app origin can read the access and refresh tokens, which the frontend stores in `localStorage`.

## Summary

| Severity | Location | Finding |
| --- | --- | --- |
| High | `frontend/js/utils/ui.js:133` | Profile-picture URLs break out of HTML attributes. `esc()` does not encode quotes, and avatars of other users are rendered with it. |
| High | `api-gateway/.../SecurityConfig.java:57` | `/api/storage/**` is public. Uploads keep the client `Content-Type` and MinIO is given a public-read policy, on the same origin as the app. |
| High | `command-service/.../TransactionEntryController.java:58` | Bulk create checks only the first item's `userId`. Later items are saved under whatever user id they carry. |
| High | `api-gateway/.../BillUploadController.java:31` | Receipt and bill uploads take `userId` from the path and never compare it to the JWT. |
| Medium | `frontend/js/utils/api.js:8` | Access and refresh tokens live in `localStorage`. The SSE client also puts the access token in the query string. There is no CSP. |
| Medium | `command-service/.../SecurityInterceptor.java:24` | Command and query trust `X-User-Id`. `/internal/*` and the OCR HTTP API do not authenticate callers. There is no NetworkPolicy. |
| Medium | `api-gateway/.../RateLimiterConfig.java:89` | Login rate limiting uses the first `X-Forwarded-For` hop, at 200 requests/second, with no account lockout. |
| Medium | `auth-service/.../UserController.java:32` | Any signed-in user can read another user's email, username, and profile picture. |
| Medium | `auth-service/.../TokenRedisService.java:72` | Password change deletes refresh tokens only. Live access tokens keep working until they expire (default 15 minutes). |
| Low | `api-gateway/.../SecurityConfig.java:50` | `/actuator/**` on the gateway is unauthenticated, including Prometheus. |
| Low | `ocr-service/app/main.py:37` | Missing env vars fall back to `password123`, MinIO user `admin`, and RabbitMQ `guest`/`guest`. |
| Low | `auth-service/.../AuthDtos.java:41` | Registration accepts a client-supplied role. The enum currently has only `USER`, so this is latent. |

## High

### 1. Stored XSS through profile pictures

`esc()` encodes `&`, `<`, and `>`. It leaves `"` and `'` alone. `escAttr()` exists and does encode quotes, but avatars do not use it.

```133:133:frontend/js/utils/ui.js
    return `<img src="${esc(profilePicUrl)}" alt="${esc(label)}" class="avatar" ...>`;
```

`PUT /api/auth/profile` stores `profilePicture` as whatever string the caller sends (`AuthController` around line 79). Group screens render other members' pictures through `avatar()` and through `data-picture-src="${esc(...)}"` in `frontend/js/views/split.js`. A picture value such as `x" onerror="...` closes the attribute and adds a handler. Anyone who opens a group with that member runs the script.

The same quote gap is in the profile page style attribute (`frontend/js/views/profile.js` around line 34). That one is self-XSS. The group avatar is not: it fires in other users' browsers.

**Fix:** Render avatars with DOM APIs (`img.src = url`) or `escAttr`. Allow only `https:` URLs on your own storage host, or only paths you generated. Reject `"` and control characters on the profile update.

### 2. Public same-origin file hosting

The gateway allows `/api/storage/**` without a token:

```47:57:api-gateway/src/main/java/com/apigateway/configuration/SecurityConfig.java
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/actuator/**").permitAll()
                        // ...
                        .pathMatchers("/api/storage/**").permitAll()
```

`MinioStorageService.ensureBucket` sets a bucket policy of `s3:GetObject` for `Principal: *` when `publicRead` is true. Receipt, bill, and profile uploads all pass `publicRead = true`. The object content type is copied from the multipart part. Nothing checks magic bytes or an allow-list.

The storage route is on the same host as the app (`/api/storage/...` via the frontend nginx proxy). An uploaded `text/html` or `image/svg+xml` document, opened directly, executes in the app origin. Combined with tokens in `localStorage`, that is account takeover. Object names contain a UUID, so they are not guessable. The attacker does not need to guess them: they upload the file and send the URL, or they set that URL as a profile picture.

The code already notes this (`MinioStorageService` around line 104: "TODO: replace with presigned URLs").

**Fix:** Stop serving storage on the app origin without auth. Use short-lived presigned URLs, or an authenticated download route that forces `Content-Disposition: attachment` and `X-Content-Type-Options: nosniff`. Allow only image types you decode and re-encode. Do not apply a public bucket policy.

### 3. Bulk create writes another user's ledger

Single create compares the body `userId` to `X-User-Id`. Bulk create compares only the first row:

```50:61:command-service/src/main/java/com/finance/command/controller/TransactionEntryController.java
    @PostMapping("/create/bulk")
    public ResponseEntity<List<CreateEntryResponse>> createBulkEntries(
            @RequestHeader("X-User-Id") String xUserId,
            @Valid @RequestBody List<CreateEntryRequest> requests) {
        if (requests.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }
        if (!requests.get(0).getUserId().toString().equalsIgnoreCase(xUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied: User ID mismatch");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createBulkEntries(requests));
```

`TransactionEntryService.createBulkEntries` (around line 102) then calls `createEntry` for every item, and each item is stored with its own `userId`. The interceptor only checks the query string and the path, not the JSON body.

A signed-in user posts one row for themselves and further rows for someone else. Those rows land in the other account. The UI's batch-import confirm path uses this endpoint.

**Fix:** Reject the whole request unless every item's `userId` equals `X-User-Id`. Ignore body `userId` and stamp the authenticated id in the service.

### 4. Bill and receipt uploads are not bound to the caller

`POST /api/bill/process/{userId}`, `/api/bill/batch-process/{userId}`, and `/api/receipt/upload/{userId}` require a JWT, then use the path id as the owner. `UserIdValidationFilter` only checks that a query `userId` is a UUID. It does not compare path or query ids to the token.

The OCR job and the completion callback carry that path id. `InternalController` then stores a notification for it. A signed-in user can enqueue OCR work and drop parsed statement data into another user's notification stream. Batch import opens a confirm modal from that event; the victim still has to save, so this is not silent ledger writing. It is still cross-account notification injection and a way to spend Groq and OCR capacity as someone else.

**Fix:** Take the user id from the JWT claim only. Drop `{userId}` from these paths.

## Medium

### 5. Sessions are readable by any script on the page

`frontend/js/utils/api.js` stores `pfa_token` and `pfa_refresh` in `localStorage`. The notification stream is:

```47:47:frontend/js/utils/api.js
    this.eventSource = new EventSource(`${API_BASE}/upsert/notifications/stream?token=${encodeURIComponent(token)}`);
```

`EventSource` cannot set `Authorization`, which is why the token is in the query. That value is copied into browser history, `Referer` on cross-origin loads, and nginx access logs (`$request` includes the query string).

`frontend/nginx.conf` and `frontend/index.html` set no `Content-Security-Policy`, `X-Content-Type-Options`, or `X-Frame-Options`. The page loads Chart.js, Cropper, Flatpickr, and Tom Select from public CDNs, so a compromised CDN script is already inside the app origin.

The gateway also caches parsed JWT claims for five minutes (`JwtReactiveAuthenticationManager` and `UserHeaderFilter`). Expiry is not rechecked on a cache hit, so a token can be accepted for a few minutes after it expires. Logout still works for that token, because the blacklist is checked before the cache.

**Fix:** Keep the refresh token in an `HttpOnly`, `Secure`, `SameSite` cookie, and keep access tokens in memory. For SSE, use a short-lived one-purpose stream ticket instead of the access token. Add a CSP that blocks inline script and unknown hosts, plus `nosniff` and `frame-ancestors 'none'`. Vendor the CDN scripts or pin them with SRI. Do not cache a successful JWT parse past its `exp`.

### 6. Services behind the gateway trust a header

`UserHeaderFilter` strips a client `X-User-Id` and sets it from the JWT. Command and query then treat that header as the user. They do not check the JWT themselves. `SecurityInterceptor` skips anything whose URI contains `/health` or `/actuator`, or starts with `/internal`.

`POST /internal/ocr-complete` and `/internal/batch-ocr-complete` take a `userId` from the JSON body and push a notification. OCR's `POST /ocr` and `POST /analyze` (`ocr-service/app/main.py`) have no auth. `/analyze` forwards the body to Groq with the service API key.

Nothing in `k8s/` defines a NetworkPolicy. Docker Compose publishes these ports on `127.0.0.1` only, so a remote attacker does not reach them that way. Any pod in the cluster, or anything that can hit those localhost ports, can impersonate any user.

**Fix:** NetworkPolicy so only the gateway can call command, query, and auth, and only OCR can call `/internal`. Require a shared internal credential on `/internal` and on the OCR HTTP API. Do not expose command, query, OCR, MinIO, Redis, RabbitMQ, or Postgres except to the processes that need them.

### 7. Login throttling is easy to step around

Auth routes use `ipPathKeyResolver`, which takes the first address in `X-Forwarded-For` (`RateLimiterConfig` around line 89). The frontend nginx sets that header from `$proxy_add_x_forwarded_for`, which keeps a value the client already sent and appends the real peer. The limiter therefore sees the client-chosen address.

The bucket is 200 requests/second, burst 400. There is no failed-login counter or lockout. Passwords are BCrypt, which slows guessing, but the limit does not bind a credential-stuffing run to one source.

**Fix:** Rate-limit login on the address your edge overwrites (Cloudflare `CF-Connecting-IP`, or the ingress real IP), and also on the account email. Drop the replenish rate for `/api/auth/login` and `/api/auth/register` to something a person can actually use. Ignore `X-Forwarded-For` unless it comes from a trusted proxy hop.

### 8. Account emails are readable by any member

`GET /api/auth/users/{userId}`, `GET /api/auth/users/search`, and `POST /api/auth/users/bulk` return `userId`, `username`, `email`, and `profilePicture` to any authenticated caller (`UserService`). Search is capped at 20 rows, which limits scraping speed. It does not stop looking up a person you already share a group with, or probing usernames.

**Fix:** Return email only to the account owner. Search can return username and user id. Bulk lookup should require that the caller shares a group with each id.

### 9. Password change does not kill live access tokens

`changePassword` calls `revokeAllUserTokensReactive`, which deletes refresh-token keys for that email. It does not blacklist outstanding access tokens. Default access lifetime is 15 minutes (`JWT_EXPIRATION`, 900000 ms). A stolen access token survives a password change until then.

Refresh rotation keeps a short grace record so two tabs can refresh at once. Anyone who still holds the old refresh token during that window receives the new access and refresh pair (`AuthService.fromRefreshGrace`).

**Fix:** On password change, blacklist every live access token for that user, or keep a `sessionVersion` claim and bump it. Keep the grace window only for the same client that won the rotation, not for every holder of the old token.

## Low

### 10. Gateway actuator is open

`SecurityConfig` permits `/actuator/**`. The gateway exposes `health`, `info`, and `prometheus`. Command service also exposes `metrics`. The ingress in this repo publishes the frontend, and nginx only proxies `/api/`, so the internet path does not hit `/actuator` today. The gateway process itself will serve those endpoints to anything that can open port 8080, including other pods.

**Fix:** Bind actuator to a separate port, or allow it only from the Prometheus namespace. Do not `permitAll` it on the public connector.

### 11. Insecure defaults when env is missing

`ocr-service/app/main.py` defaults MinIO to `admin` / `password123` and RabbitMQ to `amqp://guest:guest@rabbitmq:5672/`. The gateway does the same for MinIO and RabbitMQ (`application.yaml`). Redis password defaults to empty. Production manifests pull real secrets from sealed Secrets. A compose or local run that forgets the env file comes up with those defaults, and the RabbitMQ management port is published on localhost (`15672`).

**Fix:** Fail startup when `JWT_SECRET`, MinIO, RabbitMQ, and Redis credentials are unset. Remove the guest user from RabbitMQ.

### 12. Registration takes a role from the client

`RegisterRequest.role` is required and stored as sent (`AuthService.register` around line 83). `Role` currently contains only `USER`, so a caller cannot become an admin today. Adding `ADMIN` later, without also ignoring the client field, would make registration a privilege grant. Separately, `JwtReactiveAuthenticationManager` logs the raw token when the roles claim is empty (around line 77).

**Fix:** Set `USER` on the server and delete `role` from the register body. Log a token id, not the token.

## Controls that are in place

These are working and should stay.

- Passwords are BCrypt, hashed off the Netty event loop. Login errors do not say whether the email exists.
- JWTs are HMAC-signed. The secret must be at least 32 characters. The gateway strips a client-supplied `X-User-Id` and replaces it from the token.
- Logout blacklists the current access token in Redis. Refresh tokens rotate with a single-use consume (`GETDEL`).
- Query `userId` and path `userId` are checked against `X-User-Id` on command and query. Single create, update, delete, and amount patch check ownership. Subscription updates check `userId`.
- Upload filenames are stripped of path segments.
- Kubernetes Secrets are sealed. Compose publishes service ports on `127.0.0.1`, not on all interfaces. The ingress object only targets the frontend Service.

## Suggested order

1. Bind every write to the JWT user id, including bulk create and the upload paths.
2. Stop public same-origin storage. Presign reads, and allow only re-encoded images.
3. Escape attribute values with `escAttr` (or DOM APIs) everywhere a profile URL is interpolated. Reject arbitrary picture strings.
4. Move tokens out of `localStorage` and out of query strings. Add CSP and `nosniff`.
5. NetworkPolicy plus an internal credential on `/internal` and the OCR API.
6. Rate-limit login on a trusted client IP and on the email. Blacklist access tokens when the password changes.
