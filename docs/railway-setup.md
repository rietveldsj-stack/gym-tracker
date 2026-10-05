# Railway setup

About 10 minutes. You need: the Railway account, access to the `rietveldsj-stack/gym-tracker` GitHub repo,
and a terminal in this project folder.

## 1. Make the secrets (on your Mac)

```bash
# Her password: long and random, because the site and the code are public. Save it in your password manager.
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
APP_USERNAME=<her username>
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

## 6. On her iPhone

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
- **Staying signed in:** she stays signed in for a year, through restarts.
