# Single-server demo deployment (Vultr)

Caddy (HTTPS) → nginx edge (SPA + reverse proxy) → API-only backend on one server; **PostgreSQL 17 and Valkey
are Vultr Managed Databases** reached over TLS. The host name is `<public-ip-with-dashes>.sslip.io`, which resolves
to the server's IP and gives every tenant subdomain (`default.<ip>.sslip.io`) for free — no DNS setup.

HTTPS is required, not cosmetic: the prod profile sets the session cookie `Secure`, and passkeys need a secure
context.

## 1. Build the images (on your machine)

```bash
./deploy/demo/build-images.sh              # linux/amd64; pass linux/arm64 for an ARM server
```

## 2. Prepare the server

Paste `vultr-startup.sh` into **Orchestration → Scripts → Add Startup Script**, pick it when deploying the
instance (Ubuntu 24.04), and attach your SSH key. It installs Docker, creates the `svalinn` user with your key,
opens only 22/80/443, adds swap and turns SSH password login off. Wait for `/var/lib/svalinn-setup.done`.

## 3. Create the managed databases

- **PostgreSQL 17** and **Valkey**, in the **same region** as the server (attach the same VPC if offered).
- Add the server's IP (or VPC) under **Trusted Sources** for both — otherwise connections time out.
- Keep each database's Overview page open: host, port, user and password go into `.env` in step 5.

## 4. Copy to the server

```bash
scp -r deploy/demo/* svalinn@<server>:~/svalinn-demo/
```

## 5. Configure and start (on the server)

```bash
cd ~/svalinn-demo
./gen-env.sh <public-ip> <you@example.com>   # once: secrets into .env (mode 600)
vi .env                                      # fill in DB_HOST/DB_PORT/DB_ADMIN_PASSWORD and VALKEY_*
./setup-db.sh                                # once: database, non-superuser role sso_app, extensions
./check-valkey.sh                            # keyspace notifications (logout propagation depends on them)
./deploy.sh
```

- Platform: `https://<ip-with-dashes>.sslip.io` — admin `admin`, password in `.env` (`SSO_ADMIN_PASSWORD`)
- Tenant: `https://default.<ip-with-dashes>.sslip.io`
- MailHog (email codes): `ssh -L 8025:localhost:8025 svalinn@<server>` then `http://localhost:8025`

## If keyspace notifications are off

`check-valkey.sh` subscribes to keyspace events and deletes a probe key. If no event arrives, sign-in still works
but a logout or forced session termination never reaches connected apps. Run Valkey on the server instead —
it starts with `notify-keyspace-events Egx` — by changing these lines in `.env`, then `./deploy.sh`:

```bash
VALKEY_HOST=valkey
VALKEY_PORT=6379
VALKEY_USER=default
VALKEY_TLS=false          # private compose network; the container is not published
```

Keep the generated `VALKEY_PASSWORD` or set any strong value; the container uses it as `requirepass`.

## Update / stop

```bash
./deploy/demo/build-images.sh && scp deploy/demo/svalinn-images.tar.gz svalinn@<server>:~/svalinn-demo/
./deploy.sh                                               # on the server
docker compose -f docker-compose.demo.yml down            # stop (data lives in the managed databases)
```

## If the certificate is not issued

Let's Encrypt limits certificates per registered domain. If `docker compose logs caddy` shows a rate-limit
error for sslip.io, use a free DuckDNS name (`<name>.duckdns.org`, which also resolves every subdomain) and set
`DEMO_HOST` in `.env` to it.
