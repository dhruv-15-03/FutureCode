# FutureCode

<!-- impeccable:product-schema 1 -->

## Platform

web

## Brand

**FutureCode**

**Future of Coding Based Interviews**

The user selected the **Annotated casebook** visual world: reading typography, code plates and annotation margins. Its current warm cinematic noir edition uses near-black, deep emerald actions, warm ivory/sage and oxide-red framing. A compact masthead and normal-flow bottom footer surround every workspace, including privacy. Mobile puts the same account controls inside an accessible hamburger disclosure. Avoid decorative imagery, generic SaaS marketing, bento grids, gradients/glows, fake social proof, stock personas and unnecessary icons.

## Users

Initially 100-200 students/candidates practicing code comprehension and AI-assisted engineering judgment. This is a target audience size, not a measured adoption or concurrency figure.

## Product Purpose

Inspect unfamiliar code, clarify assumptions, reproduce a defect, challenge an AI proposal, defend a patch, and propose a regression. The product trains relevant skills without claiming to reproduce an employer's actual assessment.

## Access and Community Decisions

User-confirmed launch policy:

- Guests browse problems and source.
- Google accounts are required for submissions, private records, and score tracking.
- Students can draft problems with 1-6 source files and a proposed reference solution.
- Moderator approval is required before publication.
- The initial application is free; optional AI-provider costs belong to the user's own key.

Draft, pending, published, and rejected states are explicit. Ownership, roles, revision conflicts, and source-reference limits are enforced by the backend. Published entries cannot be silently edited by an author. Community references remain distinct from curated references.

## Capabilities and Constraints

One Java 21/Spring Boot 4.0.9 application serves the browser interface and bounded APIs. Spring MVC, Spring Security/Nimbus, JDBC/HikariCP, Flyway and MySQL 8+ provide Google-linked users, hashed sessions, private records and moderated contributions. Schema version 3 adds private bookmarks/runs, discussion/votes, opt-in practice totals, append-only moderation metadata and optional payment accounting. There is no Node production server, Python microservice, ORM, password/reset infrastructure, required paid tier or persistent student-key vault.

The real server is an explicitly read-only preview until MySQL and Google OAuth are configured. No production test identities or fake saved-record success path are present. Production startup requires HTTPS origin, database, OAuth, and secret configuration.

The authored collection includes five Java drills and a five-file Java/backend incident simulation. Source exploration, evidence pins, a fixed concurrency model, seeded AI critique, written patch/test artifacts, a human-review rubric, optional BYOK coaching, and explicit exports are implemented.

Curated results are recomputed by the server. Community multiple-choice results express agreement with an author's reference; written work is ungraded. No candidate code executes on the application host. A default-disabled Judge0 CE adapter can send separately consented programs to an external sandbox, with private hash/result records and explicit runtime/sandbox limitations. Limited observed tests and AI patch advice do not establish universal semantic correctness.

OpenAI and Gemini support scoped consented questions/coaching and unverified patch advice, sharing a bounded request gate. Discussion/voting requires an attempt; authors cannot self-vote. Private topic progress distinguishes curated and author agreement. The public board is off by default and aggregates only each distinct entry's best retained curated score. Optional hosted Razorpay support checkout is disabled until explicitly configured; practice stays free and only verified matching webhooks change payment state.

Account records and author drafts persist only through explicit submissions/saves. Keys are scoped to transient inputs/requests, not MySQL, cookies, browser storage, or credential fields in exports. User-pasted secrets are not automatically detected.

## Evidence on Hand

Automated unit/HTTP/browser coverage exercises Google flow validation with synthetic tokens, ownership and publication controls, score boundaries, credential redaction, provider contracts, and the full student/author/moderator UI.

The development suites cover student/author/moderator journeys, private ownership, provider boundaries, payment replay/signature checks, consent, score provenance, source-safe rendering, computed contrast and responsive chrome against synthetic identities/transports. Test identities and memory storage are absent from the production JAR. Authored content has build-checked source hashes/reference versions; Java never evaluates JavaScript or uploaded source.

The 25 October 2026 release passed 116 Java tests, 11 content/authored-Java checks and 74 Chromium journeys; one dedicated-MySQL test is skipped. The real `FutureTool` schema is at version 3 with 13 owned tables. Its extended rollback-only probe verifies community records, unique-entry aggregation, private run storage and synthetic payment-ledger persistence without making external execution/payment calls or retaining probe rows.

The authored original and seeded proposal reproduce a controlled lost update; the atomic reference passes when compiled with JDK 25.0.2 targeting Java 17. The separate Java 13-compatible external harness is checked against repository-owned original/seeded/atomic/serialized variants. A serialized read path produces a scheduling note rather than a false failure. No candidate code is compiled by development tests.

Live MySQL provisioning, certificate/hostname-verified TLS, migrations and rollback-only repository checks passed for `FutureTool`, without retained probe rows. The dedicated `_test` suite remains opt-in. Cloud Build produced the non-root Java image from committed source; public Cloud Run reading and protection probes passed on 26 October 2026. The hosting increment passed 117 Java and 33 operations tests. A live Google redirect attempt confirmed `redirect_uri_mismatch`; the full identity lifecycle and real AI/runner/payment calls remain unverified. No human learning, adoption or hiring outcomes are claimed.

## Resource Principles

- One bounded JVM, a four-connection Hikari MySQL pool, 16 servlet workers, bounded caches/body sizes, and no per-keystroke writes.
- Configurable account/content defaults: 200 accounts, 500 problems, 10 contributions and 20 attempts per account. Community and integration storage also have explicit bounded capacities.
- Plain browser modules, system fonts, no decorative image or icon-library payload.
- Provider calls require authenticated identity and explicit consent, with fixed destinations, bounded concurrency/output, and visible errors.
- No distributed-infrastructure claims for the single-instance in-memory controls.

**User-directed hosting policy, updated 25 October 2026:** the user approved Cloud Run layered protection while acknowledging possible overage, then explicitly selected free allowances first and estimated net-spend alerts at INR 1/2/3/4/5, with the project protector triggered at INR 5. This supersedes the earlier absolute-INR-5 hold. INR 5 is a shutdown target, not a guaranteed invoice ceiling. Limit deployment and shutdown strictly to the approved GCP project and FutureCode repository; never disable the billing account or another project.

Cloud Run serves the combined browser/Java service at https://futurecode-6lhmkv4hnq-uc.a.run.app in the dedicated `futurecode-512719` project. Existing external MySQL is unchanged. Request billing, scale-to-zero, one-instance maxima and 1-vCPU/512-MiB profiles were read back. A separate private Python guardian handles scoped budget messages and hourly estimated 50% free-usage warnings. The real budget publisher, authenticated delivery, quota/state access and unlink permission were checked; no blocking billing lien exists. INR 1-5 thresholds are configured, but notification receipt and emergency shutdown were not forced. A native INR 5 gross cap is intentionally excluded to preserve the chosen net-after-credits policy.

A Windows/JDK 25.0.2 preview-only release run with a 160 MiB heap recorded 200 successful requests at concurrency 10, 123 ms observed p95, about 188 MiB peak sampled working set and 65,642 gzip bytes across browser assets. Database, identity-provider and provider traffic were excluded. A separate warm-cache, real database-backed public-read check recorded 200 successes and 74 ms observed p95. Desktop/mobile screenshots showed no horizontal overflow or page errors. These are bounded local observations, not production capacity or user-outcome claims. A 512 MiB instance is a validation target, not a free-hosting or 200-concurrent-user guarantee.

## Accessibility & Inclusion

Semantic reading order, native controls, visible focus, announced loading/error/result states, keyboard navigation, mobile layouts, and text-safe source rendering remain requirements. Color never substitutes for a reference or publication label.

## Open Validation

MySQL, cloud public reading, full-startup readiness, production assets and cost-protection configuration are verified. Production Google sign-in is blocked until the published callback is registered through the privately authenticated console. Complete that lifecycle, separately consented AI/runner and merchant test-mode checks, operator policy and three candidate observations. Reference matches are learning evidence, not an interview-readiness certificate or a salary guarantee.
