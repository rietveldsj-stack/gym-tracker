# Railway setup

About 10 minutes. You need: the Railway account, access to the `rietveldsj-stack/gym-tracker` GitHub repo,
and a terminal in this project folder.

## 1. Make the secrets (on your Mac)

```bash
# The invite code people need to create an account. Share it only with people who should get an account.
openssl rand -base64 9
# Key that signs the "stay signed in" cookie
openssl rand -hex 32
# Keys for lock-screen notifications
./mvnw -q compile && java -cp target/classes com.gymtracker.push.VapidKeyGenerator
```

## 1b. Set up email for password resets (Brevo)

1. Create a free account at https://www.brevo.com (300 emails a day).
2. Go to **Senders, domains & dedicated IPs**, then **Senders**, then **Add a sender**, and add the email address
   reset emails should come from. Brevo sends that address a confirmation email: click its link.
3. Go to **SMTP & API**, then **API keys**, then **Generate a new API key**. Copy the key (it is shown once).

Reset emails come "from" that address through Brevo. Some may land in spam, which the reset screen mentions.

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
INVITE_CODE=<the invite code from step 1>
BREVO_API_KEY=<the API key from step 1b>
MAIL_FROM=<the sender address you verified in step 1b>
APP_BASE_URL=https://<your-domain>
REMEMBER_ME_KEY=<the hex key from step 1>
VAPID_PUBLIC_KEY=<from step 1>
VAPID_PRIVATE_KEY=<from step 1>
VAPID_SUBJECT=mailto:<your email address>
```

Click **Update Variables**. Railway redeploys automatically.

`APP_BASE_URL` is the address from step 4's **Generate Domain**; the app doesn't start without it, so fill it in
once the domain exists.

## 4. Service settings

In the service's **Settings**:
- **Source:** turn on **Wait for CI**, so a deploy only happens after the GitHub tests pass.
- **Networking:** click **Generate Domain**. If Railway asks for a port, use `8080`.
- The health check (`/actuator/health`) is already set by `railway.json` in the repo.

## 5. Check it

- **Deploy logs:** **Deployments**, then the newest deploy, should end with `Started GymTrackerApplication`.
- **Health:** open `https://<your-domain>/actuator/health`. It should show `{"status":"UP"}`.

## 6. On the iPhone

1. Open `https://<your-domain>` in **Safari**, tap **Create account**, and use the invite code.
2. Tap **Share**, then **Add to Home Screen**, then **Add**.
3. Open **Gym** from the home screen (not from Safari).
4. On the **Workout** tab, tap **⚙️**, then under **Lock-screen alerts** tap **Enable**, then **Allow**.

Then do a quick test:
- **Rest alert:** start a session, log a set, lock the phone, and check the "Rest's over" alert arrives after about 90 s.
- **No signal:** turn on Airplane Mode, log a set ("1 change waiting to sync" appears), then turn Airplane Mode
  off and check the message disappears.

## Upgrading from the single-user version

The upgrade empties the database: the old workouts are not kept. After the deploy, open the app, tap
**Create account** and register with the invite code. Remove the old `APP_USERNAME` and `APP_PASSWORD` variables.

## Costs and limits

- **Price:** Railway's Hobby plan is about $5/month, which covers this app and its database.
- **Restarts:** a deploy or restart drops a rest alert that is counting down at that moment.
- **Staying signed in:** each phone stays signed in for a year, until it logs out in Settings or the password is reset.
