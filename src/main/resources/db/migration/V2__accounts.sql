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
