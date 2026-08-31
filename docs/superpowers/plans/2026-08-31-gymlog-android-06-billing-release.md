# GymLog Android Billing Privacy and Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Connect monthly/annual GymLog Pro purchases to server-verified entitlements, implement compliant account deletion and privacy disclosures, harden the release build, and prepare the single public AAB for Google Play submission.

**Architecture:** Android Play Billing initiates and acknowledges user purchases but never grants authoritative Pro. The backend verifies purchase tokens with Google Play Developer API, consumes RTDN events idempotently, and exposes entitlement state to the signed-in user. Account deletion is a server-orchestrated, retry-safe workflow spanning Postgres, R2, Clerk association, and local cleanup. Release automation produces an unsigned-by-source, externally signed AAB using secrets supplied only by CI/local secure properties.

**Tech Stack:** Google Play Billing Library 9.1, Google Play Developer API/RTDN, Next.js, Drizzle/Postgres, Clerk, R2, Android Gradle release/R8, Compose UI tests, Play Console.

**Spec:** `docs/superpowers/specs/2026-08-31-gymlog-android-nativo-design.md`

## Global Constraints

- Products are exactly monthly and annual subscriptions; no trial, introductory promotion, ads, or separate Pro APK in v1.
- Only the backend grants/revokes Pro after Google verification. Client purchase state controls loading/presentation only.
- Purchase tokens, service-account credentials, keystore files/passwords, and Clerk/R2 secrets never enter git or ordinary logs.
- Free user data stays readable/exportable after expiration or cancellation.
- Account deletion is available inside Android and through a public web URL; active subscription management is explained before confirmation.
- External Play Console production submission requires explicit owner approval after all gates pass.

---

### Task 1: Implement Google purchase verification and entitlement projection

**Files:**
- Modify: `package.json`
- Modify: `package-lock.json`
- Create: `lib/billing/google-play.ts`
- Create: `lib/billing/entitlements.ts`
- Create: `lib/billing/entitlements.test.ts`
- Create: `app/api/v1/billing/verify/route.ts`
- Create: `app/api/v1/billing/verify/route.test.ts`
- Create: `app/api/v1/billing/entitlement/route.ts`
- Create: `app/api/v1/billing/entitlement/route.test.ts`

**Interfaces:**

```ts
interface VerifyPurchaseRequest {
  productId: "gymlog_pro_monthly" | "gymlog_pro_annual";
  purchaseToken: string;
}
interface EntitlementResponse {
  tier: "FREE" | "PRO";
  productId: string | null;
  state: "NONE" | "ACTIVE" | "GRACE" | "PAUSED" | "CANCELED" | "EXPIRED";
  validUntil: number | null;
}
```

- [ ] Write fake-Google tests for valid product/package/account binding, replayed token, wrong package/product, canceled/expired/grace/paused states, Google outage, cross-user token reuse, and no secret logging.
- [ ] Run tests; confirm failure.
- [ ] Run `npm install googleapis`, implement a narrow Google client adapter, persist token hash rather than raw token where API refresh permits, and project verified state into `subscription_entitlements`.
- [ ] Implement authenticated verify/get routes with stable errors and server time.
- [ ] Run focused tests, lint, and build.
- [ ] Commit: `git add package.json package-lock.json lib/billing app/api/v1/billing && git commit -m "feat(billing): verify Google Play subscriptions"`.

### Task 2: Consume RTDN idempotently

**Files:**
- Create: `lib/billing/rtdn.ts`
- Create: `lib/billing/rtdn.test.ts`
- Create: `app/api/webhooks/google-play/route.ts`
- Create: `app/api/webhooks/google-play/route.test.ts`
- Modify: `db/schema.ts`
- Create: `drizzle/0004_billing_events.sql`

**Interfaces:** Store RTDN message/event identity with unique constraint, verify Pub/Sub authentication/audience, decode notification, refetch authoritative subscription state from Google, and update entitlement transactionally. Duplicate/out-of-order events are safe because Google state, not event order, wins.

- [ ] Test forged webhook, malformed payload, duplicate event, out-of-order renewal/cancel/refund/expire, unknown token, transient Google error retry, and atomic event/entitlement write.
- [ ] Run tests; confirm failure.
- [ ] Add additive event table/migration and authenticated webhook handler outside `/api/v1`.
- [ ] Implement retry-compatible status responses and sanitized structured logging.
- [ ] Run migration rehearsal and tests.
- [ ] Commit: `git add lib/billing app/api/webhooks db drizzle && git commit -m "feat(billing): process Play subscription notifications"`.

### Task 3: Integrate Play Billing in Android

**Files:**
- Create: `android/core/domain/src/main/kotlin/com/chrisgoac/gymlog/domain/billing/BillingRepository.kt`
- Create: `android/core/data/src/main/kotlin/com/chrisgoac/gymlog/data/billing/PlayBillingRepository.kt`
- Create: `android/core/network/src/main/kotlin/com/chrisgoac/gymlog/network/BillingApi.kt`
- Create: `android/core/data/src/test/kotlin/com/chrisgoac/gymlog/data/billing/PlayBillingRepositoryTest.kt`
- Modify: `android/gradle/libs.versions.toml`

**Interfaces:** Repository exposes `StateFlow<Entitlement>`, product details, `launchPurchase(activity, productId)`, `restore()`, and `managementUri()`. Purchase flow sends token to backend, waits for verified entitlement, then acknowledges when required; pending purchases never grant Pro.

- [ ] Test connection/reconnect, monthly/annual details, pending/canceled/error/success, already-owned, restore, duplicate callbacks, verification failure, acknowledgment, expiration refresh, and offline cached presentation.
- [ ] Run tests; confirm failure.
- [ ] Add Billing 9.1 dependency and implement a testable client adapter with lifecycle-safe connection.
- [ ] Refresh backend entitlement on startup/foreground/purchase/restore; keep AI calls protected by server regardless.
- [ ] Run unit tests and Play Billing test-environment scenarios.
- [ ] Commit: `git add android/core android/gradle/libs.versions.toml && git commit -m "feat(android): integrate verified Play Billing"`.

### Task 4: Build paywall and subscription settings without blocking data

**Files:**
- Create: `android/feature/settings/src/main/kotlin/com/chrisgoac/gymlog/settings/ProScreen.kt`
- Create: `android/feature/settings/src/main/kotlin/com/chrisgoac/gymlog/settings/SubscriptionCard.kt`
- Create: `android/feature/settings/src/test/kotlin/com/chrisgoac/gymlog/settings/ProViewModelTest.kt`
- Modify: `android/feature/coach/src/main/kotlin/com/chrisgoac/gymlog/coach/CoachScreen.kt`
- Modify: `android/feature/routines/src/main/kotlin/com/chrisgoac/gymlog/routines/MesocycleGeneratorScreen.kt`
- Modify: `android/feature/progress/src/main/kotlin/com/chrisgoac/gymlog/progress/IntelligentReportScreen.kt`

**Interfaces:** Display Play-provided localized price/period, monthly/annual choice, restore, state, and manage link. Free gates only the three AI entry points and always leaves underlying routines/history/progress visible.

- [ ] Test Free/Pro/grace/pending/expired/offline UI, purchase/restore disabled states, localized product details, accessibility, and all free screens remaining navigable after expiry.
- [ ] Run tests; confirm failure.
- [ ] Implement Hierro-native paywall, subscription card, manage link, and entitlement-aware AI navigation.
- [ ] Add plain-language recurring billing disclosure and no-trial wording before purchase.
- [ ] Run Compose and Play test-account checks.
- [ ] Commit: `git add android/feature && git commit -m "feat(android): add GymLog Pro purchase UI"`.

### Task 5: Implement retry-safe complete account deletion

**Files:**
- Create: `lib/account/deletion.ts`
- Create: `lib/account/deletion.test.ts`
- Create: `app/api/v1/account/route.ts`
- Create: `app/api/v1/account/route.test.ts`
- Create: `app/delete-account/page.tsx`
- Create: `app/delete-account/page.test.tsx`
- Modify: `db/schema.ts`
- Create: `drizzle/0005_account_deletion_jobs.sql`

**Interfaces:** `DELETE /api/v1/account` requires recent auth/confirmation token, creates or resumes a deletion job, tombstones/locks access immediately, deletes user-scoped R2 objects and Postgres data, removes entitlement association/reports, then deletes Clerk account through server credentials. Job steps are idempotent and auditable without retaining user content.

- [ ] Test confirmation/recent-auth requirement, active-subscription warning metadata, duplicate request, failure/resume at each subsystem, immediate access denial, object enumeration scope, eventual row deletion, and cross-user safety.
- [ ] Run tests; confirm failure.
- [ ] Add deletion job schema/migration and orchestrator with exact table/object scopes.
- [ ] Implement API route and public responsive web request page using the current Next.js conventions; the page must be usable outside the APK and explain subscription management separately.
- [ ] Run tests, lint, build, and a disposable-account deletion rehearsal.
- [ ] Commit: `git add lib/account app/api/v1/account app/delete-account db drizzle && git commit -m "feat(account): add complete account deletion"`.

### Task 6: Complete Android privacy, sign-out, and deletion flows

**Files:**
- Create: `android/feature/settings/src/main/kotlin/com/chrisgoac/gymlog/settings/AccountScreen.kt`
- Create: `android/feature/settings/src/main/kotlin/com/chrisgoac/gymlog/settings/PrivacyScreen.kt`
- Create: `android/feature/settings/src/test/kotlin/com/chrisgoac/gymlog/settings/AccountViewModelTest.kt`
- Modify: `android/app/src/main/kotlin/com/chrisgoac/gymlog/RootStateMachine.kt`

**Interfaces:** Sign-out with pending changes offers `Sync now`, `Stay signed in`, or explicit `Discard local pending changes`; successful sign-out deletes exact user DB/cache/credentials. Delete account shows subscription warning/manage link, requires typed/reauth confirmation, calls server, then removes all local state.

- [ ] Test every pending-sync branch, offline restriction, session expiry, exact-user cleanup, active subscription warning, deletion success/retry, and no return to product after deletion starts.
- [ ] Run tests; confirm failure.
- [ ] Implement account/privacy screens, Clerk reauthentication, sync resolution, server deletion call, and local cleanup.
- [ ] Link privacy policy, account-deletion URL, support, and Play subscription management.
- [ ] Run Compose and two-account isolation tests.
- [ ] Commit: `git add android/feature/settings android/app && git commit -m "feat(android): add privacy and account controls"`.

### Task 7: Create publication disclosures and store assets source

**Files:**
- Create: `docs/play/privacy-policy.md`
- Create: `docs/play/data-safety.md`
- Create: `docs/play/ai-content.md`
- Create: `docs/play/store-listing-es.md`
- Create: `docs/play/store-listing-en.md`
- Create: `docs/play/reviewer-instructions.md`
- Create: `docs/play/permissions-inventory.md`
- Create: `docs/play/asset-checklist.md`

**Interfaces:** Documentation enumerates account identifiers, workouts, body data, photos, purchase state, AI messages/reports, purpose, transit/storage protection, deletion, retention, and third-party processors. Reviewer instructions include a separate test account and exact Pro/navigation steps but no credentials in git.

- [ ] Derive manifest permission inventory from the merged release manifest and map each permission to user-facing purpose.
- [ ] Draft Spanish and English listing text without unsubstantiated health claims; state Coach limitations and Pro scope.
- [ ] Draft privacy/Data Safety/AI reporting disclosures consistent with actual code and current Play forms.
- [ ] Add asset size/content checklist for icon, feature graphic, phone/tablet screenshots, and privacy URL.
- [ ] Have owner/legal reviewer approve policy text before publishing; record approval date without embedding private identity data.
- [ ] Run unfinished-marker, link, and consistency scans and commit: `git add docs/play && git commit -m "docs(play): add publication and privacy materials"`.

### Task 8: Harden and sign the release build

**Files:**
- Modify: `android/app/build.gradle.kts`
- Create: `android/app/proguard-rules.pro`
- Create: `android/signing.properties.example`
- Modify: `.gitignore`
- Modify: `.github/workflows/android.yml`
- Create: `android/app/src/test/kotlin/com/chrisgoac/gymlog/ReleaseConfigTest.kt`

**Interfaces:** Release uses `versionCode`, semantic `versionName`, R8/resource shrinking, release API URL, no debug logging, and signing values loaded from ignored local/CI secret properties. CI builds an unsigned or securely signed artifact according to environment, never prints secret values.

- [ ] Test release package/application ID, API base HTTPS, target/min SDK, `debuggable=false`, backup rules, no cleartext traffic, and no debug menu/key strings in packaged resources.
- [ ] Run release tests; confirm failure.
- [ ] Configure R8 keep rules only where required by serialization/Clerk/Room, baseline profile if measured startup warrants it, signing property loading, and secure CI artifact retention.
- [ ] Inspect merged manifest and release APK/AAB with Android tooling for permissions, exported components, secrets, and native symbols.
- [ ] Run `./gradlew clean testReleaseUnitTest lintRelease bundleRelease` with secure signing inputs outside git.
- [ ] Commit: `git add android/app android/signing.properties.example .gitignore .github/workflows/android.yml && git commit -m "build(android): harden release bundle"`.

### Task 9: Execute full release qualification

**Files:**
- Create: `docs/verification/android-release-qualification.md`
- Create: `docs/play/launch-checklist.md`
- Modify: `docs/superpowers/plans/2026-08-31-gymlog-android-native-roadmap.md`

- [ ] Run all backend tests, lint, and production build; run all Android unit/lint/release/instrumented suites.
- [ ] Execute the six E2E flows from design spec section 17.4 on API 26, API 36, a Pixel, and a Samsung.
- [ ] Verify airplane-mode training/editing, two-device conflict convergence, private-photo isolation, Billing purchase/restore/revoke, AI report, and complete account deletion.
- [ ] Upload the candidate AAB only to an internal/closed Play track, run pre-launch report, and resolve every critical issue before production.
- [ ] Verify Android Vitals readiness, deobfuscation/symbol files, test credentials, countries/prices, monthly/annual products, Data Safety, content rating, privacy URL, deletion URL, and AI declaration.
- [ ] If applicable, complete and evidence the 12-testers/14-days closed-test requirement.
- [ ] Record exact AAB SHA-256, version code/name, commit SHA, test commands, device matrix, pre-launch results, and remaining non-blocking risks.
- [ ] Mark every roadmap acceptance checkbox only with linked evidence; run `git diff --check` and the global unfinished-marker scan.
- [ ] Commit: `git add docs/verification docs/play docs/superpowers/plans/2026-08-31-gymlog-android-native-roadmap.md && git commit -m "docs(play): qualify GymLog Android release"`.

### Task 10: Submit the single public release after approval

**Files:**
- Modify: `docs/play/launch-checklist.md`
- Create: `docs/verification/android-production-release.md`

- [ ] Present the qualified AAB hash, rollout configuration, listing, privacy/Data Safety answers, tester status, and known risks to the owner.
- [ ] Obtain explicit approval for the external, user-visible Play Console production submission.
- [ ] Submit the same qualified AAB to production with a conservative staged percentage; do not create a second functional APK edition.
- [ ] Record Play release ID, submission time, review status, rollout percentage, and rollback/stop-rollout procedure.
- [ ] After approval goes live, verify install, login/bootstrap, one offline workout sync, Free access, test Pro entitlement, privacy links, and deletion link from the store listing.
- [ ] Monitor Android Vitals and server error/rate/cost dashboards; stop rollout on the thresholds written in `launch-checklist.md`.
- [ ] Commit the sanitized production evidence: `git add docs/play/launch-checklist.md docs/verification/android-production-release.md && git commit -m "docs(play): record GymLog production release"`.
