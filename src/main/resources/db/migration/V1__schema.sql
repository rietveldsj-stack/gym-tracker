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
