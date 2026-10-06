# Accounts and Themes — Design Spec

Date: 2026-10-06
Status: Draft, awaiting review
Builds on: `2026-10-05-gym-tracker-design.md`

## 1. Purpose and context

Today the app has a single login, set through Railway variables, and all data
belongs to that one user. This change lets several people use the same app,
each with their own private data, and adds colour schemes.

**What changes for the user:**
- Create an account with email, password and an invite code.
- Reset a forgotten password through a link sent by email.
- Log out from Settings.
- Pick a theme in Settings: Blue (current), Pink, or Red & black.

**Decisions made during design:**
- Registration needs an invite code (`INVITE_CODE`); without it set, registration is closed.
- Accounts use email + password. Reset emails go out through Brevo's HTTPS API
  with a verified Gmail address as sender (no own domain).
- Data isolation uses an owner column (`user_id`) on each owned table, and every query filters on it.
- Existing data is not migrated: the upgrade starts with an empty database and
  the current user registers again.
- Not in scope: email verification at sign-up, changing the password while
  signed in, admin screens, deleting an account.

**Success criteria:**
- One account can never see, change or delete another account's data, also not
  by sending another account's ids to the API.
- A forgotten password can be reset from the phone without help.
- Logging out on a shared phone leaves nothing of the previous account behind,
  and that phone stops receiving the previous account's rest alerts.
- Switching theme is instant, survives a reload, and the app never flashes the
  wrong theme on start.

## 2. Data model

New Flyway migration `V2__accounts.sql`:

1. Delete all rows from `workout_set`, `workout_session`, `exercise`,
   `push_subscription` and `persistent_logins`.
2. Create `app_user`:
   - `id uuid primary key`
   - `email varchar(254) not null`, stored trimmed and lowercased, unique
   - `password_hash varchar(100) not null` (BCrypt)
   - `created_at timestamptz not null`
3. Add `user_id uuid not null references app_user(id) on delete cascade` to
   `exercise`, `workout_session` and `push_subscription`, each with an index.
   `workout_set` has no owner column; it belongs to its session.
4. Replace the global unique indexes with per-user ones:
   - `exercise_active_name_uq` on `(user_id, lower(name)) where not archived`
   - `workout_session_one_open_uq` on `(user_id) where ended_at is null`
5. Create `password_reset_token`:
   - `id uuid primary key`
   - `user_id uuid not null references app_user(id) on delete cascade`
   - `token_hash varchar(64) not null unique` (SHA-256 hex of the token)
   - `created_at timestamptz not null`, `expires_at timestamptz not null`, `used_at timestamptz`

`persistent_logins.username` holds the account's email.

## 3. Backend

### Accounts and login (`account` package, new)

- `AppUser` entity and `AppUserRepository` (`findByEmail`).
- `AppUserDetailsService` loads accounts from `app_user`. The principal name is
  the email. Login normalises the email (trim + lowercase) before lookup.
- `CurrentUser` gives services the signed-in account's id. Every service method
  that reads or writes owned data takes that id.
- The in-memory single user, `APP_USERNAME` and `APP_PASSWORD` are removed.
- The JSON form login stays: `POST /login` with `username` (the email) and
  `password`; 200 on success, 401 `{"message":"Wrong email or password"}`.
- Remember-me stays persistent-token based, always on, 365 days.
- `GET /api/me` returns `{"email": "..."}`.

### Data isolation

- Repositories look up owned rows by `id` **and** `user_id`.
- Idempotent `PUT`s with phone-generated ids: if the id exists but belongs to
  another account, respond 404 and change nothing. Creating a set checks that
  its session and exercise belong to the caller (404 otherwise).
- Stats, records, "last time" and weekly overviews only read the caller's sets.
- Push subscriptions are stored per account. If an endpoint is re-registered
  by another account (shared phone), it moves to that account.
- `RestTimerService` keeps one timer (with its own generation guard) per
  account, and sends the alert only to that account's subscriptions.

### Registration and password reset (`account` package)

`POST /api/auth/register` `{email, password, inviteCode}`
- `INVITE_CODE` blank → 403 `Registration is closed`.
- Invite code compared with `MessageDigest.isEqual`; wrong → 403 `Wrong invite code`.
- Email must match a simple `x@y.z` shape and be at most 254 chars → else 400.
- Password at least 8 and at most 72 bytes (BCrypt limit) → else 400.
- Email already registered → 409 `An account with this email already exists`.
- Success → 201, and the caller is signed in (session + remember-me cookie),
  exactly like after `POST /login`.

`POST /api/auth/forgot` `{email}`
- Always 200 with the same body: `{"message":"If an account exists for this email, we've sent a reset link."}`
- When the account exists and fewer than 3 tokens were created for it in the
  last hour: create a 32-byte random token (base64url), store its SHA-256 hash
  with a 1-hour expiry, and email the link `<APP_BASE_URL>/#/reset?token=<token>`.
- The link is built only from `APP_BASE_URL`, never from request headers.
- Mail failures are logged (without the token) and do not change the response.

`POST /api/auth/reset` `{token, password}`
- Token unknown, used or expired → 400 `This link has expired. Request a new one.`
- Same password rules as registration.
- Success → 200 `{"email": "..."}`: update the password hash, mark the token used, mark the
  account's other open tokens used, delete the account's `persistent_logins`
  rows (signs out all devices). The caller is not signed in automatically.

`/api/auth/**` and `/login` are reachable without being signed in and keep CSRF protection.

### Email (`mail` package, new)

- `MailSender` interface: `send(String to, String subject, String text)`.
- `BrevoMailSender` posts to `https://api.brevo.com/v3/smtp/email` with header
  `api-key: <BREVO_API_KEY>`, sender `MAIL_FROM`, 10-second timeout, using the
  JDK `HttpClient`. Base URL configurable so tests can point it at a local server.
- When `BREVO_API_KEY` or `MAIL_FROM` is blank, a `LoggingMailSender` is used
  that logs "email not configured" with the recipient only, never the body.
- Reset email: subject "Reset your Gym Tracker password", plain text with the
  link and "This link works once and expires in 1 hour. If you didn't ask for
  this, ignore this email."

### Rate limiting

- An in-memory limiter: at most 10 requests per client IP per 15 minutes to
  each of `POST /login`, `/api/auth/register` and `/api/auth/forgot`
  (counted separately). Over the limit → 429 `Too many attempts. Try again later.`
- The client IP comes from `request.getRemoteAddr()`, which already reflects
  `X-Forwarded-For` because `server.forward-headers-strategy=framework`.
- In memory is enough for a single Railway instance; counters reset on deploy.

### Logout

- `POST /logout` (Spring Security logout, CSRF protected): deletes the
  remember-me token, invalidates the session, clears the cookies, responds 204.
- `DELETE /api/push/subscription` `{endpoint}` removes the caller's
  subscription for that endpoint (204, also when it doesn't exist).

### Configuration

| Variable | Purpose |
|---|---|
| `INVITE_CODE` | Code needed to register; blank closes registration |
| `BREVO_API_KEY` | Brevo transactional email API key |
| `MAIL_FROM` | Verified sender address in Brevo |
| `APP_BASE_URL` | Public URL used in reset links, e.g. `https://…up.railway.app` |

Removed: `APP_USERNAME`, `APP_PASSWORD`. Unchanged: `REMEMBER_ME_KEY`,
`VAPID_*`, `SECURE_COOKIES`, database variables. `APP_BASE_URL` is required:
startup fails with a clear message when it is blank.

## 4. Frontend

### Screens (`views/login.js` becomes `views/auth.js`)

- **Sign in:** email + password, links "Create account" and "Forgot password?".
- **Create account:** email, password (show/hide toggle), invite code.
  On success the app opens.
- **Forgot password:** email → the neutral message plus "Check your spam folder too."
- **Reset password** (`#/reset?token=…`, reachable while signed out): new
  password with show/hide → sign-in screen with "Password changed. Sign in."
  and the email from the reset response filled in.
- Inputs use `type="email"`, `autocomplete="email"`, `current-password` and
  `new-password` so iPhone Keychain saves and suggests passwords.
- Server messages (400/403/409/429) are shown under the form; network failure
  shows the existing "No connection" message.

### Local data per account

- Local data (snapshot, outbox, acked ops) is tagged with the account email in
  `gt.account.v1`. After sign-in or `/api/me`, if the stored tag differs from the
  signed-in email (including untagged data from before this change), local data
  is wiped before the first sync. Data is never merged across accounts.
- The offline-first boot ("known user") keeps working, now based on the tag.

### Logout (Settings)

- Settings shows "Signed in as <email>" and a **Log out** button (danger style).
- Tapping it opens a confirmation sheet. If the outbox is not empty it says
  "N changes haven't synced yet and will be lost." with **Try again** (runs a
  sync) and **Log out anyway**; otherwise **Log out** and **Cancel**.
- When offline (the logout request fails with a network error): show
  "You're offline. Connect to the internet to log out." and stay signed in.
- Logout order: stop the rest timer → unsubscribe push (`DELETE
  /api/push/subscription`, then `pushManager` unsubscribe, set
  `alertsEnabled` false) → `POST /logout` → wipe local account data and tag →
  show sign-in. Failures in the push steps don't block logout.
- Theme and rest-timer settings stay on the phone.

### Themes

- Settings → Theme: **Blue**, **Pink**, **Red & black**, stored in prefs as
  `theme` (`blue` | `pink` | `redblack`), default `blue`.
- Applied as `data-theme` on `<html>`. A tiny inline script at the top of
  `index.html` reads the pref and sets the attribute before first paint.
- **Blue:** today's tokens, light/dark following the phone.
- **Pink:** a light theme only — blush surfaces, rose accent, dark text.
- **Red & black:** a dark theme only — near-black surfaces, strong red accent, light text.
- Each theme defines the full token set (`--surface-*`, `--text-*`,
  `--border`, `--grid`, `--accent`, `--accent-text`, `--series-1`, `--danger`).
  Primary buttons are filled with the accent, danger buttons are text-only, so
  they stay distinct in Red & black where accent and danger are both red.
- Charts read colours from the CSS tokens and are redrawn on theme change.
- `<meta name="theme-color">` is updated to the theme's surface colour.
- Every theme's text/surface contrast and chart series colour is checked with
  the dataviz palette validator; text contrast at least 4.5:1.

## 5. Error handling

Unchanged conventions: errors are `{message}` JSON with fitting status codes;
the UI shows the message. New statuses: 403 (registration closed / wrong
invite code), 409 (email taken), 429 (rate limit).

## 6. Testing

**Integration (MockMvc + Testcontainers):**
- Migration: V2 applies on top of V1 data and leaves owned tables empty.
- Register: success signs in; wrong code; registration closed; duplicate email
  (case-insensitive); short password; invalid email.
- Login with differently-cased email works.
- Isolation, for every endpoint: account B gets 404 / empty lists for account
  A's exercises, sessions, sets, stats and records; B's `PUT` with A's id
  changes nothing; B can't add a set to A's session or with A's exercise.
- Per-account rules: two accounts each have an open session and an exercise
  with the same name.
- Forgot/reset through a capturing fake `MailSender`: link works once; expired
  and reused tokens rejected; reset signs out other devices; forgot response is
  identical for known and unknown emails; at most 3 emails per hour.
- Rate limiting returns 429 after 10 attempts.
- `BrevoMailSender` against a local JDK `HttpServer`: method, URL, `api-key`
  header and JSON body.
- Rest timer: two accounts' timers don't cancel each other; alerts go only to
  the owner's subscriptions. Push subscription moves on re-registration.
- Logout deletes the remember-me token.

**E2E (Playwright WebKit, iPhone viewport):**
- Register → log a set → log out → register a second account → app is empty
  → log out → sign in to the first → data is there.
- Forgot → link from fake mail sender → new password → sign in.
- Logout warns with unsynced changes; offline logout shows the offline message.
- Theme switch applies immediately and survives a reload.

## 7. Deployment

- `docs/railway-setup.md` and `README.md` updated: new variables, removed
  variables, Brevo setup (create account, verify sender email, create API key).
- After deploy, the existing user registers again with the invite code.
