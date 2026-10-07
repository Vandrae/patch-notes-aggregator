# Deployment

The whole app runs from one `docker compose` file: [Caddy](https://caddyserver.com) (HTTPS), the app, and MySQL. This page explains the stack and then walks through putting it on one small AWS server. Any machine with Docker works the same way (a cloud VM, a rented server, a VM on your own PC): only the AWS-specific steps differ.

## The Docker stack

**With Docker** (the whole stack, no JDK or Node needed; you only need Docker):

```bash
cp .env.example .env          # fill in JWT_SECRET, MYSQL_PASSWORD, MYSQL_ROOT_PASSWORD (and STEAM_API_KEY)
docker compose -f compose.prod.yaml up -d --build
docker compose -f compose.prod.yaml logs -f app      # then open https://localhost (your browser will warn: Caddy signs it itself)
```

How it fits together:

- The **`Dockerfile` has two stages.** The first (a full JDK) runs the same `./mvnw package` as the [quick start](../README.md#quick-start), which also builds the web
  UI. The second (a JRE only) receives just the finished jar, so the image holds no source, compilers or Node. It runs as a
  non-root user and has a health check against `/actuator/health/readiness`. Dependencies are resolved before the source is
  copied, so editing code does not re-download them.
- **`.dockerignore`** keeps `.env`, `data/`, build output and `.git` out of the build, so a secret can never end up inside an image.
- **`compose.prod.yaml`** starts [Caddy](https://caddyserver.com), the app and MySQL 8.4. Caddy (configured by the `Caddyfile`) is
  the only one with published ports (80 and 443): it gets and renews a free Let's Encrypt certificate for `DOMAIN` by itself,
  redirects http to https and forwards requests to the app over the private network, telling it the visitor's address. MySQL
  keeps its data in a named volume (`down` keeps it, `down -v` wipes it); the app starts only once MySQL is healthy. Each
  container has a memory limit sized for a 2 GB machine (app 800 MB, MySQL 512 MB, Caddy 128 MB; MySQL is also trimmed to a
  128 MB cache and no performance schema). Each container's log is capped at 30 MB (three files of 10 MB, oldest dropped), because
  Docker otherwise keeps logs forever and the poller writes all day. Required secrets are written `${NAME:?message}`, so compose refuses to start when
  one is missing instead of running without it. Set `DOMAIN` and `PUBLIC_BASE_URL=https://<DOMAIN>` when you deploy (https
  also turns on the Secure cookie flag); without them the stack runs at `https://localhost`.
- `compose.yaml` (without `.prod`) is the development one: only a MySQL with its port open, for `--spring.profiles.active=mysql`.

**The `prod` profile** (`SPRING_PROFILES_ACTIVE=prod`; `compose.prod.yaml` uses `prod,mysql`). It adds `application-prod.yml` and two startup
checks, so a deployment that would be unsafe or quietly broken fails at startup with a clear message instead of coming up:

- **`JWT_SECRET` must be set** (32+ characters). Without it the app would invent a random signing key on every start, signing
  everyone out and breaking any second instance.
- **`PUBLIC_BASE_URL` must be an https address**, because the session cookie and the Steam sign-in redirect must not travel
  over plain http. `http://localhost` is the one exception, so you can try the production setup on your own machine
  (with a warning in the log). A missing `STEAM_API_KEY` only logs a warning: the app works but cannot import the catalog.
- The profile also turns on **graceful shutdown** (in-flight requests and a running poll tick get up to 30 seconds), **error
  responses without messages or stack traces**, **response compression**, trusting a reverse proxy's `X-Forwarded-*`
  headers (`FORWARD_HEADERS_STRATEGY`, default `native`: Tomcat believes them only on connections from private-network
  addresses such as Caddy on the Docker network, and reads the visitor's address from the right of `X-Forwarded-For`, where a
  visitor cannot write; set it to `none` when nothing is in front of the app, and never publish the app's own port), and
  exposes only `/actuator/health` over HTTP (metrics are recorded but not exposed).

## Deploy on one EC2 instance (about $20 a month)

This is meant for a portfolio project, not for wide use: one small machine runs everything with `compose.prod.yaml`. An AWS
Free plan account gets credits that cover a good part of this for the first months; check what yours has, and check current
prices, because the figures below are from the us-east-1 price list at the time of writing.

| Item | Roughly per month |
|---|---|
| EC2 `t3.small` (2 vCPU, 2 GB) | $15 |
| Public IPv4 address (AWS now charges for every one) | $3.65 |
| 20 GB gp3 disk | $1.60 |
| Domain name (outside AWS if you like) | about $1 (a year costs $10 to $15) |

`t4g.small` (ARM) is about 20% cheaper. Docker builds the image on the machine itself, so it would build for ARM by itself, but CI
only tests x86, so the guide uses `t3.small`.

1. **Launch the instance.** Ubuntu Server 24.04 LTS, `t3.small`, 20 GB gp3 storage, and a key pair for SSH.
2. **Security group** (the firewall): allow **22 from your own IP only**, **80 and 443 from anywhere**. Open nothing else:
   not 8080 (the app), not 3306 (MySQL).
3. **Give it a fixed address.** Allocate an Elastic IP and attach it to the instance, then create a DNS **A record** for your
   domain pointing at it. Caddy needs the record in place before it can get a certificate.
4. **Add swap, then install Docker.** Building the image needs more than 2 GB for a few minutes, so add a swap file first:

   ```bash
   sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
   echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
   curl -fsSL https://get.docker.com | sudo sh && sudo usermod -aG docker $USER   # log out and back in afterwards
   ```

5. **Get the code and configure it.**

   ```bash
   git clone https://github.com/Vandrae/patch-notes-aggregator.git && cd patch-notes-aggregator
   cp .env.example .env && nano .env
   ```

   Set `JWT_SECRET` (`openssl rand -base64 48`), `MYSQL_PASSWORD`, `MYSQL_ROOT_PASSWORD`, `STEAM_API_KEY`, `DOMAIN=your.domain`
   and `PUBLIC_BASE_URL=https://your.domain`.
6. **Start it.** The first build takes several minutes.

   ```bash
   docker compose -f compose.prod.yaml up -d --build
   docker compose -f compose.prod.yaml logs -f app
   ```

   Then open `https://your.domain` and sign in through Steam. To update later: `git pull`, then run the same `up -d --build`.
7. **Back up the database.** Everything that matters is in MySQL (a copy of Steam's catalog and your users' lists; the patch
   notes themselves can be fetched again). A nightly dump, kept off the machine, is enough:

   ```bash
   docker compose -f compose.prod.yaml exec -T mysql sh -c 'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction patchnotes' \
     | gzip > backup-$(date +%F).sql.gz            # then copy it away, for example: aws s3 cp backup-*.sql.gz s3://<your-bucket>/
   gunzip < backup-2026-01-01.sql.gz | docker compose -f compose.prod.yaml exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" patchnotes'   # restore
   ```

   Put the first line in a cron job, and consider an EBS snapshot schedule (Amazon Data Lifecycle Manager) as a second safety net.
8. **Set a budget alert first.** In the AWS console, Billing and Cost Management, then Budgets: create a monthly cost budget of
   about $10 with an email alert at 80%. A surprise bill is the real risk of a hobby deployment.
9. **To pause spending**, stop the instance: compute billing stops, but the disk and the IPv4 address keep billing (a few dollars).
   To stop everything, take a backup, terminate the instance, and release the Elastic IP.
