# Gym Tracker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a single-user gym tracker. A Spring Boot app serves a JSON API and an offline-capable home-screen web app (exercises, workout sessions with sets, history, PRs, charts and a rest timer with lock-screen alerts), and it is deployed to Railway from a public GitHub repo.

**Architecture:**
- **One Spring Boot 4.1 app** (Java 25) with PostgreSQL (Flyway migrations). It serves both the REST API under `/api` and plain-JS ES modules from `src/main/resources/static`.
- **The frontend renders from a local snapshot plus an outbox of pending operations.** Both are stored in `localStorage`, and the outbox is sent in order to idempotent `PUT`/`POST`/`DELETE` endpoints, so everything works without signal.
- **Lock-screen rest alerts use Web Push.** The server schedules a VAPID-signed push at the rest's end time.

**Tech Stack:**
- **Backend:** Spring Boot 4.1.1 (webmvc, data-jpa, security, validation, actuator, flyway), PostgreSQL 17.
- **Push:** `nl.martijndwars:web-push` 5.1.2, with BouncyCastle `bcprov-jdk18on` 1.86 and `jose4j` 0.9.6.
- **Frontend:** plain HTML/CSS/JS ES modules and Chart.js 4.5.1 (vendored copy).
- **Tests:** JUnit 5 + MockMvc + Testcontainers 2; Playwright for Java 1.63.0 (WebKit).
- **Delivery:** Docker, GitHub Actions, Railway.

**Spec:** `docs/superpowers/specs/2026-10-05-gym-tracker-design.md`. Read it before starting; every task implements part of it.

## Global Constraints

**Platform and language**
- Java 25, Spring Boot 4.1.1 parent, base package `com.gymtracker`, Maven wrapper `./mvnw`. Maven is not installed on the dev machine.
- No Node.js and no frontend build step. Frontend code is ES modules under `src/main/resources/static/js/`.
- UI language English, weights in kg.
- Muscle groups, exactly and in this order: `CHEST, BACK, SHOULDERS, BICEPS, TRICEPS, QUADS, HAMSTRINGS, CALVES, GLUTES, CORE`.

**Validation and data rules**
- Exercise name: trimmed, 1–60 characters, unique among **non-archived** exercises, ignoring upper/lower case. Duplicate message: `You already have an exercise called '<name>'`.
- Set weight: 0–500 kg in multiples of 0.25 (0 = bodyweight). Reps: whole number, 1–100. Set type: `WARMUP` or `WORK`. Default type in the UI: `WORK`.
- At most one open session. Ending a session with zero sets deletes it. Duration = `endedAt − startedAt`, never stored.
- "Last time" = the most recent `WORK` set (by `loggedAt`) in an **ended** session.

**API conventions**
- `PUT` is create-or-update and idempotent.
- Errors are JSON `{ "message": "..." }` with these codes: `400` validation, `404` unknown id, `409` conflict, `401` not signed in (never a redirect for `/api/**`).
- CSRF: cookie `XSRF-TOKEN` and header `X-XSRF-TOKEN`.
- **Never use `SecurityMockMvcRequestPostProcessors.csrf()` in tests.** It replaces the token repository on the shared `CsrfFilter` and breaks every cookie-based test that runs after it. Use the `xsrf()` / `signedIn()` helpers from `IntegrationTestBase`.

**Secrets and configuration**
- No secrets in the repo; the repo is public. Runtime config comes only from env vars: `PORT`, `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD`, `APP_USERNAME`, `APP_PASSWORD`, `REMEMBER_ME_KEY`, `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY`, `VAPID_SUBJECT`, and the optional `SECURE_COOKIES` (default `true`).
- The test profile (`application-test.properties`) holds throwaway test-only values.

**Frontend conventions**
- Tap targets ≥ 44 pt. Follow the phone's light/dark setting. Charts use one series colour: `#2a78d6` light, `#3987e5` dark.
- Rest timer: default 90 s, adjustable in 15 s steps between 15 s and 10 min; auto-start is on by default.

## Review Focus

Five inputs or conditions the spec implies but doesn't spell out. Each one has a test in the task named after the arrow.

1. **The CSRF or session cookie has disappeared when the outbox is sent.** iOS drops session cookies when the home-screen app is killed. Queued changes must still sync and must never be dropped. → Task 7, `changesSyncAfterSessionAndCsrfCookiesAreLost`.
2. **The server session expired but the remember-me cookie is still valid.** The user must stay signed in across reloads. Parallel remember-me logins would trigger Spring's cookie-theft detection and sign the user out, so `/api/me` always runs first, on its own. → Task 7, `staysSignedInWhenServerSessionExpires`.
3. **The user types a decimal comma** (`42,5` on iPhones set to a European region). This must be read as 42.5 kg, not rejected or read as 425. → Task 7, unit page `parseWeight`; Task 8, `typedCommaWeightIsLoggedAsDecimal`.
4. **The user double-taps Save in the set sheet.** Exactly one set must be logged. → Task 8, `doubleTapOnSaveLogsOneSet`.
5. **A static file is added but not listed in the service worker's asset list.** The app then opens broken offline after a deploy. Every file under `static/` must be listed in `sw.js`. → Task 7, `ServiceWorkerAssetsTest` (and later tasks add their new files to that list).

## File Structure

```
gym-tracker/
├── pom.xml, mvnw, mvnw.cmd, .mvn/wrapper/maven-wrapper.properties
├── Dockerfile, .dockerignore, README.md
├── .github/workflows/ci.yml
├── docs/railway-setup.md
├── tools/make_icons.py                      # generates the PNG app icons (run once)
└── src/
    ├── main/java/com/gymtracker/
    │   ├── GymTrackerApplication.java
    │   ├── common/        ApiError, NotFoundException, ConflictException, BadRequestException,
    │   │                  GlobalExceptionHandler, ClockConfig
    │   ├── security/      SecurityConfig, CsrfCookieFilter, MeController
    │   ├── exercise/      MuscleGroup, Exercise, ExerciseRepository, ExerciseRequest, ExerciseResponse,
    │   │                  ExerciseService, ExerciseController
    │   ├── workout/       SetType, WorkoutSession, WorkoutSet, WorkoutSessionRepository, WorkoutSetRepository,
    │   │                  WorkSetRow, WeeklySetRow, StartSessionRequest, EndSessionRequest, SetRequest,
    │   │                  SetResponse, SessionResponse, SessionSummary, EndSessionResponse,
    │   │                  WorkoutService, SessionController, SetController
    │   ├── stats/         LastTime, RecordSet, Records, SessionPoint, ExerciseStats, ExerciseHistory, WeekStats,
    │   │                  RecordsCalculator, StatsService, StatsController
    │   └── push/          PushSubscription, PushSubscriptionRepository, PushSender, WebPushSender,
    │                      PushSubscriptionService, RestTimerService, PushConfig, PushController,
    │                      VapidKeyGenerator
    ├── main/resources/
    │   ├── application.properties
    │   ├── db/migration/V1__schema.sql
    │   └── static/
    │       ├── index.html, styles.css, manifest.json, sw.js
    │       ├── icons/icon-192.png, icon-512.png, apple-touch-icon.png
    │       ├── vendor/chart.umd.min.js
    │       └── js/ app.js, api.js, store.js, sync.js, router.js, ui.js, util.js, format.js,
    │              records.js, rest.js, push.js, prefs.js, charts.js,
    │              views/ login.js, exercises.js, workout.js, history.js, stats.js, settings.js
    └── test/
        ├── java/com/gymtracker/
        │   ├── TestcontainersConfiguration, TestGymTrackerApplication, IntegrationTestBase, DbCleaner,
        │   │   SchemaMigrationTest, ServiceWorkerAssetsTest
        │   ├── security/  SecurityIntegrationTest, SecurityConfigTest
        │   ├── exercise/  ExerciseApiTest
        │   ├── workout/   WorkoutApiTest
        │   ├── stats/     RecordsCalculatorTest, StatsApiTest
        │   ├── push/      TestPushConfig, FakePushSender, PushApiTest, VapidKeyGeneratorTest
        │   └── e2e/       E2ETestBase, JsUnitE2ETest, LoginAndExercisesE2ETest, WorkoutE2ETest,
        │                  HistoryE2ETest, RecordsE2ETest, RestTimerE2ETest, ChartsE2ETest
        └── resources/
            ├── application-test.properties
            └── static/test/unit.html               # browser-run JS unit tests (test classpath only)
```

**How to run things:**
- **All tests:** `./mvnw verify`. Docker must be running for Testcontainers.
- **One test class:** `./mvnw test -Dtest=ExerciseApiTest`.
- **App locally:** `./mvnw spring-boot:test-run -Dspring-boot.run.profiles=test`. It starts Postgres in Docker, and you sign in with `tester` / `secret-pass`.

---

### Task 1: Project scaffold, database schema, test harness

**Files:**
- Create: `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`, `.gitignore`, `.gitattributes`
- Create: `src/main/java/com/gymtracker/GymTrackerApplication.java`
- Create: `src/main/resources/application.properties`
- Create: `src/main/resources/db/migration/V1__schema.sql`
- Create: `src/test/resources/application-test.properties`
- Create: `src/test/java/com/gymtracker/TestcontainersConfiguration.java`, `TestGymTrackerApplication.java`, `IntegrationTestBase.java`, `DbCleaner.java`
- Test: `src/test/java/com/gymtracker/SchemaMigrationTest.java`

**Interfaces:**
- Produces:
  - `IntegrationTestBase`: an abstract `@SpringBootTest` base with:
    - `protected MockMvc mvc`, `protected JdbcTemplate jdbc`;
    - `protected static RequestPostProcessor xsrf()`, `protected static RequestPostProcessor signedIn()`;
    - `protected ResultActions apiGet(String url)`, `apiPut(String url, String json)`, `apiPost(String url, String json)`, `apiDelete(String url)`.
    - The database is truncated before each test.
  - `DbCleaner.clean(JdbcTemplate)`.
  - Tables: `exercise`, `workout_session` (column `session_date`), `workout_set`, `persistent_logins`, `push_subscription`.

- [ ] **Step 1: Generate the Maven wrapper files from Spring Initializr**

Run from the repo root (`gym-tracker/`):
```bash
SP=$(mktemp -d)
curl -s "https://start.spring.io/starter.zip?type=maven-project&language=java&bootVersion=4.1.1&javaVersion=25&groupId=com.gymtracker&artifactId=gym-tracker&name=gym-tracker&packageName=com.gymtracker&dependencies=webmvc" -o "$SP/starter.zip"
unzip -q "$SP/starter.zip" -d "$SP/starter"
cp -R "$SP/starter/mvnw" "$SP/starter/mvnw.cmd" "$SP/starter/.mvn" "$SP/starter/.gitattributes" .
chmod +x mvnw
```
Expected: `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` and `.gitattributes` now exist in the repo root.

- [ ] **Step 2: Write `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.1</version>
        <relativePath/>
    </parent>
    <groupId>com.gymtracker</groupId>
    <artifactId>gym-tracker</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>gym-tracker</name>

    <properties>
        <java.version>25</java.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-flyway</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- Web Push. bcprov is optional in web-push's pom, and httpclient/jose4j are runtime-only there,
             but our code needs all three at compile time. -->
        <dependency>
            <groupId>nl.martijndwars</groupId>
            <artifactId>web-push</artifactId>
            <version>5.1.2</version>
        </dependency>
        <dependency>
            <groupId>org.bouncycastle</groupId>
            <artifactId>bcprov-jdk18on</artifactId>
            <version>1.86</version>
        </dependency>
        <dependency>
            <groupId>org.apache.httpcomponents</groupId>
            <artifactId>httpasyncclient</artifactId>
            <version>4.1.5</version>
        </dependency>
        <dependency>
            <groupId>org.bitbucket.b_c</groupId>
            <artifactId>jose4j</artifactId>
            <version>0.9.6</version>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-flyway-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-testcontainers</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-postgresql</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.microsoft.playwright</groupId>
            <artifactId>playwright</artifactId>
            <version>1.63.0</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <finalName>app</finalName>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 3: Write `.gitignore`**

```gitignore
target/
.DS_Store
.idea/
*.iml
.vscode/
HELP.md
```

- [ ] **Step 4: Write the application class and configuration**

`src/main/java/com/gymtracker/GymTrackerApplication.java`:
```java
package com.gymtracker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class GymTrackerApplication {

    public static void main(String[] args) {
        SpringApplication.run(GymTrackerApplication.class, args);
    }
}
```

`src/main/resources/application.properties`:
```properties
spring.application.name=gym-tracker
server.port=${PORT:8080}
server.forward-headers-strategy=framework
server.servlet.session.timeout=7d
server.servlet.session.cookie.secure=${app.secure-cookies}

spring.datasource.url=jdbc:postgresql://${PGHOST:localhost}:${PGPORT:5432}/${PGDATABASE:gymtracker}
spring.datasource.username=${PGUSER:gymtracker}
spring.datasource.password=${PGPASSWORD:gymtracker}
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false

management.endpoints.web.exposure.include=health

app.username=${APP_USERNAME:}
app.password=${APP_PASSWORD:}
app.remember-me-key=${REMEMBER_ME_KEY:}
app.secure-cookies=${SECURE_COOKIES:true}
app.vapid.public-key=${VAPID_PUBLIC_KEY:}
app.vapid.private-key=${VAPID_PRIVATE_KEY:}
app.vapid.subject=${VAPID_SUBJECT:}
```

`src/test/resources/application-test.properties` (test-only values; the VAPID pair is a throwaway generated for tests):
```properties
app.username=tester
app.password=secret-pass
app.remember-me-key=test-remember-me-key
app.secure-cookies=false
app.vapid.public-key=BLeb9PGJiA9-R9DEzj4AmumqClt7-8HxyHk9jEItaHqiIV0cjTrDfHyFj4cNIisPQ1DN4VUUy5Qj99wGtEzGnuA
app.vapid.private-key=lf5HflvYZaUBxCSAzrj01cdVJTTwFaB42VxTWfFhjGs
app.vapid.subject=mailto:test@example.com
```

- [ ] **Step 5: Write the schema migration**

`src/main/resources/db/migration/V1__schema.sql`:
```sql
create table exercise (
    id           uuid primary key,
    name         varchar(60) not null,
    muscle_group varchar(20) not null,
    archived     boolean     not null default false,
    created_at   timestamptz not null
);
-- Names are unique among non-archived exercises, ignoring case.
create unique index exercise_active_name_uq on exercise (lower(name)) where not archived;

create table workout_session (
    id           uuid primary key,
    session_date date        not null,
    started_at   timestamptz not null,
    ended_at     timestamptz
);
-- At most one open session.
create unique index workout_session_one_open_uq on workout_session ((true)) where ended_at is null;

create table workout_set (
    id          uuid primary key,
    session_id  uuid          not null references workout_session (id) on delete cascade,
    exercise_id uuid          not null references exercise (id),
    weight_kg   numeric(6, 2) not null,
    reps        integer       not null,
    type        varchar(10)   not null,
    logged_at   timestamptz   not null
);
create index workout_set_session_idx on workout_set (session_id);
create index workout_set_exercise_idx on workout_set (exercise_id);

-- Spring Security's JdbcTokenRepositoryImpl (remember-me) expects exactly this table.
create table persistent_logins (
    username  varchar(64) not null,
    series    varchar(64) primary key,
    token     varchar(64) not null,
    last_used timestamp   not null
);

create table push_subscription (
    endpoint   varchar(2048) primary key,
    p256dh     varchar(255)  not null,
    auth       varchar(255)  not null,
    created_at timestamptz   not null
);
```

- [ ] **Step 6: Write the test harness**

`src/test/java/com/gymtracker/TestcontainersConfiguration.java`:
```java
package com.gymtracker;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
    }
}
```

`src/test/java/com/gymtracker/TestGymTrackerApplication.java` (runs the app locally against a throwaway Postgres):
```java
package com.gymtracker;

import org.springframework.boot.SpringApplication;

public class TestGymTrackerApplication {

    public static void main(String[] args) {
        SpringApplication.from(GymTrackerApplication::main).with(TestcontainersConfiguration.class).run(args);
    }
}
```

`src/test/java/com/gymtracker/DbCleaner.java`:
```java
package com.gymtracker;

import org.springframework.jdbc.core.JdbcTemplate;

public final class DbCleaner {

    private DbCleaner() {
    }

    public static void clean(JdbcTemplate jdbc) {
        jdbc.execute("truncate table workout_set, workout_session, exercise, persistent_logins, push_subscription");
    }
}
```

`src/test/java/com/gymtracker/IntegrationTestBase.java`:
```java
package com.gymtracker;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import jakarta.servlet.http.Cookie;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTestBase {

    protected static final String XSRF = "test-xsrf-token";

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        DbCleaner.clean(jdbc);
    }

    /**
     * A real XSRF-TOKEN cookie plus matching header. Do not use SecurityMockMvcRequestPostProcessors.csrf():
     * it swaps the token repository on the shared CsrfFilter and breaks cookie-based tests that run later.
     */
    protected static RequestPostProcessor xsrf() {
        return request -> {
            Cookie[] existing = request.getCookies() == null ? new Cookie[0] : request.getCookies();
            Cookie[] cookies = Arrays.copyOf(existing, existing.length + 1);
            cookies[existing.length] = new Cookie("XSRF-TOKEN", XSRF);
            request.setCookies(cookies);
            request.addHeader("X-XSRF-TOKEN", XSRF);
            return request;
        };
    }

    /** Signed in as the test user, with a valid CSRF cookie and header. */
    protected static RequestPostProcessor signedIn() {
        return request -> xsrf().postProcessRequest(user("tester").postProcessRequest(request));
    }

    protected ResultActions apiGet(String url) throws Exception {
        return mvc.perform(get(url).with(signedIn()));
    }

    protected ResultActions apiPut(String url, String json) throws Exception {
        return mvc.perform(put(url).with(signedIn()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    protected ResultActions apiPost(String url, String json) throws Exception {
        return mvc.perform(post(url).with(signedIn()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    protected ResultActions apiDelete(String url) throws Exception {
        return mvc.perform(delete(url).with(signedIn()));
    }
}
```

- [ ] **Step 7: Write the failing schema test**

`src/test/java/com/gymtracker/SchemaMigrationTest.java`:
```java
package com.gymtracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class SchemaMigrationTest extends IntegrationTestBase {

    @Test
    void createsAllTables() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'", String.class);
        assertThat(tables).contains("exercise", "workout_session", "workout_set", "persistent_logins", "push_subscription");
    }

    @Test
    void allowsOnlyOneOpenSession() {
        insertSession(null);
        insertSession("2026-10-05T09:00:00Z");
        assertThatThrownBy(() -> insertSession(null)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void exerciseNamesAreUniqueIgnoringCaseAmongActiveOnly() {
        insertExercise("Squat", true);
        insertExercise("Squat", false);
        assertThatThrownBy(() -> insertExercise("squat", false)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertSession(String endedAt) {
        jdbc.update("insert into workout_session (id, session_date, started_at, ended_at) "
                + "values (?, date '2026-10-05', timestamptz '2026-10-05T08:00:00Z', ?::timestamptz)",
                UUID.randomUUID(), endedAt);
    }

    private void insertExercise(String name, boolean archived) {
        jdbc.update("insert into exercise (id, name, muscle_group, archived, created_at) values (?, ?, 'QUADS', ?, now())",
                UUID.randomUUID(), name, archived);
    }
}
```

- [ ] **Step 8: Run the test**

Run: `./mvnw test -Dtest=SchemaMigrationTest`
Expected: PASS, 3 tests. The first run downloads dependencies and the `postgres:17-alpine` image, which takes a few minutes. If it fails with "Could not find a valid Docker environment", start Docker Desktop and rerun.

There is no separate red step: the migration and the test arrive together. Check that the test catches a broken schema by deleting the `workout_session_one_open_uq` line. Rerun and expect `allowsOnlyOneOpenSession` to FAIL, then restore the line.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "Scaffold Spring Boot project with schema and test harness"
```

---

### Task 2: Security: single user, JSON login, remember-me, CSRF cookie

**Files:**
- Create: `src/main/java/com/gymtracker/security/SecurityConfig.java`, `CsrfCookieFilter.java`, `MeController.java`
- Test: `src/test/java/com/gymtracker/security/SecurityIntegrationTest.java`, `SecurityConfigTest.java`

**Interfaces:**
- Consumes: `IntegrationTestBase` (Task 1).
- Produces:
  - `POST /login`: form fields `username`, `password`. Returns `200` (empty body) on success, `401 {"message":"Wrong username or password"}` on failure. Always sets a `remember-me` cookie on success.
  - `GET /api/me`: `{"username": "..."}`, or `401`.
  - Every response sets the `XSRF-TOKEN` cookie if it is missing.
  - `/api/**` requires authentication and returns `401`, never a redirect. Everything else is public.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/gymtracker/security/SecurityIntegrationTest.java`:
```java
package com.gymtracker.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gymtracker.IntegrationTestBase;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class SecurityIntegrationTest extends IntegrationTestBase {

    @Test
    void apiRequiresSignInAndAnswers401WithCsrfCookie() throws Exception {
        MvcResult result = mvc.perform(get("/api/me")).andExpect(status().isUnauthorized()).andReturn();
        assertThat(result.getResponse().getCookie("XSRF-TOKEN")).isNotNull();
    }

    @Test
    void meReturnsUsername() throws Exception {
        apiGet("/api/me").andExpect(status().isOk()).andExpect(jsonPath("$.username").value("tester"));
    }

    @Test
    void loginSucceedsAndRememberMeCookieSignsInWithoutSession() throws Exception {
        MvcResult login = login("tester", "secret-pass").andExpect(status().isOk()).andReturn();
        Cookie rememberMe = login.getResponse().getCookie("remember-me");
        assertThat(rememberMe).isNotNull();
        assertThat(rememberMe.getMaxAge()).isEqualTo(365 * 24 * 3600);
        assertThat(rememberMe.isHttpOnly()).isTrue();

        mvc.perform(get("/api/me").cookie(rememberMe))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("tester"));
    }

    @Test
    void wrongPasswordAnswers401WithMessage() throws Exception {
        login("tester", "nope")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Wrong username or password"));
    }

    @Test
    void loginWithoutCsrfTokenIsForbidden() throws Exception {
        mvc.perform(post("/login").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "tester").param("password", "secret-pass"))
                .andExpect(status().isForbidden());
    }

    @Test
    void writeWithoutCsrfTokenIsForbidden() throws Exception {
        mvc.perform(put("/api/me").with(user("tester"))).andExpect(status().isForbidden());
    }

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions login(String username, String password) throws Exception {
        return mvc.perform(post("/login").with(xsrf()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("username", username).param("password", password));
    }
}
```

`src/test/java/com/gymtracker/security/SecurityConfigTest.java`:
```java
package com.gymtracker.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class SecurityConfigTest {

    @Test
    void refusesToStartWithoutCredentials() {
        var encoder = new BCryptPasswordEncoder();
        assertThatThrownBy(() -> SecurityConfig.singleUser("", "pw", encoder))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_USERNAME");
        assertThatThrownBy(() -> SecurityConfig.singleUser("user", " ", encoder))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesToStartWithoutRememberMeKey() {
        assertThatThrownBy(() -> SecurityConfig.requireRememberMeKey(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REMEMBER_ME_KEY");
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest='SecurityIntegrationTest,SecurityConfigTest'`
Expected: compilation FAILS with `cannot find symbol: class SecurityConfig`.

- [ ] **Step 3: Implement the security configuration**

`src/main/java/com/gymtracker/security/CsrfCookieFilter.java`:
```java
package com.gymtracker.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

/** Loads the deferred CSRF token on every request so the XSRF-TOKEN cookie is always (re)written when missing. */
final class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token != null) {
            token.getToken();
        }
        chain.doFilter(request, response);
    }
}
```

`src/main/java/com/gymtracker/security/SecurityConfig.java`:
```java
package com.gymtracker.security;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.authentication.rememberme.JdbcTokenRepositoryImpl;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration
public class SecurityConfig {

    private static final int ONE_YEAR_SECONDS = 365 * 24 * 3600;

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(@Value("${app.username}") String username,
                                          @Value("${app.password}") String password,
                                          PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(singleUser(username, password, encoder));
    }

    static UserDetails singleUser(String username, String password, PasswordEncoder encoder) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalStateException("APP_USERNAME and APP_PASSWORD must be set");
        }
        return User.withUsername(username.strip()).password(encoder.encode(password)).roles("USER").build();
    }

    static String requireRememberMeKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("REMEMBER_ME_KEY must be set");
        }
        return key;
    }

    @Bean
    PersistentTokenRepository persistentTokenRepository(DataSource dataSource) {
        JdbcTokenRepositoryImpl repository = new JdbcTokenRepositoryImpl();
        repository.setDataSource(dataSource);
        return repository;
    }

    /**
     * Database-backed tokens. The hash-based variant signs cookies with the stored password hash, which changes
     * on every start because the password is re-hashed with a new salt, so every deploy would sign the user out.
     */
    @Bean
    RememberMeServices rememberMeServices(@Value("${app.remember-me-key}") String key,
                                          @Value("${app.secure-cookies}") boolean secureCookies,
                                          UserDetailsService userDetailsService,
                                          PersistentTokenRepository tokenRepository) {
        var services = new PersistentTokenBasedRememberMeServices(
                requireRememberMeKey(key), userDetailsService, tokenRepository);
        services.setAlwaysRemember(true);
        services.setTokenValiditySeconds(ONE_YEAR_SECONDS);
        services.setUseSecureCookie(secureCookies);
        return services;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            RememberMeServices rememberMeServices,
                                            @Value("${app.remember-me-key}") String key) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()).spa())
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                .formLogin(form -> form
                        .loginProcessingUrl("/login")
                        .successHandler((request, response, authentication) -> response.setStatus(200))
                        .failureHandler((request, response, exception) -> {
                            response.setStatus(401);
                            response.setContentType("application/json");
                            response.getWriter().write("{\"message\":\"Wrong username or password\"}");
                        }))
                .rememberMe(remember -> remember.rememberMeServices(rememberMeServices).key(key))
                .requestCache(cache -> cache.disable())
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        PathPatternRequestMatcher.withDefaults().matcher("/api/**")));
        return http.build();
    }
}
```

`src/main/java/com/gymtracker/security/MeController.java`:
```java
package com.gymtracker.security;

import java.security.Principal;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class MeController {

    @GetMapping("/api/me")
    Map<String, String> me(Principal principal) {
        return Map.of("username", principal.getName());
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw test -Dtest='SecurityIntegrationTest,SecurityConfigTest,SchemaMigrationTest'`
Expected: PASS, 12 tests.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Add single-user security with JSON login, remember-me and CSRF cookie"
```

---

### Task 3: Exercises API and shared error handling

**Files:**
- Create: `src/main/java/com/gymtracker/common/ApiError.java`, `NotFoundException.java`, `ConflictException.java`, `BadRequestException.java`, `GlobalExceptionHandler.java`, `ClockConfig.java`
- Create: `src/main/java/com/gymtracker/exercise/MuscleGroup.java`, `Exercise.java`, `ExerciseRepository.java`, `ExerciseRequest.java`, `ExerciseResponse.java`, `ExerciseService.java`, `ExerciseController.java`
- Test: `src/test/java/com/gymtracker/exercise/ExerciseApiTest.java`

**Interfaces:**
- Consumes: `IntegrationTestBase` helpers (Task 1). Security (Task 2).
- Produces:
  - **Exceptions:** `NotFoundException(String)`, `ConflictException(String)` and `BadRequestException(String)`, all `RuntimeException`. They are mapped to `404`/`409`/`400` with `{message}`.
  - **`Clock` bean:** `Clock.systemUTC()`.
  - **`MuscleGroup` enum** (the 10 values, in order).
  - **`Exercise` entity**, with getters `getId()`, `getName()`, `getMuscleGroup()`, `isArchived()`, `getCreatedAt()`, plus `update(String name, MuscleGroup group)` and `archive()`.
  - **`ExerciseRepository`:** `JpaRepository<Exercise, UUID>` with `List<Exercise> findByArchivedFalse()` and `boolean existsActiveNameExcluding(String name, UUID id)`.
  - **`ExerciseService`:** `List<Exercise> listActive()`, `Exercise upsert(UUID id, ExerciseRequest request)`, `void archive(UUID id)`.
  - **`ExerciseResponse` record:** `(UUID id, String name, MuscleGroup muscleGroup)`. Task 5 extends it.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/gymtracker/exercise/ExerciseApiTest.java`:
```java
package com.gymtracker.exercise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gymtracker.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExerciseApiTest extends IntegrationTestBase {

    private static String body(String name, String group) {
        return """
                {"name": "%s", "muscleGroup": "%s"}""".formatted(name, group);
    }

    @Test
    void createsAndListsExercisesSortedByNameIgnoringCase() throws Exception {
        UUID id = UUID.randomUUID();
        apiPut("/api/exercises/" + id, body("squat", "QUADS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.name").value("squat"))
                .andExpect(jsonPath("$.muscleGroup").value("QUADS"));
        apiPut("/api/exercises/" + UUID.randomUUID(), body("Deadlift", "BACK")).andExpect(status().isOk());
        apiPut("/api/exercises/" + UUID.randomUUID(), body("bench press", "CHEST")).andExpect(status().isOk());

        apiGet("/api/exercises")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name").value(org.hamcrest.Matchers.contains("bench press", "Deadlift", "squat")));
    }

    @Test
    void putIsIdempotentAndUpdates() throws Exception {
        UUID id = UUID.randomUUID();
        apiPut("/api/exercises/" + id, body("Squat", "QUADS")).andExpect(status().isOk());
        apiPut("/api/exercises/" + id, body("Squat", "QUADS")).andExpect(status().isOk());
        apiPut("/api/exercises/" + id, body("Back squat", "GLUTES"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Back squat"))
                .andExpect(jsonPath("$.muscleGroup").value("GLUTES"));
        assertThat(jdbc.queryForObject("select count(*) from exercise", Integer.class)).isEqualTo(1);
    }

    @Test
    void trimsName() throws Exception {
        apiPut("/api/exercises/" + UUID.randomUUID(), body("  Squat  ", "QUADS"))
                .andExpect(jsonPath("$.name").value("Squat"));
    }

    @Test
    void rejectsDuplicateNameIgnoringCase() throws Exception {
        apiPut("/api/exercises/" + UUID.randomUUID(), body("Squat", "QUADS")).andExpect(status().isOk());
        apiPut("/api/exercises/" + UUID.randomUUID(), body("squat", "GLUTES"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("You already have an exercise called 'squat'"));
    }

    @Test
    void renamingToOwnNameWithDifferentCaseIsAllowed() throws Exception {
        UUID id = UUID.randomUUID();
        apiPut("/api/exercises/" + id, body("squat", "QUADS")).andExpect(status().isOk());
        apiPut("/api/exercises/" + id, body("Squat", "QUADS")).andExpect(status().isOk());
    }

    @Test
    void archivingHidesExerciseAndFreesItsName() throws Exception {
        UUID id = UUID.randomUUID();
        apiPut("/api/exercises/" + id, body("Squat", "QUADS")).andExpect(status().isOk());
        apiDelete("/api/exercises/" + id).andExpect(status().isNoContent());
        apiDelete("/api/exercises/" + id).andExpect(status().isNoContent());
        apiGet("/api/exercises").andExpect(jsonPath("$", hasSize(0)));
        apiPut("/api/exercises/" + UUID.randomUUID(), body("Squat", "QUADS")).andExpect(status().isOk());
    }

    @Test
    void editingArchivedExerciseIsConflict() throws Exception {
        UUID id = UUID.randomUUID();
        apiPut("/api/exercises/" + id, body("Squat", "QUADS"));
        apiDelete("/api/exercises/" + id);
        apiPut("/api/exercises/" + id, body("Squat 2", "QUADS"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This exercise has been deleted"));
    }

    @Test
    void archivingUnknownExerciseIs404() throws Exception {
        apiDelete("/api/exercises/" + UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Exercise not found"));
    }

    @Test
    void validatesInput() throws Exception {
        String url = "/api/exercises/" + UUID.randomUUID();
        apiPut(url, body("   ", "QUADS")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("name")));
        apiPut(url, body("x".repeat(61), "QUADS")).andExpect(status().isBadRequest());
        apiPut(url, """
                {"name": "Squat"}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("muscleGroup")));
        apiPut(url, body("Squat", "LEGS")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request"));
        apiPut("/api/exercises/not-a-uuid", body("Squat", "QUADS")).andExpect(status().isBadRequest());
    }

    @Test
    void requiresSignIn() throws Exception {
        mvc.perform(get("/api/exercises")).andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=ExerciseApiTest`
Expected: the tests run but FAIL with `404` responses, because there is no controller yet. `requiresSignIn` passes.

- [ ] **Step 3: Implement the shared error handling**

`src/main/java/com/gymtracker/common/ApiError.java`:
```java
package com.gymtracker.common;

public record ApiError(String message) {
}
```

`src/main/java/com/gymtracker/common/NotFoundException.java`:
```java
package com.gymtracker.common;

public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
```

`src/main/java/com/gymtracker/common/ConflictException.java`:
```java
package com.gymtracker.common;

public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
```

`src/main/java/com/gymtracker/common/BadRequestException.java`:
```java
package com.gymtracker.common;

public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
```

`src/main/java/com/gymtracker/common/ClockConfig.java`:
```java
package com.gymtracker.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
```

`src/main/java/com/gymtracker/common/GlobalExceptionHandler.java`:
```java
package com.gymtracker.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ApiError> notFound(NotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ApiError> conflict(ConflictException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(BadRequestException.class)
    ResponseEntity<ApiError> badRequest(BadRequestException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e) {
        FieldError field = e.getBindingResult().getFieldError();
        String message = field == null ? "Invalid request" : field.getField() + " " + field.getDefaultMessage();
        return error(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> malformed(Exception e) {
        return error(HttpStatus.BAD_REQUEST, "Malformed request");
    }

    /** Backstop for the unique indexes when two requests race. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> integrity(DataIntegrityViolationException e) {
        return error(HttpStatus.CONFLICT, "This conflicts with existing data");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e) {
        if (e instanceof ErrorResponse response) {
            // Spring's own errors (unknown path, wrong method, ...) keep their status.
            String detail = response.getBody().getDetail();
            return error(response.getStatusCode(), detail == null ? "Request failed" : detail);
        }
        log.error("Unexpected error", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong");
    }

    private static ResponseEntity<ApiError> error(HttpStatusCode status, String message) {
        return ResponseEntity.status(status).body(new ApiError(message));
    }
}
```

- [ ] **Step 4: Implement the exercise domain and API**

`src/main/java/com/gymtracker/exercise/MuscleGroup.java`:
```java
package com.gymtracker.exercise;

public enum MuscleGroup {
    CHEST, BACK, SHOULDERS, BICEPS, TRICEPS, QUADS, HAMSTRINGS, CALVES, GLUTES, CORE
}
```

`src/main/java/com/gymtracker/exercise/Exercise.java`:
```java
package com.gymtracker.exercise;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "exercise")
public class Exercise {

    @Id
    private UUID id;

    @Column(nullable = false, length = 60)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "muscle_group", nullable = false, length = 20)
    private MuscleGroup muscleGroup;

    @Column(nullable = false)
    private boolean archived;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Exercise() {
    }

    public Exercise(UUID id, String name, MuscleGroup muscleGroup, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.muscleGroup = muscleGroup;
        this.createdAt = createdAt;
    }

    public void update(String name, MuscleGroup muscleGroup) {
        this.name = name;
        this.muscleGroup = muscleGroup;
    }

    public void archive() {
        this.archived = true;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public MuscleGroup getMuscleGroup() {
        return muscleGroup;
    }

    public boolean isArchived() {
        return archived;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
```

`src/main/java/com/gymtracker/exercise/ExerciseRepository.java`:
```java
package com.gymtracker.exercise;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ExerciseRepository extends JpaRepository<Exercise, UUID> {

    List<Exercise> findByArchivedFalse();

    @Query("""
            select count(e) > 0 from Exercise e
            where lower(e.name) = lower(:name) and e.archived = false and e.id <> :id""")
    boolean existsActiveNameExcluding(String name, UUID id);
}
```

`src/main/java/com/gymtracker/exercise/ExerciseRequest.java`:
```java
package com.gymtracker.exercise;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ExerciseRequest(@NotBlank @Size(max = 60) String name, @NotNull MuscleGroup muscleGroup) {
}
```

`src/main/java/com/gymtracker/exercise/ExerciseResponse.java`:
```java
package com.gymtracker.exercise;

import java.util.UUID;

public record ExerciseResponse(UUID id, String name, MuscleGroup muscleGroup) {

    static ExerciseResponse of(Exercise exercise) {
        return new ExerciseResponse(exercise.getId(), exercise.getName(), exercise.getMuscleGroup());
    }
}
```

`src/main/java/com/gymtracker/exercise/ExerciseService.java`:
```java
package com.gymtracker.exercise;

import com.gymtracker.common.ConflictException;
import com.gymtracker.common.NotFoundException;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ExerciseService {

    private final ExerciseRepository exercises;
    private final Clock clock;

    public ExerciseService(ExerciseRepository exercises, Clock clock) {
        this.exercises = exercises;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Exercise> listActive() {
        return exercises.findByArchivedFalse().stream()
                .sorted(Comparator.comparing(Exercise::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public Exercise upsert(UUID id, ExerciseRequest request) {
        String name = request.name().strip();
        if (exercises.existsActiveNameExcluding(name, id)) {
            throw new ConflictException("You already have an exercise called '" + name + "'");
        }
        return exercises.findById(id)
                .map(existing -> {
                    if (existing.isArchived()) {
                        throw new ConflictException("This exercise has been deleted");
                    }
                    existing.update(name, request.muscleGroup());
                    return existing;
                })
                .orElseGet(() -> exercises.save(new Exercise(id, name, request.muscleGroup(), clock.instant())));
    }

    public void archive(UUID id) {
        exercises.findById(id).orElseThrow(() -> new NotFoundException("Exercise not found")).archive();
    }
}
```

`src/main/java/com/gymtracker/exercise/ExerciseController.java`:
```java
package com.gymtracker.exercise;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/exercises")
class ExerciseController {

    private final ExerciseService service;

    ExerciseController(ExerciseService service) {
        this.service = service;
    }

    @GetMapping
    List<ExerciseResponse> list() {
        return service.listActive().stream().map(ExerciseResponse::of).toList();
    }

    @PutMapping("/{id}")
    ExerciseResponse put(@PathVariable UUID id, @Valid @RequestBody ExerciseRequest request) {
        return ExerciseResponse.of(service.upsert(id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        service.archive(id);
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=ExerciseApiTest`
Expected: PASS, 10 tests.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Add exercises API with archiving and shared error handling"
```

---
### Task 4: Sessions and sets API

**Files:**
- Create: `src/main/java/com/gymtracker/workout/SetType.java`, `WorkoutSession.java`, `WorkoutSet.java`, `WorkoutSessionRepository.java`, `WorkoutSetRepository.java`, `StartSessionRequest.java`, `EndSessionRequest.java`, `SetRequest.java`, `SetResponse.java`, `SessionResponse.java`, `SessionSummary.java`, `EndSessionResponse.java`, `WorkoutService.java`, `SessionController.java`, `SetController.java`
- Modify: `src/test/java/com/gymtracker/IntegrationTestBase.java` (add data helpers)
- Test: `src/test/java/com/gymtracker/workout/WorkoutApiTest.java`

**Interfaces:**
- Consumes: `ExerciseRepository`, `Exercise`, `MuscleGroup` (Task 3); the exceptions in `common` (Task 3).
- Produces:
  - **`WorkoutSession` entity:** `getId()`, `getDate()` (`LocalDate`, column `session_date`), `getStartedAt()`, `getEndedAt()`, `end(Instant)`.
  - **`WorkoutSet` entity:** `getId()`, `getSessionId()`, `getExerciseId()`, `getWeightKg()` (`BigDecimal`), `getReps()`, `getType()`, `getLoggedAt()`, `update(...)`.
  - **`WorkoutSessionRepository`:** `findFirstByEndedAtIsNull()`, `findByEndedAtIsNotNullOrderByStartedAtDesc()`.
  - **`WorkoutSetRepository`:** `findBySessionIdOrderByLoggedAtAsc(UUID)`, `findBySessionIdIn(Collection<UUID>)`, `countBySessionId(UUID)`, `deleteBySessionId(UUID)`.
  - **JSON shapes:**
    - `SetResponse {id, sessionId, exerciseId, exerciseName, muscleGroup, weightKg, reps, type, loggedAt}`;
    - `SessionResponse {id, date, startedAt, endedAt, sets[]}`;
    - `SessionSummary {id, date, startedAt, endedAt, durationSeconds, setCount, muscleGroups[]}`;
    - `EndSessionResponse {discarded, session}`.
  - **Test helpers in `IntegrationTestBase`:**
    - `UUID createExercise(String name, String muscleGroup)`;
    - `UUID startSession(String startedAt)`;
    - `UUID logSet(UUID sessionId, UUID exerciseId, String weightKg, int reps, String type, String loggedAt)`;
    - `static String setJson(...)` with the same parameters;
    - `void endSession(UUID sessionId, String endedAt)`.

- [ ] **Step 1: Add data helpers to `IntegrationTestBase`**

Add these imports:
```java
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
```
Add these methods inside the class, after `apiDelete`:
```java
    protected UUID createExercise(String name, String muscleGroup) throws Exception {
        UUID id = UUID.randomUUID();
        apiPut("/api/exercises/" + id, """
                {"name": "%s", "muscleGroup": "%s"}""".formatted(name, muscleGroup)).andExpect(status().isOk());
        return id;
    }

    /** Starts a session whose date is the date part of {@code startedAt} (an ISO instant). */
    protected UUID startSession(String startedAt) throws Exception {
        UUID id = UUID.randomUUID();
        apiPut("/api/sessions/" + id, """
                {"date": "%s", "startedAt": "%s"}""".formatted(startedAt.substring(0, 10), startedAt))
                .andExpect(status().isOk());
        return id;
    }

    protected UUID logSet(UUID sessionId, UUID exerciseId, String weightKg, int reps, String type, String loggedAt)
            throws Exception {
        UUID id = UUID.randomUUID();
        apiPut("/api/sets/" + id, setJson(sessionId, exerciseId, weightKg, reps, type, loggedAt))
                .andExpect(status().isOk());
        return id;
    }

    protected static String setJson(UUID sessionId, UUID exerciseId, String weightKg, int reps, String type,
                                    String loggedAt) {
        return """
                {"sessionId": "%s", "exerciseId": "%s", "weightKg": %s, "reps": %d, "type": "%s", "loggedAt": "%s"}"""
                .formatted(sessionId, exerciseId, weightKg, reps, type, loggedAt);
    }

    protected void endSession(UUID sessionId, String endedAt) throws Exception {
        apiPost("/api/sessions/" + sessionId + "/end", """
                {"endedAt": "%s"}""".formatted(endedAt)).andExpect(status().isOk());
    }
```

- [ ] **Step 2: Write the failing tests**

`src/test/java/com/gymtracker/workout/WorkoutApiTest.java`:
```java
package com.gymtracker.workout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gymtracker.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkoutApiTest extends IntegrationTestBase {

    private UUID squat;

    @BeforeEach
    void createSquat() throws Exception {
        squat = createExercise("Squat", "QUADS");
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    @Test
    void startsSessionAndReturnsItAsActive() throws Exception {
        UUID id = UUID.randomUUID();
        apiPut("/api/sessions/" + id, """
                {"date": "2026-10-05", "startedAt": "2026-10-05T08:00:00Z"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.date").value("2026-10-05"))
                .andExpect(jsonPath("$.startedAt").value("2026-10-05T08:00:00Z"))
                .andExpect(jsonPath("$.endedAt").value(nullValue()))
                .andExpect(jsonPath("$.sets", hasSize(0)));
        apiGet("/api/sessions/active").andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    void noActiveSessionAnswers204() throws Exception {
        apiGet("/api/sessions/active").andExpect(status().isNoContent());
    }

    @Test
    void resendingStartIsHarmless() throws Exception {
        UUID id = UUID.randomUUID();
        String body = """
                {"date": "2026-10-05", "startedAt": "2026-10-05T08:00:00Z"}""";
        apiPut("/api/sessions/" + id, body).andExpect(status().isOk());
        apiPut("/api/sessions/" + id, body).andExpect(status().isOk());
        assertThat(count("workout_session")).isEqualTo(1);
    }

    @Test
    void secondOpenSessionIsConflict() throws Exception {
        startSession("2026-10-05T08:00:00Z");
        apiPut("/api/sessions/" + UUID.randomUUID(), """
                {"date": "2026-10-05", "startedAt": "2026-10-05T09:00:00Z"}""")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Another workout is already in progress"));
    }

    @Test
    void logsSetsAndReturnsThemInLoggedOrder() throws Exception {
        UUID session = startSession("2026-10-05T08:00:00Z");
        logSet(session, squat, "42.5", 8, "WORK", "2026-10-05T08:10:00Z");
        logSet(session, squat, "20", 10, "WARMUP", "2026-10-05T08:05:00Z");

        apiGet("/api/sessions/active")
                .andExpect(jsonPath("$.sets", hasSize(2)))
                .andExpect(jsonPath("$.sets[0].type").value("WARMUP"))
                .andExpect(jsonPath("$.sets[0].weightKg").value(20.0))
                .andExpect(jsonPath("$.sets[1].weightKg").value(42.5))
                .andExpect(jsonPath("$.sets[1].reps").value(8))
                .andExpect(jsonPath("$.sets[1].exerciseName").value("Squat"))
                .andExpect(jsonPath("$.sets[1].muscleGroup").value("QUADS"));
    }

    @Test
    void resendingSetIsIdempotentAndPutEditsIt() throws Exception {
        UUID session = startSession("2026-10-05T08:00:00Z");
        UUID setId = UUID.randomUUID();
        String body = setJson(session, squat, "40", 10, "WORK", "2026-10-05T08:10:00Z");
        apiPut("/api/sets/" + setId, body).andExpect(status().isOk());
        apiPut("/api/sets/" + setId, body).andExpect(status().isOk());
        apiPut("/api/sets/" + setId, setJson(session, squat, "40", 12, "WORK", "2026-10-05T08:10:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reps").value(12));
        assertThat(count("workout_set")).isEqualTo(1);
    }

    @Test
    void validatesSets() throws Exception {
        UUID session = startSession("2026-10-05T08:00:00Z");
        String at = "2026-10-05T08:10:00Z";
        apiPut("/api/sets/" + UUID.randomUUID(), setJson(session, squat, "500.25", 5, "WORK", at))
                .andExpect(status().isBadRequest());
        apiPut("/api/sets/" + UUID.randomUUID(), setJson(session, squat, "-1", 5, "WORK", at))
                .andExpect(status().isBadRequest());
        apiPut("/api/sets/" + UUID.randomUUID(), setJson(session, squat, "42.3", 5, "WORK", at))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("weightKg must be a multiple of 0.25"));
        apiPut("/api/sets/" + UUID.randomUUID(), setJson(session, squat, "40", 0, "WORK", at))
                .andExpect(status().isBadRequest());
        apiPut("/api/sets/" + UUID.randomUUID(), setJson(session, squat, "40", 101, "WORK", at))
                .andExpect(status().isBadRequest());
        apiPut("/api/sets/" + UUID.randomUUID(), setJson(session, squat, "40", 5, "HEAVY", at))
                .andExpect(status().isBadRequest());
        apiPut("/api/sets/" + UUID.randomUUID(), setJson(session, squat, "500", 1, "WORK", at))
                .andExpect(status().isOk());
        apiPut("/api/sets/" + UUID.randomUUID(), setJson(session, squat, "0", 15, "WORK", at))
                .andExpect(status().isOk());
    }

    @Test
    void unknownSessionOrExerciseIs404() throws Exception {
        UUID session = startSession("2026-10-05T08:00:00Z");
        apiPut("/api/sets/" + UUID.randomUUID(), setJson(UUID.randomUUID(), squat, "40", 5, "WORK", "2026-10-05T08:10:00Z"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Workout not found"));
        apiPut("/api/sets/" + UUID.randomUUID(), setJson(session, UUID.randomUUID(), "40", 5, "WORK", "2026-10-05T08:10:00Z"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Exercise not found"));
    }

    @Test
    void setsOfEndedSessionCannotChange() throws Exception {
        UUID session = startSession("2026-10-05T08:00:00Z");
        UUID setId = logSet(session, squat, "40", 10, "WORK", "2026-10-05T08:10:00Z");
        endSession(session, "2026-10-05T09:00:00Z");
        apiPut("/api/sets/" + UUID.randomUUID(), setJson(session, squat, "40", 10, "WORK", "2026-10-05T08:20:00Z"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This workout has already ended"));
        apiDelete("/api/sets/" + setId).andExpect(status().isConflict());
    }

    @Test
    void newSetForArchivedExerciseIsConflict() throws Exception {
        UUID session = startSession("2026-10-05T08:00:00Z");
        apiDelete("/api/exercises/" + squat).andExpect(status().isNoContent());
        apiPut("/api/sets/" + UUID.randomUUID(), setJson(session, squat, "40", 10, "WORK", "2026-10-05T08:10:00Z"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This exercise has been deleted"));
    }

    @Test
    void deletesSetIdempotently() throws Exception {
        UUID session = startSession("2026-10-05T08:00:00Z");
        UUID setId = logSet(session, squat, "40", 10, "WORK", "2026-10-05T08:10:00Z");
        apiDelete("/api/sets/" + setId).andExpect(status().isNoContent());
        apiDelete("/api/sets/" + setId).andExpect(status().isNoContent());
        assertThat(count("workout_set")).isZero();
    }

    @Test
    void endingTwiceKeepsOriginalEndedAt() throws Exception {
        UUID session = startSession("2026-10-05T08:00:00Z");
        logSet(session, squat, "40", 10, "WORK", "2026-10-05T08:10:00Z");
        apiPost("/api/sessions/" + session + "/end", """
                {"endedAt": "2026-10-05T09:00:00Z"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discarded").value(false))
                .andExpect(jsonPath("$.session.endedAt").value("2026-10-05T09:00:00Z"));
        apiPost("/api/sessions/" + session + "/end", """
                {"endedAt": "2026-10-05T10:00:00Z"}""")
                .andExpect(jsonPath("$.session.endedAt").value("2026-10-05T09:00:00Z"));
        apiGet("/api/sessions/active").andExpect(status().isNoContent());
    }

    @Test
    void endingEmptySessionDeletesIt() throws Exception {
        UUID session = startSession("2026-10-05T08:00:00Z");
        apiPost("/api/sessions/" + session + "/end", """
                {"endedAt": "2026-10-05T08:05:00Z"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discarded").value(true))
                .andExpect(jsonPath("$.session").value(nullValue()));
        assertThat(count("workout_session")).isZero();
        apiGet("/api/sessions").andExpect(jsonPath("$", hasSize(0)));
        apiPost("/api/sessions/" + session + "/end", """
                {"endedAt": "2026-10-05T08:05:00Z"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discarded").value(true));
    }

    @Test
    void endBeforeStartIs400() throws Exception {
        UUID session = startSession("2026-10-05T08:00:00Z");
        logSet(session, squat, "40", 10, "WORK", "2026-10-05T08:10:00Z");
        apiPost("/api/sessions/" + session + "/end", """
                {"endedAt": "2026-10-05T07:00:00Z"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("endedAt must not be before startedAt"));
    }

    @Test
    void discardRemovesSessionAndItsSets() throws Exception {
        UUID session = startSession("2026-10-05T08:00:00Z");
        logSet(session, squat, "40", 10, "WORK", "2026-10-05T08:10:00Z");
        logSet(session, squat, "40", 10, "WORK", "2026-10-05T08:12:00Z");
        apiDelete("/api/sessions/" + session).andExpect(status().isNoContent());
        apiDelete("/api/sessions/" + session).andExpect(status().isNoContent());
        assertThat(count("workout_session")).isZero();
        assertThat(count("workout_set")).isZero();
    }

    @Test
    void historyListsEndedSessionsNewestFirstWithSummary() throws Exception {
        UUID bench = createExercise("Bench press", "CHEST");
        UUID first = startSession("2026-10-01T08:00:00Z");
        logSet(first, squat, "60", 5, "WORK", "2026-10-01T08:10:00Z");
        logSet(first, bench, "40", 8, "WORK", "2026-10-01T08:20:00Z");
        endSession(first, "2026-10-01T08:45:00Z");
        UUID second = startSession("2026-10-03T18:00:00Z");
        logSet(second, squat, "62.5", 5, "WORK", "2026-10-03T18:10:00Z");
        endSession(second, "2026-10-03T18:30:00Z");
        startSession("2026-10-05T08:00:00Z");

        apiGet("/api/sessions")
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(second.toString()))
                .andExpect(jsonPath("$[1].date").value("2026-10-01"))
                .andExpect(jsonPath("$[1].durationSeconds").value(2700))
                .andExpect(jsonPath("$[1].setCount").value(2))
                .andExpect(jsonPath("$[1].muscleGroups", contains("QUADS", "CHEST")));
    }

    @Test
    void sessionCrossingMidnightKeepsItsStartDate() throws Exception {
        UUID session = startSession("2026-10-05T23:50:00Z");
        logSet(session, squat, "40", 10, "WORK", "2026-10-05T23:55:00Z");
        endSession(session, "2026-10-06T00:30:00Z");
        apiGet("/api/sessions")
                .andExpect(jsonPath("$[0].date").value("2026-10-05"))
                .andExpect(jsonPath("$[0].durationSeconds").value(2400));
    }

    @Test
    void getsOneSessionWithItsSets() throws Exception {
        UUID session = startSession("2026-10-05T08:00:00Z");
        logSet(session, squat, "40", 10, "WORK", "2026-10-05T08:10:00Z");
        endSession(session, "2026-10-05T09:00:00Z");
        apiGet("/api/sessions/" + session)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sets[0].exerciseName").value("Squat"));
        apiGet("/api/sessions/" + UUID.randomUUID()).andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=WorkoutApiTest`
Expected: the tests run but FAIL (`404` for `/api/sessions/...`).

- [ ] **Step 4: Implement entities and repositories**

`src/main/java/com/gymtracker/workout/SetType.java`:
```java
package com.gymtracker.workout;

public enum SetType {
    WARMUP, WORK
}
```

`src/main/java/com/gymtracker/workout/WorkoutSession.java`:
```java
package com.gymtracker.workout;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "workout_session")
public class WorkoutSession {

    @Id
    private UUID id;

    @Column(name = "session_date", nullable = false)
    private LocalDate date;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    protected WorkoutSession() {
    }

    public WorkoutSession(UUID id, LocalDate date, Instant startedAt) {
        this.id = id;
        this.date = date;
        this.startedAt = startedAt;
    }

    public void end(Instant endedAt) {
        this.endedAt = endedAt;
    }

    public UUID getId() {
        return id;
    }

    public LocalDate getDate() {
        return date;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }
}
```

`src/main/java/com/gymtracker/workout/WorkoutSet.java`:
```java
package com.gymtracker.workout;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "workout_set")
public class WorkoutSet {

    @Id
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "exercise_id", nullable = false)
    private UUID exerciseId;

    @Column(name = "weight_kg", nullable = false, precision = 6, scale = 2)
    private BigDecimal weightKg;

    @Column(nullable = false)
    private int reps;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SetType type;

    @Column(name = "logged_at", nullable = false)
    private Instant loggedAt;

    protected WorkoutSet() {
    }

    public WorkoutSet(UUID id, UUID sessionId, UUID exerciseId, BigDecimal weightKg, int reps, SetType type,
                      Instant loggedAt) {
        this.id = id;
        this.sessionId = sessionId;
        update(exerciseId, weightKg, reps, type, loggedAt);
    }

    public void update(UUID exerciseId, BigDecimal weightKg, int reps, SetType type, Instant loggedAt) {
        this.exerciseId = exerciseId;
        this.weightKg = weightKg;
        this.reps = reps;
        this.type = type;
        this.loggedAt = loggedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public UUID getExerciseId() {
        return exerciseId;
    }

    public BigDecimal getWeightKg() {
        return weightKg;
    }

    public int getReps() {
        return reps;
    }

    public SetType getType() {
        return type;
    }

    public Instant getLoggedAt() {
        return loggedAt;
    }
}
```

`src/main/java/com/gymtracker/workout/WorkoutSessionRepository.java`:
```java
package com.gymtracker.workout;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkoutSessionRepository extends JpaRepository<WorkoutSession, UUID> {

    Optional<WorkoutSession> findFirstByEndedAtIsNull();

    List<WorkoutSession> findByEndedAtIsNotNullOrderByStartedAtDesc();
}
```

`src/main/java/com/gymtracker/workout/WorkoutSetRepository.java`:
```java
package com.gymtracker.workout;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface WorkoutSetRepository extends JpaRepository<WorkoutSet, UUID> {

    List<WorkoutSet> findBySessionIdOrderByLoggedAtAsc(UUID sessionId);

    List<WorkoutSet> findBySessionIdIn(Collection<UUID> sessionIds);

    long countBySessionId(UUID sessionId);

    @Modifying
    @Query("delete from WorkoutSet s where s.sessionId = :sessionId")
    void deleteBySessionId(UUID sessionId);
}
```

- [ ] **Step 5: Implement the DTOs**

`src/main/java/com/gymtracker/workout/StartSessionRequest.java`:
```java
package com.gymtracker.workout;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;

public record StartSessionRequest(@NotNull LocalDate date, @NotNull Instant startedAt) {
}
```

`src/main/java/com/gymtracker/workout/EndSessionRequest.java`:
```java
package com.gymtracker.workout;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record EndSessionRequest(@NotNull Instant endedAt) {
}
```

`src/main/java/com/gymtracker/workout/SetRequest.java`:
```java
package com.gymtracker.workout;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SetRequest(
        @NotNull UUID sessionId,
        @NotNull UUID exerciseId,
        @NotNull @DecimalMin("0") @DecimalMax("500") BigDecimal weightKg,
        @NotNull @Min(1) @Max(100) Integer reps,
        @NotNull SetType type,
        @NotNull Instant loggedAt) {
}
```

`src/main/java/com/gymtracker/workout/SetResponse.java`:
```java
package com.gymtracker.workout;

import com.gymtracker.exercise.Exercise;
import com.gymtracker.exercise.MuscleGroup;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SetResponse(UUID id, UUID sessionId, UUID exerciseId, String exerciseName, MuscleGroup muscleGroup,
                          BigDecimal weightKg, int reps, SetType type, Instant loggedAt) {

    static SetResponse of(WorkoutSet set, Exercise exercise) {
        return new SetResponse(set.getId(), set.getSessionId(), set.getExerciseId(), exercise.getName(),
                exercise.getMuscleGroup(), set.getWeightKg(), set.getReps(), set.getType(), set.getLoggedAt());
    }
}
```

`src/main/java/com/gymtracker/workout/SessionResponse.java`:
```java
package com.gymtracker.workout;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record SessionResponse(UUID id, LocalDate date, Instant startedAt, Instant endedAt, List<SetResponse> sets) {
}
```

`src/main/java/com/gymtracker/workout/SessionSummary.java`:
```java
package com.gymtracker.workout;

import com.gymtracker.exercise.MuscleGroup;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record SessionSummary(UUID id, LocalDate date, Instant startedAt, Instant endedAt, long durationSeconds,
                             int setCount, List<MuscleGroup> muscleGroups) {
}
```

`src/main/java/com/gymtracker/workout/EndSessionResponse.java`:
```java
package com.gymtracker.workout;

public record EndSessionResponse(boolean discarded, SessionResponse session) {

    static EndSessionResponse discardedResult() {
        return new EndSessionResponse(true, null);
    }

    static EndSessionResponse ended(SessionResponse session) {
        return new EndSessionResponse(false, session);
    }
}
```

- [ ] **Step 6: Implement the service and controllers**

`src/main/java/com/gymtracker/workout/WorkoutService.java`:
```java
package com.gymtracker.workout;

import static java.util.stream.Collectors.groupingBy;

import com.gymtracker.common.BadRequestException;
import com.gymtracker.common.ConflictException;
import com.gymtracker.common.NotFoundException;
import com.gymtracker.exercise.Exercise;
import com.gymtracker.exercise.ExerciseRepository;
import com.gymtracker.exercise.MuscleGroup;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class WorkoutService {

    private static final BigDecimal QUARTERS_PER_KG = BigDecimal.valueOf(4);

    private final WorkoutSessionRepository sessions;
    private final WorkoutSetRepository sets;
    private final ExerciseRepository exercises;

    public WorkoutService(WorkoutSessionRepository sessions, WorkoutSetRepository sets, ExerciseRepository exercises) {
        this.sessions = sessions;
        this.sets = sets;
        this.exercises = exercises;
    }

    public SessionResponse start(UUID id, StartSessionRequest request) {
        Optional<WorkoutSession> existing = sessions.findById(id);
        if (existing.isPresent()) {
            return toResponse(existing.get());
        }
        if (sessions.findFirstByEndedAtIsNull().isPresent()) {
            throw new ConflictException("Another workout is already in progress");
        }
        return toResponse(sessions.save(new WorkoutSession(id, request.date(), request.startedAt())));
    }

    public EndSessionResponse end(UUID id, EndSessionRequest request) {
        Optional<WorkoutSession> found = sessions.findById(id);
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

    public void discard(UUID id) {
        sets.deleteBySessionId(id);
        sessions.findById(id).ifPresent(sessions::delete);
    }

    @Transactional(readOnly = true)
    public Optional<SessionResponse> active() {
        return sessions.findFirstByEndedAtIsNull().map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public SessionResponse get(UUID id) {
        return sessions.findById(id).map(this::toResponse).orElseThrow(() -> new NotFoundException("Workout not found"));
    }

    @Transactional(readOnly = true)
    public List<SessionSummary> history() {
        List<WorkoutSession> ended = sessions.findByEndedAtIsNotNullOrderByStartedAtDesc();
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

    public SetResponse putSet(UUID id, SetRequest request) {
        if (!isQuarterStep(request.weightKg())) {
            throw new BadRequestException("weightKg must be a multiple of 0.25");
        }
        WorkoutSession session = sessions.findById(request.sessionId())
                .orElseThrow(() -> new NotFoundException("Workout not found"));
        if (session.getEndedAt() != null) {
            throw new ConflictException("This workout has already ended");
        }
        Exercise exercise = exercises.findById(request.exerciseId())
                .orElseThrow(() -> new NotFoundException("Exercise not found"));
        Optional<WorkoutSet> existing = sets.findById(id);
        if (existing.isPresent()) {
            WorkoutSet set = existing.get();
            if (!set.getSessionId().equals(request.sessionId())) {
                throw new ConflictException("This set belongs to another workout");
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

    public void deleteSet(UUID id) {
        sets.findById(id).ifPresent(set -> {
            boolean ended = sessions.findById(set.getSessionId()).map(s -> s.getEndedAt() != null).orElse(false);
            if (ended) {
                throw new ConflictException("This workout has already ended");
            }
            sets.delete(set);
        });
    }

    static boolean isQuarterStep(BigDecimal weight) {
        return weight.multiply(QUARTERS_PER_KG).stripTrailingZeros().scale() <= 0;
    }

    private SessionResponse toResponse(WorkoutSession session) {
        List<WorkoutSet> sessionSets = sets.findBySessionIdOrderByLoggedAtAsc(session.getId());
        Map<UUID, Exercise> exerciseById = exercisesFor(sessionSets);
        List<SetResponse> setResponses = sessionSets.stream()
                .map(set -> SetResponse.of(set, exerciseById.get(set.getExerciseId())))
                .toList();
        return new SessionResponse(session.getId(), session.getDate(), session.getStartedAt(), session.getEndedAt(),
                setResponses);
    }

    private Map<UUID, Exercise> exercisesFor(Collection<WorkoutSet> workoutSets) {
        List<UUID> ids = workoutSets.stream().map(WorkoutSet::getExerciseId).distinct().toList();
        return exercises.findAllById(ids).stream().collect(Collectors.toMap(Exercise::getId, Function.identity()));
    }
}
```

`src/main/java/com/gymtracker/workout/SessionController.java`:
```java
package com.gymtracker.workout;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sessions")
class SessionController {

    private final WorkoutService service;

    SessionController(WorkoutService service) {
        this.service = service;
    }

    @GetMapping("/active")
    ResponseEntity<SessionResponse> active() {
        return service.active().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping
    List<SessionSummary> history() {
        return service.history();
    }

    @GetMapping("/{id}")
    SessionResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    SessionResponse start(@PathVariable UUID id, @Valid @RequestBody StartSessionRequest request) {
        return service.start(id, request);
    }

    @PostMapping("/{id}/end")
    EndSessionResponse end(@PathVariable UUID id, @Valid @RequestBody EndSessionRequest request) {
        return service.end(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void discard(@PathVariable UUID id) {
        service.discard(id);
    }
}
```

`src/main/java/com/gymtracker/workout/SetController.java`:
```java
package com.gymtracker.workout;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sets")
class SetController {

    private final WorkoutService service;

    SetController(WorkoutService service) {
        this.service = service;
    }

    @PutMapping("/{id}")
    SetResponse put(@PathVariable UUID id, @Valid @RequestBody SetRequest request) {
        return service.putSet(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        service.deleteSet(id);
    }
}
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./mvnw test -Dtest='WorkoutApiTest,ExerciseApiTest'`
Expected: PASS, 28 tests.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "Add sessions and sets API with idempotent writes and history"
```

---

### Task 5: Stats: records, last time, chart data, weekly overview

**Files:**
- Create: `src/main/java/com/gymtracker/workout/WorkSetRow.java`, `WeeklySetRow.java`
- Modify: `src/main/java/com/gymtracker/workout/WorkoutSetRepository.java`, `WorkoutSessionRepository.java`
- Create: `src/main/java/com/gymtracker/stats/LastTime.java`, `RecordSet.java`, `Records.java`, `SessionPoint.java`, `ExerciseStats.java`, `ExerciseHistory.java`, `WeekStats.java`, `RecordsCalculator.java`, `StatsService.java`, `StatsController.java`
- Modify: `src/main/java/com/gymtracker/exercise/ExerciseResponse.java`, `ExerciseController.java`
- Test: `src/test/java/com/gymtracker/stats/RecordsCalculatorTest.java`, `StatsApiTest.java`

**Interfaces:**
- Consumes: the workout repositories and entities (Task 4); `ExerciseRepository` and `MuscleGroup` (Task 3).
- Produces:
  - **`WorkSetRow`:** `(UUID exerciseId, UUID sessionId, LocalDate date, BigDecimal weightKg, int reps, Instant loggedAt)`.
  - **`RecordsCalculator`** (static methods):
    - `Records records(List<WorkSetRow>)`;
    - `LastTime lastTime(List<WorkSetRow>)`;
    - `List<SessionPoint> sessionPoints(List<WorkSetRow>)`;
    - `BigDecimal estimatedOneRepMax(BigDecimal weight, int reps)`.
  - **`StatsService`:**
    - `Map<UUID, ExerciseHistory> historyByExercise()`;
    - `ExerciseHistory historyFor(UUID)`;
    - `ExerciseStats exerciseStats(UUID)`;
    - `List<WeekStats> weekly(int weeks, LocalDate today)`.
  - **JSON shapes:**
    - `GET /api/exercises` items become `{id, name, muscleGroup, lastTime: {weightKg, reps, date} | null, records: {heaviest: {weightKg, reps, date} | null, repRecords: [{weightKg, reps, date}]}}`;
    - `GET /api/exercises/{id}/stats` → `{records, sessions: [{date, maxWeightKg, est1rmKg}]}`;
    - `GET /api/stats/weekly?weeks=12&today=YYYY-MM-DD` → `[{weekStart, workouts, workSetsByMuscle: {QUADS: 2}}]`.

- [ ] **Step 1: Write the failing unit test for the calculator**

`src/test/java/com/gymtracker/stats/RecordsCalculatorTest.java`:
```java
package com.gymtracker.stats;

import static org.assertj.core.api.Assertions.assertThat;

import com.gymtracker.workout.WorkSetRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecordsCalculatorTest {

    private static final UUID EXERCISE = UUID.randomUUID();
    private static final UUID S1 = UUID.randomUUID();
    private static final UUID S2 = UUID.randomUUID();
    private static final UUID S3 = UUID.randomUUID();

    private static WorkSetRow row(UUID session, String date, String weight, int reps, String time) {
        return new WorkSetRow(EXERCISE, session, LocalDate.parse(date), new BigDecimal(weight), reps,
                Instant.parse(date + "T" + time + ":00Z"));
    }

    @Test
    void emptyHistoryHasNoRecords() {
        Records records = RecordsCalculator.records(List.of());
        assertThat(records.heaviest()).isNull();
        assertThat(records.repRecords()).isEmpty();
        assertThat(RecordsCalculator.lastTime(List.of())).isNull();
        assertThat(RecordsCalculator.sessionPoints(List.of())).isEmpty();
    }

    @Test
    void heaviestIgnoresBodyweightAndBreaksTiesByRepsThenEarliestDate() {
        Records records = RecordsCalculator.records(List.of(
                row(S1, "2026-09-01", "60.00", 5, "08:00"),
                row(S1, "2026-09-01", "0.00", 20, "08:10"),
                row(S2, "2026-09-08", "60.00", 6, "08:00"),
                row(S3, "2026-09-15", "60.00", 6, "08:00")));
        assertThat(records.heaviest().weightKg()).isEqualByComparingTo("60");
        assertThat(records.heaviest().reps()).isEqualTo(6);
        assertThat(records.heaviest().date()).isEqualTo(LocalDate.parse("2026-09-08"));
    }

    @Test
    void onlyBodyweightGivesRepRecordButNoHeaviest() {
        Records records = RecordsCalculator.records(List.of(row(S1, "2026-09-01", "0.00", 20, "08:00")));
        assertThat(records.heaviest()).isNull();
        assertThat(records.repRecords()).hasSize(1);
        assertThat(records.repRecords().getFirst().reps()).isEqualTo(20);
    }

    @Test
    void repRecordsHoldMostRepsPerWeightHeaviestFirstWithEarliestDate() {
        Records records = RecordsCalculator.records(List.of(
                row(S1, "2026-09-01", "40.00", 10, "08:00"),
                row(S1, "2026-09-01", "0.00", 15, "08:05"),
                row(S2, "2026-09-08", "40.00", 12, "08:00"),
                row(S2, "2026-09-08", "50.00", 5, "08:10"),
                row(S3, "2026-09-15", "40.00", 12, "08:00")));
        List<RecordSet> reps = records.repRecords();
        assertThat(reps).extracting(r -> r.weightKg().intValue()).containsExactly(50, 40, 0);
        assertThat(reps.get(1).reps()).isEqualTo(12);
        assertThat(reps.get(1).date()).isEqualTo(LocalDate.parse("2026-09-08"));
        assertThat(reps.get(2).reps()).isEqualTo(15);
    }

    @Test
    void lastTimeIsMostRecentSet() {
        LastTime last = RecordsCalculator.lastTime(List.of(
                row(S2, "2026-09-08", "45.00", 8, "08:00"),
                row(S1, "2026-09-01", "40.00", 10, "08:00"),
                row(S2, "2026-09-08", "47.50", 6, "08:20")));
        assertThat(last.weightKg()).isEqualByComparingTo("47.5");
        assertThat(last.reps()).isEqualTo(6);
        assertThat(last.date()).isEqualTo(LocalDate.parse("2026-09-08"));
    }

    @Test
    void estimatedOneRepMaxUsesEpleyRoundedToHalfKilo() {
        assertThat(RecordsCalculator.estimatedOneRepMax(new BigDecimal("40"), 10)).isEqualByComparingTo("53.5");
        assertThat(RecordsCalculator.estimatedOneRepMax(new BigDecimal("100"), 1)).isEqualByComparingTo("100.0");
        assertThat(RecordsCalculator.estimatedOneRepMax(new BigDecimal("60"), 5)).isEqualByComparingTo("70.0");
        assertThat(RecordsCalculator.estimatedOneRepMax(new BigDecimal("42.5"), 8)).isEqualByComparingTo("54.0");
    }

    @Test
    void sessionPointsAreOnePerSessionInOrderAndSkipBodyweightOnlySessions() {
        List<SessionPoint> points = RecordsCalculator.sessionPoints(List.of(
                row(S2, "2026-09-08", "60.00", 5, "08:00"),
                row(S2, "2026-09-08", "50.00", 10, "08:10"),
                row(S1, "2026-09-01", "55.00", 5, "08:00"),
                row(S3, "2026-09-15", "0.00", 20, "08:00")));
        assertThat(points).hasSize(2);
        assertThat(points.get(0).date()).isEqualTo(LocalDate.parse("2026-09-01"));
        assertThat(points.get(0).maxWeightKg()).isEqualByComparingTo("55");
        assertThat(points.get(0).est1rmKg()).isEqualByComparingTo("64.0");
        assertThat(points.get(1).maxWeightKg()).isEqualByComparingTo("60");
        assertThat(points.get(1).est1rmKg()).isEqualByComparingTo("70.0");
    }
}
```

- [ ] **Step 2: Run the unit test to verify it fails**

Run: `./mvnw test -Dtest=RecordsCalculatorTest`
Expected: compilation FAILS (`WorkSetRow`, `RecordsCalculator` do not exist).

- [ ] **Step 3: Implement the rows, records types and calculator**

`src/main/java/com/gymtracker/workout/WorkSetRow.java`:
```java
package com.gymtracker.workout;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One WORK set from an ended session, with the session's date. */
public record WorkSetRow(UUID exerciseId, UUID sessionId, LocalDate date, BigDecimal weightKg, int reps,
                         Instant loggedAt) {
}
```

`src/main/java/com/gymtracker/workout/WeeklySetRow.java`:
```java
package com.gymtracker.workout;

import com.gymtracker.exercise.MuscleGroup;
import java.time.LocalDate;

/** One WORK set from an ended session: the session's date and the exercise's muscle group. */
public record WeeklySetRow(LocalDate date, MuscleGroup muscleGroup) {
}
```

`src/main/java/com/gymtracker/stats/LastTime.java`:
```java
package com.gymtracker.stats;

import java.math.BigDecimal;
import java.time.LocalDate;

public record LastTime(BigDecimal weightKg, int reps, LocalDate date) {
}
```

`src/main/java/com/gymtracker/stats/RecordSet.java`:
```java
package com.gymtracker.stats;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RecordSet(BigDecimal weightKg, int reps, LocalDate date) {
}
```

`src/main/java/com/gymtracker/stats/Records.java`:
```java
package com.gymtracker.stats;

import java.util.List;

public record Records(RecordSet heaviest, List<RecordSet> repRecords) {

    public static final Records EMPTY = new Records(null, List.of());
}
```

`src/main/java/com/gymtracker/stats/SessionPoint.java`:
```java
package com.gymtracker.stats;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SessionPoint(LocalDate date, BigDecimal maxWeightKg, BigDecimal est1rmKg) {
}
```

`src/main/java/com/gymtracker/stats/ExerciseStats.java`:
```java
package com.gymtracker.stats;

import java.util.List;

public record ExerciseStats(Records records, List<SessionPoint> sessions) {
}
```

`src/main/java/com/gymtracker/stats/ExerciseHistory.java`:
```java
package com.gymtracker.stats;

import com.gymtracker.workout.WorkSetRow;
import java.util.List;

/** What the exercise list needs per exercise: "last time" and the PR records. */
public record ExerciseHistory(LastTime lastTime, Records records) {

    public static final ExerciseHistory EMPTY = new ExerciseHistory(null, Records.EMPTY);

    public static ExerciseHistory of(List<WorkSetRow> rows) {
        return new ExerciseHistory(RecordsCalculator.lastTime(rows), RecordsCalculator.records(rows));
    }
}
```

`src/main/java/com/gymtracker/stats/WeekStats.java`:
```java
package com.gymtracker.stats;

import com.gymtracker.exercise.MuscleGroup;
import java.time.LocalDate;
import java.util.Map;

public record WeekStats(LocalDate weekStart, int workouts, Map<MuscleGroup, Integer> workSetsByMuscle) {
}
```

`src/main/java/com/gymtracker/stats/RecordsCalculator.java`:
```java
package com.gymtracker.stats;

import static java.util.stream.Collectors.groupingBy;

import com.gymtracker.workout.WorkSetRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Pure calculations over WORK sets from ended sessions of one exercise (spec §5). */
public final class RecordsCalculator {

    private static final BigDecimal THIRTY = BigDecimal.valueOf(30);
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private RecordsCalculator() {
    }

    public static Records records(List<WorkSetRow> rows) {
        RecordSet heaviest = rows.stream()
                .filter(row -> row.weightKg().signum() > 0)
                .max(Comparator.comparing(WorkSetRow::weightKg)
                        .thenComparingInt(WorkSetRow::reps)
                        .thenComparing(WorkSetRow::loggedAt, Comparator.reverseOrder()))
                .map(row -> new RecordSet(row.weightKg(), row.reps(), row.date()))
                .orElse(null);

        // TreeMap compares BigDecimals by value, so 40.0 and 40.00 share a key.
        Map<BigDecimal, WorkSetRow> bestByWeight = new TreeMap<>(Comparator.reverseOrder());
        rows.stream().sorted(Comparator.comparing(WorkSetRow::loggedAt)).forEach(row -> {
            WorkSetRow best = bestByWeight.get(row.weightKg());
            if (best == null || row.reps() > best.reps()) {
                bestByWeight.put(row.weightKg(), row);
            }
        });
        List<RecordSet> repRecords = bestByWeight.values().stream()
                .map(row -> new RecordSet(row.weightKg(), row.reps(), row.date()))
                .toList();
        return new Records(heaviest, repRecords);
    }

    public static LastTime lastTime(List<WorkSetRow> rows) {
        return rows.stream()
                .max(Comparator.comparing(WorkSetRow::loggedAt))
                .map(row -> new LastTime(row.weightKg(), row.reps(), row.date()))
                .orElse(null);
    }

    public static List<SessionPoint> sessionPoints(List<WorkSetRow> rows) {
        Map<?, List<WorkSetRow>> bySession = rows.stream()
                .filter(row -> row.weightKg().signum() > 0)
                .collect(groupingBy(WorkSetRow::sessionId));
        record Timed(Instant firstLoggedAt, SessionPoint point) {
        }
        List<Timed> points = new ArrayList<>();
        for (List<WorkSetRow> sessionRows : bySession.values()) {
            WorkSetRow first = sessionRows.stream().min(Comparator.comparing(WorkSetRow::loggedAt)).orElseThrow();
            BigDecimal max = sessionRows.stream().map(WorkSetRow::weightKg).max(Comparator.naturalOrder()).orElseThrow();
            BigDecimal est = sessionRows.stream()
                    .map(row -> estimatedOneRepMax(row.weightKg(), row.reps()))
                    .max(Comparator.naturalOrder())
                    .orElseThrow();
            points.add(new Timed(first.loggedAt(), new SessionPoint(first.date(), max, est)));
        }
        return points.stream().sorted(Comparator.comparing(Timed::firstLoggedAt)).map(Timed::point).toList();
    }

    /** Epley: weight × (1 + reps/30); a 1-rep set counts as its own weight. Rounded to 0.5 kg. */
    public static BigDecimal estimatedOneRepMax(BigDecimal weight, int reps) {
        BigDecimal raw = reps == 1
                ? weight
                : weight.multiply(BigDecimal.valueOf(30L + reps)).divide(THIRTY, 6, RoundingMode.HALF_UP);
        return raw.multiply(TWO).setScale(0, RoundingMode.HALF_UP).divide(TWO, 1, RoundingMode.UNNECESSARY);
    }
}
```

- [ ] **Step 4: Run the unit test to verify it passes**

Run: `./mvnw test -Dtest=RecordsCalculatorTest`
Expected: PASS, 7 tests.

- [ ] **Step 5: Write the failing API tests**

`src/test/java/com/gymtracker/stats/StatsApiTest.java`:
```java
package com.gymtracker.stats;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gymtracker.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StatsApiTest extends IntegrationTestBase {

    @Test
    void exercisesIncludeLastTimeAndRecordsFromEndedSessionsOnly() throws Exception {
        UUID squat = createExercise("Squat", "QUADS");
        UUID ended = startSession("2026-09-28T08:00:00Z");
        logSet(ended, squat, "20", 10, "WARMUP", "2026-09-28T08:05:00Z");
        logSet(ended, squat, "60", 5, "WORK", "2026-09-28T08:10:00Z");
        logSet(ended, squat, "50", 8, "WORK", "2026-09-28T08:20:00Z");
        endSession(ended, "2026-09-28T09:00:00Z");
        UUID open = startSession("2026-10-05T08:00:00Z");
        logSet(open, squat, "100", 1, "WORK", "2026-10-05T08:10:00Z");

        apiGet("/api/exercises")
                .andExpect(jsonPath("$[0].lastTime.weightKg").value(50.0))
                .andExpect(jsonPath("$[0].lastTime.reps").value(8))
                .andExpect(jsonPath("$[0].lastTime.date").value("2026-09-28"))
                .andExpect(jsonPath("$[0].records.heaviest.weightKg").value(60.0))
                .andExpect(jsonPath("$[0].records.heaviest.reps").value(5))
                .andExpect(jsonPath("$[0].records.repRecords", hasSize(2)))
                .andExpect(jsonPath("$[0].records.repRecords[0].weightKg").value(60.0));
    }

    @Test
    void exerciseWithoutHistoryHasEmptyRecords() throws Exception {
        createExercise("Squat", "QUADS");
        apiGet("/api/exercises")
                .andExpect(jsonPath("$[0].lastTime").value(nullValue()))
                .andExpect(jsonPath("$[0].records.heaviest").value(nullValue()))
                .andExpect(jsonPath("$[0].records.repRecords", hasSize(0)));
    }

    @Test
    void exerciseStatsHaveOnePointPerEndedSession() throws Exception {
        UUID squat = createExercise("Squat", "QUADS");
        UUID session = startSession("2026-09-28T08:00:00Z");
        logSet(session, squat, "60", 5, "WORK", "2026-09-28T08:10:00Z");
        logSet(session, squat, "50", 8, "WORK", "2026-09-28T08:20:00Z");
        endSession(session, "2026-09-28T09:00:00Z");

        apiGet("/api/exercises/" + squat + "/stats")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records.heaviest.weightKg").value(60.0))
                .andExpect(jsonPath("$.sessions", hasSize(1)))
                .andExpect(jsonPath("$.sessions[0].date").value("2026-09-28"))
                .andExpect(jsonPath("$.sessions[0].maxWeightKg").value(60.0))
                .andExpect(jsonPath("$.sessions[0].est1rmKg").value(70.0));
        apiGet("/api/exercises/" + UUID.randomUUID() + "/stats")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Exercise not found"));
    }

    @Test
    void weeklyCountsEndedSessionsPerMondayWeek() throws Exception {
        UUID squat = createExercise("Squat", "QUADS");
        UUID bench = createExercise("Bench press", "CHEST");
        UUID sunday = startSession("2026-10-04T08:00:00Z");
        logSet(sunday, squat, "20", 10, "WARMUP", "2026-10-04T08:05:00Z");
        endSession(sunday, "2026-10-04T08:30:00Z");
        UUID monday = startSession("2026-10-05T08:00:00Z");
        logSet(monday, squat, "60", 5, "WORK", "2026-10-05T08:10:00Z");
        logSet(monday, squat, "60", 5, "WORK", "2026-10-05T08:15:00Z");
        logSet(monday, bench, "40", 8, "WORK", "2026-10-05T08:25:00Z");
        logSet(monday, bench, "20", 10, "WARMUP", "2026-10-05T08:20:00Z");
        endSession(monday, "2026-10-05T09:00:00Z");
        UUID open = startSession("2026-10-06T08:00:00Z");
        logSet(open, squat, "60", 5, "WORK", "2026-10-06T08:10:00Z");

        apiGet("/api/stats/weekly?weeks=3&today=2026-10-07")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].weekStart").value("2026-09-21"))
                .andExpect(jsonPath("$[0].workouts").value(0))
                .andExpect(jsonPath("$[1].weekStart").value("2026-09-28"))
                .andExpect(jsonPath("$[1].workouts").value(1))
                .andExpect(jsonPath("$[1].workSetsByMuscle").isEmpty())
                .andExpect(jsonPath("$[2].weekStart").value("2026-10-05"))
                .andExpect(jsonPath("$[2].workouts").value(1))
                .andExpect(jsonPath("$[2].workSetsByMuscle.QUADS").value(2))
                .andExpect(jsonPath("$[2].workSetsByMuscle.CHEST").value(1));
    }

    @Test
    void weeklyValidatesWeeksAndDefaultsToTwelve() throws Exception {
        apiGet("/api/stats/weekly?weeks=0").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("weeks must be between 1 and 52"));
        apiGet("/api/stats/weekly?weeks=53").andExpect(status().isBadRequest());
        apiGet("/api/stats/weekly").andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(12)));
    }
}
```

- [ ] **Step 6: Run the API tests to verify they fail**

Run: `./mvnw test -Dtest=StatsApiTest`
Expected: FAIL. `$[0].lastTime` doesn't exist, and `/api/stats/weekly` answers `404`.

- [ ] **Step 7: Add the repository queries**

Add to `WorkoutSetRepository` (the `import java.time.LocalDate;` import as well):
```java
    @Query("""
            select new com.gymtracker.workout.WorkSetRow(s.exerciseId, s.sessionId, ws.date, s.weightKg, s.reps, s.loggedAt)
            from WorkoutSet s join WorkoutSession ws on ws.id = s.sessionId
            where s.type = com.gymtracker.workout.SetType.WORK and ws.endedAt is not null""")
    List<WorkSetRow> findEndedWorkSets();

    @Query("""
            select new com.gymtracker.workout.WorkSetRow(s.exerciseId, s.sessionId, ws.date, s.weightKg, s.reps, s.loggedAt)
            from WorkoutSet s join WorkoutSession ws on ws.id = s.sessionId
            where s.type = com.gymtracker.workout.SetType.WORK and ws.endedAt is not null
              and s.exerciseId = :exerciseId""")
    List<WorkSetRow> findEndedWorkSetsForExercise(UUID exerciseId);

    @Query("""
            select new com.gymtracker.workout.WeeklySetRow(ws.date, e.muscleGroup)
            from WorkoutSet s
              join WorkoutSession ws on ws.id = s.sessionId
              join com.gymtracker.exercise.Exercise e on e.id = s.exerciseId
            where s.type = com.gymtracker.workout.SetType.WORK and ws.endedAt is not null
              and ws.date between :from and :to""")
    List<WeeklySetRow> findEndedWorkSetMuscles(LocalDate from, LocalDate to);
```

Add to `WorkoutSessionRepository` (with `import java.time.LocalDate;`):
```java
    List<WorkoutSession> findByEndedAtIsNotNullAndDateBetween(LocalDate from, LocalDate to);
```

- [ ] **Step 8: Implement the stats service and controller**

`src/main/java/com/gymtracker/stats/StatsService.java`:
```java
package com.gymtracker.stats;

import static java.util.stream.Collectors.groupingBy;

import com.gymtracker.common.NotFoundException;
import com.gymtracker.exercise.ExerciseRepository;
import com.gymtracker.exercise.MuscleGroup;
import com.gymtracker.workout.WeeklySetRow;
import com.gymtracker.workout.WorkSetRow;
import com.gymtracker.workout.WorkoutSession;
import com.gymtracker.workout.WorkoutSessionRepository;
import com.gymtracker.workout.WorkoutSetRepository;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class StatsService {

    private final WorkoutSetRepository sets;
    private final WorkoutSessionRepository sessions;
    private final ExerciseRepository exercises;

    public StatsService(WorkoutSetRepository sets, WorkoutSessionRepository sessions, ExerciseRepository exercises) {
        this.sets = sets;
        this.sessions = sessions;
        this.exercises = exercises;
    }

    public Map<UUID, ExerciseHistory> historyByExercise() {
        Map<UUID, ExerciseHistory> result = new HashMap<>();
        sets.findEndedWorkSets().stream()
                .collect(groupingBy(WorkSetRow::exerciseId))
                .forEach((exerciseId, rows) -> result.put(exerciseId, ExerciseHistory.of(rows)));
        return result;
    }

    public ExerciseHistory historyFor(UUID exerciseId) {
        return ExerciseHistory.of(sets.findEndedWorkSetsForExercise(exerciseId));
    }

    public ExerciseStats exerciseStats(UUID exerciseId) {
        if (!exercises.existsById(exerciseId)) {
            throw new NotFoundException("Exercise not found");
        }
        List<WorkSetRow> rows = sets.findEndedWorkSetsForExercise(exerciseId);
        return new ExerciseStats(RecordsCalculator.records(rows), RecordsCalculator.sessionPoints(rows));
    }

    /** The {@code weeks} Monday-to-Sunday weeks ending with the week that contains {@code today}, oldest first. */
    public List<WeekStats> weekly(int weeks, LocalDate today) {
        LocalDate thisWeek = weekStart(today);
        LocalDate from = thisWeek.minusWeeks(weeks - 1L);
        LocalDate to = thisWeek.plusDays(6);

        Map<LocalDate, Integer> workouts = new HashMap<>();
        for (WorkoutSession session : sessions.findByEndedAtIsNotNullAndDateBetween(from, to)) {
            workouts.merge(weekStart(session.getDate()), 1, Integer::sum);
        }
        Map<LocalDate, Map<MuscleGroup, Integer>> muscles = new HashMap<>();
        for (WeeklySetRow row : sets.findEndedWorkSetMuscles(from, to)) {
            muscles.computeIfAbsent(weekStart(row.date()), week -> new EnumMap<>(MuscleGroup.class))
                    .merge(row.muscleGroup(), 1, Integer::sum);
        }

        List<WeekStats> result = new ArrayList<>();
        for (int i = 0; i < weeks; i++) {
            LocalDate week = from.plusWeeks(i);
            result.add(new WeekStats(week, workouts.getOrDefault(week, 0),
                    muscles.getOrDefault(week, new EnumMap<>(MuscleGroup.class))));
        }
        return result;
    }

    private static LocalDate weekStart(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }
}
```

`src/main/java/com/gymtracker/stats/StatsController.java`:
```java
package com.gymtracker.stats;

import com.gymtracker.common.BadRequestException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class StatsController {

    private final StatsService stats;
    private final Clock clock;

    StatsController(StatsService stats, Clock clock) {
        this.stats = stats;
        this.clock = clock;
    }

    @GetMapping("/api/exercises/{id}/stats")
    ExerciseStats exerciseStats(@PathVariable UUID id) {
        return stats.exerciseStats(id);
    }

    @GetMapping("/api/stats/weekly")
    List<WeekStats> weekly(@RequestParam(defaultValue = "12") int weeks,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate today) {
        if (weeks < 1 || weeks > 52) {
            throw new BadRequestException("weeks must be between 1 and 52");
        }
        return stats.weekly(weeks, today != null ? today : LocalDate.now(clock));
    }
}
```

- [ ] **Step 9: Include last time and records in the exercise responses**

Replace `src/main/java/com/gymtracker/exercise/ExerciseResponse.java` with:
```java
package com.gymtracker.exercise;

import com.gymtracker.stats.ExerciseHistory;
import com.gymtracker.stats.LastTime;
import com.gymtracker.stats.Records;
import java.util.UUID;

public record ExerciseResponse(UUID id, String name, MuscleGroup muscleGroup, LastTime lastTime, Records records) {

    static ExerciseResponse of(Exercise exercise, ExerciseHistory history) {
        return new ExerciseResponse(exercise.getId(), exercise.getName(), exercise.getMuscleGroup(),
                history.lastTime(), history.records());
    }
}
```

In `ExerciseController`, add these imports:
```java
import com.gymtracker.stats.ExerciseHistory;
import com.gymtracker.stats.StatsService;
import java.util.Map;
```
Then replace the field, constructor, `list()` and `put(...)` with:
```java
    private final ExerciseService service;
    private final StatsService stats;

    ExerciseController(ExerciseService service, StatsService stats) {
        this.service = service;
        this.stats = stats;
    }

    @GetMapping
    List<ExerciseResponse> list() {
        Map<UUID, ExerciseHistory> histories = stats.historyByExercise();
        return service.listActive().stream()
                .map(e -> ExerciseResponse.of(e, histories.getOrDefault(e.getId(), ExerciseHistory.EMPTY)))
                .toList();
    }

    @PutMapping("/{id}")
    ExerciseResponse put(@PathVariable UUID id, @Valid @RequestBody ExerciseRequest request) {
        Exercise exercise = service.upsert(id, request);
        return ExerciseResponse.of(exercise, stats.historyFor(id));
    }
```

- [ ] **Step 10: Run all backend tests**

Run: `./mvnw test`
Expected: PASS, all tests: SchemaMigration 3, Security 9, Exercise 10, Workout 18, RecordsCalculator 7, StatsApi 5.

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "Add personal records, last time, chart data and weekly stats"
```

---

### Task 6: Push notifications and server-side rest timer

**Files:**
- Create: `src/main/java/com/gymtracker/push/PushSubscription.java`, `PushSubscriptionRepository.java`, `PushSubscriptionService.java`, `PushSender.java`, `WebPushSender.java`, `RestTimerService.java`, `PushConfig.java`, `PushController.java`, `VapidKeyGenerator.java`
- Create (test): `src/test/java/com/gymtracker/push/FakePushSender.java`, `TestPushConfig.java`
- Modify: `src/test/java/com/gymtracker/IntegrationTestBase.java` (import `TestPushConfig`)
- Test: `src/test/java/com/gymtracker/push/PushApiTest.java`, `VapidKeyGeneratorTest.java`

**Interfaces:**
- Consumes: `BadRequestException` (Task 3), the `Clock` bean (Task 3).
- Produces:
  - **`PushSender`:** `SendResult send(PushSubscription subscription, String payloadJson)`, with nested `enum SendResult { DELIVERED, GONE, FAILED }`.
  - **`RestTimerService`:** `schedule(Instant endsAt)` and `cancel()`.
  - **Endpoints:**
    - `GET /api/push/public-key` → `{publicKey}`;
    - `PUT /api/push/subscription` with `{endpoint, keys: {p256dh, auth}}` → `200`;
    - `PUT /api/rest-timer` with `{endsAt}` → `204`;
    - `DELETE /api/rest-timer` → `204`.
  - **Notification payload:** `{"title":"Rest's over","body":"Time for your next set 💪"}`.
  - **`VapidKeyGenerator`:** `main` prints `VAPID_PUBLIC_KEY=...` and `VAPID_PRIVATE_KEY=...`; `static String[] generate()`.
  - **`FakePushSender`** (test): `List<String> sent()`, `List<String> payloads()`, `markGone(String endpoint)`, `reset()`.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/gymtracker/push/VapidKeyGeneratorTest.java`:
```java
package com.gymtracker.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class VapidKeyGeneratorTest {

    @Test
    void generatesKeysThatWebPushAccepts() throws Exception {
        String[] keys = VapidKeyGenerator.generate();
        assertThat(keys[0]).hasSize(87).matches("[A-Za-z0-9_-]+");
        assertThat(keys[1]).hasSize(43).matches("[A-Za-z0-9_-]+");
        new WebPushSender(keys[0], keys[1], "mailto:test@example.com");
    }

    @Test
    void refusesToStartWithoutKeys() {
        assertThatThrownBy(() -> new WebPushSender("", "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VAPID_PUBLIC_KEY");
    }
}
```

`src/test/java/com/gymtracker/push/FakePushSender.java`:
```java
package com.gymtracker.push;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class FakePushSender implements PushSender {

    private final List<String> sent = new CopyOnWriteArrayList<>();
    private final List<String> payloads = new CopyOnWriteArrayList<>();
    private final Set<String> gone = ConcurrentHashMap.newKeySet();

    @Override
    public SendResult send(PushSubscription subscription, String payloadJson) {
        sent.add(subscription.getEndpoint());
        payloads.add(payloadJson);
        return gone.contains(subscription.getEndpoint()) ? SendResult.GONE : SendResult.DELIVERED;
    }

    public List<String> sent() {
        return List.copyOf(sent);
    }

    public List<String> payloads() {
        return List.copyOf(payloads);
    }

    public void markGone(String endpoint) {
        gone.add(endpoint);
    }

    public void reset() {
        sent.clear();
        payloads.clear();
        gone.clear();
    }
}
```

`src/test/java/com/gymtracker/push/TestPushConfig.java`:
```java
package com.gymtracker.push;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class TestPushConfig {

    @Bean
    @Primary
    FakePushSender fakePushSender() {
        return new FakePushSender();
    }
}
```

In `IntegrationTestBase`, add `import com.gymtracker.push.TestPushConfig;` and change the import annotation to:
```java
@Import({TestcontainersConfiguration.class, TestPushConfig.class})
```

`src/test/java/com/gymtracker/push/PushApiTest.java`:
```java
package com.gymtracker.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gymtracker.IntegrationTestBase;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PushApiTest extends IntegrationTestBase {

    @Autowired
    FakePushSender pushSender;

    @Autowired
    RestTimerService restTimer;

    @AfterEach
    void reset() {
        restTimer.cancel();
        pushSender.reset();
    }

    private void subscribe(String endpoint, String p256dh, String auth) throws Exception {
        apiPut("/api/push/subscription", """
                {"endpoint": "%s", "keys": {"p256dh": "%s", "auth": "%s"}}""".formatted(endpoint, p256dh, auth))
                .andExpect(status().isOk());
    }

    private void scheduleIn(Duration delay) throws Exception {
        apiPut("/api/rest-timer", """
                {"endsAt": "%s"}""".formatted(Instant.now().plus(delay))).andExpect(status().isNoContent());
    }

    private static void waitFor(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met within " + timeout);
            }
            Thread.sleep(50);
        }
    }

    @Test
    void exposesPublicKey() throws Exception {
        apiGet("/api/push/public-key")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicKey").value(org.hamcrest.Matchers.startsWith("BLeb9PGJ")));
    }

    @Test
    void savesSubscriptionOnceAndUpdatesItsKeys() throws Exception {
        subscribe("https://push.example/1", "key-1", "auth-1");
        subscribe("https://push.example/1", "key-2", "auth-2");
        assertThat(jdbc.queryForObject("select count(*) from push_subscription", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select p256dh from push_subscription", String.class)).isEqualTo("key-2");
    }

    @Test
    void validatesSubscription() throws Exception {
        apiPut("/api/push/subscription", """
                {"endpoint": "https://push.example/1"}""").andExpect(status().isBadRequest());
    }

    @Test
    void sendsAlertAtEndTime() throws Exception {
        subscribe("https://push.example/1", "key", "auth");
        scheduleIn(Duration.ofSeconds(1));
        assertThat(pushSender.sent()).isEmpty();
        waitFor(() -> pushSender.sent().size() == 1, Duration.ofSeconds(5));
        assertThat(pushSender.payloads().getFirst()).contains("Rest's over");
    }

    @Test
    void newTimerReplacesTheOldOne() throws Exception {
        subscribe("https://push.example/1", "key", "auth");
        scheduleIn(Duration.ofSeconds(1));
        scheduleIn(Duration.ofSeconds(3));
        Thread.sleep(2000);
        assertThat(pushSender.sent()).isEmpty();
        waitFor(() -> pushSender.sent().size() == 1, Duration.ofSeconds(4));
        Thread.sleep(500);
        assertThat(pushSender.sent()).hasSize(1);
    }

    @Test
    void cancelStopsTheAlert() throws Exception {
        subscribe("https://push.example/1", "key", "auth");
        scheduleIn(Duration.ofSeconds(1));
        apiDelete("/api/rest-timer").andExpect(status().isNoContent());
        Thread.sleep(2000);
        assertThat(pushSender.sent()).isEmpty();
    }

    @Test
    void cancelWithoutTimerIsFine() throws Exception {
        apiDelete("/api/rest-timer").andExpect(status().isNoContent());
    }

    @Test
    void rejectsPastOrFarFutureEndTimes() throws Exception {
        apiPut("/api/rest-timer", """
                {"endsAt": "%s"}""".formatted(Instant.now().minusSeconds(1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("endsAt must be in the future and at most 1 hour away"));
        apiPut("/api/rest-timer", """
                {"endsAt": "%s"}""".formatted(Instant.now().plus(Duration.ofMinutes(61))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void goneSubscriptionIsRemoved() throws Exception {
        subscribe("https://push.example/old", "key", "auth");
        subscribe("https://push.example/new", "key", "auth");
        pushSender.markGone("https://push.example/old");
        scheduleIn(Duration.ofSeconds(1));
        waitFor(() -> pushSender.sent().size() == 2, Duration.ofSeconds(5));
        waitFor(() -> jdbc.queryForObject("select count(*) from push_subscription", Integer.class) == 1,
                Duration.ofSeconds(2));
        assertThat(jdbc.queryForObject("select endpoint from push_subscription", String.class))
                .isEqualTo("https://push.example/new");
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest='PushApiTest,VapidKeyGeneratorTest'`
Expected: compilation FAILS (`PushSender`, `PushSubscription`, `RestTimerService` and others are missing).

- [ ] **Step 3: Implement the subscription storage**

`src/main/java/com/gymtracker/push/PushSubscription.java`:
```java
package com.gymtracker.push;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "push_subscription")
public class PushSubscription {

    @Id
    @Column(length = 2048)
    private String endpoint;

    @Column(nullable = false)
    private String p256dh;

    @Column(nullable = false)
    private String auth;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PushSubscription() {
    }

    public PushSubscription(String endpoint, String p256dh, String auth, Instant createdAt) {
        this.endpoint = endpoint;
        this.p256dh = p256dh;
        this.auth = auth;
        this.createdAt = createdAt;
    }

    public void updateKeys(String p256dh, String auth) {
        this.p256dh = p256dh;
        this.auth = auth;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getP256dh() {
        return p256dh;
    }

    public String getAuth() {
        return auth;
    }
}
```

`src/main/java/com/gymtracker/push/PushSubscriptionRepository.java`:
```java
package com.gymtracker.push;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, String> {
}
```

`src/main/java/com/gymtracker/push/PushSubscriptionService.java`:
```java
package com.gymtracker.push;

import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PushSubscriptionService {

    private final PushSubscriptionRepository repository;
    private final Clock clock;

    public PushSubscriptionService(PushSubscriptionRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public void save(String endpoint, String p256dh, String auth) {
        repository.findById(endpoint).ifPresentOrElse(
                existing -> existing.updateKeys(p256dh, auth),
                () -> repository.save(new PushSubscription(endpoint, p256dh, auth, clock.instant())));
    }

    @Transactional(readOnly = true)
    public List<PushSubscription> all() {
        return repository.findAll();
    }

    public void remove(String endpoint) {
        repository.deleteById(endpoint);
    }
}
```

- [ ] **Step 4: Implement sending and the timer**

`src/main/java/com/gymtracker/push/PushSender.java`:
```java
package com.gymtracker.push;

public interface PushSender {

    enum SendResult { DELIVERED, GONE, FAILED }

    SendResult send(PushSubscription subscription, String payloadJson);
}
```

`src/main/java/com/gymtracker/push/WebPushSender.java`:
```java
package com.gymtracker.push;

import java.security.GeneralSecurityException;
import java.security.Security;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Urgency;
import org.apache.http.HttpResponse;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WebPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(WebPushSender.class);
    private static final int TTL_SECONDS = 60;

    private final PushService pushService;

    public WebPushSender(@Value("${app.vapid.public-key}") String publicKey,
                         @Value("${app.vapid.private-key}") String privateKey,
                         @Value("${app.vapid.subject}") String subject) {
        if (isBlank(publicKey) || isBlank(privateKey) || isBlank(subject)) {
            throw new IllegalStateException("VAPID_PUBLIC_KEY, VAPID_PRIVATE_KEY and VAPID_SUBJECT must be set");
        }
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        try {
            this.pushService = new PushService(publicKey, privateKey, subject);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Invalid VAPID keys", e);
        }
    }

    @Override
    public SendResult send(PushSubscription subscription, String payloadJson) {
        try {
            Notification notification = Notification.builder()
                    .endpoint(subscription.getEndpoint())
                    .userPublicKey(subscription.getP256dh())
                    .userAuth(subscription.getAuth())
                    .payload(payloadJson)
                    .ttl(TTL_SECONDS)
                    .urgency(Urgency.HIGH)
                    .build();
            HttpResponse response = pushService.send(notification, Encoding.AES128GCM);
            int status = response.getStatusLine().getStatusCode();
            if (status == 404 || status == 410) {
                return SendResult.GONE;
            }
            if (status >= 200 && status < 300) {
                return SendResult.DELIVERED;
            }
            log.warn("Push service answered {} for {}", status, subscription.getEndpoint());
            return SendResult.FAILED;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Sending push notification failed", e);
            return SendResult.FAILED;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
```

`src/main/java/com/gymtracker/push/RestTimerService.java`:
```java
package com.gymtracker.push;

import com.gymtracker.common.BadRequestException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

/** Holds the one pending "rest is over" alert in memory; a restart loses it, which the spec accepts. */
@Service
public class RestTimerService {

    static final String PAYLOAD = "{\"title\":\"Rest's over\",\"body\":\"Time for your next set 💪\"}";
    private static final Duration MAX_AHEAD = Duration.ofHours(1);

    private final TaskScheduler scheduler;
    private final PushSubscriptionService subscriptions;
    private final PushSender sender;
    private final Clock clock;

    private ScheduledFuture<?> pending;
    private long generation;

    public RestTimerService(TaskScheduler scheduler, PushSubscriptionService subscriptions, PushSender sender,
                            Clock clock) {
        this.scheduler = scheduler;
        this.subscriptions = subscriptions;
        this.sender = sender;
        this.clock = clock;
    }

    public synchronized void schedule(Instant endsAt) {
        Instant now = clock.instant();
        if (!endsAt.isAfter(now) || endsAt.isAfter(now.plus(MAX_AHEAD))) {
            throw new BadRequestException("endsAt must be in the future and at most 1 hour away");
        }
        cancel();
        long current = ++generation;
        pending = scheduler.schedule(() -> fire(current), endsAt);
    }

    public synchronized void cancel() {
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
    }

    private void fire(long firedGeneration) {
        synchronized (this) {
            if (firedGeneration != generation) {
                return;
            }
            pending = null;
        }
        for (PushSubscription subscription : subscriptions.all()) {
            if (sender.send(subscription, PAYLOAD) == PushSender.SendResult.GONE) {
                subscriptions.remove(subscription.getEndpoint());
            }
        }
    }
}
```

`src/main/java/com/gymtracker/push/PushConfig.java`:
```java
package com.gymtracker.push;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
class PushConfig {

    @Bean
    ThreadPoolTaskScheduler restTimerScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("rest-timer-");
        return scheduler;
    }
}
```

`src/main/java/com/gymtracker/push/PushController.java`:
```java
package com.gymtracker.push;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
class PushController {

    record Keys(@NotBlank String p256dh, @NotBlank String auth) {
    }

    record SubscriptionRequest(@NotBlank @Size(max = 2048) String endpoint, @NotNull @Valid Keys keys) {
    }

    record RestTimerRequest(@NotNull Instant endsAt) {
    }

    private final String publicKey;
    private final PushSubscriptionService subscriptions;
    private final RestTimerService restTimer;

    PushController(@Value("${app.vapid.public-key}") String publicKey, PushSubscriptionService subscriptions,
                   RestTimerService restTimer) {
        this.publicKey = publicKey;
        this.subscriptions = subscriptions;
        this.restTimer = restTimer;
    }

    @GetMapping("/push/public-key")
    Map<String, String> publicKey() {
        return Map.of("publicKey", publicKey);
    }

    @PutMapping("/push/subscription")
    void subscribe(@Valid @RequestBody SubscriptionRequest request) {
        subscriptions.save(request.endpoint(), request.keys().p256dh(), request.keys().auth());
    }

    @PutMapping("/rest-timer")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void schedule(@Valid @RequestBody RestTimerRequest request) {
        restTimer.schedule(request.endsAt());
    }

    @DeleteMapping("/rest-timer")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancel() {
        restTimer.cancel();
    }
}
```

`src/main/java/com/gymtracker/push/VapidKeyGenerator.java`:
```java
package com.gymtracker.push;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/**
 * Prints a fresh VAPID key pair for Railway's variables:
 * {@code ./mvnw -q compile && java -cp target/classes com.gymtracker.push.VapidKeyGenerator}
 */
public final class VapidKeyGenerator {

    private VapidKeyGenerator() {
    }

    public static void main(String[] args) throws Exception {
        String[] keys = generate();
        System.out.println("VAPID_PUBLIC_KEY=" + keys[0]);
        System.out.println("VAPID_PRIVATE_KEY=" + keys[1]);
    }

    /** Returns {publicKey, privateKey}: base64url without padding; the public key is the uncompressed P-256 point. */
    public static String[] generate() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        ECPublicKey publicKey = (ECPublicKey) pair.getPublic();
        ECPrivateKey privateKey = (ECPrivateKey) pair.getPrivate();

        byte[] point = new byte[65];
        point[0] = 0x04;
        System.arraycopy(unsigned32(publicKey.getW().getAffineX()), 0, point, 1, 32);
        System.arraycopy(unsigned32(publicKey.getW().getAffineY()), 0, point, 33, 32);

        Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        return new String[] {base64.encodeToString(point), base64.encodeToString(unsigned32(privateKey.getS()))};
    }

    private static byte[] unsigned32(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[32];
        int length = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - length, out, 32 - length, length);
        return out;
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw test -Dtest='PushApiTest,VapidKeyGeneratorTest'`
Expected: PASS, 11 tests. This takes about 15 s because the timer tests wait in real time.

- [ ] **Step 6: Run the whole backend suite**

Run: `./mvnw test`
Expected: PASS, every test class so far.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "Add web push subscriptions and server-side rest timer alerts"
```

---
### Task 7: Frontend foundation: shell, login, offline store and sync, Exercises tab

**Files:**
- Create: `tools/make_icons.py`, then run it to create `src/main/resources/static/icons/icon-192.png`, `icon-512.png` and `apple-touch-icon.png`
- Create: `src/main/resources/static/index.html`, `styles.css`, `manifest.json`, `sw.js`
- Create: `src/main/resources/static/js/util.js`, `format.js`, `api.js`, `store.js`, `sync.js`, `router.js`, `ui.js`, `app.js`
- Create: `src/main/resources/static/js/views/login.js`, `views/exercises.js`
- Create (test): `src/test/resources/static/test/unit.html`
- Test: `src/test/java/com/gymtracker/ServiceWorkerAssetsTest.java`, `src/test/java/com/gymtracker/e2e/E2ETestBase.java`, `JsUnitE2ETest.java`, `LoginAndExercisesE2ETest.java`

**Interfaces:**
- Consumes: every API from Tasks 2–6.
- Produces. These are the JS module exports later tasks rely on:
  - **`util.js`:** `uuid()`, `esc(value)`, `localDateIso(date = new Date())`.
  - **`format.js`:**
    - `MUSCLE_GROUPS`, `muscleLabel(group)`;
    - `formatWeight(kg)`, `formatSet(weightKg, reps)`, `formatSetCount(n)`;
    - `formatClock(seconds)`, `formatCountdown(seconds)`, `formatDuration(seconds)`;
    - `formatDate(iso, {weekday})`, `formatLongDate(iso)`;
    - `parseWeight(text)`.
  - **`api.js`:** `request(method, path, body?, {form}?)` → `{kind: 'ok'|'network'|'unauthorized'|'forbidden'|'rejected', status?, data?, message?}`; `login(username, password)`.
  - **`store.js`:**
    - `view()`, `subscribe(fn)`, `onDispatch(fn)`, `dispatch(kind, payload)`;
    - `pending()`, `peek()`, `shift()`;
    - `setSnapshot(patch)`, `cacheSessionDetail(id, detail)`, `cacheExerciseStats(id, stats)`;
    - `applyOps(state, ops)`, `summarize(session, endedAt)`.
  - **Outbox operation kinds:** `exercise.put {id,name,muscleGroup}`, `exercise.delete {id}`, `session.start {id,date,startedAt}`, `session.end {id,endedAt}`, `session.discard {id}`, `set.put {id,sessionId,exerciseId,weightKg,reps,type,loggedAt}`, `set.delete {id}`.
  - **`sync.js`:** `flush()`, `startSync()`, `onUnauthorized(fn)`, `onRejected(fn)`. It sets `document.body.dataset.sync` to `syncing`, `idle`, `offline` or `signed-out`.
  - **`router.js`:** `getRoute()`, `navigate(route)`, `setRenderer(fn)`. A route is `{tab, ...extra}`.
  - **`ui.js`:** `toast(message)`, `openSheet(html, {onClose}) → {el, close}`, `confirmDialog(message, {ok, danger}) → Promise<boolean>`.
  - **`app.js`:** a `TABS` array of `{id, label, icon, view}`, where each view module exports `render(container, route)`.
  - **`views/exercises.js`:** `render(container, route)`, `openExerciseForm(exercise | null, {onSaved, onDeleted})`, `validateExercise(name, group, selfId, exercises)`.
  - **`E2ETestBase`** (Java):
    - fields `page`, `context`, `jdbc`;
    - helpers `signIn()`, `button(name)`, `tab(name)`, `createExerciseViaUi(name, muscleLabel)`, `waitUntilSynced()`, `count(sql)`;
    - seeding helpers `seedExercise(name, group)`, `seedEndedSession(date, startedAt, endedAt)`, `seedSet(sessionId, exerciseId, weight, reps, type, loggedAt)`.

- [ ] **Step 1: Write the failing service-worker asset test**

`src/test/java/com/gymtracker/ServiceWorkerAssetsTest.java`:
```java
package com.gymtracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Every static file must be precached by the service worker, or the app opens broken offline. */
class ServiceWorkerAssetsTest {

    private static final Path STATIC = Path.of("src/main/resources/static");

    @Test
    void everyStaticFileIsListedInServiceWorker() throws IOException {
        String serviceWorker = Files.readString(STATIC.resolve("sw.js"));
        try (Stream<Path> files = Files.walk(STATIC)) {
            List<String> missing = files.filter(Files::isRegularFile)
                    .map(path -> "/" + STATIC.relativize(path).toString().replace('\\', '/'))
                    .filter(path -> !path.equals("/sw.js") && !path.endsWith(".DS_Store"))
                    .filter(path -> !serviceWorker.contains("'" + path + "'"))
                    .toList();
            assertThat(missing).isEmpty();
        }
    }
}
```

Run: `./mvnw test -Dtest=ServiceWorkerAssetsTest`
Expected: FAIL with `NoSuchFileException: src/main/resources/static/sw.js`.

- [ ] **Step 2: Generate the app icons**

`tools/make_icons.py`:
```python
"""Writes the app icons (a white dumbbell on the accent blue) as PNGs. Run: python3 tools/make_icons.py"""
import pathlib
import struct
import zlib

OUT = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources/static/icons"
BACKGROUND = (42, 120, 214)  # #2a78d6
FOREGROUND = (255, 255, 255)
# (left, top, right, bottom) as fractions of the icon size
SHAPES = [
    (0.20, 0.47, 0.80, 0.53),  # bar
    (0.26, 0.30, 0.34, 0.70), (0.36, 0.36, 0.42, 0.64),  # left plates
    (0.66, 0.30, 0.74, 0.70), (0.58, 0.36, 0.64, 0.64),  # right plates
]


def png(size, rows):
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    raw = b"".join(b"\x00" + bytes(row) for row in rows)
    header = struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")


def icon(size):
    rows = []
    for y in range(size):
        row = bytearray()
        fy = (y + 0.5) / size
        for x in range(size):
            fx = (x + 0.5) / size
            inside = any(left <= fx <= right and top <= fy <= bottom for left, top, right, bottom in SHAPES)
            row += bytes(FOREGROUND if inside else BACKGROUND)
        rows.append(row)
    return png(size, rows)


OUT.mkdir(parents=True, exist_ok=True)
for name, size in [("icon-192.png", 192), ("icon-512.png", 512), ("apple-touch-icon.png", 180)]:
    (OUT / name).write_bytes(icon(size))
    print("wrote", OUT / name)
```

Run: `python3 tools/make_icons.py`
Expected: three `wrote .../icons/...png` lines.

- [ ] **Step 3: Write the page, manifest and styles**

`src/main/resources/static/index.html`:
```html
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
  <meta name="apple-mobile-web-app-capable" content="yes">
  <meta name="mobile-web-app-capable" content="yes">
  <meta name="apple-mobile-web-app-status-bar-style" content="default">
  <meta name="apple-mobile-web-app-title" content="Gym">
  <meta name="theme-color" content="#fcfcfb" media="(prefers-color-scheme: light)">
  <meta name="theme-color" content="#1a1a19" media="(prefers-color-scheme: dark)">
  <title>Gym Tracker</title>
  <link rel="manifest" href="/manifest.json">
  <link rel="icon" href="/icons/icon-192.png">
  <link rel="apple-touch-icon" href="/icons/apple-touch-icon.png">
  <link rel="stylesheet" href="/styles.css">
  <script type="module" src="/js/app.js"></script>
</head>
<body>
  <div id="syncbar" hidden></div>
  <main id="view"></main>
  <div id="restbar" hidden></div>
  <nav id="tabs" hidden></nav>
  <div id="overlay-root"></div>
  <div id="toast" role="status" aria-live="polite"></div>
</body>
</html>
```

`src/main/resources/static/manifest.json`:
```json
{
  "name": "Gym Tracker",
  "short_name": "Gym",
  "start_url": "/",
  "scope": "/",
  "display": "standalone",
  "background_color": "#fcfcfb",
  "theme_color": "#2a78d6",
  "icons": [
    { "src": "/icons/icon-192.png", "sizes": "192x192", "type": "image/png" },
    { "src": "/icons/icon-512.png", "sizes": "512x512", "type": "image/png" }
  ]
}
```

`src/main/resources/static/styles.css`:
```css
:root {
  color-scheme: light;
  --surface-0: #f3f3f1;
  --surface-1: #fcfcfb;
  --surface-2: #ebebe8;
  --text-primary: #0b0b0b;
  --text-secondary: #52514e;
  --text-muted: #6f6e69;
  --border: #dddcd7;
  --grid: #e4e3df;
  --accent: #2a78d6;
  --accent-text: #ffffff;
  --series-1: #2a78d6;
  --danger: #c93a39;
  --radius: 14px;
  --tap: 44px;
  font-family: -apple-system, BlinkMacSystemFont, "SF Pro Text", system-ui, sans-serif;
  -webkit-text-size-adjust: 100%;
}

@media (prefers-color-scheme: dark) {
  :root {
    color-scheme: dark;
    --surface-0: #111110;
    --surface-1: #1a1a19;
    --surface-2: #2a2a28;
    --text-primary: #ffffff;
    --text-secondary: #c3c2b7;
    --text-muted: #9a998f;
    --border: #33332f;
    --grid: #2e2e2b;
    --accent: #3987e5;
    --series-1: #3987e5;
    --danger: #e66767;
  }
}

[hidden] { display: none !important; }
* { box-sizing: border-box; }

body {
  margin: 0;
  background: var(--surface-0);
  color: var(--text-primary);
  -webkit-tap-highlight-color: transparent;
}

h1 { font-size: 28px; margin: 0; }
h2 { font-size: 17px; margin: 0 0 8px; }
p { margin: 0; }
button { font: inherit; color: inherit; cursor: pointer; }

.screen {
  max-width: 640px;
  margin: 0 auto;
  padding: calc(16px + env(safe-area-inset-top)) 16px calc(170px + env(safe-area-inset-bottom));
}

.topbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.actions { display: flex; gap: 8px; align-items: center; }
.stack { display: flex; flex-direction: column; gap: 12px; }
.muted { color: var(--text-secondary); }
.empty { color: var(--text-secondary); text-align: center; padding: 32px 16px; }
.error { color: var(--danger); }

.btn {
  min-height: var(--tap);
  border: 0;
  border-radius: 12px;
  padding: 0 16px;
  background: var(--surface-2);
  font-weight: 600;
}
.btn.primary { background: var(--accent); color: var(--accent-text); }
.btn.secondary { background: var(--surface-2); }
.btn.danger { background: transparent; color: var(--danger); }
.btn.ghost { background: transparent; }
.btn.big { width: 100%; min-height: 52px; font-size: 17px; }
.btn.huge { width: 100%; min-height: 72px; font-size: 20px; margin: 24px 0; }
.btn.small { min-height: 36px; padding: 0 12px; font-size: 15px; }
.btn.icon { width: var(--tap); padding: 0; font-size: 22px; }
.btn:disabled { opacity: 0.5; }

.card {
  background: var(--surface-1);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 16px;
  margin: 12px 0;
}

.group-title {
  font-size: 13px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--text-secondary);
  margin: 20px 4px 8px;
}

.list {
  list-style: none;
  margin: 0;
  padding: 0;
  background: var(--surface-1);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  overflow: hidden;
}
.row {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  width: 100%;
  min-height: 48px;
  padding: 12px 16px;
  background: none;
  border: 0;
  border-bottom: 1px solid var(--border);
  text-align: left;
}
.list li:last-child .row { border-bottom: 0; }
.row-sub { color: var(--text-secondary); font-size: 14px; }

.tag {
  font-size: 12px;
  color: var(--text-secondary);
  background: var(--surface-2);
  border-radius: 6px;
  padding: 2px 6px;
}

.sets { list-style: none; margin: 8px 0; padding: 0; }
.set-row {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  min-height: var(--tap);
  padding: 0 4px;
  background: none;
  border: 0;
  text-align: left;
  font-variant-numeric: tabular-nums;
}
.set-no { color: var(--text-muted); width: 20px; }
.pr { margin-left: auto; }
.timer { font-size: 20px; color: var(--text-secondary); font-variant-numeric: tabular-nums; margin-top: 2px; }

#tabs {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 10;
  display: flex;
  background: var(--surface-1);
  border-top: 1px solid var(--border);
  padding-bottom: env(safe-area-inset-bottom);
}
.tab {
  flex: 1;
  min-height: 56px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  background: none;
  border: 0;
  font-size: 11px;
  color: var(--text-secondary);
}
.tab span { font-size: 20px; }
.tab.active { color: var(--accent); }

#syncbar {
  position: sticky;
  top: 0;
  z-index: 5;
  background: var(--surface-2);
  color: var(--text-secondary);
  font-size: 13px;
  text-align: center;
  padding: calc(6px + env(safe-area-inset-top)) 16px 6px;
}

#restbar {
  position: fixed;
  left: 8px;
  right: 8px;
  bottom: calc(64px + env(safe-area-inset-bottom));
  z-index: 9;
  background: var(--surface-1);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.12);
  padding: 10px 12px;
}
.rest-progress { height: 4px; background: var(--surface-2); border-radius: 2px; overflow: hidden; margin-bottom: 8px; }
.rest-progress span { display: block; height: 100%; background: var(--accent); }
.rest-row { display: flex; align-items: center; gap: 8px; }
.rest-time { font-size: 22px; font-weight: 700; font-variant-numeric: tabular-nums; min-width: 64px; }
.rest-row .btn { flex: 1; }

#overlay-root .backdrop {
  position: fixed;
  inset: 0;
  z-index: 20;
  background: rgba(0, 0, 0, 0.4);
  display: flex;
  align-items: flex-end;
  justify-content: center;
}
.sheet {
  width: 100%;
  max-width: 640px;
  max-height: 90vh;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
  background: var(--surface-1);
  border-radius: 20px 20px 0 0;
  padding: 20px 16px calc(20px + env(safe-area-inset-bottom));
  animation: sheet-up 0.18s ease-out;
}
.sheet h2 { font-size: 20px; margin: 0; }
@keyframes sheet-up { from { transform: translateY(30px); opacity: 0.6; } }
.confirm-text { font-size: 17px; }
.actions .btn { flex: 1; }

.field { display: flex; flex-direction: column; gap: 6px; font-size: 14px; color: var(--text-secondary); }
.field input, .search {
  min-height: var(--tap);
  font-size: 17px;
  padding: 0 12px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--surface-0);
  color: var(--text-primary);
}

.chips { display: flex; flex-wrap: wrap; gap: 8px; border: 0; padding: 0; margin: 0; }
.chip {
  min-height: 40px;
  padding: 0 14px;
  border: 1px solid var(--border);
  border-radius: 20px;
  background: var(--surface-1);
}
.chip.selected { background: var(--accent); border-color: var(--accent); color: var(--accent-text); }

.stepper { display: grid; grid-template-columns: 64px 1fr 64px; gap: 8px; align-items: center; }
.step { min-height: 64px; font-size: 28px; }
.step-value { display: flex; align-items: baseline; justify-content: center; gap: 6px; }
.step-value input {
  width: 100%;
  max-width: 150px;
  border: 0;
  background: transparent;
  color: var(--text-primary);
  font-size: 40px;
  font-weight: 700;
  text-align: center;
  font-variant-numeric: tabular-nums;
}
.step-value span { color: var(--text-secondary); }
.stepper.compact { grid-template-columns: 44px 72px 44px; }
.stepper.compact .step { min-height: 44px; font-size: 22px; }
output { text-align: center; font-weight: 700; font-variant-numeric: tabular-nums; }

.segmented { display: grid; grid-template-columns: 1fr 1fr; background: var(--surface-2); border-radius: 12px; padding: 3px; }
.segmented button { min-height: 40px; border: 0; border-radius: 10px; background: transparent; font-weight: 600; color: var(--text-secondary); }
.segmented button[aria-checked="true"] { background: var(--surface-1); color: var(--text-primary); box-shadow: 0 1px 3px rgba(0, 0, 0, 0.15); }

#toast {
  position: fixed;
  left: 50%;
  bottom: calc(150px + env(safe-area-inset-bottom));
  z-index: 30;
  max-width: 90vw;
  transform: translate(-50%, 20px);
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.2s, transform 0.2s;
  background: var(--text-primary);
  color: var(--surface-1);
  border-radius: 20px;
  padding: 10px 16px;
  text-align: center;
}
#toast.show { opacity: 1; transform: translate(-50%, 0); }

.summary { text-align: center; }
.duration { font-size: 56px; font-weight: 800; margin: 24px 0; font-variant-numeric: tabular-nums; }
.stats-row { display: flex; justify-content: center; gap: 32px; margin-bottom: 24px; }
.stats-row strong { display: block; font-size: 28px; }
.stats-row span { color: var(--text-secondary); }
.pr-list { list-style: none; padding: 0; margin: 0 0 24px; text-align: left; }
.pr-list li { padding: 8px 0; border-top: 1px solid var(--border); }

.chart-wrap { position: relative; height: 220px; }
.big-number { font-size: 28px; font-weight: 700; margin: 4px 0; font-variant-numeric: tabular-nums; }
.records { width: 100%; border-collapse: collapse; font-variant-numeric: tabular-nums; }
.records th { text-align: left; font-size: 13px; font-weight: 600; color: var(--text-secondary); padding: 6px 0; }
.records td { padding: 8px 0; border-top: 1px solid var(--border); }
.week-nav { display: flex; align-items: center; justify-content: space-between; }
.week-nav h2 { margin: 0; }
.week-nav button { min-width: var(--tap); min-height: var(--tap); border: 0; background: none; font-size: 24px; color: var(--accent); }
.week-nav button:disabled { color: var(--text-muted); }
.back { padding: 0 8px; color: var(--accent); }

.setting { display: flex; align-items: center; justify-content: space-between; gap: 12px; min-height: var(--tap); }
input[type="checkbox"][role="switch"] {
  appearance: none;
  width: 51px;
  height: 31px;
  border-radius: 16px;
  background: var(--surface-2);
  position: relative;
  transition: background 0.2s;
  flex: none;
}
input[type="checkbox"][role="switch"]::before {
  content: "";
  position: absolute;
  top: 2px;
  left: 2px;
  width: 27px;
  height: 27px;
  border-radius: 50%;
  background: #ffffff;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.3);
  transition: transform 0.2s;
}
input[type="checkbox"][role="switch"]:checked { background: var(--accent); }
input[type="checkbox"][role="switch"]:checked::before { transform: translateX(20px); }

.login { padding-top: 20vh; }
.login h1 { margin-bottom: 24px; }
```

- [ ] **Step 4: Write the service worker**

`src/main/resources/static/sw.js`:
```js
// Serves the app's own files from the cache straight away and refreshes them in the background,
// so the app opens instantly (also offline) and picks up a new deploy on the next launch.
const CACHE = 'gym-tracker-v1';
const ASSETS = [
  '/',
  '/index.html',
  '/styles.css',
  '/manifest.json',
  '/icons/icon-192.png',
  '/icons/icon-512.png',
  '/icons/apple-touch-icon.png',
  '/js/app.js',
  '/js/api.js',
  '/js/format.js',
  '/js/router.js',
  '/js/store.js',
  '/js/sync.js',
  '/js/ui.js',
  '/js/util.js',
  '/js/views/exercises.js',
  '/js/views/login.js',
];

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(ASSETS)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);
  if (event.request.method !== 'GET' || url.origin !== self.location.origin) return;
  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/actuator/')
      || url.pathname === '/login' || url.pathname.startsWith('/test/')) return;

  const key = event.request.mode === 'navigate' ? '/index.html' : url.pathname;
  event.respondWith(caches.open(CACHE).then(async (cache) => {
    const cached = await cache.match(key);
    const network = fetch(event.request).then((response) => {
      if (response.ok) cache.put(key, response.clone());
      return response;
    });
    if (cached) {
      event.waitUntil(network.catch(() => {}));
      return cached;
    }
    return network;
  }));
});
```

- [ ] **Step 5: Write the shared modules**

`src/main/resources/static/js/util.js`:
```js
export function uuid() {
  if (globalThis.crypto?.randomUUID) return crypto.randomUUID();
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = [...bytes].map((b) => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

const ESCAPES = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };

export function esc(value) {
  return String(value ?? '').replace(/[&<>"']/g, (c) => ESCAPES[c]);
}

/** The phone's local date as YYYY-MM-DD. */
export function localDateIso(date = new Date()) {
  const pad = (n) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}
```

`src/main/resources/static/js/format.js`:
```js
export const MUSCLE_GROUPS = ['CHEST', 'BACK', 'SHOULDERS', 'BICEPS', 'TRICEPS', 'QUADS', 'HAMSTRINGS', 'CALVES', 'GLUTES', 'CORE'];

export function muscleLabel(group) {
  return group ? group.charAt(0) + group.slice(1).toLowerCase() : '';
}

export function formatWeight(kg) {
  const n = Number(kg);
  const text = Number.isInteger(n) ? String(n) : n.toFixed(2).replace(/0$/, '');
  return `${text} kg`;
}

export function formatSet(weightKg, reps) {
  return Number(weightKg) === 0 ? `Bodyweight × ${reps}` : `${formatWeight(weightKg)} × ${reps}`;
}

export function formatSetCount(n) {
  return `${n} ${n === 1 ? 'set' : 'sets'}`;
}

/** H:MM:SS, for the live workout timer. */
export function formatClock(totalSeconds) {
  const s = Math.max(0, Math.floor(totalSeconds));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  return `${h}:${String(m).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`;
}

/** M:SS, rounded up, for the rest timer. */
export function formatCountdown(totalSeconds) {
  const s = Math.max(0, Math.ceil(totalSeconds));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

/** "42 min" or "1 h 05 min". */
export function formatDuration(totalSeconds) {
  const minutes = Math.round(totalSeconds / 60);
  if (minutes < 60) return `${minutes} min`;
  return `${Math.floor(minutes / 60)} h ${String(minutes % 60).padStart(2, '0')} min`;
}

function toDate(iso) {
  const [y, m, d] = iso.slice(0, 10).split('-').map(Number);
  return new Date(y, m - 1, d);
}

/** "Mon 5 Oct", or "5 Oct" without the weekday. */
export function formatDate(iso, { weekday = true } = {}) {
  return toDate(iso).toLocaleDateString('en-GB', { ...(weekday && { weekday: 'short' }), day: 'numeric', month: 'short' });
}

/** "Monday 5 October". */
export function formatLongDate(iso) {
  return toDate(iso).toLocaleDateString('en-GB', { weekday: 'long', day: 'numeric', month: 'long' });
}

/** Reads "42.5" or "42,5"; rounds to 0.25 kg and clamps to 0–500. Returns null when it isn't a number. */
export function parseWeight(text) {
  const trimmed = String(text ?? '').trim().replace(',', '.');
  if (trimmed === '') return null;
  const n = Number(trimmed);
  if (!Number.isFinite(n)) return null;
  return Math.min(500, Math.max(0, Math.round(n * 4) / 4));
}
```

`src/main/resources/static/js/api.js`:
```js
function csrfToken() {
  const match = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/);
  return match ? decodeURIComponent(match[1]) : '';
}

/**
 * Result kinds: ok | network (no connection or server error, try again later) | unauthorized (401)
 * | forbidden (403, usually a missing CSRF cookie) | rejected (other 4xx, with the server's message).
 */
export async function request(method, path, body, { form = false } = {}) {
  const headers = {};
  if (method !== 'GET') headers['X-XSRF-TOKEN'] = csrfToken();
  let payload;
  if (body !== undefined) {
    headers['Content-Type'] = form ? 'application/x-www-form-urlencoded' : 'application/json';
    payload = form ? new URLSearchParams(body).toString() : JSON.stringify(body);
  }
  let response;
  try {
    response = await fetch(path, { method, headers, body: payload, credentials: 'same-origin', cache: 'no-store' });
  } catch {
    return { kind: 'network' };
  }
  const text = await response.text().catch(() => '');
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = null;
  }
  if (response.ok) return { kind: 'ok', status: response.status, data };
  if (response.status === 401) return { kind: 'unauthorized', message: data?.message };
  if (response.status === 403) return { kind: 'forbidden' };
  if (response.status >= 400 && response.status < 500) {
    return { kind: 'rejected', status: response.status, message: data?.message || `Request failed (${response.status})` };
  }
  return { kind: 'network' };
}

export function login(username, password) {
  return request('POST', '/login', { username, password }, { form: true });
}
```

`src/main/resources/static/js/store.js`:
```js
import { uuid } from './util.js';

// The screen shows view() = the last server snapshot with the outbox (pending changes) applied on top.
const SNAPSHOT_KEY = 'gt.snapshot.v1';
const OUTBOX_KEY = 'gt.outbox.v1';
const EMPTY = { exercises: [], activeSession: null, history: [], weekly: null, weeklyAsOf: null, exerciseStats: {}, sessionDetails: {} };

function load(key, fallback) {
  try {
    const raw = localStorage.getItem(key);
    return raw ? JSON.parse(raw) : fallback;
  } catch {
    return fallback;
  }
}

function save(key, value) {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch {
    // Storage blocked or full: keep working from memory.
  }
}

let snapshot = { ...EMPTY, ...load(SNAPSHOT_KEY, {}) };
let outbox = load(OUTBOX_KEY, []);
let cachedView = null;
const listeners = new Set();
const dispatchListeners = new Set();

function changed() {
  cachedView = null;
  listeners.forEach((fn) => fn());
}

export function subscribe(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

export function onDispatch(fn) {
  dispatchListeners.add(fn);
}

export function dispatch(kind, payload) {
  outbox.push({ opId: uuid(), kind, payload });
  save(OUTBOX_KEY, outbox);
  changed();
  dispatchListeners.forEach((fn) => fn());
}

export function pending() {
  return outbox.length;
}

export function peek() {
  return outbox[0];
}

export function shift() {
  outbox.shift();
  save(OUTBOX_KEY, outbox);
  changed();
}

export function setSnapshot(patch) {
  snapshot = { ...snapshot, ...patch };
  save(SNAPSHOT_KEY, snapshot);
  changed();
}

export function cacheSessionDetail(id, detail) {
  setSnapshot({ sessionDetails: { ...snapshot.sessionDetails, [id]: detail } });
}

export function cacheExerciseStats(id, stats) {
  setSnapshot({ exerciseStats: { ...snapshot.exerciseStats, [id]: stats } });
}

export function view() {
  if (!cachedView) cachedView = applyOps(structuredClone(snapshot), outbox);
  return cachedView;
}

const byTime = (a, b) => Date.parse(a.loggedAt) - Date.parse(b.loggedAt);
const byName = (a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' });

export function summarize(session, endedAt) {
  return {
    id: session.id,
    date: session.date,
    startedAt: session.startedAt,
    endedAt,
    durationSeconds: Math.round((Date.parse(endedAt) - Date.parse(session.startedAt)) / 1000),
    setCount: session.sets.length,
    muscleGroups: [...new Set([...session.sets].sort(byTime).map((s) => s.muscleGroup).filter(Boolean))],
  };
}

/** Applies pending operations to a copy of the snapshot. Mirrors what the server will do with them. */
export function applyOps(state, ops) {
  for (const { kind, payload: p } of ops) {
    switch (kind) {
      case 'exercise.put': {
        const existing = state.exercises.find((e) => e.id === p.id);
        if (existing) Object.assign(existing, { name: p.name, muscleGroup: p.muscleGroup });
        else state.exercises.push({ id: p.id, name: p.name, muscleGroup: p.muscleGroup, lastTime: null, records: { heaviest: null, repRecords: [] } });
        state.exercises.sort(byName);
        break;
      }
      case 'exercise.delete':
        state.exercises = state.exercises.filter((e) => e.id !== p.id);
        break;
      case 'session.start':
        if (!state.activeSession) state.activeSession = { id: p.id, date: p.date, startedAt: p.startedAt, endedAt: null, sets: [] };
        break;
      case 'set.put': {
        const session = state.activeSession;
        if (!session || session.id !== p.sessionId) break;
        const exercise = state.exercises.find((e) => e.id === p.exerciseId);
        const previous = session.sets.find((s) => s.id === p.id);
        const set = {
          ...p,
          exerciseName: exercise?.name ?? previous?.exerciseName ?? '',
          muscleGroup: exercise?.muscleGroup ?? previous?.muscleGroup ?? null,
        };
        if (previous) Object.assign(previous, set);
        else session.sets.push(set);
        session.sets.sort(byTime);
        break;
      }
      case 'set.delete':
        if (state.activeSession) state.activeSession.sets = state.activeSession.sets.filter((s) => s.id !== p.id);
        break;
      case 'session.end': {
        const session = state.activeSession;
        if (!session || session.id !== p.id) break;
        state.activeSession = null;
        if (session.sets.length) state.history.unshift(summarize(session, p.endedAt));
        break;
      }
      case 'session.discard':
        if (state.activeSession?.id === p.id) state.activeSession = null;
        state.history = state.history.filter((h) => h.id !== p.id);
        break;
      default:
        break;
    }
  }
  return state;
}
```

`src/main/resources/static/js/sync.js`:
```js
import { request } from './api.js';
import * as store from './store.js';
import { localDateIso } from './util.js';

const ROUTES = {
  'exercise.put': (p) => ['PUT', `/api/exercises/${p.id}`, { name: p.name, muscleGroup: p.muscleGroup }],
  'exercise.delete': (p) => ['DELETE', `/api/exercises/${p.id}`],
  'session.start': (p) => ['PUT', `/api/sessions/${p.id}`, { date: p.date, startedAt: p.startedAt }],
  'session.end': (p) => ['POST', `/api/sessions/${p.id}/end`, { endedAt: p.endedAt }],
  'session.discard': (p) => ['DELETE', `/api/sessions/${p.id}`],
  'set.put': ({ id, ...body }) => ['PUT', `/api/sets/${id}`, body],
  'set.delete': (p) => ['DELETE', `/api/sets/${p.id}`],
};

let running = false;
let again = false;
let unauthorizedHandler = () => {};
let rejectedHandler = () => {};

export function onUnauthorized(fn) {
  unauthorizedHandler = fn;
}

export function onRejected(fn) {
  rejectedHandler = fn;
}

function setState(state) {
  document.body.dataset.sync = state;
}

function signedOut() {
  unauthorizedHandler();
  return 'signed-out';
}

async function send(op) {
  const [method, path, body] = ROUTES[op.kind](op.payload);
  let result = await request(method, path, body);
  if (result.kind === 'forbidden') {
    // Missing or stale CSRF cookie (iOS may drop cookies when the app is killed): fetch a fresh one, retry once.
    await request('GET', '/api/me');
    result = await request(method, path, body);
    if (result.kind === 'forbidden') return { kind: 'network' }; // keep the change and try again later
  }
  return result;
}

async function refresh() {
  const results = {};
  const sources = [
    ['exercises', '/api/exercises'],
    ['activeSession', '/api/sessions/active'],
    ['history', '/api/sessions'],
    ['weekly', `/api/stats/weekly?weeks=12&today=${localDateIso()}`],
  ];
  for (const [key, path] of sources) {
    const result = await request('GET', path);
    if (result.kind === 'unauthorized') return signedOut();
    if (result.kind !== 'ok') return 'offline';
    results[key] = result.data;
  }
  store.setSnapshot({ ...results, weeklyAsOf: new Date().toISOString() });
  return 'idle';
}

async function flushOnce() {
  // Always first and on its own: refreshes the CSRF cookie and lets a remember-me login finish before other
  // requests. Parallel remember-me logins would trip Spring's cookie-theft check and sign the user out.
  const me = await request('GET', '/api/me');
  if (me.kind === 'unauthorized') return signedOut();
  if (me.kind !== 'ok') return 'offline';
  while (store.pending() > 0) {
    const result = await send(store.peek());
    if (result.kind === 'ok') {
      store.shift();
    } else if (result.kind === 'unauthorized') {
      return signedOut();
    } else if (result.kind === 'rejected') {
      store.shift();
      rejectedHandler(result.message);
    } else {
      return 'offline';
    }
  }
  return refresh();
}

/** Sends pending changes in order, then reloads the snapshot. Safe to call any time. */
export async function flush() {
  if (running) {
    again = true;
    return;
  }
  running = true;
  setState('syncing');
  let outcome = 'idle';
  try {
    do {
      again = false;
      outcome = await flushOnce();
    } while (again && outcome === 'idle');
  } finally {
    running = false;
    setState(outcome);
  }
}

export function startSync() {
  store.onDispatch(() => flush());
  window.addEventListener('online', () => flush());
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') flush();
  });
  setInterval(() => {
    if (store.pending() > 0) flush();
  }, 15000);
}
```

`src/main/resources/static/js/router.js`:
```js
let route = { tab: 'workout' };
let renderer = () => {};

export function setRenderer(fn) {
  renderer = fn;
}

export function getRoute() {
  return route;
}

export function navigate(next) {
  route = next;
  renderer();
  window.scrollTo(0, 0);
}
```

`src/main/resources/static/js/ui.js`:
```js
import { esc } from './util.js';

let toastTimer;

export function toast(message, ms = 2500) {
  const el = document.getElementById('toast');
  el.textContent = message;
  el.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove('show'), ms);
}

/** Opens a bottom sheet. Tapping the backdrop or any [data-close] element closes it. */
export function openSheet(html, { onClose } = {}) {
  const backdrop = document.createElement('div');
  backdrop.className = 'backdrop';
  backdrop.innerHTML = `<div class="sheet" role="dialog" aria-modal="true">${html}</div>`;
  document.getElementById('overlay-root').appendChild(backdrop);
  const el = backdrop.firstElementChild;
  let closed = false;
  const close = () => {
    if (closed) return;
    closed = true;
    backdrop.remove();
    onClose?.();
  };
  backdrop.addEventListener('click', (event) => {
    if (event.target === backdrop || event.target.closest('[data-close]')) close();
  });
  return { el, close };
}

export function confirmDialog(message, { ok = 'OK', danger = false } = {}) {
  return new Promise((resolve) => {
    let answer = false;
    const { el, close } = openSheet(`
      <p class="confirm-text">${esc(message)}</p>
      <div class="actions">
        <button class="btn secondary" data-close>Cancel</button>
        <button class="btn ${danger ? 'danger' : 'primary'}" data-confirm>${esc(ok)}</button>
      </div>`, { onClose: () => resolve(answer) });
    el.querySelector('[data-confirm]').addEventListener('click', () => {
      answer = true;
      close();
    });
  });
}
```

- [ ] **Step 6: Write the login and exercises views**

`src/main/resources/static/js/views/login.js`:
```js
import { login, request } from '../api.js';

export function renderLogin(container, { onSuccess }) {
  container.innerHTML = `
    <section class="screen login">
      <h1>Gym Tracker</h1>
      <form class="stack" id="login-form">
        <label class="field">Username
          <input name="username" autocomplete="username" autocapitalize="none" autocorrect="off" required>
        </label>
        <label class="field">Password
          <input name="password" type="password" autocomplete="current-password" required>
        </label>
        <p class="error" id="login-error" hidden></p>
        <button class="btn primary big" type="submit">Sign in</button>
      </form>
    </section>`;
  const form = container.querySelector('#login-form');
  const error = container.querySelector('#login-error');
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const button = form.querySelector('button');
    button.disabled = true;
    error.hidden = true;
    await request('GET', '/api/me'); // makes sure the CSRF cookie exists
    const data = new FormData(form);
    const result = await login(String(data.get('username')).trim(), String(data.get('password')));
    button.disabled = false;
    if (result.kind === 'ok') {
      onSuccess();
      return;
    }
    error.textContent = result.kind === 'network' ? 'No connection. Try again when you have signal.' : 'Wrong username or password';
    error.hidden = false;
  });
}
```

`src/main/resources/static/js/views/exercises.js`:
```js
import * as store from '../store.js';
import { openSheet, confirmDialog, toast } from '../ui.js';
import { esc, uuid } from '../util.js';
import { MUSCLE_GROUPS, muscleLabel } from '../format.js';

export function render(container) {
  const { exercises } = store.view();
  const groups = MUSCLE_GROUPS
    .map((group) => ({ group, items: exercises.filter((e) => e.muscleGroup === group) }))
    .filter((g) => g.items.length);
  container.innerHTML = `
    <section class="screen">
      <header class="topbar">
        <h1>Exercises</h1>
        <button class="btn icon primary" data-action="add" aria-label="Add exercise">+</button>
      </header>
      ${groups.length ? groups.map(({ group, items }) => `
        <h2 class="group-title">${muscleLabel(group)}</h2>
        <ul class="list">
          ${items.map((e) => `<li><button class="row" data-id="${e.id}">${esc(e.name)}</button></li>`).join('')}
        </ul>`).join('') : '<p class="empty">No exercises yet. Tap + to add your first one.</p>'}
    </section>`;
  container.querySelector('[data-action="add"]').addEventListener('click', () => openExerciseForm());
  container.querySelectorAll('[data-id]').forEach((button) => button.addEventListener('click', () => {
    openExerciseForm(exercises.find((e) => e.id === button.dataset.id));
  }));
}

export function validateExercise(name, group, selfId, exercises) {
  if (!name) return 'Enter a name';
  if (name.length > 60) return 'Name can be at most 60 characters';
  if (!group) return 'Choose a muscle group';
  const clash = exercises.find((e) => e.id !== selfId && e.name.toLowerCase() === name.toLowerCase());
  return clash ? `You already have an exercise called '${clash.name}'` : null;
}

/** Create (exercise = null) or edit form in a sheet. */
export function openExerciseForm(exercise = null, { onSaved, onDeleted } = {}) {
  let group = exercise?.muscleGroup ?? null;
  const { el, close } = openSheet(`
    <h2>${exercise ? 'Edit exercise' : 'New exercise'}</h2>
    <form class="stack" novalidate>
      <label class="field">Name
        <input name="exercise-name" maxlength="60" autocomplete="off" value="${esc(exercise?.name ?? '')}">
      </label>
      <fieldset class="chips" aria-label="Muscle group">
        ${MUSCLE_GROUPS.map((g) => `
          <button type="button" class="chip${g === group ? ' selected' : ''}" data-group="${g}" aria-pressed="${g === group}">${muscleLabel(g)}</button>`).join('')}
      </fieldset>
      <p class="error" hidden></p>
      <button class="btn primary big" type="submit">Save</button>
      ${exercise ? '<button class="btn danger" type="button" data-action="delete">Delete exercise</button>' : ''}
    </form>`);
  const form = el.querySelector('form');
  const error = el.querySelector('.error');
  el.querySelectorAll('[data-group]').forEach((chip) => chip.addEventListener('click', () => {
    group = chip.dataset.group;
    el.querySelectorAll('[data-group]').forEach((c) => {
      c.classList.toggle('selected', c === chip);
      c.setAttribute('aria-pressed', String(c === chip));
    });
  }));
  form.addEventListener('submit', (event) => {
    event.preventDefault();
    const name = form.elements.namedItem('exercise-name').value.trim();
    const problem = validateExercise(name, group, exercise?.id, store.view().exercises);
    if (problem) {
      error.textContent = problem;
      error.hidden = false;
      return;
    }
    const id = exercise?.id ?? uuid();
    store.dispatch('exercise.put', { id, name, muscleGroup: group });
    close();
    onSaved?.(store.view().exercises.find((e) => e.id === id));
  });
  el.querySelector('[data-action="delete"]')?.addEventListener('click', async () => {
    close();
    const ok = await confirmDialog(`Delete "${exercise.name}"? Past workouts keep their sets.`, { ok: 'Delete', danger: true });
    if (!ok) return;
    store.dispatch('exercise.delete', { id: exercise.id });
    toast('Exercise deleted');
    onDeleted?.();
  });
}
```

- [ ] **Step 7: Write the app entry point**

`src/main/resources/static/js/app.js`:
```js
import { request } from './api.js';
import * as store from './store.js';
import * as sync from './sync.js';
import { getRoute, navigate, setRenderer } from './router.js';
import { toast } from './ui.js';
import { renderLogin } from './views/login.js';
import * as exercises from './views/exercises.js';

const TABS = [
  { id: 'exercises', label: 'Exercises', icon: '📋', view: exercises },
];

const viewEl = document.getElementById('view');
const tabsEl = document.getElementById('tabs');
const syncEl = document.getElementById('syncbar');
let signedIn = false;

function render() {
  if (!signedIn) return;
  const route = getRoute();
  const current = TABS.find((t) => t.id === route.tab) ?? TABS[0];
  tabsEl.hidden = false;
  tabsEl.innerHTML = TABS.map((t) => `
    <button class="tab${t.id === current.id ? ' active' : ''}" data-tab="${t.id}"${t.id === current.id ? ' aria-current="page"' : ''}>
      <span aria-hidden="true">${t.icon}</span>${t.label}
    </button>`).join('');
  const pending = store.pending();
  syncEl.hidden = pending === 0;
  syncEl.textContent = `${pending} change${pending === 1 ? '' : 's'} waiting to sync`;
  current.view.render(viewEl, route);
}

function showLogin() {
  signedIn = false;
  tabsEl.hidden = true;
  syncEl.hidden = true;
  renderLogin(viewEl, { onSuccess: showApp });
}

function showApp() {
  signedIn = true;
  render();
  sync.flush();
}

tabsEl.addEventListener('click', (event) => {
  const button = event.target.closest('[data-tab]');
  if (button) navigate({ tab: button.dataset.tab });
});

async function boot() {
  if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js').catch(() => {});
  setRenderer(render);
  navigate({ tab: TABS[0].id });
  sync.onUnauthorized(showLogin);
  sync.onRejected((message) => toast(message));
  store.subscribe(render);
  sync.startSync();
  const me = await request('GET', '/api/me');
  if (me.kind === 'unauthorized') showLogin();
  else showApp(); // signed in, or offline: work from the saved snapshot
}

boot();
```

- [ ] **Step 8: Run the asset test to verify it passes**

Run: `./mvnw test -Dtest=ServiceWorkerAssetsTest`
Expected: PASS.

- [ ] **Step 9: Write the browser test harness, unit page and E2E tests**

`src/test/java/com/gymtracker/e2e/E2ETestBase.java`:
```java
package com.gymtracker.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.gymtracker.DbCleaner;
import com.gymtracker.TestcontainersConfiguration;
import com.gymtracker.push.TestPushConfig;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.assertions.LocatorAssertions;
import com.microsoft.playwright.options.AriaRole;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** Runs the real app on a random port and drives it with WebKit (Safari's engine) at iPhone size. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestPushConfig.class})
public abstract class E2ETestBase {

    private static Playwright playwright;
    private static Browser browser;

    @LocalServerPort
    int port;

    @Autowired
    protected JdbcTemplate jdbc;

    protected BrowserContext context;
    protected Page page;
    protected final List<String> pageErrors = new ArrayList<>();

    @BeforeAll
    static void launchBrowser() {
        playwright = Playwright.create();
        browser = playwright.webkit().launch();
    }

    @AfterAll
    static void closeBrowser() {
        playwright.close();
    }

    @BeforeEach
    void openPage() {
        DbCleaner.clean(jdbc);
        context = browser.newContext(new Browser.NewContextOptions()
                .setBaseURL("http://localhost:" + port)
                .setViewportSize(390, 844)
                .setDeviceScaleFactor(3)
                .setIsMobile(true)
                .setHasTouch(true)
                .setLocale("en-GB")
                .setTimezoneId("Europe/Amsterdam"));
        page = context.newPage();
        page.onPageError(pageErrors::add);
    }

    @AfterEach
    void closePage() {
        context.close();
        org.assertj.core.api.Assertions.assertThat(pageErrors).as("uncaught errors in the page").isEmpty();
    }

    protected void signIn() {
        page.navigate("/");
        page.getByLabel("Username").fill("tester");
        page.getByLabel("Password").fill("secret-pass");
        button("Sign in").click();
        assertThat(page.locator("#tabs")).isVisible();
    }

    protected Locator button(String name) {
        return page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(name).setExact(true));
    }

    protected Locator tab(String name) {
        return page.locator("#tabs").getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName(name));
    }

    protected void createExerciseViaUi(String name, String muscleLabel) {
        tab("Exercises").click();
        page.getByLabel("Add exercise").click();
        page.getByLabel("Name").fill(name);
        button(muscleLabel).click();
        button("Save").click();
        assertThat(button(name)).isVisible();
    }

    /** Waits until the outbox is empty and the snapshot has been reloaded from the server. */
    protected void waitUntilSynced() {
        assertThat(page.locator("body[data-sync='idle']"))
                .hasCount(1, new LocatorAssertions.HasCountOptions().setTimeout(20_000));
        assertThat(page.locator("#syncbar")).isHidden();
    }

    protected int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    protected UUID seedExercise(String name, String muscleGroup) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into exercise (id, name, muscle_group, archived, created_at) values (?, ?, ?, false, now())",
                id, name, muscleGroup);
        return id;
    }

    protected UUID seedEndedSession(String date, String startedAt, String endedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into workout_session (id, session_date, started_at, ended_at) "
                + "values (?, ?::date, ?::timestamptz, ?::timestamptz)", id, date, startedAt, endedAt);
        return id;
    }

    protected void seedSet(UUID sessionId, UUID exerciseId, String weightKg, int reps, String type, String loggedAt) {
        jdbc.update("insert into workout_set (id, session_id, exercise_id, weight_kg, reps, type, logged_at) "
                        + "values (?, ?, ?, ?::numeric, ?, ?, ?::timestamptz)",
                UUID.randomUUID(), sessionId, exerciseId, weightKg, reps, type, loggedAt);
    }
}
```

`src/test/resources/static/test/unit.html`:
```html
<!doctype html>
<meta charset="utf-8">
<title>Unit tests</title>
<script type="module">
  import { parseWeight, formatSet, formatSetCount, formatDuration, formatClock, formatCountdown } from '/js/format.js';
  import { applyOps } from '/js/store.js';

  const results = [];
  const test = (name, fn) => {
    try { fn(); results.push({ name, ok: true }); } catch (e) { results.push({ name, ok: false, error: String(e) }); }
  };
  const eq = (actual, expected) => {
    const a = JSON.stringify(actual);
    const b = JSON.stringify(expected);
    if (a !== b) throw new Error(`expected ${b} but got ${a}`);
  };
  const empty = () => ({ exercises: [], activeSession: null, history: [], weekly: null, exerciseStats: {}, sessionDetails: {} });
  const start = { kind: 'session.start', payload: { id: 's1', date: '2026-10-05', startedAt: '2026-10-05T08:00:00.000Z' } };
  const squat = { kind: 'exercise.put', payload: { id: 'e1', name: 'Squat', muscleGroup: 'QUADS' } };
  const set = (id, weightKg, reps, loggedAt, type = 'WORK') =>
    ({ kind: 'set.put', payload: { id, sessionId: 's1', exerciseId: 'e1', weightKg, reps, type, loggedAt } });

  test('parseWeight accepts dot and comma decimals', () => {
    eq(parseWeight('42.5'), 42.5);
    eq(parseWeight('42,5'), 42.5);
    eq(parseWeight(' 60 '), 60);
  });
  test('parseWeight rounds to 0.25 and clamps to 0–500', () => {
    eq(parseWeight('42.3'), 42.25);
    eq(parseWeight('-5'), 0);
    eq(parseWeight('900'), 500);
  });
  test('parseWeight rejects text that is not a number', () => {
    eq(parseWeight(''), null);
    eq(parseWeight('abc'), null);
  });
  test('formatSet shows kilograms or bodyweight', () => {
    eq(formatSet(40, 10), '40 kg × 10');
    eq(formatSet('42.50', 8), '42.5 kg × 8');
    eq(formatSet(42.25, 5), '42.25 kg × 5');
    eq(formatSet(0, 12), 'Bodyweight × 12');
  });
  test('formatSetCount', () => {
    eq(formatSetCount(1), '1 set');
    eq(formatSetCount(3), '3 sets');
  });
  test('time formats', () => {
    eq(formatDuration(2400), '40 min');
    eq(formatDuration(3900), '1 h 05 min');
    eq(formatClock(2537), '0:42:17');
    eq(formatCountdown(90), '1:30');
    eq(formatCountdown(0.2), '0:01');
  });
  test('applyOps: create an exercise and log a set while offline', () => {
    const state = applyOps(empty(), [squat, start, set('x1', 40, 10, '2026-10-05T08:10:00.000Z')]);
    eq(state.exercises.map((e) => e.name), ['Squat']);
    eq(state.activeSession.sets.map((s) => [s.exerciseName, s.weightKg]), [['Squat', 40]]);
  });
  test('applyOps: ending moves the session to history', () => {
    const state = applyOps(empty(), [squat, start, set('x1', 40, 10, '2026-10-05T08:10:00.000Z'),
      { kind: 'session.end', payload: { id: 's1', endedAt: '2026-10-05T08:42:00.000Z' } }]);
    eq(state.activeSession, null);
    eq([state.history[0].durationSeconds, state.history[0].setCount, state.history[0].muscleGroups], [2520, 1, ['QUADS']]);
  });
  test('applyOps: ending an empty session discards it', () => {
    const state = applyOps(empty(), [start, { kind: 'session.end', payload: { id: 's1', endedAt: '2026-10-05T08:01:00.000Z' } }]);
    eq([state.activeSession, state.history.length], [null, 0]);
  });
  test('applyOps: editing and deleting sets and exercises', () => {
    const state = applyOps(empty(), [squat, start,
      set('x1', 40, 10, '2026-10-05T08:10:00.000Z'),
      set('x1', 42.5, 8, '2026-10-05T08:10:00.000Z'),
      set('x2', 40, 10, '2026-10-05T08:12:00.000Z'),
      { kind: 'set.delete', payload: { id: 'x2' } },
      { kind: 'exercise.put', payload: { id: 'e2', name: 'Bench', muscleGroup: 'CHEST' } },
      { kind: 'exercise.delete', payload: { id: 'e2' } }]);
    eq(state.activeSession.sets.map((s) => [s.id, s.weightKg, s.reps]), [['x1', 42.5, 8]]);
    eq(state.exercises.map((e) => e.id), ['e1']);
  });
  test('applyOps: sets are ordered by time even when timestamp formats differ', () => {
    const state = applyOps(empty(), [squat, start,
      set('server', 40, 10, '2026-10-05T08:10:00Z'),
      set('phone', 40, 10, '2026-10-05T08:09:59.500Z')]);
    eq(state.activeSession.sets.map((s) => s.id), ['phone', 'server']);
  });

  window.__results = results;
</script>
```

`src/test/java/com/gymtracker/e2e/JsUnitE2ETest.java`:
```java
package com.gymtracker.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsUnitE2ETest extends E2ETestBase {

    @Test
    @SuppressWarnings("unchecked")
    void browserUnitTestsPass() {
        page.navigate("/test/unit.html");
        page.waitForFunction("() => window.__results !== undefined");
        List<Map<String, Object>> results = (List<Map<String, Object>>) page.evaluate("() => window.__results");
        List<String> failures = results.stream()
                .filter(result -> !Boolean.TRUE.equals(result.get("ok")))
                .map(result -> result.get("name") + ": " + result.get("error"))
                .toList();
        assertThat(results).isNotEmpty();
        assertThat(failures).isEmpty();
    }
}
```

`src/test/java/com/gymtracker/e2e/LoginAndExercisesE2ETest.java`:
```java
package com.gymtracker.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.BrowserContext;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class LoginAndExercisesE2ETest extends E2ETestBase {

    @Test
    void wrongPasswordShowsError() {
        page.navigate("/");
        page.getByLabel("Username").fill("tester");
        page.getByLabel("Password").fill("wrong");
        button("Sign in").click();
        assertThat(page.getByText("Wrong username or password")).isVisible();
    }

    @Test
    void createsExerciseAndRejectsDuplicateName() {
        signIn();
        createExerciseViaUi("Squat", "Quads");
        waitUntilSynced();
        Assertions.assertThat(jdbc.queryForObject("select muscle_group from exercise where name = 'Squat'", String.class))
                .isEqualTo("QUADS");

        page.getByLabel("Add exercise").click();
        page.getByLabel("Name").fill("squat");
        button("Quads").click();
        button("Save").click();
        assertThat(page.getByText("You already have an exercise called 'Squat'")).isVisible();
    }

    @Test
    void editsAndDeletesExercise() {
        signIn();
        createExerciseViaUi("Squat", "Quads");
        button("Squat").click();
        page.getByLabel("Name").fill("Back squat");
        button("Save").click();
        assertThat(button("Back squat")).isVisible();

        button("Back squat").click();
        button("Delete exercise").click();
        button("Delete").click();
        assertThat(page.getByText("No exercises yet")).isVisible();
        waitUntilSynced();
        Assertions.assertThat(count("select count(*) from exercise where archived")).isEqualTo(1);
    }

    @Test
    void offlineChangesSyncWhenBackOnline() {
        signIn();
        waitUntilSynced();
        context.setOffline(true);
        createExerciseViaUi("Bench press", "Chest");
        assertThat(page.locator("#syncbar")).hasText("1 change waiting to sync");
        Assertions.assertThat(count("select count(*) from exercise")).isZero();

        context.setOffline(false);
        waitUntilSynced();
        Assertions.assertThat(count("select count(*) from exercise")).isEqualTo(1);
    }

    @Test
    void changesSyncAfterSessionAndCsrfCookiesAreLost() {
        signIn();
        waitUntilSynced();
        context.clearCookies(new BrowserContext.ClearCookiesOptions().setName("XSRF-TOKEN"));
        context.clearCookies(new BrowserContext.ClearCookiesOptions().setName("JSESSIONID"));

        createExerciseViaUi("Deadlift", "Back");
        waitUntilSynced();
        Assertions.assertThat(count("select count(*) from exercise")).isEqualTo(1);
        assertThat(page.getByLabel("Password")).hasCount(0);
    }

    @Test
    void staysSignedInWhenServerSessionExpires() {
        signIn();
        waitUntilSynced();
        for (int i = 0; i < 2; i++) {
            context.clearCookies(new BrowserContext.ClearCookiesOptions().setName("JSESSIONID"));
            page.reload();
            assertThat(page.locator("#tabs")).isVisible();
            waitUntilSynced();
        }
        assertThat(page.getByLabel("Password")).hasCount(0);
    }
}
```

- [ ] **Step 10: Run the browser tests**

Run: `./mvnw test -Dtest='JsUnitE2ETest,LoginAndExercisesE2ETest'`
Expected: PASS, 7 tests. The first run downloads WebKit for Playwright, about 100 MB.

If an E2E test fails, read the failure and the `pageErrors` assertion first; they show uncaught JS errors. To watch the browser, temporarily change `playwright.webkit().launch()` to `launch(new BrowserType.LaunchOptions().setHeadless(false).setSlowMo(300))`.

- [ ] **Step 11: Run all tests and commit**

Run: `./mvnw test`
Expected: PASS.

```bash
git add -A
git commit -m "Add web app shell with login, offline outbox sync and exercises tab"
```

---
### Task 8: Workout tab: start, live timer, logging sets, end with summary

**Files:**
- Create: `src/main/resources/static/js/views/workout.js`
- Modify: `src/main/resources/static/js/app.js` (add the Workout tab first), `src/main/resources/static/sw.js` (add the asset)
- Test: `src/test/java/com/gymtracker/e2e/WorkoutE2ETest.java`

**Interfaces:**
- Consumes: `store`, `router`, `ui`, `util`, `format`, and `openExerciseForm` from `views/exercises.js` (Task 7).
- Produces. `views/workout.js` exports:
  - `render(container, route)`;
  - `groupSets(sets)`: `[{exerciseId, name, sets}]` in order of each exercise's first set;
  - `prefillFor(exerciseId, sessionSets, lastTime)`: `{weightKg, reps}`;
  - `buildSummary(session, endedAt)`: `{date, durationSeconds, exerciseCount, workSetCount, prs}`. `prs` is `[]` in this task; Task 10 fills it.
- Hooks later tasks edit, all inside `openSetSheet`'s save handler and `endSession`:
  - Task 9 caches the ended session's detail in `endSession`;
  - Task 10 adds the PR toast;
  - Task 11 starts and stops the rest timer.
- DOM hooks used by tests:
  - `#session-timer`, `.set-row`;
  - `#summary-duration`, `[data-stat='exercises'] strong`, `[data-stat='work-sets'] strong`;
  - aria-labels `Weight in kg`, `Reps`, `Increase weight`, `Decrease weight`, `Increase reps`, `Decrease reps`, `More options`;
  - radios `Warmup` / `Work`.

- [ ] **Step 1: Write the failing E2E tests**

`src/test/java/com/gymtracker/e2e/WorkoutE2ETest.java`:
```java
package com.gymtracker.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Clock;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import java.math.BigDecimal;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class WorkoutE2ETest extends E2ETestBase {

    private void startSessionWithSquat() {
        seedExercise("Squat", "QUADS");
        signIn();
        tab("Workout").click();
        button("Start session").click();
        assertThat(page.locator("#session-timer")).isVisible();
    }

    private void addExerciseAndLogSet(String exercise, String weight, String reps, boolean warmup) {
        button("+ Add exercise").click();
        button(exercise).click();
        fillAndSaveSet(weight, reps, warmup);
    }

    private void fillAndSaveSet(String weight, String reps, boolean warmup) {
        page.getByLabel("Weight in kg").fill(weight);
        page.getByLabel("Reps").fill(reps);
        if (warmup) {
            radio("Warmup").click();
        }
        button("Save").click();
    }

    private Locator radio(String name) {
        return page.getByRole(AriaRole.RADIO, new Page.GetByRoleOptions().setName(name).setExact(true));
    }

    private Locator setRows() {
        return page.locator(".set-row");
    }

    @Test
    void fullWorkoutShowsLiveTimerAndDuration() {
        page.clock().install(new Clock.InstallOptions().setTime("2026-10-05T07:59:00Z"));
        page.clock().pauseAt("2026-10-05T08:00:00Z");
        startSessionWithSquat();
        assertThat(page.locator("#session-timer")).hasText("0:00:00");

        addExerciseAndLogSet("Squat", "40", "10", true);
        assertThat(setRows().nth(0)).containsText("40 kg × 10");
        assertThat(setRows().nth(0)).containsText("Warmup");

        button("+ Set").click();
        assertThat(page.getByLabel("Weight in kg")).hasValue("40");
        assertThat(page.getByLabel("Reps")).hasValue("10");
        assertThat(radio("Work")).hasAttribute("aria-checked", "true");
        page.getByLabel("Increase weight").click();
        button("Save").click();
        assertThat(setRows().nth(1)).containsText("42.5 kg × 10");
        assertThat(setRows().nth(1)).not().containsText("Warmup");

        page.clock().runFor("42:00");
        assertThat(page.locator("#session-timer")).hasText("0:42:00");
        button("End").click();
        button("End workout").click();
        assertThat(page.locator("#summary-duration")).hasText("42 min");
        assertThat(page.locator("[data-stat='exercises'] strong")).hasText("1");
        assertThat(page.locator("[data-stat='work-sets'] strong")).hasText("1");
        button("Done").click();
        assertThat(button("Start session")).isVisible();

        waitUntilSynced();
        Assertions.assertThat(count("select count(*) from workout_session where ended_at is not null")).isEqualTo(1);
        Assertions.assertThat(count("select count(*) from workout_set")).isEqualTo(2);
    }

    @Test
    void editsAndDeletesSets() {
        startSessionWithSquat();
        addExerciseAndLogSet("Squat", "40", "10", false);
        setRows().nth(0).click();
        page.getByLabel("Reps").fill("12");
        button("Save").click();
        assertThat(setRows().nth(0)).containsText("40 kg × 12");

        setRows().nth(0).click();
        button("Delete set").click();
        button("Delete").click();
        assertThat(setRows()).hasCount(0);
        waitUntilSynced();
        Assertions.assertThat(count("select count(*) from workout_set")).isZero();
    }

    @Test
    void typedCommaWeightIsLoggedAsDecimal() {
        startSessionWithSquat();
        addExerciseAndLogSet("Squat", "42,5", "8", false);
        assertThat(setRows().nth(0)).containsText("42.5 kg × 8");
        waitUntilSynced();
        Assertions.assertThat(jdbc.queryForObject("select weight_kg from workout_set", BigDecimal.class))
                .isEqualByComparingTo("42.5");
    }

    @Test
    void doubleTapOnSaveLogsOneSet() {
        startSessionWithSquat();
        button("+ Add exercise").click();
        button("Squat").click();
        page.getByLabel("Weight in kg").fill("40");
        button("Save").dblclick();
        assertThat(setRows()).hasCount(1);
        waitUntilSynced();
        Assertions.assertThat(count("select count(*) from workout_set")).isEqualTo(1);
    }

    @Test
    void setsLoggedOfflineSyncWhenBackOnline() {
        startSessionWithSquat();
        waitUntilSynced();
        context.setOffline(true);
        addExerciseAndLogSet("Squat", "40", "10", false);
        button("+ Set").click();
        button("Save").click();
        button("+ Set").click();
        button("Save").click();
        assertThat(setRows()).hasCount(3);
        assertThat(page.locator("#syncbar")).hasText("3 changes waiting to sync");
        Assertions.assertThat(count("select count(*) from workout_set")).isZero();

        context.setOffline(false);
        waitUntilSynced();
        Assertions.assertThat(count("select count(*) from workout_set")).isEqualTo(3);
    }

    @Test
    void resumesAfterReloadAndShowsLastTimeNextSession() {
        startSessionWithSquat();
        addExerciseAndLogSet("Squat", "40", "10", false);
        waitUntilSynced();
        page.reload();
        assertThat(setRows()).hasCount(1);
        assertThat(page.locator("#session-timer")).isVisible();

        button("End").click();
        button("End workout").click();
        button("Done").click();
        waitUntilSynced();

        button("Start session").click();
        button("+ Add exercise").click();
        button("Squat").click();
        assertThat(page.getByText("Last time: 40 kg × 10")).isVisible();
        assertThat(page.getByLabel("Weight in kg")).hasValue("40");
    }

    @Test
    void endingEmptySessionDiscardsIt() {
        startSessionWithSquat();
        button("End").click();
        assertThat(page.getByText("No sets logged. This workout won't be saved.")).isVisible();
        button("End workout").click();
        assertThat(page.locator("#toast")).hasText("Empty workout discarded");
        assertThat(button("Start session")).isVisible();
        waitUntilSynced();
        Assertions.assertThat(count("select count(*) from workout_session")).isZero();
    }

    @Test
    void discardsSessionFromMenu() {
        startSessionWithSquat();
        addExerciseAndLogSet("Squat", "40", "10", false);
        page.getByLabel("More options").click();
        button("Discard session").click();
        button("Discard").click();
        assertThat(button("Start session")).isVisible();
        waitUntilSynced();
        Assertions.assertThat(count("select count(*) from workout_session")).isZero();
        Assertions.assertThat(count("select count(*) from workout_set")).isZero();
    }

    @Test
    void newExerciseFromPickerGoesStraightToSetSheet() {
        signIn();
        tab("Workout").click();
        button("Start session").click();
        button("+ Add exercise").click();
        button("+ New exercise").click();
        page.getByLabel("Name").fill("Hip thrust");
        button("Glutes").click();
        button("Save").click();
        assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Hip thrust"))).isVisible();
        fillAndSaveSet("60", "10", false);
        assertThat(setRows().nth(0)).containsText("60 kg × 10");
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest=WorkoutE2ETest`
Expected: FAIL. There is no "Workout" tab yet, so `tab("Workout").click()` times out.

- [ ] **Step 3: Implement the workout view**

`src/main/resources/static/js/views/workout.js`:
```js
import * as store from '../store.js';
import { navigate } from '../router.js';
import { openSheet, confirmDialog, toast } from '../ui.js';
import { esc, uuid, localDateIso } from '../util.js';
import {
  MUSCLE_GROUPS, muscleLabel, formatSet, formatSetCount, formatClock, formatDuration, formatDate, formatLongDate, parseWeight,
} from '../format.js';
import { openExerciseForm } from './exercises.js';

let timer = null;
const byTime = (a, b) => Date.parse(a.loggedAt) - Date.parse(b.loggedAt);

export function render(container, route) {
  clearInterval(timer);
  if (route.summary) {
    renderSummary(container, route.summary);
    return;
  }
  const session = store.view().activeSession;
  if (session) renderSession(container, session);
  else renderStart(container);
}

function renderStart(container) {
  const last = store.view().history[0];
  container.innerHTML = `
    <section class="screen">
      <header class="topbar"><h1>Workout</h1></header>
      <p class="muted">${formatLongDate(localDateIso())}</p>
      <button class="btn primary huge" data-action="start">Start session</button>
      ${last ? `
        <div class="card">
          <h2>Last workout</h2>
          <p>${formatDate(last.date)} · ${formatDuration(last.durationSeconds)} · ${formatSetCount(last.setCount)}</p>
        </div>` : ''}
    </section>`;
  container.querySelector('[data-action="start"]').addEventListener('click', () => {
    store.dispatch('session.start', { id: uuid(), date: localDateIso(), startedAt: new Date().toISOString() });
  });
}

/** Groups sets by exercise, in the order each exercise was first done. */
export function groupSets(sets) {
  const groups = new Map();
  for (const set of [...sets].sort(byTime)) {
    if (!groups.has(set.exerciseId)) groups.set(set.exerciseId, { exerciseId: set.exerciseId, name: set.exerciseName, sets: [] });
    groups.get(set.exerciseId).sets.push(set);
  }
  return [...groups.values()];
}

function elapsed(session) {
  return formatClock((Date.now() - Date.parse(session.startedAt)) / 1000);
}

function setRowExtras(set) {
  return set.type === 'WARMUP' ? '<span class="tag">Warmup</span>' : '';
}

function renderSession(container, session) {
  const groups = groupSets(session.sets);
  container.innerHTML = `
    <section class="screen">
      <header class="topbar">
        <div>
          <h1>${formatDate(session.date)}</h1>
          <p class="timer" id="session-timer">${elapsed(session)}</p>
        </div>
        <div class="actions">
          <button class="btn icon ghost" data-action="menu" aria-label="More options">⋯</button>
          <button class="btn primary" data-action="end">End</button>
        </div>
      </header>
      ${groups.map((group) => `
        <div class="card">
          <h2>${esc(group.name)}</h2>
          <ul class="sets">
            ${group.sets.map((set, i) => `
              <li><button class="set-row" data-set="${set.id}">
                <span class="set-no">${i + 1}</span>
                <span>${formatSet(set.weightKg, set.reps)}</span>
                ${setRowExtras(set)}
              </button></li>`).join('')}
          </ul>
          <button class="btn secondary small" data-add-set="${group.exerciseId}">+ Set</button>
        </div>`).join('')}
      ${groups.length ? '' : '<p class="empty">Add an exercise to start logging sets.</p>'}
      <button class="btn primary big" data-action="add-exercise">+ Add exercise</button>
    </section>`;
  timer = setInterval(() => {
    const el = document.getElementById('session-timer');
    if (el) el.textContent = elapsed(session);
  }, 1000);
  container.querySelector('[data-action="end"]').addEventListener('click', () => endSession(session));
  container.querySelector('[data-action="menu"]').addEventListener('click', () => openMenu(session));
  container.querySelector('[data-action="add-exercise"]').addEventListener('click', () => openPicker(session));
  container.querySelectorAll('[data-add-set]').forEach((button) => button.addEventListener('click', () => {
    openSetSheet(session.id, exerciseFor(button.dataset.addSet, session));
  }));
  container.querySelectorAll('[data-set]').forEach((button) => button.addEventListener('click', () => {
    const set = session.sets.find((s) => s.id === button.dataset.set);
    openSetSheet(session.id, exerciseFor(set.exerciseId, session), set);
  }));
}

/** The exercise from the list, or a stand-in built from its sets if it has been deleted since. */
function exerciseFor(exerciseId, session) {
  const exercise = store.view().exercises.find((e) => e.id === exerciseId);
  if (exercise) return exercise;
  const set = session.sets.find((s) => s.exerciseId === exerciseId);
  return { id: exerciseId, name: set?.exerciseName ?? '', muscleGroup: set?.muscleGroup ?? null, lastTime: null, records: null };
}

function openPicker(session) {
  const { el, close } = openSheet(`
    <h2>Add exercise</h2>
    <input class="search" type="search" placeholder="Search" aria-label="Search exercises">
    <button class="btn secondary" data-action="new">+ New exercise</button>
    <div class="picker-list"></div>`);
  const list = el.querySelector('.picker-list');
  const draw = (query) => {
    const q = query.trim().toLowerCase();
    const all = store.view().exercises;
    const matches = all.filter((e) => e.name.toLowerCase().includes(q));
    list.innerHTML = MUSCLE_GROUPS.map((group) => {
      const items = matches.filter((e) => e.muscleGroup === group);
      return items.length ? `
        <h3 class="group-title">${muscleLabel(group)}</h3>
        <ul class="list">${items.map((e) => `<li><button class="row" data-pick="${e.id}">${esc(e.name)}</button></li>`).join('')}</ul>` : '';
    }).join('') || `<p class="empty">${all.length ? 'No matching exercises.' : 'No exercises yet.'}</p>`;
  };
  draw('');
  el.querySelector('.search').addEventListener('input', (event) => draw(event.target.value));
  list.addEventListener('click', (event) => {
    const button = event.target.closest('[data-pick]');
    if (!button) return;
    close();
    openSetSheet(session.id, store.view().exercises.find((e) => e.id === button.dataset.pick));
  });
  el.querySelector('[data-action="new"]').addEventListener('click', () => {
    close();
    openExerciseForm(null, { onSaved: (exercise) => openSetSheet(session.id, exercise) });
  });
}

/** Weight and reps to start from: this session's last set of the exercise, else last time, else 0 kg × 10. */
export function prefillFor(exerciseId, sessionSets, lastTime) {
  const inSession = sessionSets.filter((s) => s.exerciseId === exerciseId).sort(byTime).at(-1);
  if (inSession) return { weightKg: Number(inSession.weightKg), reps: inSession.reps };
  if (lastTime) return { weightKg: Number(lastTime.weightKg), reps: lastTime.reps };
  return { weightKg: 0, reps: 10 };
}

const clampWeight = (w) => Math.min(500, Math.max(0, Math.round(w * 4) / 4));
const clampReps = (r) => Math.min(100, Math.max(1, r));

/** Bottom sheet to log a new set (no `set`) or edit an existing one. */
function openSetSheet(sessionId, exercise, set = null) {
  const sessionSets = store.view().activeSession?.sets ?? [];
  const start = set ?? prefillFor(exercise.id, sessionSets, exercise.lastTime);
  let type = set?.type ?? 'WORK';
  const { el, close } = openSheet(`
    <h2>${esc(exercise.name)}</h2>
    ${exercise.lastTime ? `<p class="muted">Last time: ${formatSet(exercise.lastTime.weightKg, exercise.lastTime.reps)}</p>` : ''}
    <div class="stepper" data-field="weight">
      <button class="btn step" data-step="-2.5" aria-label="Decrease weight">−</button>
      <label class="step-value"><input inputmode="decimal" aria-label="Weight in kg" value="${Number(start.weightKg)}"><span>kg</span></label>
      <button class="btn step" data-step="2.5" aria-label="Increase weight">+</button>
    </div>
    <div class="stepper" data-field="reps">
      <button class="btn step" data-step="-1" aria-label="Decrease reps">−</button>
      <label class="step-value"><input inputmode="numeric" aria-label="Reps" value="${start.reps}"><span>reps</span></label>
      <button class="btn step" data-step="1" aria-label="Increase reps">+</button>
    </div>
    <div class="segmented" role="radiogroup" aria-label="Set type">
      <button type="button" role="radio" data-type="WARMUP">Warmup</button>
      <button type="button" role="radio" data-type="WORK">Work</button>
    </div>
    <button class="btn primary big" data-action="save">Save</button>
    ${set ? '<button class="btn danger" data-action="delete">Delete set</button>' : ''}`);
  const weightInput = el.querySelector('[data-field="weight"] input');
  const repsInput = el.querySelector('[data-field="reps"] input');
  const paintType = () => el.querySelectorAll('[data-type]').forEach((b) => b.setAttribute('aria-checked', String(b.dataset.type === type)));
  paintType();
  el.querySelectorAll('[data-type]').forEach((b) => b.addEventListener('click', () => {
    type = b.dataset.type;
    paintType();
  }));
  el.querySelectorAll('[data-step]').forEach((b) => b.addEventListener('click', () => {
    const step = Number(b.dataset.step);
    if (b.closest('[data-field="weight"]')) weightInput.value = clampWeight((parseWeight(weightInput.value) ?? 0) + step);
    else repsInput.value = clampReps((parseInt(repsInput.value, 10) || 0) + step);
  }));
  let saved = false;
  el.querySelector('[data-action="save"]').addEventListener('click', () => {
    if (saved) return; // a double tap must log one set, not two
    const weightKg = parseWeight(weightInput.value);
    const reps = Number(repsInput.value);
    if (weightKg === null) {
      toast('Enter a weight');
      return;
    }
    if (!Number.isInteger(reps) || reps < 1 || reps > 100) {
      toast('Reps must be between 1 and 100');
      return;
    }
    saved = true;
    const id = set?.id ?? uuid();
    store.dispatch('set.put', {
      id, sessionId, exerciseId: exercise.id, weightKg, reps, type, loggedAt: set?.loggedAt ?? new Date().toISOString(),
    });
    close();
  });
  el.querySelector('[data-action="delete"]')?.addEventListener('click', async () => {
    close();
    if (await confirmDialog('Delete this set?', { ok: 'Delete', danger: true })) store.dispatch('set.delete', { id: set.id });
  });
}

function openMenu(session) {
  const { el, close } = openSheet(`
    <div class="stack">
      <button class="btn danger big" data-action="discard">Discard session</button>
      <button class="btn secondary big" data-close>Cancel</button>
    </div>`);
  el.querySelector('[data-action="discard"]').addEventListener('click', async () => {
    close();
    const ok = await confirmDialog('Discard this workout? All its sets will be deleted.', { ok: 'Discard', danger: true });
    if (!ok) return;
    store.dispatch('session.discard', { id: session.id });
    toast('Workout discarded');
  });
}

export function buildSummary(session, endedAt) {
  return {
    date: session.date,
    durationSeconds: Math.round((Date.parse(endedAt) - Date.parse(session.startedAt)) / 1000),
    exerciseCount: new Set(session.sets.map((s) => s.exerciseId)).size,
    workSetCount: session.sets.filter((s) => s.type === 'WORK').length,
    prs: [],
  };
}

async function endSession(session) {
  const empty = session.sets.length === 0;
  const ok = await confirmDialog(empty ? "No sets logged. This workout won't be saved." : 'End workout?', { ok: 'End workout' });
  if (!ok) return;
  const endedAt = new Date().toISOString();
  const summary = buildSummary(session, endedAt); // before dispatching, while the records still predate this workout
  store.dispatch('session.end', { id: session.id, endedAt });
  if (empty) {
    toast('Empty workout discarded');
    return;
  }
  navigate({ tab: 'workout', summary });
}

function renderSummary(container, summary) {
  container.innerHTML = `
    <section class="screen summary">
      <h1>Workout complete</h1>
      <p class="muted">${formatLongDate(summary.date)}</p>
      <p class="duration" id="summary-duration">${formatDuration(summary.durationSeconds)}</p>
      <div class="stats-row">
        <div data-stat="exercises"><strong>${summary.exerciseCount}</strong><span>exercises</span></div>
        <div data-stat="work-sets"><strong>${summary.workSetCount}</strong><span>work sets</span></div>
      </div>
      ${summary.prs.length ? `
        <h2>Personal records</h2>
        <ul class="pr-list">${summary.prs.map((pr) => `<li>🏆 ${esc(pr.name)} — ${formatSet(pr.weightKg, pr.reps)} · ${pr.kind}</li>`).join('')}</ul>` : ''}
      <button class="btn primary big" data-action="done">Done</button>
    </section>`;
  container.querySelector('[data-action="done"]').addEventListener('click', () => navigate({ tab: 'workout' }));
}
```

- [ ] **Step 4: Register the tab and the asset**

In `src/main/resources/static/js/app.js`, add the import below the exercises import:
```js
import * as workout from './views/workout.js';
```
and make the Workout tab first:
```js
const TABS = [
  { id: 'workout', label: 'Workout', icon: '🏋️', view: workout },
  { id: 'exercises', label: 'Exercises', icon: '📋', view: exercises },
];
```

In `src/main/resources/static/sw.js`, add `'/js/views/workout.js',` to `ASSETS` after `'/js/views/login.js',`.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw test -Dtest='WorkoutE2ETest,LoginAndExercisesE2ETest,ServiceWorkerAssetsTest'`
Expected: PASS, 16 tests.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Add workout tab with live timer, set sheet, offline logging and summary"
```

---

### Task 9: History tab

**Files:**
- Create: `src/main/resources/static/js/views/history.js`
- Modify: `src/main/resources/static/js/app.js` (tab), `sw.js` (asset), `js/views/workout.js` (cache the finished session's detail)
- Test: `src/test/java/com/gymtracker/e2e/HistoryE2ETest.java`

**Interfaces:**
- Consumes: `groupSets` from `views/workout.js` (Task 8); `store.cacheSessionDetail` and `request` (Task 7).
- Produces: `views/history.js` `render(container, route)`. The route is `{tab: 'history'}` for the list or `{tab: 'history', sessionId}` for the detail. Rows have the `[data-session]` attribute.

- [ ] **Step 1: Write the failing E2E tests**

`src/test/java/com/gymtracker/e2e/HistoryE2ETest.java`:
```java
package com.gymtracker.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Locator;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HistoryE2ETest extends E2ETestBase {

    private void seedWorkout() {
        UUID squat = seedExercise("Squat", "QUADS");
        UUID bench = seedExercise("Bench press", "CHEST");
        UUID session = seedEndedSession("2026-10-01", "2026-10-01T08:00:00Z", "2026-10-01T08:42:00Z");
        seedSet(session, squat, "20", 10, "WARMUP", "2026-10-01T08:05:00Z");
        seedSet(session, squat, "60", 5, "WORK", "2026-10-01T08:10:00Z");
        seedSet(session, bench, "40", 8, "WORK", "2026-10-01T08:20:00Z");
    }

    @Test
    void listsWorkoutsAndShowsTheirSets() {
        seedWorkout();
        signIn();
        tab("History").click();
        Locator row = page.locator("[data-session]");
        assertThat(row).containsText("1 Oct");
        assertThat(row).containsText("42 min · 3 sets · Quads · Chest");

        row.click();
        assertThat(page.locator("h1")).containsText("1 October");
        Locator cards = page.locator("#session-detail .card");
        assertThat(cards).hasCount(2);
        assertThat(cards.nth(0)).containsText("Squat");
        assertThat(cards.nth(0).locator(".set-row").nth(0)).containsText("20 kg × 10");
        assertThat(cards.nth(0).locator(".set-row").nth(0)).containsText("Warmup");
        assertThat(cards.nth(1)).containsText("40 kg × 8");
    }

    @Test
    void detailStaysAvailableOfflineOnceLoaded() {
        seedWorkout();
        signIn();
        tab("History").click();
        page.locator("[data-session]").click();
        assertThat(page.locator("#session-detail .card")).hasCount(2);
        button("‹ History").click();
        context.setOffline(true);
        page.locator("[data-session]").click();
        assertThat(page.locator("#session-detail .card")).hasCount(2);
    }

    @Test
    void justFinishedWorkoutShowsUpRightAway() {
        seedExercise("Squat", "QUADS");
        signIn();
        tab("Workout").click();
        button("Start session").click();
        button("+ Add exercise").click();
        button("Squat").click();
        page.getByLabel("Weight in kg").fill("40");
        button("Save").click();
        button("End").click();
        button("End workout").click();
        button("Done").click();

        tab("History").click();
        assertThat(page.locator("[data-session]")).containsText("1 set · Quads");
        page.locator("[data-session]").click();
        assertThat(page.locator("#session-detail .set-row")).containsText("40 kg × 10");
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest=HistoryE2ETest`
Expected: FAIL. `tab("History")` is not found.

- [ ] **Step 3: Implement the history view**

`src/main/resources/static/js/views/history.js`:
```js
import * as store from '../store.js';
import { request } from '../api.js';
import { getRoute, navigate } from '../router.js';
import { esc } from '../util.js';
import { muscleLabel, formatDate, formatLongDate, formatDuration, formatSet, formatSetCount } from '../format.js';
import { groupSets } from './workout.js';

let fetchedFor = null;
const failures = {};

export function render(container, route) {
  if (route.sessionId) {
    renderDetail(container, route.sessionId);
    return;
  }
  fetchedFor = null;
  const { history } = store.view();
  container.innerHTML = `
    <section class="screen">
      <header class="topbar"><h1>History</h1></header>
      ${history.length ? `<ul class="list">${history.map((h) => `
        <li><button class="row" data-session="${h.id}">
          <span>${formatDate(h.date)}</span>
          <span class="row-sub">${[formatDuration(h.durationSeconds), formatSetCount(h.setCount), ...h.muscleGroups.map(muscleLabel)].join(' · ')}</span>
        </button></li>`).join('')}</ul>` : '<p class="empty">No workouts yet.</p>'}
    </section>`;
  container.querySelectorAll('[data-session]').forEach((button) => button.addEventListener('click', () => {
    navigate({ tab: 'history', sessionId: button.dataset.session });
  }));
}

function setsHtml(sets) {
  return groupSets(sets).map((group) => `
    <div class="card">
      <h2>${esc(group.name)}</h2>
      <ul class="sets">
        ${group.sets.map((set, i) => `
          <li class="set-row">
            <span class="set-no">${i + 1}</span>
            <span>${formatSet(set.weightKg, set.reps)}</span>
            ${set.type === 'WARMUP' ? '<span class="tag">Warmup</span>' : ''}
          </li>`).join('')}
      </ul>
    </div>`).join('');
}

function renderDetail(container, id) {
  const summary = store.view().history.find((h) => h.id === id);
  const detail = store.view().sessionDetails[id];
  const body = detail ? setsHtml(detail.sets) : `<p class="empty">${failures[id] ?? 'Loading…'}</p>`;
  container.innerHTML = `
    <section class="screen">
      <header class="topbar"><button class="btn ghost back" data-action="back">‹ History</button></header>
      <h1>${summary ? formatLongDate(summary.date) : 'Workout'}</h1>
      ${summary ? `<p class="muted">${formatDuration(summary.durationSeconds)} · ${formatSetCount(summary.setCount)}</p>` : ''}
      <div id="session-detail">${body}</div>
    </section>`;
  container.querySelector('[data-action="back"]').addEventListener('click', () => navigate({ tab: 'history' }));
  if (detail || fetchedFor === id) return;
  fetchedFor = id;
  delete failures[id];
  request('GET', `/api/sessions/${id}`).then((result) => {
    if (result.kind === 'ok') {
      store.cacheSessionDetail(id, result.data);
      return;
    }
    failures[id] = result.kind === 'network' ? 'Not available offline.' : 'Could not load this workout.';
    const el = document.getElementById('session-detail');
    if (el && getRoute().sessionId === id) el.innerHTML = `<p class="empty">${failures[id]}</p>`;
  });
}
```

- [ ] **Step 4: Cache the finished session, and register the tab and asset**

In `js/views/workout.js`, inside `endSession`, add one line right after `store.dispatch('session.end', ...)`:
```js
  store.dispatch('session.end', { id: session.id, endedAt });
  if (!empty) store.cacheSessionDetail(session.id, { ...session, endedAt });
```

In `js/app.js`, add `import * as history from './views/history.js';` and the tab after Exercises:
```js
  { id: 'history', label: 'History', icon: '🗓️', view: history },
```

In `sw.js`, add `'/js/views/history.js',` to `ASSETS` after `'/js/views/exercises.js',`.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw test -Dtest='HistoryE2ETest,WorkoutE2ETest,ServiceWorkerAssetsTest'`
Expected: PASS, 13 tests.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Add history tab with offline-cached workout details"
```

---

### Task 10: Personal records during a workout

**Files:**
- Create: `src/main/resources/static/js/records.js`
- Modify: `src/main/resources/static/js/views/workout.js`, `sw.js` (asset), `src/test/resources/static/test/unit.html` (records tests)
- Test: `src/test/java/com/gymtracker/e2e/RecordsE2ETest.java`

**Interfaces:**
- Consumes: the `records` field on exercises from `GET /api/exercises` (Task 5): `{heaviest: {weightKg, reps, date} | null, repRecords: [{weightKg, reps, date}]}`.
- Produces. `records.js` exports:
  - `prFlags(sets, recordsByExercise)`: a `Map(setId → {heaviest: boolean, reps: boolean})` for WORK sets only;
  - `isPr(flag)`;
  - `recordsByExercise(exercises)`: `{[exerciseId]: records}`.
- In the UI: a `.pr` trophy on the set row, the toast `🏆 New PR!`, and summary PR lines `🏆 <name> — <set> · heaviest weight | rep record` in `.pr-list`.

- [ ] **Step 1: Add the failing unit tests for the PR rules**

In `src/test/resources/static/test/unit.html`, add this import below the existing imports:
```js
  import { prFlags, isPr } from '/js/records.js';
```
and add these tests just before `window.__results = results;`:
```js
  const ws = (id, weightKg, reps, minute, type = 'WORK') =>
    ({ id, exerciseId: 'e1', weightKg, reps, type, loggedAt: `2026-10-05T08:${minute}:00.000Z` });
  const history = { e1: {
    heaviest: { weightKg: '60.00', reps: 5, date: '2026-09-28' },
    repRecords: [{ weightKg: '60.00', reps: 5 }, { weightKg: '40.00', reps: 10 }, { weightKg: '0.00', reps: 20 }],
  } };

  test('prFlags: heavier than ever is a heaviest-weight PR', () => {
    eq(prFlags([ws('a', 62.5, 3, '10')], history).get('a'), { heaviest: true, reps: false });
  });
  test('prFlags: more reps at a weight done before is a rep PR', () => {
    eq(prFlags([ws('a', 40, 12, '10')], history).get('a'), { heaviest: false, reps: true });
  });
  test('prFlags: equal reps, or a lighter weight never done before, is no PR', () => {
    const flags = prFlags([ws('a', 40, 10, '10'), ws('b', 30, 5, '11')], history);
    eq([isPr(flags.get('a')), isPr(flags.get('b'))], [false, false]);
  });
  test('prFlags: bodyweight can be a rep PR but never a heaviest PR', () => {
    eq(prFlags([ws('a', 0, 25, '10')], history).get('a'), { heaviest: false, reps: true });
  });
  test('prFlags: the first set ever of an exercise is no PR', () => {
    eq(isPr(prFlags([ws('a', 40, 10, '10')], {}).get('a')), false);
  });
  test('prFlags: earlier sets in the same session count as history', () => {
    const flags = prFlags([ws('a', 40, 10, '10'), ws('b', 40, 11, '11'), ws('c', 45, 5, '12')], {});
    eq([isPr(flags.get('a')), flags.get('b'), flags.get('c')], [false, { heaviest: false, reps: true }, { heaviest: true, reps: false }]);
  });
  test('prFlags: warmups are never PRs and do not count as history', () => {
    const flags = prFlags([ws('w', 100, 1, '09', 'WARMUP'), ws('a', 40, 10, '10')], {});
    eq([flags.has('w'), isPr(flags.get('a'))], [false, false]);
  });
  test('prFlags: order follows loggedAt, not array order', () => {
    const flags = prFlags([ws('late', 45, 5, '12'), ws('early', 40, 10, '10')], {});
    eq([isPr(flags.get('early')), flags.get('late').heaviest], [false, true]);
  });
```

Run: `./mvnw test -Dtest=JsUnitE2ETest`
Expected: FAIL. The module import of `/js/records.js` fails (404), so `window.__results` is never set and `waitForFunction` times out.

- [ ] **Step 2: Implement the PR rules**

`src/main/resources/static/js/records.js`:
```js
// PR detection for the open session (spec §5). Runs on the phone so it also works offline.
const key = (weightKg) => Number(weightKg).toFixed(2);
const byTime = (a, b) => Date.parse(a.loggedAt) - Date.parse(b.loggedAt);

function initialState(records) {
  const repRecords = records?.repRecords ?? [];
  const weights = repRecords.map((r) => Number(r.weightKg));
  if (records?.heaviest) weights.push(Number(records.heaviest.weightKg));
  return {
    hasAny: weights.length > 0,
    maxWeight: weights.length ? Math.max(...weights) : 0,
    bestReps: new Map(repRecords.map((r) => [key(r.weightKg), r.reps])),
  };
}

/** Map(setId -> {heaviest, reps}) for the WORK sets, comparing each with stored records and earlier sets. */
export function prFlags(sets, recordsByExercise) {
  const states = new Map();
  const flags = new Map();
  for (const set of [...sets].sort(byTime)) {
    if (set.type !== 'WORK') continue;
    if (!states.has(set.exerciseId)) states.set(set.exerciseId, initialState(recordsByExercise[set.exerciseId]));
    const state = states.get(set.exerciseId);
    const weight = Number(set.weightKg);
    const best = state.bestReps.get(key(weight));
    flags.set(set.id, {
      heaviest: weight > 0 && state.hasAny && weight > state.maxWeight,
      reps: best !== undefined && set.reps > best,
    });
    state.hasAny = true;
    state.maxWeight = Math.max(state.maxWeight, weight);
    state.bestReps.set(key(weight), Math.max(best ?? 0, set.reps));
  }
  return flags;
}

export function isPr(flag) {
  return Boolean(flag && (flag.heaviest || flag.reps));
}

export function recordsByExercise(exercises) {
  return Object.fromEntries(exercises.map((e) => [e.id, e.records]));
}
```

In `sw.js`, add `'/js/records.js',` to `ASSETS` after `'/js/format.js',`.

Run: `./mvnw test -Dtest='JsUnitE2ETest,ServiceWorkerAssetsTest'`
Expected: PASS.

- [ ] **Step 3: Write the failing E2E test**

`src/test/java/com/gymtracker/e2e/RecordsE2ETest.java`:
```java
package com.gymtracker.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecordsE2ETest extends E2ETestBase {

    private void logSet(String weight, String reps) {
        page.getByLabel("Weight in kg").fill(weight);
        page.getByLabel("Reps").fill(reps);
        button("Save").click();
    }

    @Test
    void personalRecordsAreCelebratedAndListedInSummary() {
        UUID squat = seedExercise("Squat", "QUADS");
        UUID earlier = seedEndedSession("2026-09-28", "2026-09-28T08:00:00Z", "2026-09-28T09:00:00Z");
        seedSet(earlier, squat, "40", 10, "WORK", "2026-09-28T08:10:00Z");
        signIn();
        tab("Workout").click();
        button("Start session").click();

        button("+ Add exercise").click();
        button("Squat").click();
        logSet("40", "12");
        assertThat(page.locator("#toast")).hasText("🏆 New PR!");
        assertThat(page.locator(".set-row").nth(0).locator(".pr")).hasCount(1);

        button("+ Set").click();
        logSet("30", "5");
        assertThat(page.locator(".set-row").nth(1).locator(".pr")).hasCount(0);

        button("+ Set").click();
        logSet("45", "3");
        assertThat(page.locator(".pr")).hasCount(2);

        button("End").click();
        button("End workout").click();
        assertThat(page.locator(".pr-list li")).hasCount(2);
        assertThat(page.locator(".pr-list li").nth(0)).hasText("🏆 Squat — 40 kg × 12 · rep record");
        assertThat(page.locator(".pr-list li").nth(1)).hasText("🏆 Squat — 45 kg × 3 · heaviest weight");
    }

    @Test
    void firstEverSetAndWarmupsAreNoRecords() {
        seedExercise("Squat", "QUADS");
        signIn();
        tab("Workout").click();
        button("Start session").click();
        button("+ Add exercise").click();
        button("Squat").click();
        logSet("40", "10");
        assertThat(page.locator(".pr")).hasCount(0);
        assertThat(page.locator("#toast")).not().hasText("🏆 New PR!");
    }
}
```

Run: `./mvnw test -Dtest=RecordsE2ETest`
Expected: FAIL (no toast, no `.pr` elements).

- [ ] **Step 4: Show PRs in the workout view**

In `js/views/workout.js`:

1. Add the import:
```js
import { prFlags, isPr, recordsByExercise } from '../records.js';
```

2. Replace `setRowExtras` with a version that takes the PR flags:
```js
function setRowExtras(set, flags) {
  const warmup = set.type === 'WARMUP' ? '<span class="tag">Warmup</span>' : '';
  const pr = isPr(flags.get(set.id)) ? '<span class="pr" aria-label="Personal record">🏆</span>' : '';
  return warmup + pr;
}

function sessionFlags(sets) {
  return prFlags(sets, recordsByExercise(store.view().exercises));
}
```

3. In `renderSession`, add `const flags = sessionFlags(session.sets);` below `const groups = groupSets(session.sets);`, and change `${setRowExtras(set)}` to `${setRowExtras(set, flags)}`.

4. In the save handler of `openSetSheet`, add the PR toast directly after `close();`:
```js
    close();
    if (isPr(sessionFlags(store.view().activeSession?.sets ?? []).get(id))) toast('🏆 New PR!');
```

5. Replace `buildSummary` with:
```js
export function buildSummary(session, endedAt) {
  const flags = sessionFlags(session.sets);
  return {
    date: session.date,
    durationSeconds: Math.round((Date.parse(endedAt) - Date.parse(session.startedAt)) / 1000),
    exerciseCount: new Set(session.sets.map((s) => s.exerciseId)).size,
    workSetCount: session.sets.filter((s) => s.type === 'WORK').length,
    prs: [...session.sets].sort(byTime).filter((s) => isPr(flags.get(s.id))).map((s) => ({
      name: s.exerciseName,
      weightKg: s.weightKg,
      reps: s.reps,
      kind: flags.get(s.id).heaviest ? 'heaviest weight' : 'rep record',
    })),
  };
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw test -Dtest='RecordsE2ETest,WorkoutE2ETest,JsUnitE2ETest'`
Expected: PASS, 12 tests.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Celebrate personal records during workouts and in the summary"
```

---
### Task 11: Rest timer, settings and lock-screen alerts

**Files:**
- Create: `src/main/resources/static/js/prefs.js`, `js/rest.js`, `js/push.js`, `js/views/settings.js`
- Modify: `js/views/workout.js`, `js/app.js`, `sw.js` (assets plus `push` and `notificationclick` handlers)
- Test: `src/test/java/com/gymtracker/e2e/RestTimerE2ETest.java`

**Interfaces:**
- Consumes:
  - `PUT/DELETE /api/rest-timer`, `GET /api/push/public-key`, `PUT /api/push/subscription` (Task 6);
  - `request` (Task 7); `formatCountdown` (Task 7).
- Produces:
  - **`prefs.js`:** `getPrefs()` → `{restSeconds: 90, autoStart: true, alertsEnabled: false}` by default, and `updatePrefs(patch)`.
  - **`rest.js`:** `initRest()`, `setSessionOpen(open)`, `startRest(seconds?)`, `adjustRest(deltaSeconds)`, `skipRest()`, `onSetSaved()`.
  - **`push.js`:** `enableAlerts()` → `{ok, message?}`.
  - **`views/settings.js`:** `openSettings()`.
  - **DOM:**
    - `#restbar`, `#rest-time`;
    - buttons `−15 s`, `+15 s`, `Skip`, `Start rest`;
    - the text `Rest over`;
    - settings aria-labels `Settings`, `Shorter rest`, `Longer rest`; `#rest-seconds`; the switch `Auto-start rest after each set`.

- [ ] **Step 1: Write the failing E2E tests**

`src/test/java/com/gymtracker/e2e/RestTimerE2ETest.java`:
```java
package com.gymtracker.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Clock;
import com.microsoft.playwright.Locator;
import org.junit.jupiter.api.Test;

class RestTimerE2ETest extends E2ETestBase {

    private void freezeClock() {
        page.clock().install(new Clock.InstallOptions().setTime("2026-10-05T07:59:00Z"));
        page.clock().pauseAt("2026-10-05T08:00:00Z");
    }

    private void startSessionAndLogSet() {
        seedExercise("Squat", "QUADS");
        signIn();
        tab("Workout").click();
        button("Start session").click();
        logSquatSet();
    }

    private void logSquatSet() {
        button("+ Add exercise").click();
        button("Squat").click();
        page.getByLabel("Weight in kg").fill("40");
        button("Save").click();
    }

    private Locator restTime() {
        return page.locator("#rest-time");
    }

    @Test
    void restStartsAfterSavingASetAndCountsDown() {
        freezeClock();
        startSessionAndLogSet();
        assertThat(restTime()).hasText("1:30");
        button("+15 s").click();
        assertThat(restTime()).hasText("1:45");
        button("−15 s").click();
        assertThat(restTime()).hasText("1:30");

        page.clock().runFor("01:30");
        assertThat(page.locator("#restbar")).containsText("Rest over");
        page.clock().runFor("00:06");
        assertThat(page.locator("#restbar")).isHidden();
    }

    @Test
    void skipHidesTheTimer() {
        freezeClock();
        startSessionAndLogSet();
        assertThat(restTime()).isVisible();
        button("Skip").click();
        assertThat(page.locator("#restbar")).isHidden();
    }

    @Test
    void endingTheWorkoutStopsTheRest() {
        freezeClock();
        startSessionAndLogSet();
        assertThat(restTime()).isVisible();
        button("End").click();
        button("End workout").click();
        assertThat(page.locator("#restbar")).isHidden();
    }

    @Test
    void restTimeAndAutoStartComeFromSettings() {
        freezeClock();
        seedExercise("Squat", "QUADS");
        signIn();
        tab("Workout").click();
        page.getByLabel("Settings").click();
        page.getByLabel("Shorter rest").click();
        page.getByLabel("Shorter rest").click();
        assertThat(page.locator("#rest-seconds")).hasText("1:00");
        page.getByLabel("Auto-start rest after each set").uncheck();
        button("Done").click();

        button("Start session").click();
        logSquatSet();
        assertThat(button("Start rest")).isVisible();
        button("Start rest").click();
        assertThat(restTime()).hasText("1:00");
    }

    @Test
    void restSurvivesReload() {
        startSessionAndLogSet();
        assertThat(restTime()).isVisible();
        page.reload();
        assertThat(restTime()).isVisible();
    }

    @Test
    void lockScreenAlertsNeedTheHomeScreenApp() {
        signIn();
        tab("Workout").click();
        page.getByLabel("Settings").click();
        button("Enable").click();
        assertThat(page.getByText("Add the app to your Home Screen first")).isVisible();
    }
}
```

Run: `./mvnw test -Dtest=RestTimerE2ETest`
Expected: FAIL (`#rest-time` and the Settings button don't exist).

- [ ] **Step 2: Implement preferences, rest timer and push registration**

`src/main/resources/static/js/prefs.js`:
```js
const KEY = 'gt.prefs.v1';
const DEFAULTS = { restSeconds: 90, autoStart: true, alertsEnabled: false };

export function getPrefs() {
  try {
    return { ...DEFAULTS, ...JSON.parse(localStorage.getItem(KEY) || '{}') };
  } catch {
    return { ...DEFAULTS };
  }
}

export function updatePrefs(patch) {
  const next = { ...getPrefs(), ...patch };
  try {
    localStorage.setItem(KEY, JSON.stringify(next));
  } catch {
    // Storage blocked: the change lasts until the app is closed.
  }
  return next;
}
```

`src/main/resources/static/js/rest.js`:
```js
import { request } from './api.js';
import { getPrefs } from './prefs.js';
import { formatCountdown } from './format.js';

// The rest bar above the tabs. The end time is saved, so the countdown survives closing the app.
const KEY = 'gt.rest.v1';
const OVER_MS = 5000;

let rest = load(); // { endsAt, totalMs } or null
let overUntil = 0;
let sessionOpen = false;
let mode = null; // what the bar shows: hidden | running | over | start
let audio = null;

function load() {
  try {
    return JSON.parse(localStorage.getItem(KEY) || 'null');
  } catch {
    return null;
  }
}

function persist() {
  try {
    localStorage.setItem(KEY, JSON.stringify(rest));
  } catch {
    // ignore: the timer still runs in memory
  }
}

export function initRest() {
  document.addEventListener('pointerdown', unlockAudio, { capture: true });
  document.getElementById('restbar').addEventListener('click', (event) => {
    const button = event.target.closest('button');
    if (!button) return;
    if (button.dataset.restAdjust) adjustRest(Number(button.dataset.restAdjust));
    else if (button.dataset.action === 'skip-rest') skipRest();
    else if (button.dataset.action === 'start-rest') startRest();
  });
  if (rest && rest.endsAt < Date.now() - OVER_MS) {
    rest = null;
    persist();
  }
  setInterval(tick, 250);
}

/** Called on every render; the rest timer only exists while a session is open. */
export function setSessionOpen(open) {
  sessionOpen = open;
  if (!open && rest) skipRest();
  draw();
}

export function startRest(seconds = getPrefs().restSeconds) {
  rest = { endsAt: Date.now() + seconds * 1000, totalMs: seconds * 1000 };
  overUntil = 0;
  persist();
  schedulePush();
  draw();
}

export function adjustRest(deltaSeconds) {
  if (!rest) return;
  rest = { endsAt: rest.endsAt + deltaSeconds * 1000, totalMs: Math.max(1000, rest.totalMs + deltaSeconds * 1000) };
  persist();
  if (rest.endsAt > Date.now()) schedulePush();
  else cancelPush();
  tick();
}

export function skipRest() {
  rest = null;
  overUntil = 0;
  persist();
  cancelPush();
  draw();
}

export function onSetSaved() {
  if (getPrefs().autoStart) startRest();
}

function tick() {
  if (rest && Date.now() >= rest.endsAt) {
    rest = null;
    persist();
    overUntil = Date.now() + OVER_MS;
    beep();
  }
  if (overUntil && Date.now() >= overUntil) overUntil = 0;
  draw();
}

// Alerts are best effort: a late alert is useless, so these calls are never queued or retried.
function schedulePush() {
  if (getPrefs().alertsEnabled && rest) request('PUT', '/api/rest-timer', { endsAt: new Date(rest.endsAt).toISOString() });
}

function cancelPush() {
  if (getPrefs().alertsEnabled) request('DELETE', '/api/rest-timer');
}

const BAR = {
  hidden: '',
  running: `
    <div class="rest-progress"><span></span></div>
    <div class="rest-row">
      <span class="rest-time" id="rest-time"></span>
      <button class="btn small" data-rest-adjust="-15">−15 s</button>
      <button class="btn small" data-rest-adjust="15">+15 s</button>
      <button class="btn small" data-action="skip-rest">Skip</button>
    </div>`,
  over: '<div class="rest-row"><span class="rest-time">Rest over</span></div>',
  start: '<button class="btn primary big" data-action="start-rest">Start rest</button>',
};

function draw() {
  const bar = document.getElementById('restbar');
  let next = 'hidden';
  if (sessionOpen && rest) next = 'running';
  else if (sessionOpen && overUntil) next = 'over';
  else if (sessionOpen && !getPrefs().autoStart) next = 'start';
  if (next !== mode) {
    // Only rebuild when the mode changes, so a tap on a button is never lost to a re-render.
    mode = next;
    bar.hidden = next === 'hidden';
    bar.innerHTML = BAR[next];
  }
  if (mode === 'running') {
    const left = rest.endsAt - Date.now();
    bar.querySelector('#rest-time').textContent = formatCountdown(left / 1000);
    bar.querySelector('.rest-progress span').style.width = `${Math.max(0, Math.min(100, (left / rest.totalMs) * 100))}%`;
  }
}

function unlockAudio() {
  try {
    const Context = window.AudioContext || window.webkitAudioContext;
    if (!audio && Context) audio = new Context();
    if (audio?.state === 'suspended') audio.resume();
  } catch {
    audio = null;
  }
}

function beep() {
  try {
    if (!audio || audio.state !== 'running') return;
    [0, 0.3].forEach((offset) => {
      const start = audio.currentTime + offset;
      const oscillator = audio.createOscillator();
      const gain = audio.createGain();
      oscillator.frequency.value = 880;
      gain.gain.setValueAtTime(0.3, start);
      gain.gain.exponentialRampToValueAtTime(0.001, start + 0.25);
      oscillator.connect(gain).connect(audio.destination);
      oscillator.start(start);
      oscillator.stop(start + 0.25);
    });
  } catch {
    // no sound available
  }
}
```

`src/main/resources/static/js/push.js`:
```js
import { request } from './api.js';
import { updatePrefs } from './prefs.js';

function isStandalone() {
  return window.navigator.standalone === true || window.matchMedia('(display-mode: standalone)').matches;
}

function pushSupported() {
  return 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;
}

function base64UrlToBytes(value) {
  const padded = value + '='.repeat((4 - (value.length % 4)) % 4);
  const raw = atob(padded.replace(/-/g, '+').replace(/_/g, '/'));
  return Uint8Array.from(raw, (c) => c.charCodeAt(0));
}

/** Must be called from a tap: iOS only shows the permission prompt in response to one. */
export async function enableAlerts() {
  if (!isStandalone() || !pushSupported()) {
    return { ok: false, message: 'Add the app to your Home Screen first (iOS 16.4 or later), then open it from there.' };
  }
  const permission = await Notification.requestPermission();
  if (permission !== 'granted') {
    return { ok: false, message: 'Notifications are off. Allow them in the iPhone Settings app under Notifications › Gym.' };
  }
  const key = await request('GET', '/api/push/public-key');
  if (key.kind !== 'ok') return { ok: false, message: 'No connection. Try again when you have signal.' };
  try {
    const registration = await navigator.serviceWorker.ready;
    const subscription = await registration.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: base64UrlToBytes(key.data.publicKey),
    });
    const { endpoint, keys } = subscription.toJSON();
    const saved = await request('PUT', '/api/push/subscription', { endpoint, keys: { p256dh: keys.p256dh, auth: keys.auth } });
    if (saved.kind !== 'ok') return { ok: false, message: 'Could not save the alert settings. Try again.' };
  } catch {
    return { ok: false, message: 'Could not turn on alerts on this device.' };
  }
  updatePrefs({ alertsEnabled: true });
  return { ok: true };
}
```

`src/main/resources/static/js/views/settings.js`:
```js
import { openSheet } from '../ui.js';
import { getPrefs, updatePrefs } from '../prefs.js';
import { formatCountdown } from '../format.js';
import { enableAlerts } from '../push.js';

export function openSettings() {
  const prefs = getPrefs();
  const { el } = openSheet(`
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
}
```

- [ ] **Step 3: Wire the timer and settings into the app**

In `js/views/workout.js`:

1. Add these imports:
```js
import { onSetSaved } from '../rest.js';
import { openSettings } from './settings.js';
```

2. In `renderStart`, replace the header line with one that has a gear button, and wire it up:
```js
      <header class="topbar">
        <h1>Workout</h1>
        <button class="btn icon ghost" data-action="settings" aria-label="Settings">⚙️</button>
      </header>
```
Then add this after the start button's listener:
```js
  container.querySelector('[data-action="settings"]').addEventListener('click', openSettings);
```

3. In `openMenu`, add a Settings button above Discard, and its listener:
```js
      <button class="btn secondary big" data-action="settings">Settings</button>
```
```js
  el.querySelector('[data-action="settings"]').addEventListener('click', () => {
    close();
    openSettings();
  });
```

4. In the save handler of `openSetSheet`, add this after the PR toast line:
```js
    onSetSaved();
```

In `js/app.js`:
1. Add `import { initRest, setSessionOpen } from './rest.js';`.
2. In `render()`, add this line after `syncEl.textContent = ...`:
```js
  setSessionOpen(Boolean(store.view().activeSession));
```
3. In `showLogin()`, add `setSessionOpen(false);` after `syncEl.hidden = true;`.
4. In `boot()`, add `initRest();` right after `setRenderer(render);`.

Ending or discarding a session stops the rest by itself: the next render calls `setSessionOpen(false)`.

- [ ] **Step 4: Update the service worker**

In `sw.js`, add these entries to `ASSETS`:
```js
  '/js/prefs.js',
  '/js/push.js',
  '/js/rest.js',
  '/js/views/settings.js',
```
Then add at the end of the file:
```js
self.addEventListener('push', (event) => {
  const data = event.data ? event.data.json() : { title: "Rest's over", body: 'Time for your next set 💪' };
  event.waitUntil(self.registration.showNotification(data.title, {
    body: data.body,
    icon: '/icons/icon-192.png',
    tag: 'rest-timer',
  }));
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  event.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((windows) => {
    if (windows.length) return windows[0].focus();
    return self.clients.openWindow('/');
  }));
});
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw test -Dtest='RestTimerE2ETest,WorkoutE2ETest,RecordsE2ETest,ServiceWorkerAssetsTest'`
Expected: PASS, 18 tests.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Add rest timer with settings and lock-screen alert registration"
```

---

### Task 12: Exercise detail with progress chart, and the Stats tab

**Files:**
- Create: `src/main/resources/static/vendor/chart.umd.min.js` (downloaded), `js/charts.js`, `js/views/stats.js`
- Modify: `index.html` (Chart.js script), `js/views/exercises.js` (detail screen), `js/app.js` (Stats tab), `sw.js` (assets)
- Modify (test): `src/test/java/com/gymtracker/e2e/LoginAndExercisesE2ETest.java` (`editsAndDeletesExercise` now goes through the detail screen)
- Test: `src/test/java/com/gymtracker/e2e/ChartsE2ETest.java`

**Interfaces:**
- Consumes: `GET /api/exercises/{id}/stats` and `weekly` in the snapshot (Tasks 5, 7); `store.cacheExerciseStats` (Task 7).
- Produces:
  - **`charts.js`:** `lineChart(canvas, labels, values, unit)` and `barChart(canvas, labels, values, {unit, horizontal})`.
  - **Exercise detail route:** `{tab: 'exercises', exerciseId}`.
  - **DOM:** `#heaviest`, `.records tbody tr`, `#progress-chart`, `#weekly-chart`, `#muscle-chart`, `#week-title`, and radios `Heaviest weight` / `Estimated 1RM`.

- [ ] **Step 1: Write the failing E2E tests**

`src/test/java/com/gymtracker/e2e/ChartsE2ETest.java`:
```java
package com.gymtracker.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class ChartsE2ETest extends E2ETestBase {

    private final LocalDate today = LocalDate.now(ZoneId.of("Europe/Amsterdam"));

    private UUID seedSession(LocalDate date) {
        return seedEndedSession(date.toString(), date + "T08:00:00Z", date + "T09:00:00Z");
    }

    private void waitForChart(String canvasId) {
        page.waitForFunction("id => !!(window.Chart && window.Chart.getChart(document.getElementById(id)))", canvasId);
    }

    @SuppressWarnings("unchecked")
    private List<Double> chartValues(String canvasId) {
        List<Number> values = (List<Number>) page.evaluate(
                "id => window.Chart.getChart(document.getElementById(id)).data.datasets[0].data", canvasId);
        return values.stream().map(Number::doubleValue).toList();
    }

    @SuppressWarnings("unchecked")
    private List<String> chartLabels(String canvasId) {
        return (List<String>) page.evaluate("id => window.Chart.getChart(document.getElementById(id)).data.labels", canvasId);
    }

    private Locator radio(String name) {
        return page.getByRole(AriaRole.RADIO, new Page.GetByRoleOptions().setName(name).setExact(true));
    }

    @Test
    void exerciseDetailShowsRecordsAndProgressChart() {
        UUID squat = seedExercise("Squat", "QUADS");
        UUID first = seedSession(today.minusDays(7));
        seedSet(first, squat, "55", 5, "WORK", today.minusDays(7) + "T08:10:00Z");
        UUID second = seedSession(today);
        seedSet(second, squat, "60", 5, "WORK", today + "T08:10:00Z");
        seedSet(second, squat, "50", 10, "WORK", today + "T08:20:00Z");
        signIn();
        tab("Exercises").click();
        button("Squat").click();

        assertThat(page.locator("#heaviest")).hasText("60 kg × 5");
        assertThat(page.locator(".records tbody tr")).hasCount(3);
        waitForChart("progress-chart");
        Assertions.assertThat(chartValues("progress-chart")).containsExactly(55.0, 60.0);

        radio("Estimated 1RM").click();
        waitForChart("progress-chart");
        Assertions.assertThat(chartValues("progress-chart")).containsExactly(64.0, 70.0);
    }

    @Test
    void bodyweightOnlyExerciseShowsRepRecordsButNoChart() {
        UUID pullUp = seedExercise("Pull-up", "BACK");
        UUID session = seedSession(today);
        seedSet(session, pullUp, "0", 8, "WORK", today + "T08:10:00Z");
        signIn();
        tab("Exercises").click();
        button("Pull-up").click();
        assertThat(page.getByText("Only bodyweight sets so far")).isVisible();
        assertThat(page.locator(".records tbody tr td").nth(0)).hasText("Bodyweight");
        assertThat(page.locator(".records tbody tr td").nth(1)).hasText("8");
        assertThat(page.locator("#progress-chart")).hasCount(0);
    }

    @Test
    void statsTabShowsWeeklyCharts() {
        UUID squat = seedExercise("Squat", "QUADS");
        UUID session = seedSession(today);
        seedSet(session, squat, "20", 10, "WARMUP", today + "T08:05:00Z");
        seedSet(session, squat, "60", 5, "WORK", today + "T08:10:00Z");
        seedSet(session, squat, "60", 5, "WORK", today + "T08:15:00Z");
        signIn();
        tab("Stats").click();

        waitForChart("weekly-chart");
        List<Double> perWeek = chartValues("weekly-chart");
        Assertions.assertThat(perWeek).hasSize(12);
        Assertions.assertThat(perWeek.getLast()).isEqualTo(1.0);

        assertThat(page.locator("#week-title")).hasText("This week");
        waitForChart("muscle-chart");
        Assertions.assertThat(chartLabels("muscle-chart")).containsExactly("Quads");
        Assertions.assertThat(chartValues("muscle-chart")).containsExactly(2.0);

        page.getByLabel("Previous week").click();
        assertThat(page.getByText("No workouts this week")).isVisible();
    }
}
```

Update `editsAndDeletesExercise` in `LoginAndExercisesE2ETest`, because tapping an exercise now opens its detail screen:
```java
    @Test
    void editsAndDeletesExercise() {
        signIn();
        createExerciseViaUi("Squat", "Quads");
        button("Squat").click();
        button("Edit").click();
        page.getByLabel("Name").fill("Back squat");
        button("Save").click();
        assertThat(page.locator("h1")).hasText("Back squat");

        button("Edit").click();
        button("Delete exercise").click();
        button("Delete").click();
        assertThat(page.getByText("No exercises yet")).isVisible();
        waitUntilSynced();
        Assertions.assertThat(count("select count(*) from exercise where archived")).isEqualTo(1);
    }
```

Run: `./mvnw test -Dtest='ChartsE2ETest,LoginAndExercisesE2ETest'`
Expected: FAIL. There is no detail screen and no Stats tab yet.

- [ ] **Step 2: Add Chart.js**

```bash
mkdir -p src/main/resources/static/vendor
curl -sSfL https://cdn.jsdelivr.net/npm/chart.js@4.5.1/dist/chart.umd.min.js -o src/main/resources/static/vendor/chart.umd.min.js
head -c 120 src/main/resources/static/vendor/chart.umd.min.js
```
Expected: the file starts with a `/*! ... Chart.js v4.5.1` banner.

In `index.html`, add this line directly above the `<script type="module" src="/js/app.js">` line:
```html
  <script src="/vendor/chart.umd.min.js" defer></script>
```

- [ ] **Step 3: Implement the chart helpers**

`src/main/resources/static/js/charts.js`:
```js
// Single-series charts: one accent colour, no legend (the card title names the series),
// labels in text colours, faint gridlines, 2px lines, rounded bar ends and tooltips on tap.
function tokens() {
  const css = getComputedStyle(document.documentElement);
  const read = (name) => css.getPropertyValue(name).trim();
  return { series: read('--series-1'), text: read('--text-secondary'), grid: read('--grid'), surface: read('--surface-1') };
}

function baseOptions(t, unit) {
  return {
    responsive: true,
    maintainAspectRatio: false,
    animation: false,
    plugins: {
      legend: { display: false },
      tooltip: { displayColors: false, intersect: false, mode: 'index', callbacks: { label: (c) => `${c.formattedValue} ${unit}` } },
    },
    scales: {
      x: { grid: { display: false }, border: { color: t.grid }, ticks: { color: t.text, maxRotation: 0, autoSkipPadding: 12 } },
      y: { grid: { color: t.grid }, border: { display: false }, ticks: { color: t.text } },
    },
  };
}

export function lineChart(canvas, labels, values, unit) {
  const { Chart } = window;
  Chart.getChart(canvas)?.destroy();
  const t = tokens();
  return new Chart(canvas, {
    type: 'line',
    data: {
      labels,
      datasets: [{
        data: values,
        borderColor: t.series,
        backgroundColor: t.series,
        borderWidth: 2,
        pointRadius: 4,
        pointHoverRadius: 6,
        pointBorderColor: t.surface,
        pointBorderWidth: 2,
      }],
    },
    options: baseOptions(t, unit),
  });
}

export function barChart(canvas, labels, values, { unit, horizontal = false }) {
  const { Chart } = window;
  Chart.getChart(canvas)?.destroy();
  const t = tokens();
  const options = baseOptions(t, unit);
  const valueAxis = { ...options.scales.y, beginAtZero: true, ticks: { ...options.scales.y.ticks, precision: 0 } };
  const categoryAxis = options.scales.x;
  options.scales = horizontal ? { x: valueAxis, y: categoryAxis } : { x: categoryAxis, y: valueAxis };
  if (horizontal) options.indexAxis = 'y';
  return new Chart(canvas, {
    type: 'bar',
    data: { labels, datasets: [{ data: values, backgroundColor: t.series, borderRadius: 4, borderSkipped: 'start', maxBarThickness: 28 }] },
    options,
  });
}
```

- [ ] **Step 4: Add the exercise detail screen**

In `js/views/exercises.js`:

1. Replace the imports with:
```js
import * as store from '../store.js';
import { request } from '../api.js';
import { navigate } from '../router.js';
import { openSheet, confirmDialog, toast } from '../ui.js';
import { esc, uuid } from '../util.js';
import { MUSCLE_GROUPS, muscleLabel, formatSet, formatWeight, formatDate } from '../format.js';
import { lineChart } from '../charts.js';

let fetchedFor = null;
let chartMode = 'weight';
```

2. Change the start of `render` to handle the detail route, and make the rows open it:
```js
export function render(container, route = {}) {
  if (route.exerciseId) {
    renderDetail(container, route.exerciseId);
    return;
  }
  fetchedFor = null;
```
Then replace the row click handler (the `container.querySelectorAll('[data-id]')` block) with:
```js
  container.querySelectorAll('[data-id]').forEach((button) => button.addEventListener('click', () => {
    navigate({ tab: 'exercises', exerciseId: button.dataset.id });
  }));
```

3. Add these functions at the end of the file:
```js
function emptyChartMessage(stats, records) {
  if (!stats) return navigator.onLine ? 'Loading…' : 'Not available offline.';
  return records.repRecords.length ? 'Only bodyweight sets so far' : 'No work sets yet';
}

function renderDetail(container, id) {
  const exercise = store.view().exercises.find((e) => e.id === id);
  if (!exercise) {
    navigate({ tab: 'exercises' });
    return;
  }
  const stats = store.view().exerciseStats[id];
  const records = stats?.records ?? exercise.records ?? { heaviest: null, repRecords: [] };
  const points = stats?.sessions ?? [];
  const { heaviest } = records;
  container.innerHTML = `
    <section class="screen">
      <header class="topbar">
        <button class="btn ghost back" data-action="back">‹ Exercises</button>
        <button class="btn secondary small" data-action="edit">Edit</button>
      </header>
      <h1>${esc(exercise.name)}</h1>
      <p class="muted">${muscleLabel(exercise.muscleGroup)}</p>
      <div class="card">
        <h2>Heaviest weight</h2>
        <p class="big-number" id="heaviest">${heaviest ? formatSet(heaviest.weightKg, heaviest.reps) : '—'}</p>
        ${heaviest ? `<p class="muted">${formatDate(heaviest.date, { weekday: false })}</p>` : ''}
      </div>
      <div class="card">
        <h2>Progress</h2>
        ${points.length ? `
          <div class="segmented" role="radiogroup" aria-label="Chart">
            <button type="button" role="radio" data-mode="weight" aria-checked="${chartMode === 'weight'}">Heaviest weight</button>
            <button type="button" role="radio" data-mode="e1rm" aria-checked="${chartMode === 'e1rm'}">Estimated 1RM</button>
          </div>
          <div class="chart-wrap"><canvas id="progress-chart" role="img" aria-label="Progress per workout"></canvas></div>`
          : `<p class="empty">${emptyChartMessage(stats, records)}</p>`}
      </div>
      <div class="card">
        <h2>Rep records</h2>
        ${records.repRecords.length ? `
          <table class="records">
            <thead><tr><th>Weight</th><th>Most reps</th><th>Date</th></tr></thead>
            <tbody>${records.repRecords.map((r) => `
              <tr>
                <td>${Number(r.weightKg) === 0 ? 'Bodyweight' : formatWeight(r.weightKg)}</td>
                <td>${r.reps}</td>
                <td>${formatDate(r.date, { weekday: false })}</td>
              </tr>`).join('')}
            </tbody>
          </table>` : '<p class="empty">No work sets yet</p>'}
      </div>
    </section>`;
  container.querySelector('[data-action="back"]').addEventListener('click', () => navigate({ tab: 'exercises' }));
  container.querySelector('[data-action="edit"]').addEventListener('click', () => {
    openExerciseForm(exercise, { onDeleted: () => navigate({ tab: 'exercises' }) });
  });
  container.querySelectorAll('[data-mode]').forEach((button) => button.addEventListener('click', () => {
    chartMode = button.dataset.mode;
    renderDetail(container, id);
  }));
  if (points.length) {
    lineChart(
      container.querySelector('#progress-chart'),
      points.map((p) => formatDate(p.date, { weekday: false })),
      points.map((p) => Number(chartMode === 'weight' ? p.maxWeightKg : p.est1rmKg)),
      'kg',
    );
  }
  if (fetchedFor !== id) {
    fetchedFor = id;
    request('GET', `/api/exercises/${id}/stats`).then((result) => {
      if (result.kind === 'ok') store.cacheExerciseStats(id, result.data);
    });
  }
}
```

- [ ] **Step 5: Add the Stats tab**

`src/main/resources/static/js/views/stats.js`:
```js
import * as store from '../store.js';
import { barChart } from '../charts.js';
import { localDateIso } from '../util.js';
import { MUSCLE_GROUPS, muscleLabel, formatDate } from '../format.js';

let selected = null;

export function render(container) {
  const { weekly, weeklyAsOf } = store.view();
  if (!weekly?.length) {
    container.innerHTML = `
      <section class="screen">
        <header class="topbar"><h1>Stats</h1></header>
        <p class="empty">Stats appear once the app has been online.</p>
      </section>`;
    return;
  }
  if (selected === null || selected >= weekly.length) selected = weekly.length - 1;
  const week = weekly[selected];
  const isThisWeek = selected === weekly.length - 1;
  const muscles = MUSCLE_GROUPS.filter((g) => (week.workSetsByMuscle[g] ?? 0) > 0);
  container.innerHTML = `
    <section class="screen">
      <header class="topbar"><h1>Stats</h1></header>
      ${navigator.onLine || !weeklyAsOf ? '' : `<p class="muted">As of ${formatDate(localDateIso(new Date(weeklyAsOf)))}</p>`}
      <div class="card">
        <h2>Workouts per week</h2>
        <div class="chart-wrap"><canvas id="weekly-chart" role="img" aria-label="Workouts per week, last 12 weeks"></canvas></div>
      </div>
      <div class="card">
        <div class="week-nav">
          <button data-week="-1" aria-label="Previous week"${selected === 0 ? ' disabled' : ''}>‹</button>
          <h2 id="week-title">${isThisWeek ? 'This week' : `Week of ${formatDate(week.weekStart, { weekday: false })}`}</h2>
          <button data-week="1" aria-label="Next week"${isThisWeek ? ' disabled' : ''}>›</button>
        </div>
        <p class="muted">Work sets per muscle group</p>
        ${muscles.length
          ? `<div class="chart-wrap" style="height: ${muscles.length * 36 + 40}px"><canvas id="muscle-chart" role="img" aria-label="Work sets per muscle group"></canvas></div>`
          : '<p class="empty">No workouts this week</p>'}
      </div>
    </section>`;
  barChart(
    container.querySelector('#weekly-chart'),
    weekly.map((w) => formatDate(w.weekStart, { weekday: false })),
    weekly.map((w) => w.workouts),
    { unit: 'workouts' },
  );
  if (muscles.length) {
    barChart(
      container.querySelector('#muscle-chart'),
      muscles.map(muscleLabel),
      muscles.map((g) => week.workSetsByMuscle[g]),
      { unit: 'work sets', horizontal: true },
    );
  }
  container.querySelectorAll('[data-week]').forEach((button) => button.addEventListener('click', () => {
    selected += Number(button.dataset.week);
    render(container);
  }));
}
```

In `js/app.js`, add `import * as stats from './views/stats.js';` and the last tab:
```js
  { id: 'stats', label: 'Stats', icon: '📈', view: stats },
```

In `sw.js`, add these to `ASSETS`:
```js
  '/js/charts.js',
  '/js/views/stats.js',
  '/vendor/chart.umd.min.js',
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw test -Dtest='ChartsE2ETest,LoginAndExercisesE2ETest,ServiceWorkerAssetsTest'`
Expected: PASS, 10 tests.

- [ ] **Step 7: Run everything and look at it**

Run: `./mvnw verify`
Expected: BUILD SUCCESS with every test passing.

Then look at the real thing. The validator for charts only checks colour, not layout, so this check matters:
```bash
./mvnw spring-boot:test-run -Dspring-boot.run.profiles=test
```
Open `http://localhost:8080` in Safari, open Responsive Design Mode (⌥⌘R) and choose an iPhone, then sign in as `tester` / `secret-pass`. Log a short workout, end it, and check:
- the Exercises detail and Stats screens, in light mode and in dark mode (System Settings › Appearance);
- that no labels overlap, no text is clipped, and nothing scrolls sideways.

Stop the app with Ctrl+C.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "Add exercise progress charts, records table and weekly stats tab"
```

---

### Task 13: Deployment: Docker, CI, Railway guide, publish to GitHub

**Files:**
- Create: `Dockerfile`, `.dockerignore`, `railway.json`, `.github/workflows/ci.yml`, `README.md`, `docs/railway-setup.md`

**Interfaces:**
- Consumes: the finished app (Tasks 1–12) and `VapidKeyGenerator` (Task 6).
- Produces:
  - a Docker image that listens on `$PORT`;
  - CI that runs `./mvnw verify` on every push;
  - the public repo `rietveldsj-stack/gym-tracker`;
  - the Railway walkthrough `docs/railway-setup.md`.

- [ ] **Step 1: Write the container and Railway config**

`Dockerfile`:
```dockerfile
# Build stage
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q package -DskipTests

# Run stage
FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd --system --uid 1001 app
COPY --from=build /workspace/target/app.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
```

`.dockerignore`:
```
target
.git
.github
docs
tools
*.md
```

`railway.json`:
```json
{
  "$schema": "https://railway.com/railway.schema.json",
  "build": { "builder": "DOCKERFILE" },
  "deploy": {
    "healthcheckPath": "/actuator/health",
    "healthcheckTimeout": 120,
    "restartPolicyType": "ON_FAILURE"
  }
}
```

- [ ] **Step 2: Check that the image builds and starts**

```bash
docker build -t gym-tracker .
docker network create gt-smoke
docker run -d --rm --name gt-db --network gt-smoke -e POSTGRES_USER=gymtracker -e POSTGRES_PASSWORD=pw -e POSTGRES_DB=gymtracker postgres:17-alpine
for i in $(seq 1 300); do docker exec gt-db pg_isready -U gymtracker -q && break; done
docker run -d --rm --name gt-app --network gt-smoke -p 8080:8080 \
  -e PGHOST=gt-db -e PGUSER=gymtracker -e PGPASSWORD=pw -e PGDATABASE=gymtracker \
  -e APP_USERNAME=smoke -e APP_PASSWORD=smoke-pass -e REMEMBER_ME_KEY=smoke-key -e SECURE_COOKIES=false \
  -e VAPID_PUBLIC_KEY=BLeb9PGJiA9-R9DEzj4AmumqClt7-8HxyHk9jEItaHqiIV0cjTrDfHyFj4cNIisPQ1DN4VUUy5Qj99wGtEzGnuA \
  -e VAPID_PRIVATE_KEY=lf5HflvYZaUBxCSAzrj01cdVJTTwFaB42VxTWfFhjGs -e VAPID_SUBJECT=mailto:test@example.com \
  gym-tracker
curl -sf --retry 45 --retry-delay 2 --retry-all-errors localhost:8080/actuator/health; echo
curl -s -o /dev/null -w "%{http_code}\n" localhost:8080/api/me
docker stop gt-app gt-db && docker network rm gt-smoke
```
Expected:
- `{"status":"UP"}`, then `401`.
- If the health check never answers, run `docker logs gt-app` before stopping the containers.

- [ ] **Step 3: Write the CI workflow**

`.github/workflows/ci.yml`:
```yaml
name: CI

on:
  push:
  pull_request:

jobs:
  test:
    runs-on: ubuntu-latest
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@v5
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: '25'
          cache: maven
      - name: Install WebKit and its system libraries for Playwright
        run: ./mvnw -B -q test-compile exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.classpathScope=test -Dexec.args="install --with-deps webkit"
      - name: Test
        run: ./mvnw -B verify
```

- [ ] **Step 4: Write the README and the Railway guide**

`README.md`:
```markdown
# Gym Tracker

A personal gym tracker: exercises, workout sessions with warmup/work sets, history, personal records,
progress charts and a rest timer with lock-screen alerts. Spring Boot serves the API and an
offline-capable web app that is added to the iPhone home screen from Safari.

## Run locally

Needs Java 25 and Docker (for the throwaway Postgres).

    ./mvnw spring-boot:test-run -Dspring-boot.run.profiles=test

Open http://localhost:8080 and sign in with `tester` / `secret-pass`.

## Tests

    ./mvnw verify

Runs the API tests against Postgres in Docker and the browser tests in WebKit (Safari's engine).

## Deploy

See [docs/railway-setup.md](docs/railway-setup.md). All secrets are Railway variables; nothing secret is in this repo.
```

`docs/railway-setup.md`:
````markdown
# Railway setup

About 10 minutes. You need: the Railway account, access to the `rietveldsj-stack/gym-tracker` GitHub repo,
and a terminal in this project folder.

## 1. Make the secrets (on your Mac)

```bash
# The app password: long and random, because the site and the code are public. Save it in your password manager.
openssl rand -base64 18
# Key that signs the "stay signed in" cookie
openssl rand -hex 32
# Keys for lock-screen notifications
./mvnw -q compile && java -cp target/classes com.gymtracker.push.VapidKeyGenerator
```

## 2. Create the project

1. Go to https://railway.com and sign in. Click **New Project**, then **Deploy from GitHub repo**, and pick
   `gym-tracker`. If Railway asks, allow its GitHub app to access that repo.
2. Railway finds the `Dockerfile` and starts building. The first deploy will fail, because the settings
   aren't there yet. That's expected.
3. On the project canvas click **Create**, then **Database**, then **PostgreSQL**. Wait until it shows as running.

## 3. Add the settings

Click the **gym-tracker** service, then **Variables**, then **Raw Editor**, and paste the block below with
your values filled in. `Postgres` must match the database service's name on the canvas.

```
PGHOST=${{Postgres.PGHOST}}
PGPORT=${{Postgres.PGPORT}}
PGDATABASE=${{Postgres.PGDATABASE}}
PGUSER=${{Postgres.PGUSER}}
PGPASSWORD=${{Postgres.PGPASSWORD}}
APP_USERNAME=<username>
APP_PASSWORD=<the password from step 1>
REMEMBER_ME_KEY=<the hex key from step 1>
VAPID_PUBLIC_KEY=<from step 1>
VAPID_PRIVATE_KEY=<from step 1>
VAPID_SUBJECT=mailto:<your email address>
```

Click **Update Variables**. Railway redeploys automatically.

## 4. Service settings

In the service's **Settings**:
- **Source:** turn on **Wait for CI**, so a deploy only happens after the GitHub tests pass.
- **Networking:** click **Generate Domain**. If Railway asks for a port, use `8080`.
- The health check (`/actuator/health`) is already set by `railway.json` in the repo.

## 5. Check it

- **Deploy logs:** **Deployments**, then the newest deploy, should end with `Started GymTrackerApplication`.
- **Health:** open `https://<your-domain>/actuator/health`. It should show `{"status":"UP"}`.

## 6. On the iPhone

1. Open `https://<your-domain>` in **Safari** and sign in.
2. Tap **Share**, then **Add to Home Screen**, then **Add**.
3. Open **Gym** from the home screen (not from Safari).
4. On the **Workout** tab, tap **⚙️**, then under **Lock-screen alerts** tap **Enable**, then **Allow**.

Then do a quick test:
- **Rest alert:** start a session, log a set, lock the phone, and check the "Rest's over" alert arrives after about 90 s.
- **No signal:** turn on Airplane Mode, log a set ("1 change waiting to sync" appears), then turn Airplane Mode
  off and check the message disappears.

## Costs and limits

- **Price:** Railway's Hobby plan is about $5/month, which covers this app and its database.
- **Restarts:** a deploy or restart drops a rest alert that is counting down at that moment.
- **Staying signed in:** the user stays signed in for a year, through restarts.
````

- [ ] **Step 5: Final full test run**

Run: `./mvnw verify`
Expected: BUILD SUCCESS, every test passing.

- [ ] **Step 6: Check for secrets, then commit and publish**

```bash
git grep -nE "(PASSWORD|SECRET|PRIVATE_KEY)=[^$<{]" -- ':!docs' ':!src/test' || echo "no secrets outside tests"
git add -A
git commit -m "Add Dockerfile, CI workflow, Railway config and setup guide"
gh repo create gym-tracker --public --source . --remote origin --push
gh run watch --exit-status
```
Expected:
- `no secrets outside tests`. The only key material in the repo is the throwaway VAPID pair in `src/test/resources/application-test.properties`.
- The repo is created and pushed, and the CI run ends with success.
- If CI fails, read `gh run view --log-failed` and fix the cause before going further.

---
