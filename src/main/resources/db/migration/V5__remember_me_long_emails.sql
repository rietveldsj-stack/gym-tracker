-- Remember-me stores the account's email here, and emails can be up to 254 characters.
alter table persistent_logins alter column username type varchar(254);
