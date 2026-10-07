-- The name the greeting uses. Accounts made before names existed have none until it is set in Settings.
alter table app_user add column name varchar(40);
