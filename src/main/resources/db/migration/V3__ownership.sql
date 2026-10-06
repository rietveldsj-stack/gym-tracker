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
