# Accounts and Themes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let several people use the app with private data (register with an invite code, sign in by email, reset a forgotten password by email, log out), and add Pink and Red & black themes next to the current Blue.

**Architecture:**
- **Accounts:** they live in a new `app_user` table, and Spring Security loads them from there.
- **Data ownership:** every owned table gets a `user_id`. Controllers pass the signed-in user's id into the services, and every query filters on it.
- **Email:** reset emails go through Brevo's HTTPS API behind a `MailSender` interface, so tests use a fake.
- **Frontend:** it gains auth screens, a logout flow that wipes the phone's data, and themes as CSS token sets chosen through `data-theme` on `<html>`.

**Tech Stack:**
- **Backend:** Spring Boot 4.1 / Java 25, Spring Security 7, JPA + Flyway on PostgreSQL 17, JDK `HttpClient` + Jackson 3 (`tools.jackson`) for Brevo.
- **Frontend:** plain ES modules.
- **Tests:** JUnit 5 + MockMvc + Testcontainers, Playwright for Java (WebKit).

**Spec:** `docs/superpowers/specs/2026-10-06-accounts-and-themes-design.md`

## Global Constraints

- Java 25, Spring Boot 4.1.1, no new Maven dependencies (JDK `HttpClient`, `com.sun.net.httpserver` and Jackson 3 `tools.jackson.databind.json.JsonMapper` are already available).
- Errors are `{"message": "..."}` JSON. New statuses: 403 (`Registration is closed`, `Wrong invite code`), 409 (`An account with this email already exists`), 429 (`Too many attempts. Try again later.`).
- Emails are stored and compared trimmed and lowercased (`Locale.ROOT`).
- Password rules: at least 8 characters (`Use at least 8 characters for your password`), at most 72 UTF-8 bytes (`Use at most 72 characters for your password`).
- Reset links are built only from `APP_BASE_URL`: `<APP_BASE_URL>/#/reset?token=<token>`. They expire after 1 hour, work once, and each account gets at most 3 reset emails per hour.
- The forgot-password reply is always `If an account exists for this email, we've sent a reset link.`
- Expired/used/unknown reset link: 400 `This link has expired. Request a new one.`
- Rate limit: 10 requests per client IP per 15 minutes, counted separately for `POST /login`, `/api/auth/register` and `/api/auth/forgot`.
- Railway variables: add `INVITE_CODE`, `BREVO_API_KEY`, `MAIL_FROM`, `APP_BASE_URL` (required); remove `APP_USERNAME`, `APP_PASSWORD`.
- Test settings: invite code `test-invite`, base URL `http://localhost:8080`, test account `tester@example.com` / `secret-pass` (`com.gymtracker.TestUsers`).
- UI copy is English. Themes: `blue` (default, today's look), `pink` (light), `redblack` (dark).
- Docs and comments use neutral wording ("the user"); never write personal names or the owner's email into the repo.
- The project lives in an iCloud-synced folder: if a build fails with "wrong name" or duplicate `* 2` files, run `./mvnw clean` and delete empty `* 2` folders.

**Rulings made while writing this plan (cost if wrong in brackets):**
- **Two migrations instead of one:** the spec's `V2` is split into `V2__accounts.sql` (accounts tables) and `V3__ownership.sql` (wipe + `user_id`), so that each task stays green. [Nothing: both run in the same deploy.]
- **New `password_changed_at` column** on `app_user`, plus a filter that ends sessions opened before the last password change. Without it, a password reset would delete remember-me tokens but leave open server sessions alive for up to 7 days, and the spec promises "signs out all devices". [About 60 lines.]
- **Another account's ids are treated as if they didn't exist.**
  - Where that would create a row, the request is answered 404 instead: PUT exercise, PUT session, PUT set.
  - Elsewhere, the existing "missing" behaviour applies: ending such a session answers `discarded`, and deleting it or a set answers 204. Nothing changes in either case.
  - [The phone may show a "discarded" result for a forged id. Only a hand-crafted request can trigger it.]
- **Logout deletes only this device's remember-me token.** Spring's default deletes all of the account's tokens, which would sign out every device. [None.]
- **First start offline with no account on the phone shows the sign-in screen**, instead of an app that has no account to sync to. [The "No connection" message stays until there's signal.]
- **Contrast checks only for the new themes:** the ≥ 4.5:1 text-contrast check runs for Pink and Red & black. Blue stays exactly as today, as the spec requires, and some of its muted/danger tokens are at 4.2–4.5. [Blue unchanged.]
- **Register's 403 messages:** `api.js` keeps the server's message on 403 responses. Its CSRF retry is skipped for `/login`, `/logout` and `/api/auth/*`, whose forms always fetch a CSRF cookie first. [None.]

## Review Focus

1. **A phone that already has the old app's data** (untagged `gt.snapshot.v1`/outbox, old `gt.knownUser`). After the upgrade it must show sign-in, and the old data must be wiped at first sign-in, never uploaded to the new account. Covered by the unit test and `AccountsE2ETest.switchingAccountsWipesThePreviousAccountsData`.
2. **A forged id from another account in any write** (exercise, session, set, push endpoint) must change nothing. Covered by `IsolationApiTest` and `PushApiTest`.
3. **Sessions opened before a password reset** must stop working, both remember-me and server session. Covered by `PasswordResetApiTest.resetSignsOutEveryDevice`.
4. **Logging out on a weak or no connection** must not leave the phone half signed out. The logout flow only wipes data after the server confirmed. Covered by `LogoutE2ETest.offlineLogoutKeepsYouSignedIn`.
5. **Theme applied before first paint, and charts after a theme switch,** must use the new colours. Covered by `ThemeE2ETest`.

---

### Task 1: Accounts table and sign-in by email

**Files:**
- Create: `src/main/resources/db/migration/V2__accounts.sql`
- Create: `src/main/java/com/gymtracker/account/AppUser.java`, `AppUserRepository.java`, `AppUserDetailsService.java`, `Emails.java`
- Modify: `src/main/java/com/gymtracker/security/SecurityConfig.java`, `src/main/java/com/gymtracker/security/MeController.java`
- Modify: `src/main/resources/application.properties`, `src/test/resources/application-test.properties`
- Modify: `src/main/resources/static/js/views/login.js`
- Create: `src/test/java/com/gymtracker/TestUsers.java`
- Modify: `src/test/java/com/gymtracker/IntegrationTestBase.java`, `DbCleaner.java`, `e2e/E2ETestBase.java`, `security/SecurityConfigTest.java`, `security/SecurityIntegrationTest.java`, `e2e/LoginAndExercisesE2ETest.java`, `SchemaMigrationTest.java`

**Interfaces:**
- Produces:
  - `com.gymtracker.account.AppUser` with `getId(): UUID`, `getEmail()`, `getPasswordHash()`, `getPasswordChangedAt(): Instant` and `changePassword(String hash, Instant at)`;
  - `AppUserRepository.findByEmail(String): Optional<AppUser>`;
  - `Emails.normalize(String): String`;
  - `GET /api/me` → `{"email": ...}`;
  - test helpers `TestUsers.EMAIL`, `TestUsers.PASSWORD`, `TestUsers.insert(JdbcTemplate, String email): UUID`;
  - `IntegrationTestBase` fields `testerId` and `actingAs`, methods `createUser(String)` and instance `signedIn()`;
  - `E2ETestBase.testerId`.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/gymtracker/TestUsers.java`:

```java
package com.gymtracker;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** The account most tests act as, and a quick way to add more. */
public final class TestUsers {

    public static final String EMAIL = "tester@example.com";
    public static final String PASSWORD = "secret-pass";
    private static final String HASH = new BCryptPasswordEncoder().encode(PASSWORD);

    private TestUsers() {
    }

    /** Adds an account with {@link #PASSWORD} as its password. */
    public static UUID insert(JdbcTemplate jdbc, String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into app_user (id, email, password_hash, created_at, password_changed_at) "
                + "values (?, ?, ?, now(), now())", id, email, HASH);
        return id;
    }
}
```

In `DbCleaner.clean`, replace the truncate statement with:

```java
                jdbc.execute("truncate table workout_set, workout_session, exercise, persistent_logins, "
                        + "push_subscription, password_reset_token, app_user");
```

In `IntegrationTestBase`:
- Replace `cleanDatabase()` and the static `signedIn()` with the code below.
- Add the imports `org.springframework.jdbc.core.JdbcTemplate` (already there) and `java.util.UUID` (already there).

```java
    /** The signed-in test account; tests may switch {@link #actingAs} to another account they created. */
    protected UUID testerId;
    protected String actingAs;

    @BeforeEach
    void cleanDatabase() {
        DbCleaner.clean(jdbc);
        testerId = TestUsers.insert(jdbc, TestUsers.EMAIL);
        actingAs = TestUsers.EMAIL;
    }

    protected UUID createUser(String email) {
        return TestUsers.insert(jdbc, email);
    }

    /** Signed in as {@link #actingAs}, with a valid CSRF cookie and header. */
    protected RequestPostProcessor signedIn() {
        String email = actingAs;
        return request -> xsrf().postProcessRequest(user(email).postProcessRequest(request));
    }
```

In `E2ETestBase`:
- add the field `protected java.util.UUID testerId;`;
- change `openPage()` to insert the tester right after `DbCleaner.clean(jdbc);`: `testerId = com.gymtracker.TestUsers.insert(jdbc, com.gymtracker.TestUsers.EMAIL);`;
- change `signIn()` to:

```java
    protected void signIn() {
        page.navigate("/");
        page.getByLabel("Email").fill(com.gymtracker.TestUsers.EMAIL);
        page.getByLabel("Password", new Page.GetByLabelOptions().setExact(true)).fill(com.gymtracker.TestUsers.PASSWORD);
        button("Sign in").click();
        assertThat(page.locator("#tabs")).isVisible();
        waitUntilSynced(); // tests start from loaded data; slow-load behaviour has its own tests
    }
```

In `SecurityConfigTest`, delete `refusesToStartWithoutCredentials` and the now-unused `BCryptPasswordEncoder` import.

In `SecurityIntegrationTest`:
- replace every `login("tester", "secret-pass")` with `login(TestUsers.EMAIL, TestUsers.PASSWORD)`;
- replace `login("tester", "nope")` with `login(TestUsers.EMAIL, "nope")`;
- replace the `.param("username", "tester").param("password", "secret-pass")` in `loginWithoutCsrfTokenIsForbidden` with `.param("username", TestUsers.EMAIL).param("password", TestUsers.PASSWORD)`;
- add `import com.gymtracker.TestUsers;`;
- change the assertions and add the tests below.

```java
    @Test
    void meReturnsEmail() throws Exception {
        apiGet("/api/me").andExpect(status().isOk()).andExpect(jsonPath("$.email").value(TestUsers.EMAIL));
    }
```

(This replaces `meReturnsUsername`. In `loginSucceedsAndRememberMeCookieSignsInWithoutSession`, change `jsonPath("$.username").value("tester")` to `jsonPath("$.email").value(TestUsers.EMAIL)`. In `wrongPasswordAnswers401WithMessage`, the expected message becomes `"Wrong email or password"`.)

```java
    @Test
    void loginIgnoresCaseAndSpacesInEmail() throws Exception {
        Cookie rememberMe = login("  Tester@Example.COM ", TestUsers.PASSWORD)
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("remember-me");
        mvc.perform(get("/api/me").cookie(rememberMe))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(TestUsers.EMAIL));
    }

    @Test
    void unknownEmailGetsTheSameMessageAsAWrongPassword() throws Exception {
        login("nobody@example.com", TestUsers.PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Wrong email or password"));
    }
```

In `SchemaMigrationTest.createsAllTables`, extend the expected list with `"app_user", "password_reset_token"`.

In `LoginAndExercisesE2ETest`:
- add `import com.gymtracker.TestUsers;`;
- replace `wrongPasswordShowsError` with the version below;
- in `loginFormIsNotWipedWhileChangesWait`, replace both `getByLabel("Username")` with `getByLabel("Email")` and `"tester"` with `TestUsers.EMAIL`.

```java
    @Test
    void wrongPasswordShowsError() {
        page.navigate("/");
        page.getByLabel("Email").fill(TestUsers.EMAIL);
        page.getByLabel("Password", new com.microsoft.playwright.Page.GetByLabelOptions().setExact(true)).fill("wrong");
        button("Sign in").click();
        assertThat(page.getByText("Wrong email or password")).isVisible();
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q test -Dtest='SecurityIntegrationTest,SchemaMigrationTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL. `TestUsers.insert` fails with `relation "app_user" does not exist` in every test's setup.

- [ ] **Step 3: Write the migration and the account classes**

Create `src/main/resources/db/migration/V2__accounts.sql`:

```sql
create table app_user (
    id                  uuid primary key,
    email               varchar(254) not null,
    password_hash       varchar(100) not null,
    created_at          timestamptz  not null,
    -- Sessions that started before this moment are signed out (see PasswordChangeSignOutFilter).
    password_changed_at timestamptz  not null
);
-- Stored trimmed and lowercased, so this also makes emails unique ignoring case.
create unique index app_user_email_uq on app_user (email);

create table password_reset_token (
    id         uuid primary key,
    user_id    uuid        not null references app_user (id) on delete cascade,
    -- SHA-256 of the token in the email; the token itself is never stored.
    token_hash varchar(64) not null unique,
    created_at timestamptz not null,
    expires_at timestamptz not null,
    used_at    timestamptz
);
create index password_reset_token_user_idx on password_reset_token (user_id);
```

Create `src/main/java/com/gymtracker/account/Emails.java`:

```java
package com.gymtracker.account;

import java.util.Locale;

public final class Emails {

    private Emails() {
    }

    /** How emails are stored and compared: without surrounding spaces, in lower case. */
    public static String normalize(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }
}
```

Create `src/main/java/com/gymtracker/account/AppUser.java`:

```java
package com.gymtracker.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    private UUID id;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "password_changed_at", nullable = false)
    private Instant passwordChangedAt;

    protected AppUser() {
    }

    public AppUser(UUID id, String email, String passwordHash, Instant createdAt) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.createdAt = createdAt;
        this.passwordChangedAt = createdAt;
    }

    public void changePassword(String passwordHash, Instant changedAt) {
        this.passwordHash = passwordHash;
        this.passwordChangedAt = changedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Instant getPasswordChangedAt() {
        return passwordChangedAt;
    }
}
```

Create `src/main/java/com/gymtracker/account/AppUserRepository.java`:

```java
package com.gymtracker.account;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    /** {@code email} must already be normalized with {@link Emails#normalize}. */
    Optional<AppUser> findByEmail(String email);
}
```

Create `src/main/java/com/gymtracker/account/AppUserDetailsService.java`:

```java
package com.gymtracker.account;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sign-in by email. The principal's name is the normalized email. */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;

    public AppUserDetailsService(AppUserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        AppUser user = users.findByEmail(Emails.normalize(username))
                .orElseThrow(() -> new UsernameNotFoundException("No account for this email"));
        return User.withUsername(user.getEmail()).password(user.getPasswordHash()).roles("USER").build();
    }
}
```

In `SecurityConfig`:
- delete the `userDetailsService(...)` bean method, `singleUser(...)` and the unused imports (`User`, `UserDetails`, `InMemoryUserDetailsManager`);
- the `rememberMeServices` bean keeps its `UserDetailsService userDetailsService` parameter, which Spring now fills with `AppUserDetailsService`;
- change the failure message line to:

```java
                            response.getWriter().write("{\"message\":\"Wrong email or password\"}");
```

In `MeController`, return the email:

```java
    @GetMapping("/api/me")
    Map<String, String> me(Principal principal) {
        return Map.of("email", principal.getName());
    }
```

In `application.properties`, delete the `app.username=` and `app.password=` lines. In `application-test.properties`, delete `app.username=tester` and `app.password=secret-pass`.

In `src/main/resources/static/js/views/login.js`:
- replace the username field with the markup below;
- change the error fallback text `'Wrong username or password'` to `'Wrong email or password'`;
- change `String(data.get('username')).trim()` to `String(data.get('email')).trim()`.

The input is named `email` in the form, and `login()` still sends it as `username`, the parameter Spring expects.

```js
        <label class="field">Email
          <input name="email" type="email" autocomplete="username" autocapitalize="none" autocorrect="off" spellcheck="false" required>
        </label>
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q test -Dtest='SecurityIntegrationTest,SecurityConfigTest,SchemaMigrationTest,LoginAndExercisesE2ETest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (all tests in these four classes).

- [ ] **Step 5: Commit**

```bash
git add -A src docs
git commit -m "Accounts table and sign-in by email

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Every account owns its data

**Files:**
- Create: `src/main/resources/db/migration/V3__ownership.sql`
- Create: `src/main/java/com/gymtracker/account/CurrentUser.java`, `src/main/java/com/gymtracker/common/NotSignedInException.java`
- Modify: `common/GlobalExceptionHandler.java`
- Modify: `exercise/Exercise.java`, `ExerciseRepository.java`, `ExerciseService.java`, `ExerciseController.java`
- Modify: `workout/WorkoutSession.java`, `WorkoutSessionRepository.java`, `WorkoutSetRepository.java`, `WorkoutService.java`, `SessionController.java`, `SetController.java`
- Modify: `stats/StatsService.java`, `StatsController.java`
- Modify: `push/PushSubscription.java`, `PushSubscriptionRepository.java`, `PushSubscriptionService.java`, `PushController.java`
- Create: `src/test/java/com/gymtracker/IsolationApiTest.java`, `src/test/java/com/gymtracker/MigrationUpgradeTest.java`
- Modify: `src/test/java/com/gymtracker/SchemaMigrationTest.java`, `e2e/E2ETestBase.java`

**Interfaces:**
- Consumes: `AppUserRepository.findByEmail`, `IntegrationTestBase.actingAs/createUser/testerId` (Task 1).
- Produces:
  - `CurrentUser.id(): UUID`;
  - service signatures that take `UUID userId` as their first parameter:
    - `ExerciseService.listActive/upsert/archive`;
    - `WorkoutService.start/end/discard/active/get/history/putSet/deleteSet`;
    - `StatsService.historyByExercise/historyFor/exerciseStats/weekly`;
  - `PushSubscriptionService.save(UUID userId, String endpoint, String p256dh, String auth)`;
  - `PushSubscription.getUserId()`.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/gymtracker/IsolationApiTest.java`:

```java
package com.gymtracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** One account can never see, change or delete another account's data, even with the other account's ids. */
class IsolationApiTest extends IntegrationTestBase {

    private static final String OTHER = "other@example.com";

    private UUID squat;
    private UUID session;
    private UUID set;

    @BeforeEach
    void testerHasData() throws Exception {
        squat = createExercise("Squat", "QUADS");
        session = startSession("2026-10-05T08:00:00Z");
        set = logSet(session, squat, "60", 5, "WORK", "2026-10-05T08:10:00Z");
        createUser(OTHER);
    }

    private void actAsOther() {
        actingAs = OTHER;
    }

    @Test
    void listsOnlyOwnExercises() throws Exception {
        actAsOther();
        apiGet("/api/exercises").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void cannotRenameOrDeleteAnotherAccountsExercise() throws Exception {
        actAsOther();
        apiPut("/api/exercises/" + squat, """
                {"name": "Mine now", "muscleGroup": "CHEST"}""").andExpect(status().isNotFound());
        apiDelete("/api/exercises/" + squat).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select name from exercise where id = ?", String.class, squat)).isEqualTo("Squat");
        assertThat(jdbc.queryForObject("select archived from exercise where id = ?", Boolean.class, squat)).isFalse();
    }

    @Test
    void twoAccountsCanHaveExercisesWithTheSameName() throws Exception {
        actAsOther();
        createExercise("Squat", "QUADS");
        assertThat(jdbc.queryForObject("select count(*) from exercise where name = 'Squat'", Integer.class)).isEqualTo(2);
    }

    @Test
    void cannotSeeOrTouchAnotherAccountsSession() throws Exception {
        actAsOther();
        apiGet("/api/sessions/active").andExpect(status().isNoContent());
        apiGet("/api/sessions/" + session).andExpect(status().isNotFound());
        apiPut("/api/sessions/" + session, """
                {"date": "2026-10-05", "startedAt": "2026-10-05T08:00:00Z"}""").andExpect(status().isNotFound());
        apiPost("/api/sessions/" + session + "/end", """
                {"endedAt": "2026-10-05T09:00:00Z"}""").andExpect(status().isOk());
        apiDelete("/api/sessions/" + session).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from workout_session where id = ? and ended_at is null",
                Integer.class, session)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from workout_set where id = ?", Integer.class, set)).isEqualTo(1);
    }

    @Test
    void eachAccountHasItsOwnOpenSession() throws Exception {
        actAsOther();
        startSession("2026-10-05T08:30:00Z");
        assertThat(jdbc.queryForObject("select count(*) from workout_session where ended_at is null", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void cannotAddSetsToAnotherAccountsSessionOrWithItsExercise() throws Exception {
        actAsOther();
        UUID ownExercise = createExercise("Bench press", "CHEST");
        UUID ownSession = startSession("2026-10-05T08:30:00Z");
        apiPut("/api/sets/" + UUID.randomUUID(), setJson(session, ownExercise, "40", 8, "WORK", "2026-10-05T08:40:00Z"))
                .andExpect(status().isNotFound());
        apiPut("/api/sets/" + UUID.randomUUID(), setJson(ownSession, squat, "40", 8, "WORK", "2026-10-05T08:40:00Z"))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from workout_set", Integer.class)).isEqualTo(1);
    }

    @Test
    void cannotChangeOrDeleteAnotherAccountsSet() throws Exception {
        actAsOther();
        UUID ownExercise = createExercise("Bench press", "CHEST");
        UUID ownSession = startSession("2026-10-05T08:30:00Z");
        apiPut("/api/sets/" + set, setJson(ownSession, ownExercise, "1", 1, "WORK", "2026-10-05T08:40:00Z"))
                .andExpect(status().isNotFound());
        apiDelete("/api/sets/" + set).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select reps from workout_set where id = ?", Integer.class, set)).isEqualTo(5);
    }

    @Test
    void historyAndStatsCountOnlyOwnWorkouts() throws Exception {
        endSession(session, "2026-10-05T09:00:00Z");
        actAsOther();
        apiGet("/api/sessions").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        apiGet("/api/exercises/" + squat + "/stats").andExpect(status().isNotFound());
        apiGet("/api/stats/weekly?weeks=1&today=2026-10-05")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].workouts").value(0));
    }
}
```

Create `src/test/java/com/gymtracker/MigrationUpgradeTest.java`. It checks that upgrading a database with old single-user data leaves the owned tables empty:

```java
package com.gymtracker;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Upgrading a database that still holds single-user data (schema V1) starts every account empty. */
class MigrationUpgradeTest extends IntegrationTestBase {

    private static final String SCHEMA = "upgrade_check";

    @Autowired
    DataSource dataSource;

    @AfterEach
    void dropSchema() {
        jdbc.execute("drop schema if exists " + SCHEMA + " cascade");
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource).schemas(SCHEMA).target(target).load();
    }

    @Test
    void upgradeEmptiesTheOwnedTables() {
        flyway("1").migrate();
        jdbc.update("insert into " + SCHEMA + ".exercise (id, name, muscle_group, archived, created_at) "
                + "values (gen_random_uuid(), 'Squat', 'QUADS', false, now())");
        jdbc.update("insert into " + SCHEMA + ".workout_session (id, session_date, started_at) "
                + "values (gen_random_uuid(), current_date, now())");
        jdbc.update("insert into " + SCHEMA + ".push_subscription (endpoint, p256dh, auth, created_at) "
                + "values ('https://push.example/1', 'k', 'a', now())");
        jdbc.update("insert into " + SCHEMA + ".persistent_logins (username, series, token, last_used) "
                + "values ('old-user', 'series', 'token', now())");

        flyway("latest").migrate();

        for (String table : new String[] {"exercise", "workout_session", "workout_set", "push_subscription", "persistent_logins"}) {
            assertThat(jdbc.queryForObject("select count(*) from " + SCHEMA + "." + table, Integer.class))
                    .as(table).isZero();
        }
    }
}
```

Replace the two data tests in `SchemaMigrationTest` (and their helpers) with per-account versions:

```java
    @Test
    void allowsOneOpenSessionPerAccount() {
        UUID other = createUser("other@example.com");
        insertSession(testerId, null);
        insertSession(testerId, "2026-10-05T09:00:00Z");
        insertSession(other, null);
        assertThatThrownBy(() -> insertSession(testerId, null)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void exerciseNamesAreUniquePerAccountIgnoringCaseAmongActiveOnly() {
        UUID other = createUser("other@example.com");
        insertExercise(testerId, "Squat", true);
        insertExercise(testerId, "Squat", false);
        insertExercise(other, "Squat", false);
        assertThatThrownBy(() -> insertExercise(testerId, "squat", false))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertSession(UUID userId, String endedAt) {
        jdbc.update("insert into workout_session (id, user_id, session_date, started_at, ended_at) "
                + "values (?, ?, date '2026-10-05', timestamptz '2026-10-05T08:00:00Z', ?::timestamptz)",
                UUID.randomUUID(), userId, endedAt);
    }

    private void insertExercise(UUID userId, String name, boolean archived) {
        jdbc.update("insert into exercise (id, user_id, name, muscle_group, archived, created_at) "
                + "values (?, ?, ?, 'QUADS', ?, now())", UUID.randomUUID(), userId, name, archived);
    }
```

In `E2ETestBase`, make the seed helpers write the tester as the owner:

```java
    protected UUID seedExercise(String name, String muscleGroup) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into exercise (id, user_id, name, muscle_group, archived, created_at) "
                + "values (?, ?, ?, ?, false, now())", id, testerId, name, muscleGroup);
        return id;
    }

    protected UUID seedEndedSession(String date, String startedAt, String endedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into workout_session (id, user_id, session_date, started_at, ended_at) "
                + "values (?, ?, ?::date, ?::timestamptz, ?::timestamptz)", id, testerId, date, startedAt, endedAt);
        return id;
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q test -Dtest='IsolationApiTest,MigrationUpgradeTest,SchemaMigrationTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL.
- `SchemaMigrationTest` fails with `column "user_id" of relation "workout_session" does not exist`.
- `MigrationUpgradeTest` fails because the owned tables still hold rows (count 1).
- `IsolationApiTest.listsOnlyOwnExercises` fails because it expects length 0 but gets 1.

- [ ] **Step 3: Write the migration**

Create `src/main/resources/db/migration/V3__ownership.sql`:

```sql
-- Every account starts empty: the single-user data from before accounts existed is not carried over.
delete from workout_set;
delete from workout_session;
delete from exercise;
delete from push_subscription;
delete from persistent_logins;

alter table exercise add column user_id uuid not null references app_user (id) on delete cascade;
alter table workout_session add column user_id uuid not null references app_user (id) on delete cascade;
alter table push_subscription add column user_id uuid not null references app_user (id) on delete cascade;
create index exercise_user_idx on exercise (user_id);
create index workout_session_user_idx on workout_session (user_id);
create index push_subscription_user_idx on push_subscription (user_id);

-- Names are unique among an account's non-archived exercises, ignoring case.
drop index exercise_active_name_uq;
create unique index exercise_active_name_uq on exercise (user_id, lower(name)) where not archived;
-- At most one open session per account.
drop index workout_session_one_open_uq;
create unique index workout_session_one_open_uq on workout_session (user_id) where ended_at is null;
```

- [ ] **Step 4: Add `CurrentUser` and its exception**

Create `src/main/java/com/gymtracker/common/NotSignedInException.java`:

```java
package com.gymtracker.common;

public class NotSignedInException extends RuntimeException {

    public NotSignedInException() {
        super("Sign in again");
    }
}
```

In `GlobalExceptionHandler`, add (next to the other handlers):

```java
    @ExceptionHandler(NotSignedInException.class)
    ResponseEntity<ApiError> notSignedIn(NotSignedInException e) {
        return error(HttpStatus.UNAUTHORIZED, e.getMessage());
    }
```

Create `src/main/java/com/gymtracker/account/CurrentUser.java`:

```java
package com.gymtracker.account;

import com.gymtracker.common.NotSignedInException;
import java.util.UUID;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** The id of the account making the current request. Controllers pass it to every service call on owned data. */
@Component
public class CurrentUser {

    private final AppUserRepository users;

    public CurrentUser(AppUserRepository users) {
        this.users = users;
    }

    public UUID id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            throw new NotSignedInException();
        }
        return users.findByEmail(auth.getName()).map(AppUser::getId).orElseThrow(NotSignedInException::new);
    }
}
```

- [ ] **Step 5: Exercises belong to an account**

In `Exercise`:
- add the field below after `id`;
- change the constructor to `public Exercise(UUID id, UUID userId, String name, MuscleGroup muscleGroup, Instant createdAt)`, setting `this.userId = userId;`;
- add the two methods below.

```java
    @Column(name = "user_id", nullable = false)
    private UUID userId;
```

```java
    public UUID getUserId() {
        return userId;
    }

    public boolean isOwnedBy(UUID userId) {
        return this.userId.equals(userId);
    }
```

Replace the body of `ExerciseRepository` with:

```java
public interface ExerciseRepository extends JpaRepository<Exercise, UUID> {

    List<Exercise> findByUserIdAndArchivedFalse(UUID userId);

    Optional<Exercise> findByIdAndUserId(UUID id, UUID userId);

    boolean existsByIdAndUserId(UUID id, UUID userId);

    @Query("""
            select count(e) > 0 from Exercise e
            where e.userId = :userId and lower(e.name) = lower(:name) and e.archived = false and e.id <> :id""")
    boolean existsActiveNameExcluding(UUID userId, String name, UUID id);
}
```

(add `import java.util.Optional;`).

Replace the three methods of `ExerciseService` with:

```java
    @Transactional(readOnly = true)
    public List<Exercise> listActive(UUID userId) {
        return exercises.findByUserIdAndArchivedFalse(userId).stream()
                .sorted(Comparator.comparing(Exercise::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public Exercise upsert(UUID userId, UUID id, ExerciseRequest request) {
        String name = request.name().strip();
        Optional<Exercise> found = exercises.findById(id);
        if (found.isPresent() && !found.get().isOwnedBy(userId)) {
            throw new NotFoundException("Exercise not found"); // another account's id: never reveal or touch it
        }
        if (exercises.existsActiveNameExcluding(userId, name, id)) {
            throw new ConflictException("You already have an exercise called '" + name + "'");
        }
        return found
                .map(existing -> {
                    if (existing.isArchived()) {
                        throw new ConflictException("This exercise has been deleted");
                    }
                    existing.update(name, request.muscleGroup());
                    return existing;
                })
                .orElseGet(() -> exercises.save(new Exercise(id, userId, name, request.muscleGroup(), clock.instant())));
    }

    public void archive(UUID userId, UUID id) {
        exercises.findByIdAndUserId(id, userId).orElseThrow(() -> new NotFoundException("Exercise not found")).archive();
    }
```

(add `import java.util.Optional;`).

In `ExerciseController`:
- add a `CurrentUser currentUser` constructor parameter and field (`import com.gymtracker.account.CurrentUser;`);
- change the methods to:

```java
    @GetMapping
    List<ExerciseResponse> list() {
        UUID userId = currentUser.id();
        Map<UUID, ExerciseHistory> histories = stats.historyByExercise(userId);
        return service.listActive(userId).stream()
                .map(e -> ExerciseResponse.of(e, histories.getOrDefault(e.getId(), ExerciseHistory.EMPTY)))
                .toList();
    }

    @PutMapping("/{id}")
    ExerciseResponse put(@PathVariable UUID id, @Valid @RequestBody ExerciseRequest request) {
        UUID userId = currentUser.id();
        Exercise exercise = service.upsert(userId, id, request);
        return ExerciseResponse.of(exercise, stats.historyFor(userId, id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        service.archive(currentUser.id(), id);
    }
```

- [ ] **Step 6: Sessions and sets belong to an account**

In `WorkoutSession`:
- add the `userId` field below after `id`;
- change the constructor to `public WorkoutSession(UUID id, UUID userId, LocalDate date, Instant startedAt)`, setting `this.userId = userId;`;
- add `isOwnedBy`, as in `Exercise`.

```java
    @Column(name = "user_id", nullable = false)
    private UUID userId;
```

```java
    public boolean isOwnedBy(UUID userId) {
        return this.userId.equals(userId);
    }
```

Replace the body of `WorkoutSessionRepository` with:

```java
public interface WorkoutSessionRepository extends JpaRepository<WorkoutSession, UUID> {

    Optional<WorkoutSession> findByIdAndUserId(UUID id, UUID userId);

    Optional<WorkoutSession> findFirstByUserIdAndEndedAtIsNull(UUID userId);

    List<WorkoutSession> findByUserIdAndEndedAtIsNotNullOrderByStartedAtDesc(UUID userId);

    List<WorkoutSession> findByUserIdAndEndedAtIsNotNullAndDateBetween(UUID userId, LocalDate from, LocalDate to);
}
```

In `WorkoutSetRepository`, replace the three `@Query` methods with versions that filter on the session's owner:

```java
    @Query("""
            select new com.gymtracker.workout.WorkSetRow(s.exerciseId, s.sessionId, ws.date, s.weightKg, s.reps, s.loggedAt)
            from WorkoutSet s join WorkoutSession ws on ws.id = s.sessionId
            where ws.userId = :userId and s.type = com.gymtracker.workout.SetType.WORK and ws.endedAt is not null""")
    List<WorkSetRow> findEndedWorkSets(UUID userId);

    @Query("""
            select new com.gymtracker.workout.WorkSetRow(s.exerciseId, s.sessionId, ws.date, s.weightKg, s.reps, s.loggedAt)
            from WorkoutSet s join WorkoutSession ws on ws.id = s.sessionId
            where ws.userId = :userId and s.type = com.gymtracker.workout.SetType.WORK and ws.endedAt is not null
              and s.exerciseId = :exerciseId""")
    List<WorkSetRow> findEndedWorkSetsForExercise(UUID userId, UUID exerciseId);

    @Query("""
            select new com.gymtracker.workout.WeeklySetRow(ws.date, e.muscleGroup)
            from WorkoutSet s
              join WorkoutSession ws on ws.id = s.sessionId
              join com.gymtracker.exercise.Exercise e on e.id = s.exerciseId
            where ws.userId = :userId and s.type = com.gymtracker.workout.SetType.WORK and ws.endedAt is not null
              and ws.date between :from and :to""")
    List<WeeklySetRow> findEndedWorkSetMuscles(UUID userId, LocalDate from, LocalDate to);
```

In `WorkoutService`, replace the public methods `start`, `end`, `discard`, `active`, `get`, `history`, `putSet` and `deleteSet` with the versions below. `isQuarterStep`, `toResponse` and `exercisesFor` stay as they are.

```java
    public SessionResponse start(UUID userId, UUID id, StartSessionRequest request) {
        Optional<WorkoutSession> existing = sessions.findById(id);
        if (existing.isPresent()) {
            if (!existing.get().isOwnedBy(userId)) {
                throw new NotFoundException("Workout not found");
            }
            return toResponse(existing.get());
        }
        if (sessions.findFirstByUserIdAndEndedAtIsNull(userId).isPresent()) {
            throw new ConflictException("Another workout is already in progress");
        }
        return toResponse(sessions.save(new WorkoutSession(id, userId, request.date(), request.startedAt())));
    }

    public EndSessionResponse end(UUID userId, UUID id, EndSessionRequest request) {
        Optional<WorkoutSession> found = sessions.findByIdAndUserId(id, userId);
        if (found.isEmpty()) {
            return EndSessionResponse.discardedResult();
        }
        WorkoutSession session = found.get();
        if (session.getEndedAt() != null) {
            return EndSessionResponse.ended(toResponse(session));
        }
        if (request.endedAt().isBefore(session.getStartedAt())) {
            throw new BadRequestException("endedAt must not be before startedAt");
        }
        if (sets.countBySessionId(id) == 0) {
            sessions.delete(session);
            return EndSessionResponse.discardedResult();
        }
        session.end(request.endedAt());
        return EndSessionResponse.ended(toResponse(session));
    }

    public void discard(UUID userId, UUID id) {
        sessions.findByIdAndUserId(id, userId).ifPresent(session -> {
            sets.deleteBySessionId(id);
            sessions.delete(session);
        });
    }

    @Transactional(readOnly = true)
    public Optional<SessionResponse> active(UUID userId) {
        return sessions.findFirstByUserIdAndEndedAtIsNull(userId).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public SessionResponse get(UUID userId, UUID id) {
        return sessions.findByIdAndUserId(id, userId).map(this::toResponse)
                .orElseThrow(() -> new NotFoundException("Workout not found"));
    }

    @Transactional(readOnly = true)
    public List<SessionSummary> history(UUID userId) {
        List<WorkoutSession> ended = sessions.findByUserIdAndEndedAtIsNotNullOrderByStartedAtDesc(userId);
        List<WorkoutSet> allSets = sets.findBySessionIdIn(ended.stream().map(WorkoutSession::getId).toList());
        Map<UUID, List<WorkoutSet>> setsBySession = allSets.stream().collect(groupingBy(WorkoutSet::getSessionId));
        Map<UUID, Exercise> exerciseById = exercisesFor(allSets);
        return ended.stream().map(session -> {
            List<WorkoutSet> sessionSets = setsBySession.getOrDefault(session.getId(), List.of());
            List<MuscleGroup> groups = sessionSets.stream()
                    .sorted(Comparator.comparing(WorkoutSet::getLoggedAt))
                    .map(set -> exerciseById.get(set.getExerciseId()).getMuscleGroup())
                    .distinct()
                    .toList();
            long seconds = Duration.between(session.getStartedAt(), session.getEndedAt()).toSeconds();
            return new SessionSummary(session.getId(), session.getDate(), session.getStartedAt(), session.getEndedAt(),
                    seconds, sessionSets.size(), groups);
        }).toList();
    }

    public SetResponse putSet(UUID userId, UUID id, SetRequest request) {
        if (!isQuarterStep(request.weightKg())) {
            throw new BadRequestException("weightKg must be a multiple of 0.25");
        }
        WorkoutSession session = sessions.findByIdAndUserId(request.sessionId(), userId)
                .orElseThrow(() -> new NotFoundException("Workout not found"));
        if (session.getEndedAt() != null) {
            throw new ConflictException("This workout has already ended");
        }
        Exercise exercise = exercises.findByIdAndUserId(request.exerciseId(), userId)
                .orElseThrow(() -> new NotFoundException("Exercise not found"));
        Optional<WorkoutSet> existing = sets.findById(id);
        if (existing.isPresent()) {
            WorkoutSet set = existing.get();
            if (!set.getSessionId().equals(request.sessionId())) {
                boolean mine = sessions.findByIdAndUserId(set.getSessionId(), userId).isPresent();
                throw mine ? new ConflictException("This set belongs to another workout")
                        : new NotFoundException("Set not found");
            }
            set.update(request.exerciseId(), request.weightKg(), request.reps(), request.type(), request.loggedAt());
            return SetResponse.of(set, exercise);
        }
        if (exercise.isArchived()) {
            throw new ConflictException("This exercise has been deleted");
        }
        WorkoutSet set = sets.save(new WorkoutSet(id, request.sessionId(), request.exerciseId(), request.weightKg(),
                request.reps(), request.type(), request.loggedAt()));
        return SetResponse.of(set, exercise);
    }

    public void deleteSet(UUID userId, UUID id) {
        sets.findById(id).ifPresent(set -> sessions.findByIdAndUserId(set.getSessionId(), userId).ifPresent(session -> {
            if (session.getEndedAt() != null) {
                throw new ConflictException("This workout has already ended");
            }
            sets.delete(set);
        }));
    }
```

In `SessionController` and `SetController`:
- add a `CurrentUser currentUser` constructor parameter and field;
- pass `currentUser.id()` as the first argument of every service call, e.g. `service.active(currentUser.id())`, `service.get(currentUser.id(), id)` and `service.putSet(currentUser.id(), id, request)`.

- [ ] **Step 7: Stats only read the account's own sets**

In `StatsService`, change the four public methods to take `UUID userId` first and pass it on:
- `historyByExercise(UUID userId)` uses `sets.findEndedWorkSets(userId)`;
- `historyFor(UUID userId, UUID exerciseId)` uses `sets.findEndedWorkSetsForExercise(userId, exerciseId)`;
- `exerciseStats(UUID userId, UUID exerciseId)` checks `if (!exercises.existsByIdAndUserId(exerciseId, userId))` and uses `sets.findEndedWorkSetsForExercise(userId, exerciseId)`;
- `weekly(UUID userId, int weeks, LocalDate today)` uses `sessions.findByUserIdAndEndedAtIsNotNullAndDateBetween(userId, from, to)` and `sets.findEndedWorkSetMuscles(userId, from, to)`.

In `StatsController`:
- add a `CurrentUser currentUser` constructor parameter and field;
- call `stats.exerciseStats(currentUser.id(), id)` and `stats.weekly(currentUser.id(), weeks, today != null ? today : LocalDate.now(clock))`.

- [ ] **Step 8: Push subscriptions belong to an account**

In `PushSubscription`:
- add the field below after `endpoint`;
- change the constructor to `public PushSubscription(String endpoint, UUID userId, String p256dh, String auth, Instant createdAt)`;
- replace `updateKeys` with `assignTo` (below) and add `getUserId()`.

```java
    @Column(name = "user_id", nullable = false)
    private UUID userId;
```

```java
    /** The same phone endpoint can be re-registered by another account (shared phone): it then moves there. */
    public void assignTo(UUID userId, String p256dh, String auth) {
        this.userId = userId;
        this.p256dh = p256dh;
        this.auth = auth;
    }

    public UUID getUserId() {
        return userId;
    }
```

In `PushSubscriptionRepository`, add `List<PushSubscription> findByUserId(UUID userId);` (imports `java.util.List`, `java.util.UUID`).

In `PushSubscriptionService`, replace `save` with:

```java
    public void save(UUID userId, String endpoint, String p256dh, String auth) {
        repository.findById(endpoint).ifPresentOrElse(
                existing -> existing.assignTo(userId, p256dh, auth),
                () -> repository.save(new PushSubscription(endpoint, userId, p256dh, auth, clock.instant())));
    }
```

In `PushController`:
- add a `CurrentUser currentUser` constructor parameter and field;
- `subscribe` calls `subscriptions.save(currentUser.id(), request.endpoint(), request.keys().p256dh(), request.keys().auth());`.

(`RestTimerService` still alerts all subscriptions. Task 3 makes it per account.)

- [ ] **Step 9: Run the tests to verify they pass**

Run: `./mvnw -q test -Dtest='IsolationApiTest,MigrationUpgradeTest,SchemaMigrationTest,ExerciseApiTest,WorkoutApiTest,StatsApiTest,PushApiTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 10: Run the whole suite and commit**

Run: `./mvnw -q verify`
Expected: BUILD SUCCESS. The E2E tests seed through `E2ETestBase` and sign in as the tester.

```bash
git add -A src
git commit -m "Every account owns its exercises, workouts, stats and push subscriptions

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 3: Rest-timer alerts per account

**Files:**
- Modify: `src/main/java/com/gymtracker/push/RestTimerService.java`, `PushSubscriptionService.java`, `PushController.java`
- Test: `src/test/java/com/gymtracker/push/PushApiTest.java`

**Interfaces:**
- Consumes: `CurrentUser.id()`, `PushSubscription.getUserId()`, `PushSubscriptionService.save(UUID, ...)` (Task 2).
- Produces:
  - `RestTimerService.schedule(UUID userId, Instant endsAt)`, `cancel(UUID userId)`, `cancelAll()`;
  - `PushSubscriptionService.forUser(UUID): List<PushSubscription>` and `removeForUser(UUID userId, String endpoint)`;
  - `DELETE /api/push/subscription` with body `{"endpoint": "..."}` → 204.

- [ ] **Step 1: Write the failing tests**

In `PushApiTest`, change `reset()` to call `restTimer.cancelAll();` instead of `restTimer.cancel();`, then add:

```java
    @Test
    void alertGoesOnlyToTheOwnersPhones() throws Exception {
        subscribe("https://push.example/mine", "key", "auth");
        createUser("other@example.com");
        actingAs = "other@example.com";
        subscribe("https://push.example/theirs", "key", "auth");
        actingAs = com.gymtracker.TestUsers.EMAIL;
        scheduleIn(Duration.ofSeconds(1));
        waitFor(() -> pushSender.sent().size() == 1, Duration.ofSeconds(5));
        Thread.sleep(300);
        assertThat(pushSender.sent()).containsExactly("https://push.example/mine");
    }

    @Test
    void accountsHaveTheirOwnTimers() throws Exception {
        subscribe("https://push.example/mine", "key", "auth");
        scheduleIn(Duration.ofSeconds(1));
        createUser("other@example.com");
        actingAs = "other@example.com";
        apiDelete("/api/rest-timer").andExpect(status().isNoContent()); // cancels only their own timer
        waitFor(() -> pushSender.sent().contains("https://push.example/mine"), Duration.ofSeconds(5));
    }

    @Test
    void subscribingFromAnotherAccountMovesThePhone() throws Exception {
        subscribe("https://push.example/shared", "key", "auth");
        java.util.UUID other = createUser("other@example.com");
        actingAs = "other@example.com";
        subscribe("https://push.example/shared", "key", "auth");
        assertThat(jdbc.queryForObject("select user_id from push_subscription", java.util.UUID.class)).isEqualTo(other);
    }

    @Test
    void unsubscribeRemovesOnlyTheCallersSubscription() throws Exception {
        subscribe("https://push.example/mine", "key", "auth");
        createUser("other@example.com");
        actingAs = "other@example.com";
        unsubscribe("https://push.example/mine");
        assertThat(jdbc.queryForObject("select count(*) from push_subscription", Integer.class)).isEqualTo(1);
        actingAs = com.gymtracker.TestUsers.EMAIL;
        unsubscribe("https://push.example/mine");
        unsubscribe("https://push.example/mine"); // already gone: still fine
        assertThat(jdbc.queryForObject("select count(*) from push_subscription", Integer.class)).isZero();
    }

    private void unsubscribe(String endpoint) throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/push/subscription")
                        .with(signedIn()).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"endpoint\": \"%s\"}".formatted(endpoint)))
                .andExpect(status().isNoContent());
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q test -Dtest=PushApiTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL to compile, because `cancelAll()` doesn't exist yet.

- [ ] **Step 3: Implement**

Replace the fields and methods of `RestTimerService` (keep `PAYLOAD`, `MAX_AHEAD` and the constructor) with:

```java
    // One pending alert per account. A generation per account makes an alert that was replaced or cancelled after
    // it already started firing a no-op.
    private final Map<UUID, ScheduledFuture<?>> pending = new HashMap<>();
    private final Map<UUID, Long> generations = new HashMap<>();

    public synchronized void schedule(UUID userId, Instant endsAt) {
        Instant now = clock.instant();
        if (!endsAt.isAfter(now) || endsAt.isAfter(now.plus(MAX_AHEAD))) {
            throw new BadRequestException("endsAt must be in the future and at most 1 hour away");
        }
        cancel(userId);
        long current = generations.merge(userId, 1L, Long::sum);
        pending.put(userId, scheduler.schedule(() -> fire(userId, current), endsAt));
    }

    public synchronized void cancel(UUID userId) {
        ScheduledFuture<?> future = pending.remove(userId);
        if (future != null) {
            future.cancel(false);
        }
        generations.merge(userId, 1L, Long::sum);
    }

    public synchronized void cancelAll() {
        pending.values().forEach(future -> future.cancel(false));
        pending.clear();
        generations.replaceAll((userId, generation) -> generation + 1);
    }

    private void fire(UUID userId, long firedGeneration) {
        synchronized (this) {
            if (generations.getOrDefault(userId, 0L) != firedGeneration) {
                return;
            }
            pending.remove(userId);
        }
        for (PushSubscription subscription : subscriptions.forUser(userId)) {
            if (sender.send(subscription, PAYLOAD) == PushSender.SendResult.GONE) {
                subscriptions.remove(subscription.getEndpoint());
            }
        }
    }
```

(imports: `java.util.HashMap`, `java.util.Map`, `java.util.UUID`.) Update the class comment to: `/** Holds each account's pending "rest is over" alert in memory; a restart loses them, which the spec accepts. */`

In `PushSubscriptionService`, replace `all()` with `forUser` and add `removeForUser`:

```java
    @Transactional(readOnly = true)
    public List<PushSubscription> forUser(UUID userId) {
        return repository.findByUserId(userId);
    }

    public void removeForUser(UUID userId, String endpoint) {
        repository.findById(endpoint).filter(s -> s.getUserId().equals(userId)).ifPresent(repository::delete);
    }
```

In `PushController`:
- add the record `record UnsubscribeRequest(@NotBlank @Size(max = 2048) String endpoint) {}`;
- add the endpoint below;
- change `schedule` and `cancel` to `restTimer.schedule(currentUser.id(), request.endsAt())` and `restTimer.cancel(currentUser.id())`.

```java
    @DeleteMapping("/push/subscription")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unsubscribe(@Valid @RequestBody UnsubscribeRequest request) {
        subscriptions.removeForUser(currentUser.id(), request.endpoint());
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q test -Dtest='PushApiTest,RestTimerE2ETest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A src
git commit -m "Rest-timer alerts per account; phones can unsubscribe

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Sending email through Brevo

**Files:**
- Create: `src/main/java/com/gymtracker/mail/MailSender.java`, `MailException.java`, `BrevoMailSender.java`, `LoggingMailSender.java`, `MailConfig.java`
- Modify: `src/main/resources/application.properties`
- Create: `src/test/java/com/gymtracker/mail/BrevoMailSenderTest.java`, `MailConfigTest.java`, `CapturingMailSender.java`, `TestMailConfig.java`
- Modify: `src/test/java/com/gymtracker/IntegrationTestBase.java`, `e2e/E2ETestBase.java`

**Interfaces:**
- Produces:
  - `com.gymtracker.mail.MailSender.send(String to, String subject, String text)`, which throws `MailException` when sending fails;
  - test helpers `CapturingMailSender.mails(): List<CapturingMailSender.Mail>` (record `Mail(String to, String subject, String text)`), `failNext()` and `reset()`;
  - `IntegrationTestBase.mail` and `E2ETestBase.mail` fields (`CapturingMailSender`).

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/gymtracker/mail/BrevoMailSenderTest.java`:

```java
package com.gymtracker.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BrevoMailSenderTest {

    private HttpServer server;
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> apiKey = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private volatile int status = 201;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v3/smtp/email", exchange -> {
            method.set(exchange.getRequestMethod());
            apiKey.set(exchange.getRequestHeaders().getFirst("api-key"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] answer = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private BrevoMailSender sender() {
        URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v3/smtp/email");
        return new BrevoMailSender(url, "test-key", "sender@example.com");
    }

    @Test
    void postsTheEmailToBrevo() {
        sender().send("someone@example.com", "Reset your Gym Tracker password", "Line 1\nhttps://x/#/reset?token=abc");
        assertThat(method.get()).isEqualTo("POST");
        assertThat(apiKey.get()).isEqualTo("test-key");
        assertThat(body.get())
                .contains("\"sender\":{")
                .contains("\"email\":\"sender@example.com\"")
                .contains("\"to\":[{\"email\":\"someone@example.com\"}]")
                .contains("\"subject\":\"Reset your Gym Tracker password\"")
                .contains("\"textContent\":\"Line 1\\nhttps://x/#/reset?token=abc\"");
    }

    @Test
    void failsWhenBrevoRefuses() {
        status = 401;
        assertThatThrownBy(() -> sender().send("someone@example.com", "s", "t"))
                .isInstanceOf(MailException.class)
                .hasMessageContaining("401");
    }
}
```

Create `src/test/java/com/gymtracker/mail/MailConfigTest.java`:

```java
package com.gymtracker.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MailConfigTest {

    private static final String URL = "https://api.brevo.com/v3/smtp/email";

    @Test
    void logsInsteadOfSendingWhenBrevoIsNotConfigured() {
        assertThat(new MailConfig().mailSender("", "sender@example.com", URL)).isInstanceOf(LoggingMailSender.class);
        assertThat(new MailConfig().mailSender("key", " ", URL)).isInstanceOf(LoggingMailSender.class);
    }

    @Test
    void usesBrevoWhenConfigured() {
        assertThat(new MailConfig().mailSender("key", "sender@example.com", URL)).isInstanceOf(BrevoMailSender.class);
    }
}
```

Create `src/test/java/com/gymtracker/mail/CapturingMailSender.java`:

```java
package com.gymtracker.mail;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Keeps sent emails in memory so tests can read reset links. */
public class CapturingMailSender implements MailSender {

    public record Mail(String to, String subject, String text) {
    }

    private final List<Mail> mails = new CopyOnWriteArrayList<>();
    private volatile boolean failNext;

    @Override
    public void send(String to, String subject, String text) {
        if (failNext) {
            failNext = false;
            throw new MailException("Simulated failure");
        }
        mails.add(new Mail(to, subject, text));
    }

    public List<Mail> mails() {
        return List.copyOf(mails);
    }

    public void failNext() {
        failNext = true;
    }

    public void reset() {
        mails.clear();
        failNext = false;
    }
}
```

Create `src/test/java/com/gymtracker/mail/TestMailConfig.java`:

```java
package com.gymtracker.mail;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class TestMailConfig {

    @Bean
    @Primary
    CapturingMailSender capturingMailSender() {
        return new CapturingMailSender();
    }
}
```

In `IntegrationTestBase` and `E2ETestBase`:
- add `TestMailConfig.class` to `@Import({...})`;
- add the field `@Autowired protected CapturingMailSender mail;`;
- call `mail.reset();` at the start of `cleanDatabase()` and `openPage()`, respectively.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q test -Dtest='BrevoMailSenderTest,MailConfigTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL to compile, because the package `com.gymtracker.mail` has no `MailSender` yet.

- [ ] **Step 3: Implement**

`src/main/java/com/gymtracker/mail/MailSender.java`:

```java
package com.gymtracker.mail;

public interface MailSender {

    /** Sends a plain-text email. Throws {@link MailException} when it could not be sent. */
    void send(String to, String subject, String text);
}
```

`src/main/java/com/gymtracker/mail/MailException.java`:

```java
package com.gymtracker.mail;

public class MailException extends RuntimeException {

    public MailException(String message) {
        super(message);
    }

    public MailException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

`src/main/java/com/gymtracker/mail/BrevoMailSender.java`:

```java
package com.gymtracker.mail;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** Sends through Brevo's transactional email API over HTTPS (no SMTP, so hosting port rules don't matter). */
class BrevoMailSender implements MailSender {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final URI endpoint;
    private final String apiKey;
    private final String from;

    BrevoMailSender(URI endpoint, String apiKey, String from) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.from = from;
    }

    @Override
    public void send(String to, String subject, String text) {
        String body = JSON.writeValueAsString(Map.of(
                "sender", Map.of("name", "Gym Tracker", "email", from),
                "to", List.of(Map.of("email", to)),
                "subject", subject,
                "textContent", text));
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(TIMEOUT)
                .header("api-key", apiKey)
                .header("accept", "application/json")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new MailException("Brevo answered " + response.statusCode() + ": " + response.body());
            }
        } catch (IOException e) {
            throw new MailException("Sending email failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MailException("Sending email was interrupted", e);
        }
    }
}
```

`src/main/java/com/gymtracker/mail/LoggingMailSender.java`:

```java
package com.gymtracker.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Used when Brevo isn't configured. Logs who would have got an email, never its content (it may hold a reset link). */
class LoggingMailSender implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingMailSender.class);

    @Override
    public void send(String to, String subject, String text) {
        log.warn("Email not configured (BREVO_API_KEY, MAIL_FROM): not sending '{}' to {}", subject, to);
    }
}
```

`src/main/java/com/gymtracker/mail/MailConfig.java`:

```java
package com.gymtracker.mail;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class MailConfig {

    @Bean
    MailSender mailSender(@Value("${app.mail.brevo-api-key}") String apiKey,
                          @Value("${app.mail.from}") String from,
                          @Value("${app.mail.brevo-url}") String url) {
        if (apiKey == null || apiKey.isBlank() || from == null || from.isBlank()) {
            return new LoggingMailSender();
        }
        return new BrevoMailSender(URI.create(url), apiKey.strip(), from.strip());
    }
}
```

Append to `application.properties`:

```properties
app.mail.brevo-api-key=${BREVO_API_KEY:}
app.mail.from=${MAIL_FROM:}
app.mail.brevo-url=https://api.brevo.com/v3/smtp/email
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q test -Dtest='BrevoMailSenderTest,MailConfigTest,SecurityIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. `SecurityIntegrationTest` proves the context starts with the capturing sender.

If `tools.jackson.databind.json.JsonMapper` doesn't resolve, use `ls ~/.m2/repository/tools/jackson/core/jackson-databind` to confirm Jackson 3 is on the classpath, then check the class name in that jar with `unzip -l`. Ledger the result.

- [ ] **Step 5: Commit**

```bash
git add -A src
git commit -m "Send email through Brevo's HTTPS API

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Registration with an invite code

**Files:**
- Create: `src/main/java/com/gymtracker/common/ForbiddenException.java`
- Modify: `common/GlobalExceptionHandler.java`
- Create: `src/main/java/com/gymtracker/account/AccountService.java`, `AuthController.java`, `PasswordChangeSignOutFilter.java`
- Modify: `src/main/java/com/gymtracker/security/SecurityConfig.java`
- Modify: `src/main/resources/application.properties`, `src/test/resources/application-test.properties`
- Create: `src/test/java/com/gymtracker/account/RegistrationApiTest.java`, `AccountServiceTest.java`

**Interfaces:**
- Consumes: `AppUser`, `AppUserRepository`, `Emails` (Task 1).
- Produces:
  - `POST /api/auth/register` with `{email, password, inviteCode}` → 201 `{"email"}`, signed in;
  - `AccountService.register(...)` and `AccountService.validatePassword(String)` (static, package-visible);
  - `PasswordChangeSignOutFilter.markSignedIn(HttpServletRequest, Instant)` (static);
  - `SecurityContextRepository` bean.

`PasswordChangeSignOutFilter` is created here because registration and login must mark when a session signed in. Task 6 adds the check that uses that mark.

- [ ] **Step 1: Write the failing tests**

Add to `application-test.properties`:

```properties
app.invite-code=test-invite
```

Create `src/test/java/com/gymtracker/account/RegistrationApiTest.java`:

```java
package com.gymtracker.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gymtracker.IntegrationTestBase;
import com.gymtracker.TestUsers;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

class RegistrationApiTest extends IntegrationTestBase {

    private ResultActions register(String email, String password, String inviteCode) throws Exception {
        return mvc.perform(post("/api/auth/register").with(xsrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s", "inviteCode": "%s"}""".formatted(email, password, inviteCode)));
    }

    private int accounts() {
        return jdbc.queryForObject("select count(*) from app_user", Integer.class);
    }

    @Test
    void registersAndSignsIn() throws Exception {
        MvcResult result = register("new@example.com", "long-enough", "test-invite")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("new@example.com"))
                .andReturn();
        Cookie rememberMe = result.getResponse().getCookie("remember-me");
        assertThat(rememberMe).isNotNull();
        mvc.perform(get("/api/me").cookie(rememberMe)).andExpect(jsonPath("$.email").value("new@example.com"));
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        mvc.perform(get("/api/me").session(session)).andExpect(jsonPath("$.email").value("new@example.com"));
        assertThat(jdbc.queryForObject("select password_hash from app_user where email = 'new@example.com'", String.class))
                .startsWith("$2").doesNotContain("long-enough");
    }

    @Test
    void storesTheEmailTrimmedAndLowercased() throws Exception {
        register("  New@Example.COM ", "long-enough", "test-invite").andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("select email from app_user where email like 'new%'", String.class))
                .isEqualTo("new@example.com");
    }

    @Test
    void newAccountStartsEmpty() throws Exception {
        createExercise("Squat", "QUADS"); // the tester's
        MvcResult result = register("new@example.com", "long-enough", "test-invite").andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        mvc.perform(get("/api/exercises").session(session)).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void wrongInviteCodeIsRefused() throws Exception {
        register("new@example.com", "long-enough", "guess")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Wrong invite code"));
        assertThat(accounts()).isEqualTo(1);
    }

    @Test
    void emailThatIsTakenIgnoringCaseIsRefused() throws Exception {
        register("TESTER@example.com", "long-enough", "test-invite")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("An account with this email already exists"));
    }

    @Test
    void passwordMustBe8To72Characters() throws Exception {
        register("new@example.com", "short", "test-invite")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Use at least 8 characters for your password"));
        register("new@example.com", "a".repeat(73), "test-invite")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Use at most 72 characters for your password"));
        assertThat(accounts()).isEqualTo(1);
    }

    @Test
    void emailMustLookLikeAnEmail() throws Exception {
        register("not-an-email", "long-enough", "test-invite")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Enter a valid email address"));
    }

    @Test
    void registerNeedsTheCsrfToken() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"new@example.com\", \"password\": \"long-enough\", \"inviteCode\": \"test-invite\"}"))
                .andExpect(status().isForbidden());
        assertThat(accounts()).isEqualTo(1);
    }

    @Test
    void testerCanStillSignIn() throws Exception {
        mvc.perform(post("/login").with(xsrf()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", TestUsers.EMAIL).param("password", TestUsers.PASSWORD))
                .andExpect(status().isOk());
    }
}
```

Create `src/test/java/com/gymtracker/account/AccountServiceTest.java`:

```java
package com.gymtracker.account;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gymtracker.common.ForbiddenException;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class AccountServiceTest {

    @Test
    void registrationIsClosedWithoutAnInviteCode() {
        AccountService service = new AccountService(null, null, Clock.systemUTC(), "  ");
        assertThatThrownBy(() -> service.register("new@example.com", "long-enough", ""))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Registration is closed");
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q test -Dtest='RegistrationApiTest,AccountServiceTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL to compile, because `AccountService` and `ForbiddenException` don't exist yet.

- [ ] **Step 3: Implement**

`src/main/java/com/gymtracker/common/ForbiddenException.java`:

```java
package com.gymtracker.common;

public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
```

In `GlobalExceptionHandler`, add:

```java
    @ExceptionHandler(ForbiddenException.class)
    ResponseEntity<ApiError> forbidden(ForbiddenException e) {
        return error(HttpStatus.FORBIDDEN, e.getMessage());
    }
```

`src/main/java/com/gymtracker/account/AccountService.java`:

```java
package com.gymtracker.account;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.gymtracker.common.BadRequestException;
import com.gymtracker.common.ConflictException;
import com.gymtracker.common.ForbiddenException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AccountService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MAX_EMAIL = 254;
    private static final int MIN_PASSWORD_CHARS = 8;
    private static final int MAX_PASSWORD_BYTES = 72; // BCrypt ignores everything after 72 bytes

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final String inviteCode;

    public AccountService(AppUserRepository users, PasswordEncoder encoder, Clock clock,
                          @Value("${app.invite-code}") String inviteCode) {
        this.users = users;
        this.encoder = encoder;
        this.clock = clock;
        this.inviteCode = inviteCode == null ? "" : inviteCode.strip();
    }

    public AppUser register(String rawEmail, String password, String code) {
        if (inviteCode.isEmpty()) {
            throw new ForbiddenException("Registration is closed");
        }
        String given = code == null ? "" : code.strip();
        if (!MessageDigest.isEqual(given.getBytes(UTF_8), inviteCode.getBytes(UTF_8))) {
            throw new ForbiddenException("Wrong invite code");
        }
        String email = Emails.normalize(rawEmail);
        if (email.length() > MAX_EMAIL || !EMAIL.matcher(email).matches()) {
            throw new BadRequestException("Enter a valid email address");
        }
        validatePassword(password);
        if (users.findByEmail(email).isPresent()) {
            throw new ConflictException("An account with this email already exists");
        }
        return users.save(new AppUser(UUID.randomUUID(), email, encoder.encode(password), clock.instant()));
    }

    static void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_CHARS) {
            throw new BadRequestException("Use at least 8 characters for your password");
        }
        if (password.getBytes(UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new BadRequestException("Use at most 72 characters for your password");
        }
    }
}
```

`src/main/java/com/gymtracker/account/PasswordChangeSignOutFilter.java` (Task 6 adds the check; for now it only records the sign-in moment):

```java
package com.gymtracker.account;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;

/**
 * Ends server sessions that signed in before the account's last password change, so a password reset signs out
 * every device. Each session records when it signed in.
 */
public class PasswordChangeSignOutFilter {

    static final String SIGNED_IN_AT = PasswordChangeSignOutFilter.class.getName() + ".signedInAt";

    /** Call right after a sign-in that created or changed the session. */
    public static void markSignedIn(HttpServletRequest request, Instant at) {
        request.getSession().setAttribute(SIGNED_IN_AT, at);
    }
}
```

`src/main/java/com/gymtracker/account/AuthController.java`:

```java
package com.gymtracker.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
class AuthController {

    record RegisterRequest(String email, String password, String inviteCode) {
    }

    private final AccountService accounts;
    private final UserDetailsService userDetails;
    private final RememberMeServices rememberMe;
    private final SecurityContextRepository contextRepository;
    private final Clock clock;

    AuthController(AccountService accounts, UserDetailsService userDetails, RememberMeServices rememberMe,
                   SecurityContextRepository contextRepository, Clock clock) {
        this.accounts = accounts;
        this.userDetails = userDetails;
        this.rememberMe = rememberMe;
        this.contextRepository = contextRepository;
        this.clock = clock;
    }

    /** Creates the account and signs it in exactly like a successful POST /login would. */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, String> register(@RequestBody RegisterRequest body, HttpServletRequest request,
                                 HttpServletResponse response) {
        AppUser user = accounts.register(body.email(), body.password(), body.inviteCode());
        UserDetails details = userDetails.loadUserByUsername(user.getEmail());
        Authentication auth = UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities());
        if (request.getSession(false) != null) {
            request.changeSessionId(); // session fixation protection, as form login does
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        PasswordChangeSignOutFilter.markSignedIn(request, clock.instant());
        rememberMe.loginSuccess(request, response, auth);
        return Map.of("email", user.getEmail());
    }
}
```

In `SecurityConfig`:
1. Add a bean that both form login and registration save the signed-in user through:

```java
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(), new HttpSessionSecurityContextRepository());
    }
```

(imports `org.springframework.security.web.context.*` for the three classes and `SecurityContextRepository`.)

2. Change the `securityFilterChain` signature to also take `SecurityContextRepository securityContextRepository` and `Clock clock` (`java.time.Clock`), and in the chain:
   - make `/api/auth/**` public before the `/api/**` rule:

```java
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
```

   - make the form login's success handler record the sign-in moment:

```java
                        .successHandler((request, response, authentication) -> {
                            PasswordChangeSignOutFilter.markSignedIn(request, clock.instant());
                            response.setStatus(200);
                        })
```

(import `com.gymtracker.account.PasswordChangeSignOutFilter`.)

Add to `application.properties`:

```properties
app.invite-code=${INVITE_CODE:}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q test -Dtest='RegistrationApiTest,AccountServiceTest,SecurityIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A src
git commit -m "Register an account with an invite code

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Forgot password, reset link, and signing out every device

**Files:**
- Create: `src/main/java/com/gymtracker/account/PasswordResetToken.java`, `PasswordResetTokenRepository.java`, `PasswordResetService.java`
- Modify: `account/PasswordChangeSignOutFilter.java`, `account/AuthController.java`, `security/SecurityConfig.java`
- Modify: `src/main/resources/application.properties`, `src/test/resources/application-test.properties`
- Create: `src/test/java/com/gymtracker/account/PasswordResetApiTest.java`, `PasswordResetServiceTest.java`

**Interfaces:**
- Consumes: `MailSender` (Task 4), `AccountService.validatePassword`, `PasswordChangeSignOutFilter.markSignedIn` (Task 5), `PersistentTokenRepository` bean (existing).
- Produces:
  - `POST /api/auth/forgot` with `{email}` → 200 `{"message"}`;
  - `POST /api/auth/reset` with `{token, password}` → 200 `{"email"}`;
  - `PasswordChangeSignOutFilter` becomes a `OncePerRequestFilter` that returns 401 `{"message":"Signed out"}` for stale sessions.

- [ ] **Step 1: Write the failing tests**

Add to `application-test.properties`:

```properties
app.base-url=http://localhost:8080
```

Create `src/test/java/com/gymtracker/account/PasswordResetApiTest.java`:

```java
package com.gymtracker.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gymtracker.IntegrationTestBase;
import com.gymtracker.TestUsers;
import jakarta.servlet.http.Cookie;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

class PasswordResetApiTest extends IntegrationTestBase {

    private static final String SENT = "If an account exists for this email, we've sent a reset link.";
    private static final String EXPIRED = "This link has expired. Request a new one.";
    private static final Pattern LINK = Pattern.compile("(http://localhost:8080/#/reset\\?token=)([A-Za-z0-9_-]+)");

    private ResultActions forgot(String email) throws Exception {
        return mvc.perform(post("/api/auth/forgot").with(xsrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"%s\"}".formatted(email)));
    }

    private ResultActions reset(String token, String password) throws Exception {
        return mvc.perform(post("/api/auth/reset").with(xsrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\": \"%s\", \"password\": \"%s\"}".formatted(token, password)));
    }

    private ResultActions login(String password) throws Exception {
        return mvc.perform(post("/login").with(xsrf()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("username", TestUsers.EMAIL).param("password", password));
    }

    private String lastToken() {
        Matcher matcher = LINK.matcher(mail.mails().getLast().text());
        assertThat(matcher.find()).as("reset link in the email").isTrue();
        return matcher.group(2);
    }

    @Test
    void emailedLinkSetsANewPassword() throws Exception {
        forgot(" Tester@Example.com ").andExpect(status().isOk()).andExpect(jsonPath("$.message").value(SENT));
        assertThat(mail.mails()).hasSize(1);
        assertThat(mail.mails().getFirst().to()).isEqualTo(TestUsers.EMAIL);
        assertThat(mail.mails().getFirst().subject()).isEqualTo("Reset your Gym Tracker password");
        assertThat(mail.mails().getFirst().text()).contains("expires in 1 hour");

        reset(lastToken(), "brand-new-pass").andExpect(status().isOk()).andExpect(jsonPath("$.email").value(TestUsers.EMAIL));
        login("brand-new-pass").andExpect(status().isOk());
        login(TestUsers.PASSWORD).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select token_hash from password_reset_token", String.class))
                .hasSize(64).doesNotContain(lastToken());
    }

    @Test
    void unknownEmailGetsTheSameAnswerAndNoEmail() throws Exception {
        String known = forgot(TestUsers.EMAIL).andReturn().getResponse().getContentAsString();
        String unknown = forgot("nobody@example.com").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(unknown).isEqualTo(known);
        assertThat(mail.mails()).hasSize(1);
    }

    @Test
    void linkWorksOnce() throws Exception {
        forgot(TestUsers.EMAIL);
        String token = lastToken();
        reset(token, "brand-new-pass").andExpect(status().isOk());
        reset(token, "another-pass-1").andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(EXPIRED));
    }

    @Test
    void expiredLinkIsRefused() throws Exception {
        forgot(TestUsers.EMAIL);
        jdbc.update("update password_reset_token set expires_at = now() - interval '1 minute'");
        reset(lastToken(), "brand-new-pass").andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(EXPIRED));
    }

    @Test
    void unknownTokenIsRefused() throws Exception {
        reset("made-up-token", "brand-new-pass").andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(EXPIRED));
    }

    @Test
    void usingOneLinkCancelsTheOthers() throws Exception {
        forgot(TestUsers.EMAIL);
        String first = lastToken();
        forgot(TestUsers.EMAIL);
        reset(lastToken(), "brand-new-pass").andExpect(status().isOk());
        reset(first, "another-pass-1").andExpect(status().isBadRequest());
    }

    @Test
    void tooShortPasswordKeepsTheLinkUsable() throws Exception {
        forgot(TestUsers.EMAIL);
        reset(lastToken(), "short").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Use at least 8 characters for your password"));
        reset(lastToken(), "brand-new-pass").andExpect(status().isOk());
    }

    @Test
    void atMostThreeEmailsPerHour() throws Exception {
        for (int i = 0; i < 4; i++) {
            forgot(TestUsers.EMAIL).andExpect(status().isOk()).andExpect(jsonPath("$.message").value(SENT));
        }
        assertThat(mail.mails()).hasSize(3);
    }

    @Test
    void failedEmailGivesTheSameAnswer() throws Exception {
        mail.failNext();
        forgot(TestUsers.EMAIL).andExpect(status().isOk()).andExpect(jsonPath("$.message").value(SENT));
    }

    @Test
    void resetSignsOutEveryDevice() throws Exception {
        MvcResult phone = login(TestUsers.PASSWORD).andReturn();
        Cookie rememberMe = phone.getResponse().getCookie("remember-me");
        MockHttpSession session = (MockHttpSession) phone.getRequest().getSession(false);
        mvc.perform(get("/api/me").session(session)).andExpect(status().isOk());

        forgot(TestUsers.EMAIL);
        reset(lastToken(), "brand-new-pass").andExpect(status().isOk());

        mvc.perform(get("/api/me").session(session)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me").cookie(rememberMe)).andExpect(status().isUnauthorized());
        MvcResult again = login("brand-new-pass").andExpect(status().isOk()).andReturn();
        mvc.perform(get("/api/me").session((MockHttpSession) again.getRequest().getSession(false)))
                .andExpect(status().isOk());
    }
}
```

Create `src/test/java/com/gymtracker/account/PasswordResetServiceTest.java`:

```java
package com.gymtracker.account;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import org.junit.jupiter.api.Test;

class PasswordResetServiceTest {

    @Test
    void refusesToStartWithoutBaseUrl() {
        assertThatThrownBy(() -> new PasswordResetService(null, null, null, null, null, Clock.systemUTC(), " "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_BASE_URL");
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q test -Dtest='PasswordResetApiTest,PasswordResetServiceTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL to compile, because `PasswordResetService` doesn't exist yet.

- [ ] **Step 3: Implement the token, its repository and the service**

`src/main/java/com/gymtracker/account/PasswordResetToken.java`:

```java
package com.gymtracker.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "password_reset_token")
public class PasswordResetToken {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    protected PasswordResetToken() {
    }

    public PasswordResetToken(UUID id, UUID userId, String tokenHash, Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public boolean isUsable(Instant now) {
        return usedAt == null && now.isBefore(expiresAt);
    }

    public UUID getUserId() {
        return userId;
    }
}
```

`src/main/java/com/gymtracker/account/PasswordResetTokenRepository.java`:

```java
package com.gymtracker.account;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    long countByUserIdAndCreatedAtAfter(UUID userId, Instant since);

    @Modifying
    @Query("update PasswordResetToken t set t.usedAt = :now where t.userId = :userId and t.usedAt is null")
    void useAllOpen(UUID userId, Instant now);
}
```

`src/main/java/com/gymtracker/account/PasswordResetService.java`:

```java
package com.gymtracker.account;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.gymtracker.common.BadRequestException;
import com.gymtracker.mail.MailSender;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PasswordResetService {

    static final String SUBJECT = "Reset your Gym Tracker password";
    static final String EXPIRED = "This link has expired. Request a new one.";
    private static final Duration VALIDITY = Duration.ofHours(1);
    private static final int MAX_EMAILS_PER_HOUR = 3;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final AppUserRepository users;
    private final PasswordResetTokenRepository tokens;
    private final PersistentTokenRepository rememberMeTokens;
    private final PasswordEncoder encoder;
    private final MailSender mail;
    private final Clock clock;
    private final String baseUrl;

    public PasswordResetService(AppUserRepository users, PasswordResetTokenRepository tokens,
                                PersistentTokenRepository rememberMeTokens, PasswordEncoder encoder, MailSender mail,
                                Clock clock, @Value("${app.base-url}") String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("APP_BASE_URL must be set (the app's public URL, used in reset links)");
        }
        this.users = users;
        this.tokens = tokens;
        this.rememberMeTokens = rememberMeTokens;
        this.encoder = encoder;
        this.mail = mail;
        this.clock = clock;
        this.baseUrl = baseUrl.strip().replaceAll("/+$", "");
    }

    /** Emails a reset link when the account exists. Callers answer the same way whatever happens here. */
    public void requestReset(String rawEmail) {
        Optional<AppUser> found = users.findByEmail(Emails.normalize(rawEmail));
        if (found.isEmpty()) {
            return;
        }
        AppUser user = found.get();
        Instant now = clock.instant();
        if (tokens.countByUserIdAndCreatedAtAfter(user.getId(), now.minus(Duration.ofHours(1))) >= MAX_EMAILS_PER_HOUR) {
            log.info("Reset email limit reached for an account");
            return;
        }
        String token = newToken();
        tokens.save(new PasswordResetToken(UUID.randomUUID(), user.getId(), sha256(token), now, now.plus(VALIDITY)));
        try {
            mail.send(user.getEmail(), SUBJECT, """
                    Someone asked to reset the password for your Gym Tracker account.

                    Choose a new password here:
                    %s/#/reset?token=%s

                    This link works once and expires in 1 hour. If you didn't ask for this, ignore this email.
                    """.formatted(baseUrl, token));
        } catch (RuntimeException e) {
            log.warn("Sending the reset email failed: {}", e.getMessage()); // never log the token
        }
    }

    /** Sets the new password and signs the account out everywhere. Returns the account's email. */
    public String reset(String token, String newPassword) {
        Instant now = clock.instant();
        PasswordResetToken found = tokens.findByTokenHash(sha256(token == null ? "" : token))
                .filter(t -> t.isUsable(now))
                .orElseThrow(() -> new BadRequestException(EXPIRED));
        AccountService.validatePassword(newPassword); // before using the link, so a too-short password doesn't burn it
        AppUser user = users.findById(found.getUserId()).orElseThrow(() -> new BadRequestException(EXPIRED));
        user.changePassword(encoder.encode(newPassword), now);
        tokens.useAllOpen(user.getId(), now);
        rememberMeTokens.removeUserTokens(user.getEmail());
        return user.getEmail();
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 4: Add the endpoints**

In `AuthController`:
- add a `PasswordResetService resets` constructor parameter and field;
- add the two records and two endpoints below.

```java
    record ForgotRequest(String email) {
    }

    record ResetRequest(String token, String password) {
    }

    static final String FORGOT_MESSAGE = "If an account exists for this email, we've sent a reset link.";

    @PostMapping("/forgot")
    Map<String, String> forgot(@RequestBody ForgotRequest body) {
        resets.requestReset(body.email());
        return Map.of("message", FORGOT_MESSAGE);
    }

    @PostMapping("/reset")
    Map<String, String> reset(@RequestBody ResetRequest body) {
        return Map.of("email", resets.reset(body.token(), body.password()));
    }
```

- [ ] **Step 5: End sessions that started before the password change**

Replace `PasswordChangeSignOutFilter` with the full filter. Keep `SIGNED_IN_AT` and `markSignedIn`.

```java
package com.gymtracker.account;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ends server sessions that signed in before the account's last password change, so a password reset signs out
 * every device. Each session records when it signed in; sessions from a remember-me login are marked on their
 * first request, which is the moment that login happened.
 */
public class PasswordChangeSignOutFilter extends OncePerRequestFilter {

    static final String SIGNED_IN_AT = PasswordChangeSignOutFilter.class.getName() + ".signedInAt";

    private final AppUserRepository users;
    private final Clock clock;

    public PasswordChangeSignOutFilter(AppUserRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    /** Call right after a sign-in that created or changed the session. */
    public static void markSignedIn(HttpServletRequest request, Instant at) {
        request.getSession().setAttribute(SIGNED_IN_AT, at);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        HttpSession session = request.getSession(false);
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken || session == null) {
            chain.doFilter(request, response);
            return;
        }
        Instant signedInAt = (Instant) session.getAttribute(SIGNED_IN_AT);
        if (signedInAt == null) {
            session.setAttribute(SIGNED_IN_AT, clock.instant());
            chain.doFilter(request, response);
            return;
        }
        boolean stale = users.findByEmail(auth.getName())
                .map(user -> user.getPasswordChangedAt().isAfter(signedInAt))
                .orElse(true);
        if (stale) {
            session.invalidate();
            SecurityContextHolder.clearContext();
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"message\":\"Signed out\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
```

In `SecurityConfig.securityFilterChain`:
- add the parameter `AppUserRepository users`;
- register the filter after remember-me (import `org.springframework.security.web.authentication.rememberme.RememberMeAuthenticationFilter` and `com.gymtracker.account.AppUserRepository`).

The filter is created with `new`, never as a `@Component`, so it only runs inside the security chain.

```java
                .addFilterAfter(new PasswordChangeSignOutFilter(users, clock), RememberMeAuthenticationFilter.class)
```

Add to `application.properties`:

```properties
app.base-url=${APP_BASE_URL:}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw -q test -Dtest='PasswordResetApiTest,PasswordResetServiceTest,RegistrationApiTest,SecurityIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A src
git commit -m "Reset a forgotten password by email; a reset signs out every device

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 7: Limit sign-in, register and forgot-password attempts

**Files:**
- Create: `src/main/java/com/gymtracker/security/AttemptLimiter.java`, `RateLimitFilter.java`
- Modify: `src/main/java/com/gymtracker/security/SecurityConfig.java`
- Modify: `src/test/java/com/gymtracker/IntegrationTestBase.java`, `e2e/E2ETestBase.java`
- Create: `src/test/java/com/gymtracker/security/AttemptLimiterTest.java`, `RateLimitApiTest.java`

**Interfaces:**
- Produces:
  - `AttemptLimiter(int max, Duration window, Clock clock)` with `tryAcquire(String key): boolean` and `reset()`, as a Spring bean;
  - 429 `{"message":"Too many attempts. Try again later."}`.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/gymtracker/security/AttemptLimiterTest.java`:

```java
package com.gymtracker.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AttemptLimiterTest {

    /** A clock the test moves forward by hand. */
    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-06T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void allowsMaxAttemptsPerWindowAndPerKey() {
        TestClock clock = new TestClock();
        AttemptLimiter limiter = new AttemptLimiter(3, Duration.ofMinutes(15), clock);
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("login|1.2.3.4")).isTrue();
        }
        assertThat(limiter.tryAcquire("login|1.2.3.4")).isFalse();
        assertThat(limiter.tryAcquire("login|5.6.7.8")).isTrue();
        assertThat(limiter.tryAcquire("forgot|1.2.3.4")).isTrue();
    }

    @Test
    void attemptsExpireAfterTheWindow() {
        TestClock clock = new TestClock();
        AttemptLimiter limiter = new AttemptLimiter(1, Duration.ofMinutes(15), clock);
        assertThat(limiter.tryAcquire("k")).isTrue();
        clock.now = clock.now.plus(Duration.ofMinutes(14));
        assertThat(limiter.tryAcquire("k")).isFalse();
        clock.now = clock.now.plus(Duration.ofMinutes(2));
        assertThat(limiter.tryAcquire("k")).isTrue();
    }
}
```

`src/test/java/com/gymtracker/security/RateLimitApiTest.java`:

```java
package com.gymtracker.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gymtracker.IntegrationTestBase;
import com.gymtracker.TestUsers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class RateLimitApiTest extends IntegrationTestBase {

    private static final String TOO_MANY = "Too many attempts. Try again later.";

    private ResultActions wrongLogin() throws Exception {
        return mvc.perform(post("/login").with(xsrf()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("username", TestUsers.EMAIL).param("password", "guess"));
    }

    private ResultActions forgot() throws Exception {
        return mvc.perform(post("/api/auth/forgot").with(xsrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"nobody@example.com\"}"));
    }

    @Test
    void eleventhLoginWithin15MinutesIsRefused() throws Exception {
        for (int i = 0; i < 10; i++) {
            wrongLogin().andExpect(status().isUnauthorized());
        }
        wrongLogin().andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.message").value(TOO_MANY));
        forgot().andExpect(status().isOk()); // counted separately
    }

    @Test
    void eleventhForgotRequestIsRefused() throws Exception {
        for (int i = 0; i < 10; i++) {
            forgot().andExpect(status().isOk());
        }
        forgot().andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.message").value(TOO_MANY));
    }

    @Test
    void eleventhRegistrationIsRefused() throws Exception {
        for (int i = 0; i < 11; i++) {
            ResultActions result = mvc.perform(post("/api/auth/register").with(xsrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\": \"x@example.com\", \"password\": \"long-enough\", \"inviteCode\": \"guess\"}"));
            result.andExpect(i < 10 ? status().isForbidden() : status().isTooManyRequests());
        }
    }
}
```

In `IntegrationTestBase` and `E2ETestBase`:
- add `@Autowired protected com.gymtracker.security.AttemptLimiter attemptLimiter;`;
- call `attemptLimiter.reset();` at the start of `cleanDatabase()` / `openPage()`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q test -Dtest='AttemptLimiterTest,RateLimitApiTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL to compile, because `AttemptLimiter` doesn't exist yet.

- [ ] **Step 3: Implement**

`src/main/java/com/gymtracker/security/AttemptLimiter.java`:

```java
package com.gymtracker.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/** At most {@code max} attempts per key in any sliding {@code window}. In memory: fine for one instance. */
public class AttemptLimiter {

    private final int max;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Deque<Instant>> attempts = new HashMap<>();

    public AttemptLimiter(int max, Duration window, Clock clock) {
        this.max = max;
        this.window = window;
        this.clock = clock;
    }

    public synchronized boolean tryAcquire(String key) {
        Instant now = clock.instant();
        Instant cutoff = now.minus(window);
        attempts.values().removeIf(times -> {
            while (!times.isEmpty() && !times.peekFirst().isAfter(cutoff)) {
                times.pollFirst();
            }
            return times.isEmpty();
        });
        Deque<Instant> times = attempts.computeIfAbsent(key, k -> new ArrayDeque<>());
        if (times.size() >= max) {
            return false;
        }
        times.addLast(now);
        return true;
    }

    public synchronized void reset() {
        attempts.clear();
    }
}
```

`src/main/java/com/gymtracker/security/RateLimitFilter.java`:

```java
package com.gymtracker.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Stops password and invite-code guessing. The client IP comes from getRemoteAddr(), which reflects the proxy's
 * X-Forwarded-For because server.forward-headers-strategy=framework.
 */
final class RateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> LIMITED = Set.of("/login", "/api/auth/register", "/api/auth/forgot");

    private final AttemptLimiter limiter;

    RateLimitFilter(AttemptLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !LIMITED.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!limiter.tryAcquire(request.getRequestURI() + "|" + request.getRemoteAddr())) {
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("{\"message\":\"Too many attempts. Try again later.\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
```

In `SecurityConfig`:
- add the bean below;
- add an `AttemptLimiter attemptLimiter` parameter to `securityFilterChain`;
- register the filter before the form-login filter, with `.addFilterBefore(new RateLimitFilter(attemptLimiter), UsernamePasswordAuthenticationFilter.class)`. Import `org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter`, `java.time.Duration`.

```java
    @Bean
    AttemptLimiter attemptLimiter(Clock clock) {
        return new AttemptLimiter(10, Duration.ofMinutes(15), clock);
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q test -Dtest='AttemptLimiterTest,RateLimitApiTest,SecurityIntegrationTest,PasswordResetApiTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A src
git commit -m "Limit sign-in, register and forgot-password attempts per IP

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Log out on the server

**Files:**
- Create: `src/main/java/com/gymtracker/security/DeviceRememberMeServices.java`
- Modify: `src/main/java/com/gymtracker/security/SecurityConfig.java`
- Create: `src/test/java/com/gymtracker/security/LogoutApiTest.java`

**Interfaces:**
- Produces: `POST /logout` (CSRF protected) → 204. It deletes this device's remember-me token, ends the session and clears the cookies.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/gymtracker/security/LogoutApiTest.java`:

```java
package com.gymtracker.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gymtracker.IntegrationTestBase;
import com.gymtracker.TestUsers;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

class LogoutApiTest extends IntegrationTestBase {

    private MvcResult login() throws Exception {
        return mvc.perform(post("/login").with(xsrf()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("username", TestUsers.EMAIL).param("password", TestUsers.PASSWORD)).andReturn();
    }

    @Test
    void logoutSignsOutOnlyThisDevice() throws Exception {
        Cookie phone = login().getResponse().getCookie("remember-me");
        Cookie laptop = login().getResponse().getCookie("remember-me");

        MvcResult logout = mvc.perform(post("/logout").with(xsrf()).cookie(phone))
                .andExpect(status().isNoContent()).andReturn();
        assertThat(logout.getResponse().getCookie("remember-me").getMaxAge()).isZero();

        mvc.perform(get("/api/me").cookie(phone)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me").cookie(laptop)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from persistent_logins", Integer.class)).isEqualTo(1);
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        MockHttpSession session = (MockHttpSession) login().getRequest().getSession(false);
        mvc.perform(post("/logout").with(xsrf()).session(session)).andExpect(status().isNoContent());
        mvc.perform(get("/api/me").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutNeedsTheCsrfToken() throws Exception {
        mvc.perform(post("/logout")).andExpect(status().isForbidden());
    }

    @Test
    void logoutWhenAlreadySignedOutIsFine() throws Exception {
        mvc.perform(post("/logout").with(xsrf())).andExpect(status().isNoContent());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q test -Dtest=LogoutApiTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL.
- `logoutSignsOutOnlyThisDevice` fails because Spring's default logout answers 302 (a redirect to `/login?logout`) instead of 204.
- Spring's default also deletes every token of the account, so the laptop check fails as well.

- [ ] **Step 3: Implement**

`src/main/java/com/gymtracker/security/DeviceRememberMeServices.java`:

```java
package com.gymtracker.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.rememberme.InvalidCookieException;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;

/**
 * Logging out removes only this device's token. Spring's default removes every token of the account, which would
 * also sign out the user's other devices. Works from the cookie, so it also runs when the session had expired.
 */
final class DeviceRememberMeServices extends PersistentTokenBasedRememberMeServices {

    private final JdbcTemplate jdbc;

    DeviceRememberMeServices(String key, UserDetailsService userDetailsService,
                             PersistentTokenRepository tokenRepository, JdbcTemplate jdbc) {
        super(key, userDetailsService, tokenRepository);
        this.jdbc = jdbc;
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        cancelCookie(request, response);
        String cookie = extractRememberMeCookie(request);
        if (cookie == null || cookie.isEmpty()) {
            return;
        }
        try {
            String[] parts = decodeCookie(cookie);
            if (parts.length == 2) {
                jdbc.update("delete from persistent_logins where series = ?", parts[0]);
            }
        } catch (InvalidCookieException ignored) {
            // not a cookie we issued: nothing to delete
        }
    }
}
```

In `SecurityConfig`:
1. In the `rememberMeServices` bean:
   - add a `JdbcTemplate jdbc` parameter (`org.springframework.jdbc.core.JdbcTemplate`);
   - build `new DeviceRememberMeServices(requireRememberMeKey(key), userDetailsService, tokenRepository, jdbc)` instead of `new PersistentTokenBasedRememberMeServices(...)`;
   - keep the three setters.

   The bean's return type stays `RememberMeServices`. Spring's remember-me configurer registers it as a logout handler because it implements `LogoutHandler`.

2. In the chain, add after `.rememberMe(...)`:

```java
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q test -Dtest='LogoutApiTest,SecurityIntegrationTest,PasswordResetApiTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A src
git commit -m "Log out signs out this device only and answers 204

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Sign-in, create-account, forgot and reset screens

**Files:**
- Create: `src/main/resources/static/js/views/auth.js`, `src/main/resources/static/js/account.js`
- Delete: `src/main/resources/static/js/views/login.js`
- Modify: `src/main/resources/static/js/api.js`, `store.js`, `sync.js`, `app.js`, `src/main/resources/static/sw.js`, `src/main/resources/static/styles.css`
- Modify: `src/test/resources/static/test/unit.html`
- Create: `src/test/java/com/gymtracker/e2e/AccountsE2ETest.java`

**Interfaces:**
- Consumes:
  - `POST /api/auth/register`, `/api/auth/forgot`, `/api/auth/reset` (Tasks 5–6);
  - `GET /api/me` → `{email}` (Task 1);
  - 429 messages (Task 7).
- Produces:
  - `account.js`: `accountEmail(): string|null`, `useAccount(email)` (wipes local data when the email differs from the stored tag) and `forgetAccount()`;
  - `store.reset()`;
  - `views/auth.js`: `renderAuth(container, { mode, token, notice, email, onSuccess })`, where `onSuccess(email)` opens the app;
  - `api.js` 403 results now carry `message`.

- [ ] **Step 1: Write the failing tests**

In `unit.html`, add the import below next to the others. Then add the test before `window.__results = results;`.

```js
  import { accountEmail, useAccount, forgetAccount } from '/js/account.js';
```

```js
  test('useAccount keeps the same account's data and wipes another account's', () => {
    forgetAccount();
    useAccount('a@example.com');
    store.dispatch('exercise.put', { id: 'e9', name: 'Plank', muscleGroup: 'CORE' });
    useAccount('a@example.com');
    eq([store.pending(), store.view().exercises.length], [1, 1]);
    useAccount('b@example.com');
    eq([store.pending(), store.view().exercises.length, accountEmail()], [0, 0, 'b@example.com']);
    forgetAccount();
    eq([accountEmail(), store.pending()], [null, 0]);
  });
```

Create `src/test/java/com/gymtracker/e2e/AccountsE2ETest.java`:

```java
package com.gymtracker.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.gymtracker.TestUsers;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class AccountsE2ETest extends E2ETestBase {

    private void fillPassword(String label, String value) {
        page.getByLabel(label, new Page.GetByLabelOptions().setExact(true)).fill(value);
    }

    @Test
    void createsAnAccountWithTheInviteCode() {
        page.navigate("/");
        button("Create account").click();
        page.getByLabel("Email").fill("new@example.com");
        fillPassword("Password", "long-enough");
        page.getByLabel("Invite code").fill("test-invite");
        button("Create account").click();
        assertThat(page.locator("#tabs")).isVisible();
        waitUntilSynced();
        Assertions.assertThat(count("select count(*) from app_user where email = 'new@example.com'")).isEqualTo(1);
    }

    @Test
    void wrongInviteCodeShowsTheServersMessage() {
        page.navigate("/");
        button("Create account").click();
        page.getByLabel("Email").fill("new@example.com");
        fillPassword("Password", "long-enough");
        page.getByLabel("Invite code").fill("guess");
        button("Create account").click();
        assertThat(page.getByText("Wrong invite code")).isVisible();
    }

    @Test
    void resetsAForgottenPasswordFromTheEmailLink() {
        page.navigate("/");
        button("Forgot password?").click();
        page.getByLabel("Email").fill(TestUsers.EMAIL);
        button("Send reset link").click();
        assertThat(page.getByText("If an account exists for this email")).isVisible();

        Matcher link = Pattern.compile("#/reset\\?token=([A-Za-z0-9_-]+)").matcher(mail.mails().getLast().text());
        Assertions.assertThat(link.find()).isTrue();
        page.navigate("about:blank");
        page.navigate("/#/reset?token=" + link.group(1));
        fillPassword("New password", "brand-new-pass");
        button("Change password").click();

        assertThat(page.getByText("Password changed. Sign in.")).isVisible();
        assertThat(page.getByLabel("Email")).hasValue(TestUsers.EMAIL);
        Assertions.assertThat(page.url()).doesNotContain("token"); // the token doesn't stay in the address bar
        fillPassword("Password", "brand-new-pass");
        button("Sign in").click();
        assertThat(page.locator("#tabs")).isVisible();
    }

    @Test
    void switchingAccountsWipesThePreviousAccountsData() {
        com.gymtracker.TestUsers.insert(jdbc, "other@example.com");
        signIn();
        createExerciseViaUi("Squat", "Quads");
        waitUntilSynced();

        context.clearCookies(); // e.g. signed out elsewhere; the phone still has the tester's data
        page.reload();
        page.getByLabel("Email").fill("other@example.com");
        fillPassword("Password", TestUsers.PASSWORD);
        button("Sign in").click();
        waitUntilSynced();
        tab("Exercises").click();
        assertThat(page.getByText("No exercises yet")).isVisible();
        Assertions.assertThat(count("select count(*) from exercise")).isEqualTo(1); // nothing was copied over
    }

    @Test
    void oldDataFromBeforeAccountsIsNotUploaded() {
        page.navigate("/");
        // What a phone has after using the single-user version: data and a pending change, but no account tag.
        page.evaluate("() => { localStorage.setItem('gt.knownUser', '1'); "
                + "localStorage.setItem('gt.outbox.v1', JSON.stringify([{ opId: 'o1', kind: 'exercise.put', "
                + "payload: { id: '00000000-0000-4000-8000-000000000009', name: 'Old', muscleGroup: 'CORE' } }])); }");
        page.reload();
        assertThat(page.getByLabel("Email")).isVisible();
        signIn();
        tab("Exercises").click();
        assertThat(page.getByText("No exercises yet")).isVisible();
        Assertions.assertThat(count("select count(*) from exercise")).isZero();
    }
}
```

Also change `E2ETestBase.signIn()` to start with `page.navigate("/");`, and leave it unchanged if it already does. The last test calls `signIn()` while the sign-in screen is already showing, which is fine.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q test -Dtest='AccountsE2ETest,JsUnitE2ETest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL.
- `JsUnitE2ETest` fails because `/js/account.js` doesn't exist yet: the page's module import fails, and the wait for `__results` times out.
- `AccountsE2ETest` fails because there's no "Create account" button.

- [ ] **Step 3: `store.reset()` and `account.js`**

Add to `store.js` after `applyServerSnapshot`:

```js
/** Forgets everything stored for the account: the snapshot and the changes not sent yet. */
export function reset() {
  snapshot = { ...EMPTY };
  outbox = [];
  acked = [];
  save(SNAPSHOT_KEY, snapshot);
  save(OUTBOX_KEY, outbox);
  save(ACKED_KEY, acked);
  changed();
}
```

Create `src/main/resources/static/js/account.js`:

```js
import * as store from './store.js';

// The data on this phone belongs to one account and is tagged with its email. When another account signs in, or
// the data has no tag (it is from before accounts existed), the data is wiped first: accounts never mix.
const KEY = 'gt.account.v1';

export function accountEmail() {
  try {
    return localStorage.getItem(KEY);
  } catch {
    return null;
  }
}

/** Call with the signed-in account's email before its data is synced. */
export function useAccount(email) {
  if (accountEmail() === email) return;
  store.reset();
  try {
    localStorage.setItem(KEY, email);
    localStorage.removeItem('gt.knownUser'); // the single-user version's flag
  } catch {
    // Storage blocked: the data only lives in memory anyway.
  }
}

/** After logging out: nothing of the account stays on the phone. */
export function forgetAccount() {
  store.reset();
  try {
    localStorage.removeItem(KEY);
  } catch {
    // ignore
  }
}
```

- [ ] **Step 4: `api.js` keeps 403 messages**

In `api.js`, change the retry condition and the 403 result to:

```js
    if (result.kind === 'forbidden' && method !== 'GET' && !AUTH_PATHS.test(path)) {
```

```js
  if (response.status === 403) return { kind: 'forbidden', message: data?.message };
```

and add above `request`:

```js
// The sign-in forms fetch a CSRF cookie before they post, so a 403 there is the server's answer (like a wrong
// invite code), not a missing cookie.
const AUTH_PATHS = /^\/(login|logout|api\/auth\/)/;
```

Update the result-kinds comment: `forbidden (403: a missing CSRF cookie, or the server's refusal with a message)`.

- [ ] **Step 5: The auth screens**

Delete `src/main/resources/static/js/views/login.js` and create `src/main/resources/static/js/views/auth.js`:

```js
import { login, request } from '../api.js';
import { esc } from '../util.js';

const NO_CONNECTION = 'No connection. Try again when you have signal.';

function messageFor(result, fallback) {
  return result.kind === 'network' ? NO_CONNECTION : result.message || fallback;
}

function passwordField(label, autocomplete) {
  return `
    <label class="field">${label}
      <input name="password" type="password" autocomplete="${autocomplete}" required>
    </label>
    <button type="button" class="link" data-show-password aria-pressed="false">Show password</button>`;
}

const emailField = (email = '') => `
  <label class="field">Email
    <input name="email" type="email" autocomplete="username" autocapitalize="none" autocorrect="off" spellcheck="false" value="${esc(email)}" required>
  </label>`;

const SCREENS = {
  login: ({ notice, email }) => `
    <h1>Gym Tracker</h1>
    ${notice ? `<p class="notice">${esc(notice)}</p>` : ''}
    <form class="stack" novalidate>
      ${emailField(email)}
      ${passwordField('Password', 'current-password')}
      <p class="error" hidden></p>
      <button class="btn primary big" type="submit">Sign in</button>
    </form>
    <div class="auth-links">
      <button type="button" class="link" data-go="register">Create account</button>
      <button type="button" class="link" data-go="forgot">Forgot password?</button>
    </div>`,
  register: () => `
    <h1>Create account</h1>
    <form class="stack" novalidate>
      ${emailField()}
      ${passwordField('Password', 'new-password')}
      <label class="field">Invite code
        <input name="inviteCode" autocomplete="off" autocapitalize="none" autocorrect="off" spellcheck="false" required>
      </label>
      <p class="error" hidden></p>
      <button class="btn primary big" type="submit">Create account</button>
    </form>
    <div class="auth-links"><button type="button" class="link" data-go="login">I already have an account</button></div>`,
  forgot: () => `
    <h1>Forgot password</h1>
    <form class="stack" novalidate>
      ${emailField()}
      <p class="error" hidden></p>
      <button class="btn primary big" type="submit">Send reset link</button>
    </form>
    <div class="auth-links"><button type="button" class="link" data-go="login">Back to sign in</button></div>`,
  reset: () => `
    <h1>New password</h1>
    <form class="stack" novalidate>
      ${passwordField('New password', 'new-password')}
      <p class="error" hidden></p>
      <button class="btn primary big" type="submit">Change password</button>
    </form>
    <div class="auth-links"><button type="button" class="link" data-go="login">Back to sign in</button></div>`,
};

/** Sign-in and account screens. onSuccess(email) opens the app for that account. */
export function renderAuth(container, { mode = 'login', token = null, notice = '', email = '', onSuccess }) {
  const go = (next) => renderAuth(container, { token, onSuccess, ...next });
  container.innerHTML = `<section class="screen login">${SCREENS[mode]({ notice, email })}</section>`;
  container.querySelectorAll('[data-go]').forEach((link) => link.addEventListener('click', () => go({ mode: link.dataset.go })));
  container.querySelectorAll('[data-show-password]').forEach((toggle) => toggle.addEventListener('click', () => {
    const input = container.querySelector('input[name="password"]');
    const show = input.type === 'password';
    input.type = show ? 'text' : 'password';
    toggle.setAttribute('aria-pressed', String(show));
    toggle.textContent = show ? 'Hide password' : 'Show password';
  }));

  const form = container.querySelector('form');
  const error = container.querySelector('.error');
  const field = (name) => String(new FormData(form).get(name) ?? '');
  const submits = {
    async login() {
      await request('GET', '/api/me'); // makes sure the CSRF cookie exists
      const result = await login(field('email').trim(), field('password'));
      if (result.kind !== 'ok') return messageFor(result, 'Wrong email or password');
      const me = await request('GET', '/api/me');
      onSuccess(me.kind === 'ok' ? me.data.email : field('email').trim().toLowerCase());
      return null;
    },
    async register() {
      await request('GET', '/api/me');
      const result = await request('POST', '/api/auth/register',
        { email: field('email'), password: field('password'), inviteCode: field('inviteCode') });
      if (result.kind !== 'ok') return messageFor(result, 'Could not create the account');
      onSuccess(result.data.email);
      return null;
    },
    async forgot() {
      await request('GET', '/api/me');
      const result = await request('POST', '/api/auth/forgot', { email: field('email') });
      if (result.kind !== 'ok') return messageFor(result, 'Could not send the email');
      form.outerHTML = `<p class="notice">${esc(result.data.message)} Check your spam folder too.</p>`;
      return null;
    },
    async reset() {
      await request('GET', '/api/me');
      const result = await request('POST', '/api/auth/reset', { token, password: field('password') });
      if (result.kind !== 'ok') return messageFor(result, 'Could not change the password');
      go({ mode: 'login', notice: 'Password changed. Sign in.', email: result.data.email });
      return null;
    },
  };
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const button = form.querySelector('button[type="submit"]');
    button.disabled = true;
    error.hidden = true;
    const message = await submits[mode]();
    if (!form.isConnected) return; // the screen moved on
    button.disabled = false;
    if (message) {
      error.textContent = message;
      error.hidden = false;
    }
  });
}
```

Add to `styles.css` after `.login h1`:

```css
.login .notice { color: var(--text-secondary); margin-bottom: 16px; }
.auth-links { display: flex; justify-content: space-between; gap: 12px; margin-top: 12px; }
.link {
  min-height: var(--tap);
  padding: 0;
  border: 0;
  background: none;
  color: var(--accent);
  font-size: 15px;
  text-align: left;
  align-self: flex-start;
}
```

- [ ] **Step 6: Wire the account into sync and boot**

In `sync.js`, import `useAccount` and use the email from `/api/me` in `flushOnce`:

```js
import { useAccount } from './account.js';
```

```js
  const me = await request('GET', '/api/me');
  if (me.kind === 'unauthorized') return signedOut();
  if (me.kind !== 'ok') return 'offline';
  if (me.data?.email) useAccount(me.data.email); // a different account's data never gets sent or shown
```

In `app.js`:
- replace the `renderLogin` import with `import { renderAuth } from './views/auth.js';` and add `import { accountEmail, useAccount } from './account.js';`;
- delete `KNOWN_USER_KEY` and `knownUser()`;
- replace `showLogin`, `showApp` and `boot` with the code below.

```js
function showLogin(options = {}) {
  if (loginShown) return; // a background sync attempt must not wipe what the user is typing
  loginShown = true;
  signedIn = false;
  tabsEl.hidden = true;
  syncEl.hidden = true;
  setSessionOpen(false);
  renderAuth(viewEl, { ...options, onSuccess: showApp });
}

/** Opens the app. `email` is given right after signing in; on a normal start the saved account is used. */
function showApp(email) {
  loginShown = false;
  signedIn = true;
  if (email) useAccount(email);
  render();
  sync.flush();
}

/** A reset link opens the app at #/reset?token=…; the token is taken out of the address bar and history. */
function takeResetToken() {
  const match = window.location.hash.match(/^#\/reset\?token=([A-Za-z0-9_-]+)/);
  if (!match) return null;
  window.history.replaceState(null, '', window.location.pathname);
  return match[1];
}

async function boot() {
  if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js').catch(() => {});
  setRenderer(render);
  initRest();
  navigate({ tab: TABS[0].id });
  sync.onUnauthorized(() => showLogin());
  sync.onRejected((message) => toast(message));
  store.subscribe(onStoreChange);
  sync.startSync();
  const resetToken = takeResetToken();
  if (resetToken) {
    showLogin({ mode: 'reset', token: resetToken });
    return;
  }
  if (accountEmail()) {
    showApp(); // open straight from the saved data; the sync that follows shows the login screen on a 401
    return;
  }
  const me = await request('GET', '/api/me');
  if (me.kind === 'ok') showApp(me.data.email);
  else showLogin(); // signed out, or offline with no account on this phone yet
}
```

In `sw.js`, in `ASSETS`:
- replace `'/js/views/login.js',` with `'/js/views/auth.js',`;
- add `'/js/account.js',` after `'/js/app.js',`.

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./mvnw -q test -Dtest='AccountsE2ETest,JsUnitE2ETest,LoginAndExercisesE2ETest,ServiceWorkerAssetsTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 8: Run the whole suite and commit**

Run: `./mvnw -q verify`
Expected: BUILD SUCCESS.

```bash
git add -A src
git commit -m "Sign-in, create-account, forgot and reset-password screens; local data is tied to one account

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: Log out from Settings

**Files:**
- Create: `src/main/resources/static/js/logout.js`
- Modify: `src/main/resources/static/js/push.js`, `views/settings.js`, `app.js`, `src/main/resources/static/sw.js`, `styles.css`
- Create: `src/test/java/com/gymtracker/e2e/LogoutE2ETest.java`

**Interfaces:**
- Consumes:
  - `POST /logout` (Task 8);
  - `DELETE /api/push/subscription` (Task 3);
  - `accountEmail`, `forgetAccount` (Task 9);
  - `skipRest` (`rest.js`).
- Produces:
  - `logout.js`: `logout(): Promise<{ok: true} | {ok: false, message}>` and `onSignedOut(fn)`;
  - `push.js`: `disableAlerts(): Promise<void>`.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/gymtracker/e2e/LogoutE2ETest.java`:

```java
package com.gymtracker.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.gymtracker.TestUsers;
import com.microsoft.playwright.Page;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class LogoutE2ETest extends E2ETestBase {

    private void openLogout() {
        tab("Workout").click();
        page.getByLabel("Settings").click();
        assertThat(page.getByText("Signed in as " + TestUsers.EMAIL)).isVisible();
        button("Log out").click();
    }

    private void signInAs(String email) {
        page.getByLabel("Email").fill(email);
        page.getByLabel("Password", new Page.GetByLabelOptions().setExact(true)).fill(TestUsers.PASSWORD);
        button("Sign in").click();
        waitUntilSynced();
    }

    @Test
    void logOutAndTheNextAccountStartsEmpty() {
        com.gymtracker.TestUsers.insert(jdbc, "other@example.com");
        signIn();
        createExerciseViaUi("Squat", "Quads");
        waitUntilSynced();

        openLogout();
        assertThat(page.getByText("You'll need your email and password to sign in again.")).isVisible();
        button("Log out").click();
        assertThat(page.getByLabel("Email")).isVisible();
        Assertions.assertThat(count("select count(*) from persistent_logins")).isZero();

        page.reload(); // stays signed out
        assertThat(page.getByLabel("Email")).isVisible();

        signInAs("other@example.com");
        tab("Exercises").click();
        assertThat(page.getByText("No exercises yet")).isVisible();
        tab("Workout").click();
        page.getByLabel("Settings").click();
        button("Log out").click();
        button("Log out").click();

        signInAs(TestUsers.EMAIL);
        tab("Exercises").click();
        assertThat(button("Squat")).isVisible();
    }

    @Test
    void warnsAboutChangesThatHaveNotSynced() {
        signIn();
        context.setOffline(true);
        createExerciseViaUi("Plank", "Core");
        openLogout();
        assertThat(page.getByText("1 change hasn't synced yet and will be lost.")).isVisible();
        context.setOffline(false);
        button("Try again").click();
        assertThat(page.getByText("You'll need your email and password to sign in again.")).isVisible();
        Assertions.assertThat(count("select count(*) from exercise")).isEqualTo(1);
    }

    @Test
    void offlineLogoutKeepsYouSignedIn() {
        signIn();
        context.setOffline(true);
        openLogout();
        button("Log out").click();
        assertThat(page.getByText("You're offline. Connect to the internet to log out.")).isVisible();
        context.setOffline(false);
        assertThat(page.getByLabel("Email")).hasCount(0);
        Assertions.assertThat(count("select count(*) from persistent_logins")).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q test -Dtest=LogoutE2ETest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL, because "Signed in as tester@example.com" isn't visible.

- [ ] **Step 3: Implement**

Add to `push.js`:

```js
/** Stops this phone's lock-screen alerts for the signed-in account. Best effort: never throws. */
export async function disableAlerts() {
  try {
    if (pushSupported()) {
      const registration = await navigator.serviceWorker.getRegistration();
      const subscription = await registration?.pushManager.getSubscription();
      if (subscription) {
        await request('DELETE', '/api/push/subscription', { endpoint: subscription.endpoint });
        await subscription.unsubscribe();
      }
    }
  } catch {
    // the server forgets dead subscriptions on its own when a push fails
  }
  updatePrefs({ alertsEnabled: false });
}
```

Create `src/main/resources/static/js/logout.js`:

```js
import { request } from './api.js';
import { skipRest } from './rest.js';
import { disableAlerts } from './push.js';
import { forgetAccount } from './account.js';

const OFFLINE = "You're offline. Connect to the internet to log out.";
let signedOutHandler = () => {};

export function onSignedOut(fn) {
  signedOutHandler = fn;
}

/**
 * Signs this phone out. Only the server can cancel the sign-in cookie, so nothing changes until it answers: when
 * offline, the phone stays signed in with its data.
 */
export async function logout() {
  const me = await request('GET', '/api/me');
  if (me.kind === 'network') return { ok: false, message: OFFLINE };
  skipRest(); // also cancels the lock-screen alert that is counting down
  await disableAlerts();
  const result = await request('POST', '/logout');
  if (result.kind === 'network') return { ok: false, message: OFFLINE };
  signedOutHandler(); // shows the sign-in screen first, so wiping the data below doesn't redraw the app
  forgetAccount();
  return { ok: true };
}
```

In `app.js`:
- `import { onSignedOut } from './logout.js';`;
- in `boot()`, after `sync.onUnauthorized(...)`, add `onSignedOut(() => showLogin());`.

Replace `views/settings.js` with:

```js
import { openSheet } from '../ui.js';
import { getPrefs, updatePrefs } from '../prefs.js';
import { formatCountdown } from '../format.js';
import { enableAlerts } from '../push.js';
import { accountEmail } from '../account.js';
import { logout } from '../logout.js';
import * as store from '../store.js';
import * as sync from '../sync.js';
import { esc } from '../util.js';

export function openSettings() {
  const prefs = getPrefs();
  const { el, close } = openSheet(`
    <h2>Settings</h2>
    <div class="setting">
      <span>Rest time</span>
      <div class="stepper compact">
        <button class="btn step" data-rest="-15" aria-label="Shorter rest">−</button>
        <output id="rest-seconds">${formatCountdown(prefs.restSeconds)}</output>
        <button class="btn step" data-rest="15" aria-label="Longer rest">+</button>
      </div>
    </div>
    <label class="setting">
      <span>Auto-start rest after each set</span>
      <input type="checkbox" role="switch" id="auto-start"${prefs.autoStart ? ' checked' : ''}>
    </label>
    <div class="setting">
      <span>Lock-screen alerts</span>
      <span id="alerts-state">${prefs.alertsEnabled ? 'On' : '<button class="btn secondary small" data-action="enable-alerts">Enable</button>'}</span>
    </div>
    <p class="muted" id="alerts-message" hidden></p>
    <div class="account">
      <p class="muted">Signed in as ${esc(accountEmail() ?? '')}</p>
      <button class="btn danger" data-action="logout">Log out</button>
    </div>
    <button class="btn secondary big" data-close>Done</button>`);
  el.querySelectorAll('[data-rest]').forEach((button) => button.addEventListener('click', () => {
    const seconds = Math.min(600, Math.max(15, getPrefs().restSeconds + Number(button.dataset.rest)));
    updatePrefs({ restSeconds: seconds });
    el.querySelector('#rest-seconds').textContent = formatCountdown(seconds);
  }));
  el.querySelector('#auto-start').addEventListener('change', (event) => updatePrefs({ autoStart: event.target.checked }));
  el.querySelector('[data-action="enable-alerts"]')?.addEventListener('click', async (event) => {
    const button = event.currentTarget;
    button.disabled = true;
    const result = await enableAlerts();
    if (result.ok) {
      el.querySelector('#alerts-state').textContent = 'On';
      return;
    }
    button.disabled = false;
    const message = el.querySelector('#alerts-message');
    message.textContent = result.message;
    message.hidden = false;
  });
  el.querySelector('[data-action="logout"]').addEventListener('click', () => {
    close();
    confirmLogout();
  });
}

function confirmLogout() {
  const waiting = store.pending();
  const warning = waiting
    ? `${waiting} change${waiting === 1 ? " hasn't" : "s haven't"} synced yet and will be lost.`
    : "You'll need your email and password to sign in again.";
  const { el, close } = openSheet(`
    <h2>Log out?</h2>
    <p class="confirm-text">${esc(warning)}</p>
    <p class="error" id="logout-error" hidden></p>
    <div class="stack">
      ${waiting ? '<button class="btn secondary big" data-action="retry">Try again</button>' : ''}
      <button class="btn danger big" data-action="confirm">${waiting ? 'Log out anyway' : 'Log out'}</button>
      <button class="btn secondary big" data-close>Cancel</button>
    </div>`);
  el.querySelector('[data-action="retry"]')?.addEventListener('click', async () => {
    await sync.flush();
    close();
    confirmLogout();
  });
  el.querySelector('[data-action="confirm"]').addEventListener('click', async (event) => {
    const button = event.currentTarget;
    button.disabled = true;
    const result = await logout();
    if (result.ok) {
      close();
      return;
    }
    button.disabled = false;
    const error = el.querySelector('#logout-error');
    error.textContent = result.message;
    error.hidden = false;
  });
}
```

Add to `styles.css` after `.setting`:

```css
.account { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding-top: 8px; border-top: 1px solid var(--border); }
.account p { overflow-wrap: anywhere; }
```

In `sw.js` `ASSETS`, add `'/js/logout.js',` after `'/js/format.js',`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q test -Dtest='LogoutE2ETest,RestTimerE2ETest,ServiceWorkerAssetsTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A src
git commit -m "Log out from Settings, with a warning for unsynced changes

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: Pink and Red & black themes

**Files:**
- Create: `src/main/resources/static/js/theme.js`
- Modify: `src/main/resources/static/styles.css`, `index.html`, `js/prefs.js`, `js/app.js`, `js/views/settings.js`, `sw.js`
- Create: `src/test/java/com/gymtracker/e2e/ThemeE2ETest.java`

**Interfaces:**
- Consumes: `getPrefs/updatePrefs` (`prefs.js`), settings sheet (Task 10).
- Produces:
  - `theme.js`: `THEMES`, `applyTheme(theme?)`, `setTheme(theme)`;
  - prefs key `theme` (`'blue' | 'pink' | 'redblack'`, default `'blue'`);
  - the window event `gt:theme`.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/gymtracker/e2e/ThemeE2ETest.java`:

```java
package com.gymtracker.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class ThemeE2ETest extends E2ETestBase {

    private static final String LOW_CONTRAST = """
            (theme) => {
              document.documentElement.dataset.theme = theme;
              const css = getComputedStyle(document.documentElement);
              const hex = (name) => css.getPropertyValue(name).trim();
              const lum = (h) => {
                const [r, g, b] = [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16) / 255)
                  .map((v) => (v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4));
                return 0.2126 * r + 0.7152 * g + 0.0722 * b;
              };
              const ratio = (a, b) => {
                const [hi, lo] = [lum(hex(a)), lum(hex(b))].sort((x, y) => y - x);
                return (hi + 0.05) / (lo + 0.05);
              };
              const low = [];
              for (const text of ['--text-primary', '--text-secondary', '--text-muted', '--danger'])
                for (const surface of ['--surface-0', '--surface-1', '--surface-2'])
                  if (ratio(text, surface) < 4.5) low.push(`${text} on ${surface}`);
              if (ratio('--accent-text', '--accent') < 4.5) low.push('--accent-text on --accent');
              if (ratio('--series-1', '--surface-1') < 3) low.push('--series-1 on --surface-1');
              return low;
            }""";

    private String accent() {
        return (String) page.evaluate("() => getComputedStyle(document.documentElement).getPropertyValue('--accent').trim()");
    }

    private void pickTheme(String name) {
        tab("Workout").click();
        page.getByLabel("Settings").click();
        page.getByRole(AriaRole.RADIO, new Page.GetByRoleOptions().setName(name).setExact(true)).click();
        button("Done").click();
    }

    @Test
    void pickedThemeAppliesAtOnceAndAfterAReload() {
        signIn();
        pickTheme("Pink");
        assertThat(page.locator("html")).hasAttribute("data-theme", "pink");
        Assertions.assertThat(accent()).isEqualTo("#c2185b");

        page.reload();
        assertThat(page.locator("html")).hasAttribute("data-theme", "pink");
        Assertions.assertThat(page.evaluate("() => document.querySelector('meta[name=\"theme-color\"]').content"))
                .isEqualTo("#fff8fa");

        pickTheme("Red & black");
        Assertions.assertThat(accent()).isEqualTo("#d61f2c");
        pickTheme("Blue");
        assertThat(page.locator("html")).not().hasAttribute("data-theme", "redblack");
        Assertions.assertThat(page.locator("html").getAttribute("data-theme")).isNull();
    }

    @Test
    void savedThemeIsUsedBeforeTheAppStarts() {
        page.navigate("/");
        page.evaluate("() => localStorage.setItem('gt.prefs.v1', JSON.stringify({ theme: 'redblack' }))");
        // readyState turns 'interactive' when the HTML is parsed and before module scripts (app.js) run, so this
        // sees only what the inline script in index.html did. (Blocking app.js with page.route wouldn't work: the
        // service worker serves it from its cache, and Playwright doesn't intercept those requests.)
        page.addInitScript("document.addEventListener('readystatechange', () => { "
                + "if (document.readyState === 'interactive') window.__themeAtParse = document.documentElement.dataset.theme ?? null; });");
        page.reload();
        Assertions.assertThat(page.evaluate("() => window.__themeAtParse")).isEqualTo("redblack");
    }

    @Test
    void newThemesAreReadable() {
        page.navigate("/");
        for (String theme : List.of("pink", "redblack")) {
            @SuppressWarnings("unchecked")
            List<String> low = (List<String>) page.evaluate(LOW_CONTRAST, theme);
            Assertions.assertThat(low).as(theme).isEmpty();
        }
    }

    @Test
    void chartsUseTheThemeColours() {
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Europe/Amsterdam"));
        java.util.UUID squat = seedExercise("Squat", "QUADS");
        java.util.UUID session = seedEndedSession(today.toString(), today + "T08:00:00Z", today + "T09:00:00Z");
        seedSet(session, squat, "60", 5, "WORK", today + "T08:10:00Z");
        signIn();
        pickTheme("Red & black");
        tab("Stats").click();
        page.waitForFunction("() => window.Chart && document.querySelector('canvas') && window.Chart.getChart(document.querySelector('canvas'))");
        Object colour = page.evaluate("() => window.Chart.getChart(document.querySelector('canvas')).data.datasets[0].backgroundColor");
        Assertions.assertThat(colour).isEqualTo("#ef4655");
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q test -Dtest=ThemeE2ETest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL, because there's no "Pink" radio button, and `newThemesAreReadable` gets `NaN` ratios (the tokens are missing), so its list isn't empty.

- [ ] **Step 3: Check the new chart colours with the palette validator**

Run (validator from the dataviz skill):
```bash
V=/private/tmp/claude-501/bundled-skills/2.1.281/381b99d682b269a9b37ea93294c36c9f/dataviz/scripts/validate_palette.py
python3 "$V" "#d6336c" --mode light --surface "#fff8fa"
python3 "$V" "#ef4655" --mode dark --surface "#161314"
```
Expected: `ALL CHECKS PASS` for both. If the path no longer exists, find `validate_palette.py` under `/private/tmp/claude-501/bundled-skills/*/*/dataviz/scripts/`, and ledger it.

- [ ] **Step 4: Implement**

Add to `styles.css` directly after the `@media (prefers-color-scheme: dark)` block:

```css
/* Themes picked in Settings. Blue (above) is the default and follows the phone's light/dark mode; these are
   fixed: Pink is always light, Red & black always dark. Primary buttons are filled and danger buttons are
   text-only, so they stay apart in Red & black where both are red. */
:root[data-theme="pink"] {
  color-scheme: light;
  --surface-0: #fbeef3;
  --surface-1: #fff8fa;
  --surface-2: #f6dde6;
  --text-primary: #2a0f1a;
  --text-secondary: #6b3a4e;
  --text-muted: #80526a;
  --border: #efd0dc;
  --grid: #f2dbe4;
  --accent: #c2185b;
  --accent-text: #ffffff;
  --series-1: #d6336c;
  --danger: #b42318;
}

:root[data-theme="redblack"] {
  color-scheme: dark;
  --surface-0: #0b0b0c;
  --surface-1: #161314;
  --surface-2: #2a1d20;
  --text-primary: #ffffff;
  --text-secondary: #d9c4c7;
  --text-muted: #a8949a;
  --border: #3a2a2e;
  --grid: #2b2023;
  --accent: #d61f2c;
  --accent-text: #ffffff;
  --series-1: #ef4655;
  --danger: #ff8a80;
}
```

and after the `.segmented button[aria-checked="true"]` rule:

```css
.segmented.three { grid-template-columns: repeat(3, 1fr); }
```

In `index.html`, add this right before the `<link rel="stylesheet" ...>` line:

```html
  <script>
    // Applies the saved theme before the first paint, so the app never flashes the default colours.
    try {
      var theme = JSON.parse(localStorage.getItem('gt.prefs.v1') || '{}').theme;
      if (theme === 'pink' || theme === 'redblack') document.documentElement.dataset.theme = theme;
    } catch (e) { /* storage blocked: default theme */ }
  </script>
```

In `prefs.js`, extend the defaults: `const DEFAULTS = { restSeconds: 90, autoStart: true, alertsEnabled: false, theme: 'blue' };`

Create `src/main/resources/static/js/theme.js`:

```js
import { getPrefs, updatePrefs } from './prefs.js';

export const THEMES = [
  { id: 'blue', label: 'Blue' },
  { id: 'pink', label: 'Pink' },
  { id: 'redblack', label: 'Red & black' },
];

// Status-bar colour per theme. Blue keeps index.html's light/dark pair.
const BAR = { pink: '#fff8fa', redblack: '#161314' };
const BLUE_BARS = ['#fcfcfb', '#1a1a19'];

export function applyTheme(theme = getPrefs().theme) {
  const id = THEMES.some((t) => t.id === theme) ? theme : 'blue';
  if (id === 'blue') delete document.documentElement.dataset.theme;
  else document.documentElement.dataset.theme = id;
  document.querySelectorAll('meta[name="theme-color"]').forEach((meta, i) => {
    meta.content = BAR[id] ?? BLUE_BARS[i];
  });
  window.dispatchEvent(new Event('gt:theme')); // screens redraw, so charts pick up the new colours
}

export function setTheme(theme) {
  updatePrefs({ theme });
  applyTheme(theme);
}
```

In `app.js`:
- `import { applyTheme } from './theme.js';`;
- in `boot()`, as the first line after the service-worker registration, call `applyTheme();`;
- after `store.subscribe(onStoreChange);`, add `window.addEventListener('gt:theme', onStoreChange);`.

In `views/settings.js`, add `import { THEMES, setTheme } from '../theme.js';`. Then put this markup after the auto-start `<label>`:

```js
    <div class="stack">
      <span>Theme</span>
      <div class="segmented three" role="radiogroup" aria-label="Theme">
        ${THEMES.map((t) => `<button type="button" role="radio" data-theme-choice="${t.id}" aria-checked="${prefs.theme === t.id}">${esc(t.label)}</button>`).join('')}
      </div>
    </div>
```

and this handler after the auto-start handler:

```js
  el.querySelectorAll('[data-theme-choice]').forEach((button) => button.addEventListener('click', () => {
    setTheme(button.dataset.themeChoice);
    el.querySelectorAll('[data-theme-choice]').forEach((b) => b.setAttribute('aria-checked', String(b === button)));
  }));
```

In `sw.js` `ASSETS`, add `'/js/theme.js',` after `'/js/sync.js',`.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw -q test -Dtest='ThemeE2ETest,ChartsE2ETest,ServiceWorkerAssetsTest,ServiceWorkerVersionTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

If `chartsUseTheThemeColours` finds no canvas on the Stats tab (no weekly chart with one session), read `js/views/stats.js` to see which chart renders. Use the exercise detail chart instead: `tab("Exercises")`, then `button("Squat")`. Ledger the change.

- [ ] **Step 6: Look at it**

Run the app: `./mvnw spring-boot:test-run -Dspring-boot.run.profiles=test` (port 8080; if it's busy, add `-Dspring-boot.run.arguments=--server.port=18080`). Then:
- register with invite code `test-invite`;
- take Playwright screenshots of the Workout tab and the Settings sheet in each theme, at 390×844;
- save them in the scratchpad and read them.

Check for unreadable text, clashing colours, and the danger and primary buttons standing apart in Red & black. Fix any token that looks wrong, re-run Step 5, and stop the app.

- [ ] **Step 7: Commit**

```bash
git add -A src
git commit -m "Pink and Red & black themes, picked in Settings

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: Docs for the new setup

**Files:**
- Modify: `docs/railway-setup.md`, `README.md`

- [ ] **Step 1: Update `docs/railway-setup.md`**

1. In step 1, replace the first two lines of the code block (the app-password comment and `openssl rand -base64 18`) with:

```bash
# The invite code people need to create an account. Share it only with people who should get an account.
openssl rand -base64 9
```

2. Add a new section before "## 2. Create the project":

```markdown
## 1b. Set up email for password resets (Brevo)

1. Create a free account at https://www.brevo.com (300 emails a day).
2. Go to **Senders, domains & dedicated IPs**, then **Senders**, then **Add a sender**, and add the email address
   reset emails should come from. Brevo sends that address a confirmation email: click its link.
3. Go to **SMTP & API**, then **API keys**, then **Generate a new API key**. Copy the key (it is shown once).

Reset emails come "from" that address through Brevo. Some may land in spam, which the reset screen mentions.
```

3. In step 3, replace the `APP_USERNAME=` and `APP_PASSWORD=` lines with:

```
INVITE_CODE=<the invite code from step 1>
BREVO_API_KEY=<the API key from step 1b>
MAIL_FROM=<the sender address you verified in step 1b>
APP_BASE_URL=https://<your-domain>
```

and add after "Click **Update Variables**…": `` `APP_BASE_URL` is the address from step 4's **Generate Domain**; the app doesn't start without it, so fill it in once the domain exists. ``

4. In step 6, replace step 1 of the iPhone list with: ``1. Open `https://<your-domain>` in **Safari**, tap **Create account**, and use the invite code.``

5. Add a section before "## Costs and limits":

```markdown
## Upgrading from the single-user version

The upgrade empties the database: the old workouts are not kept. After the deploy, open the app, tap
**Create account** and register with the invite code. Remove the old `APP_USERNAME` and `APP_PASSWORD` variables.
```

6. In "Costs and limits", replace the "Staying signed in" line with: `- **Staying signed in:** each phone stays signed in for a year, until it logs out in Settings or the password is reset.`

- [ ] **Step 2: Update `README.md`**

- Change the first paragraph's first sentence to: `A gym tracker for a few people: accounts with an invite code, exercises, workout sessions with warmup/work sets, history, personal records, progress charts, a rest timer with lock-screen alerts, and three colour themes.`
- Replace `Open http://localhost:8080 and sign in with \`tester\` / \`secret-pass\`.` with ``Open http://localhost:8080, tap **Create account** and use the invite code `test-invite`. Reset emails are not sent locally (no Brevo key); the log says so.``

- [ ] **Step 3: Check the docs for personal details, then commit**

Run: `git diff HEAD -- docs/railway-setup.md README.md` and read it; then `git grep -n -i "@gmail" -- docs/railway-setup.md README.md src`
Expected: the diff names no real person or email address (only placeholders like `<your-domain>`), and the grep prints nothing.

```bash
git add docs/railway-setup.md README.md
git commit -m "Docs: invite code, Brevo and the upgrade to accounts

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 4: Full verification**

Run: `./mvnw clean verify`
Expected: BUILD SUCCESS, every test passing.
