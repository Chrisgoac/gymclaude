# Android auth: abuse and rate-limiting notes

The native app signs in through Clerk's Android SDK (Frontend API, native flow). Scope of what
GymLog owns versus what Clerk owns:

- **Registration, sign-in attempts, code delivery and account recovery are rate limited by Clerk**,
  per instance, IP and identifier. The app adds no limiter of its own; it surfaces Clerk's
  `429` / `too_many_requests` as "demasiados intentos" (`AuthFailure.RateLimited`).
- **Bot protection:** the web uses Clerk's CAPTCHA on sign-up. Native requests cannot show it, so
  protection for the native API comes from the Clerk dashboard settings for native applications
  (allow-listed package + signing certificate, and Play Integrity/device attestation when enabled).
  Keep sign-up restrictions (e.g. email verification required, disposable-domain blocking) on in the
  dashboard: the app always verifies new accounts with an emailed code.
- **No secrets in the app:** only the *publishable* key ships (from `android/local.properties`,
  never committed). Session and device tokens are stored and refreshed by the Clerk SDK; GymLog
  never persists passwords, codes or JWTs. The password and code are dropped from memory as soon as a
  session becomes active.
- **Server side:** every sync endpoint verifies the bearer JWT with Clerk; a token from the native
  app has no extra privileges.

## Manual abuse checks (user-assisted, before release)

Run against the production instance with a throwaway address you control:

1. Request email codes for the same address repeatedly: Clerk must start returning `429`, and the app
   must show the rate-limit message without crashing or looping.
2. Enter wrong codes repeatedly: expect `form_code_incorrect`, then the verification is locked
   (`verification_failed` → "pide uno nuevo").
3. Attempt several sign-ups from one device/IP in a short window: Clerk throttles them.
4. Recovery: the app offers no password reset; recovery happens in the web flow, under Clerk's limits.
