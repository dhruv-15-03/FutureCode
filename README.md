# FutureCode

**Future of Coding Based Interviews**

A free-beta code-comprehension application: browse a problemset, investigate a small Java service, question an AI proposal, submit reasoning, and keep a private practice record. Students can propose problems with source and reference solutions; moderators review them before publication. Private bookmarks, topic progress, attempt-gated discussion/voting, an opt-in practice board and a moderation audit trail support the practice loop.

The user-selected **Annotated casebook** interface uses warm near-black reading surfaces, deep emerald actions, oxide-red editorial framing, source plates and provenance notes. The masthead targets 80px on desktop and 64px on mobile; the normal-flow footer targets 128px/208px and can grow for enlarged content rather than clipping it. Mobile uses an accessible hamburger and the same account controls inside its navigation. The privacy document shares the shell. Palette roles live in `DESIGN.md`; browser checks cover actual text/control contrast, route geometry, narrow layouts and navigation.

## Current verification boundary

**Current hosting status, 26 October 2026: project billing is disabled and deployment is on hold.** The owner disabled billing after reporting an actual charge. Keep it disabled; do not deploy, rebuild, reactivate billing or remove cloud resources without a fresh explicit decision. `ops\gcp\project.json` sets `cloud_changes_enabled=false`; deployment entry points and their low-level CLI/API mutation paths fail closed. The former `--accept-estimated-target-risk` flag does not override this hold. Local development and read-only inspection can continue.

The earlier deployment used an estimated monthly net-spend INR 5 shutdown target, with INR 1/2/3/4/5 alerts after credits. That mechanism is not a guaranteed invoice ceiling and did not prevent all supporting-product charges. Historical deployment/protection checks passed, but the public service and its project-hosted guardian are now unavailable. Production Google sign-in also still needs its exact callback registered. Never close the billing account or modify another project.

The post-incident code now requires an explicit, recent cost review before any bootstrap or application work, plus fresh project-bound protector proof before application builds. Missing telemetry is unknown, never zero. These are local/source corrections, not a cloud redeployment or proof that the outstanding registry and invoice issues have been resolved.

The user subsequently selected the dedicated **`futurecode-512719`** project. The deployment manifest and runtime guardian target only that project. Do not deploy or configure shutdown in the formerly discussed project.

The backend is **Java 21 + Spring Boot 4.0.9**, with Spring MVC, Spring Security/Nimbus ID-token verification, JDBC/HikariCP, Flyway, and MySQL 8+. The application remains Java, without a Node server, ORM or AI-key vault. The separate Python cost guardian under `ops\cost_guard` is infrastructure automation, not an application or AI backend.

MySQL persistence, Google sign-in, ownership controls and browser workflows are implemented. **The isolated `FutureTool` schema is verified at version 3 with 13 tables, certificate/hostname-verified TLS and rollback-only checks covering core, community and integration storage.** No probe records were retained and the saved credentials file was unchanged. The separate opt-in test remains skipped until a dedicated `_test` database is supplied. Google/AI/runner/payment transports use synthetic tests; independent real OAuth and live-provider lifecycles remain unverified.

Without configuration, the real server runs a declared **read-only preview**. It does not manufacture accounts or pretend a draft, attempt, or score was saved. Production configuration fails closed rather than launching that preview publicly.

## Run the preview

Requires **JDK 21+** to build, or Java 21+ to run an already-built JAR. The Maven wrapper downloads pinned Maven 3.9.11 and dependencies on its first build. Node is not required to build or run the backend.

```powershell
.\mvnw.cmd -B -q package
java -Xms32m -Xmx160m -Xss512k -jar .\target\futurecode.jar
```

Open **http://127.0.0.1:4317**. Use the configured origin exactly, including `127.0.0.1` rather than `localhost`, when testing Google redirects.

The preview permits public reading. Account submissions become available after the database and Google configuration below are complete.

## Connect MySQL

Use MySQL 8+ and an application-specific schema/user. All application tables are namespaced `fc_`; the migration runner never drops a database or rewrites another application's generic `users` or `sessions` tables. Back up any existing FutureCode schema before applying later migrations.

```powershell
# Only if .env does not already exist:
Copy-Item .env.example .env
```

Set these values in the local `.env` file or your host's secret environment, **not in chat or source control**. The file uses Spring's Java-properties syntax: plain values, no quotes or `export` prefix. Windows certificate paths need escaped backslashes, for example `MYSQL_CA_FILE=C:\\certs\\provider-ca.pem`. Changes take effect after restart, not while typing.

| Setting | Purpose |
| --- | --- |
| `DATABASE_URL` | `mysql://USER:URL_ENCODED_PASSWORD@HOST:PORT/SCHEMA`; query parameters are intentionally not accepted. |
| `MYSQL_TLS` | `required` for remote databases and production. `disabled` is allowed only for local, non-production MySQL. |
| `MYSQL_CA_FILE` | Optional CA certificate file when your database provider requires one. Certificate verification stays enabled. |
| `MYSQL_CONNECTION_LIMIT` | Defaults to four connections; bounded to 1-8. |
| `DATABASE_SCHEMA` | Defaults to the isolated `FutureTool` schema and overrides the URL's database path without rewriting saved credentials. Set it explicitly empty to retain an existing URL-selected schema. |

Special characters in the URL's user/password must be percent-encoded. Use the provider's CA rather than disabling certificate verification.

The only exception to the no-query URL policy above is a recognized TLS-enforcing option: `ssl-mode=REQUIRED`, `sslMode=VERIFY_IDENTITY`, or `ssl-mode=VERIFY_CA`. All are enforced as **certificate and hostname verification** by the application; disabled/preferred TLS and unrelated driver options remain rejected.

### Provider CA certificate

If the provider uses its own CA, download its **public X.509 CA certificate** from the provider's authenticated dashboard. Save the complete `-----BEGIN CERTIFICATE-----` through `-----END CERTIFICATE-----` block as `certs\mysql-ca.pem`. Never use a private key or paste a multiline certificate into `.env`.

```properties
MYSQL_CA_FILE=certs\\mysql-ca.pem
```

That value is a path relative to the application working directory, with Java-properties backslash escaping. PEM files are ignored by Git/Docker, outside the static-asset allowlist, and not included in the executable JAR. Mount the CA file separately when deploying a container. An untrusted chain or hostname mismatch is a failure, not a reason to disable TLS verification.

### Create and verify the isolated schema

On the trusted host, with the saved `.env`:

```powershell
java -jar .\target\futurecode.jar --futurecode.command=provision --futurecode.database-schema=FutureTool
```

This explicit command requires permission to create `FutureTool` if it does not already exist. It connects without selecting or modifying the URL's former schema, creates only the named schema, applies owned Flyway migrations, and verifies repository access with a rollback-only synthetic transaction. The probe covers sessions, publication, private attempts/bookmarks, negative and changed votes, discussion revisions/moderation, distinct-entry board aggregation, run-record storage, payment idempotency/event replay, deletion and de-identification. Synthetic run/payment records exercise storage only: no program, payment or provider request is made and no probe records are retained.

Provisioning disables framework/driver console logs and emits only a fixed-shape sanitized status report. It does not display the URL, hostname, username, passwords, OAuth credentials, or raw driver exception messages. Certificate/configuration failures remain failures. Google configuration flags validate presence/format only; a real interactive Google sign-in is still required.

Run migrations after setting the database connection:

```powershell
java -jar .\target\futurecode.jar --futurecode.command=migrate
```

Google credentials are not required for the migration command. Flyway uses checksummed SQL migrations under `src\main\resources\db\migration`, with destructive cleaning disabled. A dedicated advisory-lock connection coordinates with the former migration runner; this CLI operation uses one connection in addition to the application pool, including when that pool is limited to one. MySQL DDL can implicitly commit: a failed migration is **not** transactionally rolled back. Inspect/repair its state before retrying; never edit an applied migration. Add a new Flyway migration for later schema changes and update the release's expected schema version.

Schema version **3** has 13 owned tables: the five core tables plus Flyway history, four community tables (`fc_bookmarks`, `fc_discussions`, `fc_problem_votes`, `fc_moderation_audit`) and three integration tables (`fc_runs`, `fc_payment_links`, `fc_payment_events`). Runtime startup verifies the version, checksums, owned columns, unique/idempotency keys, deletion/accounting foreign keys and quota-lock row; it does not silently run migrations.

If an existing Node-edition `fc_schema_migrations` table is found, adoption is permitted only for its single known `001_core` migration with the exact normalized SHA-256 checksum and expected owned schema structure. Only then does Flyway baseline version 1 without rerunning the core DDL or deleting student data. Unknown or modified schemas are refused. This compatibility path still needs a real-database verification; no existing student database was supplied for migration.

## Configure Google sign-in

Open [Google Auth Platform](https://console.cloud.google.com/auth/clients), select/create your project, configure its branding and audience, then create an OAuth **Web application** client. Copy its Client ID and Client secret into `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET`. This is not a service account, Firebase password flow, Gemini API key, or client-side token-decoding shortcut.

For local development:

```text
App origin:   http://127.0.0.1:4317
Redirect URI: http://127.0.0.1:4317/auth/google/callback
```

Set `APP_ORIGIN`, `GOOGLE_CLIENT_ID`, and `GOOGLE_CLIENT_SECRET`. Generate a random `SESSION_SECRET` locally:

```powershell
$bytes = New-Object byte[] 32
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($bytes)
$rng.Dispose()
[BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
```

Copy the generated 64-character value into `SESSION_SECRET`. Configure the Google fields and session secret together, then restart the Java JAR. Do not commit `.env`, certificate keys, or the generated value. Rotating the session secret invalidates existing app sessions.

The sign-in flow uses authorization code + PKCE, a signed browser-bound state/nonce cookie, the fixed Google token/certificate endpoints, and Spring Security's Nimbus verifier for ID-token signature, issuer, audience, and lifetime. The app also checks nonce, verified email, issued-at time, and authorized party when present. Identity is linked by Google's stable subject, never by a client-supplied email or role. Google access/refresh tokens are not saved.

Only `openid`, `email`, and `profile` are requested. Google's [audience documentation](https://support.google.com/cloud/answer/15549945?hl=en) describes the basic-identity exception to its normal 100-test-user restriction; that is not a promise that every school Workspace account can authorize the app. Organization policies can block it. Complete the real project's branding, audience, homepage, and privacy-policy settings before launch. See [Google's production-readiness overview](https://developers.google.com/identity/protocols/oauth2/production-readiness/overview).

The public technical data notice is **`/privacy`**. The operator must provide real support/contact details and hosting/backup/retention disclosures appropriate to the deployed service; the software does not claim legal compliance on the operator's behalf.

### Set up the moderator

Sign in once, open **Your account**, and copy your account ID. On the trusted host with database access:

```powershell
java -jar .\target\futurecode.jar --futurecode.command=admin --futurecode.admin-user-id=PASTE-YOUR-ACCOUNT-ID
```

Refresh the session. **Review desk** appears for that account. The first user is not automatically an administrator; roles cannot be requested through an API payload, email address, or Google profile claim.

## Access and publication model

| Visitor | Access |
| --- | --- |
| Guest | Browse the catalog, published community statements/source, and the curated reading copy. |
| Signed-in student | Submit attempts, bookmark entries, review private topic progress, opt into the public board, contribute, and discuss/vote after attempting an entry. |
| Moderator | Review/publish/remove contributions, moderate discussion and read append-only audit metadata. Moderators do not gain access to other students' private attempts or runs. |

Contributions follow **draft -> pending -> published/rejected**. Pending reviews must be withdrawn before editing. Published content cannot be silently changed by its author. Revision checks reject stale edits/reviews rather than overwriting them.

A complete contribution contains a bounded statement, 1-6 plain-text files, a multiple-choice or written response format, a reference explanation, optional code solution, verification suggestion, exact source citations, and a permission declaration. A matching quote proves its location, not its reasoning. Approval does not mean execution or independent rights verification.

The normal public problem API omits the proposed answer, reference solution, private review note, email, and Google identifier. An authenticated student can reveal the reference after submitting an attempt. Private drafts and full author copies are owner-scoped; review copies are moderator-only.

## Practice and score boundaries

The collection starts with five authored Java drills and **The missing requests**, a five-file Java/backend case targeting 20-30 minutes. That duration is guidance, not a measured learning-outcome claim.

The case runs **brief -> source exploration/evidence -> controlled lost-update schedule -> AI proposal critique -> proposed patch/test -> debrief**. Its seeded proposal is labeled as authored practice, not a live model reply. The narrow operation model is not an arbitrary Java runner.

The development suite compiles only repository-owned fixture variants: the Java 17 original/seeded/reference case and the Java 13-compatible external harness with original, seeded, atomic and serialized repairs. Original and seeded variants reproduce the lost update; the repairs pass. A serialized read path produces an explicit harness note rather than a false semantic failure. Candidate-written code never reaches the development runner or application host.

| Result | What it means |
| --- | --- |
| Curated drill | The server compares the selected option with an authored reference. |
| Curated case | The server recomputes four narrow checks from the submitted schedule, selections, and bounded artifacts. Written code and prose remain ungraded. |
| Community multiple choice | Agreement with the moderated author's proposed answer, not independently verified semantic correctness. |
| Community written response | A saved response for review; no automatic correctness score. |
| AI questions/coaching | Unverified suggestions. These never produce a trusted profile score. |
| Optional external execution | A named provider's observed compilation/runtime/output result under bounded limits, not exhaustive semantic correctness. |
| AI patch advice | Unverified review with exact source citations and optional matching owned execution evidence; no correctness score. |

There is no combined hiring-readiness rating or cheat-resistant leaderboard. The opt-in public board uses the best retained curated result per distinct entry; repeated submissions, votes, community-author agreement and AI judgments do not inflate it. References remain inspectable learning material. A 4/4 case record does not prove the patch compiles, its test passes, or the candidate is ready for a particular employer.

Submitted attempts, bookmarks and runs are private to the signed-in account. Discussion is visible to eligible signed-in readers/moderators and shows a public display name. Votes are aggregated, with at most one up/down vote per account/entry; authors cannot vote on their own contributions. Account deletion requires recent sign-in and removes owned sessions, attempts, bookmarks, votes, discussions, runs and contributions. Moderation metadata de-identifies deleted actors; payment accounting retains records with the user link removed. External copies and provider/hosting backups cannot be recalled.

## Optional BYOK

Platform use is free in this beta; **optional provider charges are the user's own**. No platform-funded AI key or persistent key vault is installed.

Choose OpenAI `gpt-4o-mini` or Google Gemini `gemini-3.8-flash` in the scoped control. Create a key at [OpenAI API keys](https://platform.openai.com/api-keys) or [Google AI Studio](https://aistudio.google.com/apikey), review that account's billing/data policies, and enter it only after explicit consent. The server needs no global AI key. A consumer chat subscription is not a substitute for API access. These fixed model integrations have synthetic contract coverage, not verified live-provider access.

- Private-snippet generation sends the consented snippet and instructions once to the selected fixed provider endpoint, producing three source-specific evidence rounds.
- Case coaching sends the fixed curated case and explicit successful conversation history plus the new prompt. Other written artifacts and private-snippet content are not included automatically.
- Each key has a scoped password input and transient request lifetime. Keys are not saved to MySQL, cookies, browser storage, URLs, application logs, or credential fields in exports.
- Forget, key changes, consent withdrawal, navigation/reload, and account changes cancel relevant browser requests and clear credentials as documented by the control. The coach key is also cleared when leaving its mode and at debrief.
- Already-sent data/charges cannot be recalled; upstream cancellation and secure erasure of immutable browser/JVM strings are not guaranteed. This proxy sees plaintext keys while forwarding, so it is not zero-knowledge.
- No automatic paid retry or fabricated model response is used. Refusal, quota, timeout, malformed JSON, invalid schema, and fabricated citations are explicit errors.

Four-digit PIN encryption is deliberately absent: only 10,000 candidates exist, and salt does not add PIN entropy.

No automatic secret scanner is claimed. Secrets a user pastes into source, answers, or chat can still be stored or exported as that text; permission notices and moderator review do not make that safe.

## Optional external execution and patch advice

`EXECUTION_ENABLED=false` is the default. Enabling it allows separately consented requests to **Judge0 CE on RapidAPI**, using each student's transient RapidAPI key. No compiler, shell or dependency installation runs on the application host. The fixed runtime choices are **OpenJDK 13, Python 3.8 and Node.js 12**; these legacy runtimes are not the full Java 17 case. Verify the provider's current availability, isolation, plan and data policy before enabling it.

Single-file runs accept explicit input and optional expected output. The counter task packages the repository-owned Java 13-compatible harness/controller and the proposed `RequestCounter.java` as controlled files. Provider constraints are 3 CPU seconds, 8 wall seconds, 256000 KiB memory, 40 processes/threads, 512 KiB file output and networking disabled. Limits are requested from the external provider, not a certification of its sandbox. Time-limited concurrency schedules and user-supplied patches are not a secure anti-cheat oracle.

Runs are submitted once and polled explicitly, never automatically retried. Private records retain a source hash, internal job token and bounded output, not source text or the key field. Printed output may still contain sensitive text. Keep at most 20 records and one unresolved job per account; submission is limited to six/hour. A network failure after provider acceptance is explicitly `unconfirmed`; deleting a record does not stop remote work or undo charges.

Separate AI patch advice can attach only an owned finished counter run with the exact patch hash. Both server and browser validate source citations. Advice remains **`ai-advisory-unverified`** and never awards a score. Passing the limited harness cannot establish universal semantic correctness.

## Optional support checkout

`PAYMENTS_ENABLED=false` and `SUPPORT_AMOUNT_PAISE=0` are the defaults. No payment tier is required for practice. To offer optional support, the operator must separately approve an INR amount and supply its own Razorpay key ID, key secret and 32-256-character webhook secret through secret configuration. Use test mode first; merchant onboarding and fees are the operator's responsibility. No credentials have been supplied or live checkout verified here.

The application creates hosted Payment Links with server-owned amount/currency and an account/request idempotency key. It does not load payment scripts into the source/BYOK workspace or store card details. Register **`POST /api/payments/webhook`** for `payment_link.paid`, `payment_link.expired` and `payment_link.cancelled`; signature verification uses the original bytes, deduplicates event IDs and validates the exact link/reference/amount/currency. An expiry/cancellation cannot downgrade an already paid record. A checkout return is not proof of payment. Unknown provider acceptance is `unconfirmed`, not an invitation to retry automatically.

Sources: [Gemini OpenAI compatibility](https://ai.google.dev/gemini-api/docs/openai), [Judge0 CE API](https://ce.judge0.com/), [Razorpay Payment Links](https://razorpay.com/docs/api/payments/payment-links/create-standard) and [signed webhooks](https://razorpay.com/docs/webhooks/validate-test).

## Small-instance resource policy

One Spring Boot JVM, one MySQL pool, and plain browser modules are intentional. There is no Node production runtime, Python microservice, Redis, queue fleet, SSR framework, webfont download, icon library, or decorative image dependency. The bounded Java AI adapter is separate from grading and persistence; adding an AI SDK later does not require rewriting the application.

Defaults are explicit, configurable beta limits rather than paid tiers:

| Resource | Default boundary |
| --- | --- |
| Registered accounts | 200; existing accounts can still sign in at capacity. |
| Community problems | 500 overall, 10 per author. |
| Saved attempts | 20 per account; users can download/delete older records. |
| Community activity | 50 bookmarks and 50 discussion posts/account; 100 posts/entry; 5,000 posts overall. |
| Moderation audit | 20,000 metadata events; explicit maintenance/capacity error rather than silent truncation. |
| External records | 20 runs/account; one unresolved job; six submissions/hour. |
| Optional payment records | Five new links/account/day; 10,000 links and 50,000 deduplicated events overall. |
| JVM/HTTP | Suggested initial heap 160 MiB, 16 servlet workers, 32 queued connection accepts, at most 256 connections, bounded headers/body sizes. Heap is not total process memory. |
| MySQL | Four Hikari connections; minimum idle zero and 30-second idle timeout; 8-second acquisition/socket/query bounds; 15-second transaction timeout; prepared-statement cache capped at 64 per connection. |
| Public catalog cache | 32 bounded metadata pages, 30 seconds, invalidated by local publication/profile/removal operations. Private responses are never shared-cached. |
| Browser assets | Cached production gzip, conditional weak ETags, and revalidation for unversioned files. HTML and account APIs remain no-store. No native Brotli dependency is added. |
| Source | Private snippets: 12,000 UTF-16 characters/400 lines. Contributions: 24 KB UTF-8 source total, 48 KB complete problem. |
| API bodies | 32 KB normally; 64 KB for contribution authoring. Saved-attempt payloads are bounded to 24 KB. |
| AI | Eight globally active requests, at most one per account; 25-second upstream deadline; bounded 48 KB response. |
| Coach | Six UI request attempts per practice, including failures/cancellations; at most 11 alternating messages, 2,000 characters each; 1,400 output tokens. |
| Question generation | 3,600 output tokens. |

Writes are explicit submissions/saves, not per-keystroke autosaves. Catalog search is debounced.  MySQL values use prepared parameters, publication uses revision checks, tokens are hashed, remote DB TLS is verified, and errors/logging do not echo source or credentials.

These choices suit an initial small beta; **200 registered students is not the same as 200 concurrent users**. No free-hosting guarantee is made. Select a host/database plan with adequate RAM, connections, storage, bandwidth, TLS, backups, and uptime behavior.

`.\scripts\smoke.ps1` starts its own Java preview with real integrations explicitly disabled, issues 200 preview/static/catalog requests at concurrency 10, checks a 100 KB gzip browser budget and 512 MiB sampled-process ceiling, then stops that exact owned JVM. It resolves the real JVM executable rather than measuring Oracle's small `javapath` launcher shim.

The release's Windows/JDK 25.0.2 preview run targeting Java 21, with `-Xms32m -Xmx160m -Xss512k`, recorded **200 successes, zero failures, 123 ms observed p95, 3.49-second startup, 65,642 bytes of gzipped browser assets and 196,857,856 bytes (about 188 MiB) peak sampled JVM working set**. It included **no MySQL, OAuth or provider load** and is not a production capacity benchmark. A 512 MiB application instance remains an initial validation target, not a free-tier or 256 MiB hosting guarantee.

A separate read-only check against the configured release recorded **200 public GETs at concurrency 10, zero failures and 74 ms observed p95**, with a warm catalog cache. One bounded desktop/mobile visual pass confirmed the shared frame and no horizontal overflow or page errors; automated checks cover contrast, 320px navigation, route geometry and retained fields across resize. Initial loading reserves the ledger area with `aria-busy`, without fake problem rows. These observations exclude real-device/network conditions, sustained authenticated load, actual OAuth and paid provider calls.

## Production preparation

Set `APP_ENV=production`, `HOST=0.0.0.0`, your platform's `PORT`, an exact HTTPS `APP_ORIGIN`, verified-TLS MySQL, Google credentials, and a random session secret. `NODE_ENV` remains a legacy environment-name fallback only; no Node server is used. Terminate TLS at the trusted hosting proxy and preserve the public Host header. Forwarded headers are not trusted for identity/origin checks. Do not expose the development loopback server through an arbitrary tunnel.

`Dockerfile` builds with Maven/Java 21 and copies only the executable JAR into a non-root Java 21 JRE image. Node, test classes and secret files are absent from the runtime image. Its default `JAVA_TOOL_OPTIONS` sets the small heap/stack above. Images were built by Cloud Build from clean committed source and deployed on 26 October 2026; a local Docker engine was not used.

- `/livez`: process liveness without database traffic.
- `/readyz`: Spring's completed-startup readiness plus configuration/database checks; intentionally 503 during startup/shutdown or in preview.
- Google callback: `/auth/google/callback`.
- Data notice: `/privacy`.

Run migrations explicitly with appropriate privileges; the runtime does not auto-create a schema. Prefer a least-privilege runtime database account. Do not run the browser fixture server in production.

The in-memory rate limits/cache assume a **single application instance**. Multi-instance deployment requires shared abuse controls/cache invalidation or an explicit revised policy; do not pretend the local limits become distributed.

### Cloud Run recommendation and free-tier limits

**Paused after a cost incident.** The technical settings below describe the earlier deployment, not permission to resume it. Any future deployment needs a new cost review and explicit owner approval; budget alerts are not a hard cap.

The Cloud Run deployment combines **the browser UI and Spring backend in one container**. MySQL remains the external database. The guardian is a second private Cloud Run service. Builds, registry storage, secrets, scheduler, Pub/Sub and monitoring are separate products even though the application has one public service.

The following were checked against original provider documentation on 25 October 2026:

| Resource | Published policy |
| --- | --- |
| Cloud Run, request-based billing | 2 million requests, 180,000 vCPU-seconds, and 360,000 GiB-seconds of RAM per month, valued using the documented Tier 1/us-central1 pricing basis. Usage is shared across projects on the billing account. |
| Idle Cloud Run service | With minimum instances set to zero, instances can stop and incoming HTTP requests trigger a cold start. The service URL is not a manually paused free-app slot. This is not an uptime guarantee. |
| Google Cloud billing lifecycle | The recurring Free Tier has no fixed end date, but requires an active billing account. An unupgraded Free Trial stops its resources after 90 days or when its credit is exhausted. Keep a Paid billing account active to avoid that trial shutdown; overages can then be charged. |
| MySQL | Keep the already configured service if its plan/limits fit. Cloud SQL's trial/credits are not a permanent free MySQL hosting plan. |
| Aiven free MySQL alternative | The published free plan has 1 CPU, 1 GB RAM, 1 GB disk, and a 76-connection limit, but can be powered off for inactivity and has no 99.99% SLA. It does not satisfy a literal never-shuts-down requirement. |

User-approved starting settings:

| Cloud Run setting | Initial value |
| --- | --- |
| Billing | Request-based |
| CPU / memory | 1 vCPU / 512 MiB, with the existing 160 MiB JVM heap as a starting point to validate on the real host |
| Minimum / service-level maximum instances | 0 / 1 |
| Request concurrency / timeout | 16 / 60 seconds |
| Container | Linux amd64; `HOST=0.0.0.0`; accept Cloud Run's injected `PORT` |
| App configuration | `APP_ENV=production`, `DATABASE_SCHEMA=FutureTool`, exact HTTPS `APP_ORIGIN` set to the chosen service URL |
| Database | Existing verified-TLS endpoint and mounted CA file; four-connection pool |
| Google OAuth | Add the exact production `APP_ORIGIN/auth/google/callback` URI to the existing web client |
| Secret handling | Use secret-manager environment/file references for credentials and the CA; never bake `.env` or certificate/key files into the image |

Use Tier 1 `us-central1` for the initial controlled deployment. No extra frontend host or custom domain is required. Build only committed public source, mount credentials through Secret Manager, run migrations from the trusted local host, and verify readiness plus real Google login after deployment.

Maximum instances is a cost-control setting, not a strict spend cap; the platform can briefly exceed it. Cold starts also reset this application's in-memory rate limits/cache. Request-based billing includes the active time of long AI requests even while waiting on the provider. Builds, image storage, secret operations, logs, and network egress have separate pricing/free allowances; in particular, Cloud Run's documented free internet-egress allowance is 1 GiB within North America, not an unrestricted worldwide allowance.

Ordinary budget alerts are notifications, not a guarantee of no charges. Google now also documents **spend cap budgets for Cloud Run**, but they deliberately pause new usage after the cap is triggered. They operate on gross estimated cost before discounts/credits, not simply the net bill after the Free Tier, and do not stop all persistent-resource or in-flight charges. A zero-value cap is not a way to consume the whole free allowance while guaranteeing uninterrupted service. Do not promise both unconditional zero spend and uninterrupted service.

The chosen policy deliberately does **not** apply a native INR 5 gross cap: that could stop free usage before the requested net-spend sequence. A single-project monthly billing budget includes credits and has five current-spend thresholds (20/40/60/80/100%). Its Pub/Sub notifications drive the authenticated cost guardian, which unlinks billing **only for the fixed target project** when estimated net cost is at least INR 5. Billing data is delayed, taxes can differ from estimates, and already-incurred costs cannot be recalled. Disabling billing also stops the guardian; re-enabling is a deliberate operator action.

Free-usage warnings use month-to-date Cloud Monitoring request counts and billable instance time. Both owned services must retain the fixed 1-vCPU/512-MiB request-based profile with startup CPU boost disabled; CPU seconds equal billable instance seconds and memory GiB-seconds are half that value. Warnings trigger at 1,000,000 requests, 90,000 CPU-seconds or 180,000 GiB-seconds. These are project workload estimates, not an authoritative billing-account free-credit balance. Free allowances can be shared with other projects; internet transfer is not treated as a universal free allowance.

The optional quota email uses a log-based alert, an hourly scheduler message and deduplicated state, not per-keystroke monitoring or a paid synthetic monitor. Cloud Monitoring announces alert-pricing changes no sooner than September 2027. The guardian warns in August 2027 and disables this optional alert by 31 August unless its pricing is reviewed and the policy updated; billing emails and the spend protector continue independently.

Quota calculations now require actual measured values and a raw source sample no more than 15 minutes old. A successful aggregate query with no points is not zero usage; absent, invalid or stale telemetry raises an explicit guardian failure. Month-to-date aggregation and sample freshness are checked separately. The automatic half-allowance warnings remain Cloud Run-only; supporting-product eligibility must be evidenced by the separate pre-write review, not inferred from low CPU or request counts.

Each valid budget notification records its bounded estimated net amount, currency, billing period, receipt time and provider publication time when supplied. Missing publication time remains unknown, and publication time is not the age of the underlying billing usage. Logging failure below the shutdown target is an explicit failure; logging failure at the target must not prevent the emergency stop. This records provider estimates, not a finalized invoice.

Deployment tooling is under `ops\gcp`; the guardian is under `ops\cost_guard`. It requires an authenticated Google CLI, a separate local Python environment with `ops\cost_guard\requirements.txt`, and Java for the private configuration exporter. Validate operations code with `python -m unittest discover -s ops\tests -v`. The exporter selects only the existing database/OAuth fields and generates a separate production session secret; it never changes the local `.env`. Cloud Build receives a clean committed Git archive, not the working folder or local secrets. The two runtime secrets are mounted through Secret Manager and inaccessible to the cost guardian.

With `FUTURECODE_APPROVED_GCP_PROJECT=futurecode-512719`, the `plan` phase is read-only; disabled billing is an explicit stop condition, not a request to reactivate it. The `protect`, `app` and live-probe `verify` phases are blocked by the current local hold even with `--accept-estimated-target-risk`. Editing the hold flag is not cost approval. If deployment is separately approved in future, protection must precede the public app. The guardian has no billing-account administrator role and can unlink only the target project; the application account can read only its two named secrets.

Any separately approved future operation also needs `--cost-review-file` pointing outside the checkout. `ops\gcp\cost-review.example.json` is deliberately unapproved and incomplete; copy it outside the repository for an actual owner review, never fill unknown values with zero. The review expires after one hour and binds project, repository, region, billing account and INR currency. It requires evidence for the itemized invoice and every supporting product, remaining eligible build minutes, account-wide registry usage and reserved image growth. The sum of current and additional estimated spend must stay below the target. This evidence file does not itself grant permission or guarantee future costs.

Bootstrap has an additional, explicit exposure approval because its guardian cannot protect its own creation. Read-only review, release CI and storage checks run before resource writes; the email/budget foundation is created before image-build infrastructure. Unknown initial registry measurements remain a blocker, not an assumed empty repository. Application work additionally requires confirmed alert inbox receipt. No cleanup or billing activation is automatic.

When live cloud operations are separately approved, `verify` sends authenticated probe messages and is therefore not a read-only command. The application phase requires a successful protection probe, not merely existing resource names. Its HTTP startup check sends the exact configured Host to `/readyz` and waits for all Spring startup runners. A listening TCP port alone is insufficient: request-based CPU throttling can otherwise interrupt migration verification. Do not replace this probe with `/livez` or a port-only check.

Application work now performs live verification instead of accepting an old `protectionVerified` flag. The resulting attestation is bound to the project, billing account, budget, guardian URL/revision and a unique probe ID, and expires after five minutes. Only logs correlated to that probe satisfy the check. A failed verification clears the prior attestation. Cached legacy timestamps cannot authorize work.

**Cost corrections are local only; no new cloud build was used to validate them.** The three original builds used `E2_MEDIUM`, outside the documented default-pool `e2-standard-2` free-build promotion. New build requests explicitly select `E2_STANDARD_2`, verified against the current public API schema and pricing. The free minutes are shared by the billing account and subject to provider changes; the machine setting alone is not a zero-cost guarantee.

Registry checks now use the actual `artifactregistry.googleapis.com/repository/size` storage gauge, not compressed Docker manifest sizes. Missing, paginated, invalid or stale measurements fail closed, as does a measured project footprint at or above 0.5 GiB. The last reported footprint was about 0.957 GiB and needs review before any future build. This is an instantaneous, potentially delayed measurement, not the account's remaining free GiB-month balance or a guarantee that the next image will fit. No images, secrets, storage objects or database data were deleted.

After a new build, the tool requires a registry source sample at least as recent as the build's completion before deployment. If reporting lags, the tool stops; it does not delete data or resubmit the build. An unresolved recorded build also prevents a duplicate submission. Image reuse hashes relevant committed build inputs rather than the entire commit, so documentation-only changes reuse a validated, project-scoped image digest.

Registry cleanup policy configuration is now dry-run only. Enabling deletion requires a separate owner decision; provisioning must not silently activate it. This source change does not modify the existing cloud policy while billing is disabled. Review that existing policy as part of the storage gate before any future billing reactivation.

The earlier rollout URL is **https://futurecode-6lhmkv4hnq-uc.a.run.app**, currently unavailable while billing is disabled. Public reading, the database, fixed profiles, private guardian, budget thresholds, the official budget publisher, authenticated probes and private-path protection passed earlier checks. Inbox receipt and an actual emergency shutdown were not independently exercised; do not interpret those historical checks as current monitoring availability.

Production Google sign-in currently returns **`redirect_uri_mismatch`**. A web OAuth client must authorize **`https://futurecode-6lhmkv4hnq-uc.a.run.app/auth/google/callback`**. Google Cloud Console requires private user sign-in before that registration can be completed; no unrelated project's OAuth settings were modified. Do not call authenticated student workflows production-verified yet.

Sources: [Cloud Run pricing](https://cloud.google.com/run/pricing), [Google Cloud Free Tier and trial lifecycle](https://docs.cloud.google.com/free/docs/free-cloud-features), [autoscaling](https://docs.cloud.google.com/run/docs/about-instance-autoscaling), [container contract](https://docs.cloud.google.com/run/docs/container-contract), [maximum instances](https://docs.cloud.google.com/run/docs/configuring/max-instances), [billing budgets](https://docs.cloud.google.com/billing/docs/how-to/budgets), [spend cap budgets](https://docs.cloud.google.com/billing/docs/how-to/budgets-spend-caps), [Cloud SQL pricing](https://cloud.google.com/sql/pricing), and [Aiven MySQL free-plan limits and inactivity policy](https://aiven.io/docs/products/mysql/concepts/mysql-free-tier).

## Verification

```powershell
.\mvnw.cmd -B -q clean package
.\scripts\smoke.ps1
```

Optional browser/content tooling requires Node.js 22+ in development only:

```powershell
npm ci
npx playwright install chromium
npm run check
npm test
npm run test:browser
```

The browser suite starts a **Java test-classpath synthetic identity/storage fixture** at port 4318. Its launcher force-disables real database/OAuth and runner/payment configuration even if `.env` contains credentials. Test identities, role headers and memory storage are not production fallbacks and are absent from the JAR. Journeys cover the student/author/moderator flow, private records, references, publication, source-safe rendering, consent/cancellation, bookmarks/progress, opt-in totals, moderation audit, default-disabled integrations and declared synthetic runner/review responses. Shared-shell checks cover contrast, keyboard focus, footer geometry, real links, mobile account controls, unsaved state across resize and stable initial loading.

Run the commands above for current test results; synthetic integration coverage is not a claim that real providers were exercised. Signed synthetic RSA tokens test signature, issuer, audience, expiration, nonce and verified-email failures without a real Google account. The dedicated-MySQL test is explicitly skipped unless its separate opt-in settings are present.

The 25 October 2026 release check passed **116 Java tests, 11 content/authored-Java tests and 74 Chromium journeys**, with one dedicated-MySQL test skipped. The actual `FutureTool` upgrade and rollback-only repository probe were verified separately without revealing credentials.

The 26 October hosting increment passed **117 Java tests and 33 operations tests**, with the dedicated-MySQL test still skipped. Live checks verified both Cloud Run services, resource profiles, budget scope/thresholds, guardian authentication/permissions, quota reads/state writes, current assets, private-path rejection and desktop/mobile navigation. These checks do not replace the still-blocked real OAuth lifecycle.

`.github\workflows\verify.yml` is non-deploying CI on standard Ubuntu runners, with a read-only GitHub token and no Google credentials. It runs operations, Java, authored-content and Chromium checks, then builds both containers only on the runner and smoke-tests the runtime without credentials. It does not upload images or call Google Cloud. The production Docker build now runs Java tests rather than skipping them. Image sizes reported by this workflow are inventory, not a remaining free-storage balance.

Any future deployment must find a successful push run of this exact workflow for the exact `main` commit in `dhruv-15-03/FutureCode`; missing, failed, unrelated or unavailable CI is a blocker. A local passing test or a successful check for an older commit is insufficient. The checked-in cloud hold remains enabled, and these changes do not configure GitHub branch-protection rules or authorize a cloud restart.

The browser's authored data/schema modules are exported once to `src\main\resources\content\curated.json`; no JavaScript or uploaded code is evaluated by the backend. If those authored modules change, run `npm run content:export`. Maven tests compare normalized source hashes and reference-version digests, so a stale browser/server copy fails the build even without Node installed on the build host.

For a real MySQL check, provision a **dedicated schema ending in `_test`**, never the live student database:

```powershell
$env:FUTURECODE_TEST_DATABASE_URL = "mysql://TEST_USER:TEST_PASSWORD@HOST/DEDICATED_test"
$env:FUTURECODE_ALLOW_TEST_WRITES = "yes"
.\mvnw.cmd -B -q "-Dtest=MySqlIntegrationTest" test
```

TLS rules still apply. This opt-in test applies the owned migrations, creates uniquely identified fixture rows, and deletes only its own fixture account/data. It never drops a database. Without these explicit settings it is skipped, not called verified.

## Launch gates still outside this build

The configured MySQL service has passed verified-TLS connection, `FutureTool` provisioning/migrations and rollback-only repository checks. Still verify the cloud deployment and its protection configuration, real Google sign-in/callback/sign-out, separately consented AI/runner calls, merchant test-mode checkout before enabling payments, operator contact/retention details and three actual candidate observations. Automated journeys are not human observations, provider certification or proven learning outcomes.

Before adding more content or paid infrastructure, check whether candidates can explain their findings and return to practice. Google/Canva-style skills are the target, not a claimed official question bank or a salary guarantee.

Public context: [Google pilot reporting](https://www.businessinsider.com/google-job-interview-software-engineers-ai-assistant-coding-2026-5), [Canva's engineering announcement](https://www.canva.dev/blog/engineering/yes-you-can-use-ai-in-our-interviews/), [Capypad](https://capypad.com/en), and [PrepLinx](https://www.preplinx.com/). Those sources support the practice category, not demand or outcome claims for FutureCode.
