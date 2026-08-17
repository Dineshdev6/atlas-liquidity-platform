# Layer 0 — Foundations

No application code in this layer. The goal is that `mvn -v`, `docker ps`, and
`git push` all work, and that VS Code is set up like a Java engineer's IDE
rather than a text editor.

Time: ~45 minutes. Do it once, properly.

---

## 0.1 Verify the toolchain

Open a terminal and run each of these. Write down what you get.

```bash
java -version      # need 17 or 21. 21 preferred.
mvn -v             # need 3.9.x
docker --version   # need Docker Desktop running
docker compose version
git --version
node -v            # needed from Layer 9 onward
```

**If `node -v` fails** — that is expected, you did not have it installed. Install
Node 20 LTS or newer from https://nodejs.org before Layer 9. It is not needed
for Layers 1–8, so do not let it block you now.

**If `java -version` shows 8 or 11** — install JDK 21 (Eclipse Temurin is the
easy choice: https://adoptium.net). Spring Boot 3.x requires 17 minimum.

**Sanity check Docker actually runs containers**, not just that the CLI exists:

```bash
docker run --rm hello-world
```

If that hangs or errors, Docker Desktop is not started. Everything from Layer 2
onward depends on it.

---

## 0.2 VS Code setup

Install these extensions (Ctrl/Cmd+Shift+X, search by ID):

| Extension ID | Why |
|---|---|
| `vscjava.vscode-java-pack` | Extension Pack for Java — language server, debugger, Maven, test runner. The one that matters. |
| `vmware.vscode-boot-dev-pack` | Spring Boot tooling: bean navigation, `application.yml` autocomplete, dashboard. |
| `ms-azuretools.vscode-docker` | Dockerfile/Compose editing and container inspection. |
| `redhat.vscode-yaml` | K8s and CI YAML with schema validation. Saves you from Layer 10 pain. |
| `eamodio.gitlens` | Blame, history, and it will teach you git by osmosis. |
| `humao.rest-client` | Fire HTTP requests from a `.http` file, checked into the repo. Better than Postman for this project. |
| `sonarsource.sonarlint-vscode` | Catches the code-quality issues Citi's pipeline would catch. |

Then, one important setting. Open the command palette → *Preferences: Open User
Settings (JSON)* and add:

```json
"java.configuration.updateBuildConfiguration": "automatic",
"java.compile.nullAnalysis.mode": "automatic",
"editor.formatOnSave": true
```

The first one is the fix for "I added a dependency to pom.xml and VS Code still
says it cannot find the class". Remember it — you will hit that at least once.

---

## 0.3 Git identity

```bash
git config --global user.name "Dinesh"
git config --global user.email "chettedinesh3@gmail.com"
git config --global init.defaultBranch main
git config --global pull.rebase true
```

`pull.rebase true` keeps history linear. Enterprise teams almost always want
this, and "we use rebase workflow, squash on merge" is a good thing to be able
to say.

---

## 0.4 Create the GitHub repository

**On github.com:**

1. New repository → name it `atlas-liquidity-platform`
2. Description: *Cash & intraday liquidity management platform — event-driven, multi-region, cloud-native.*
3. Public (you want a hiring manager to be able to open it)
4. Do **not** initialise with a README, .gitignore, or licence — the scaffold I
   send you already has them, and an initialised repo will just cause a merge
   conflict on your first push.

**Authentication.** If you have never pushed from this machine, set up one of:

- **GitHub CLI** (easiest): install from https://cli.github.com, then `gh auth login`
- **SSH key**:
  ```bash
  ssh-keygen -t ed25519 -C "chettedinesh3@gmail.com"
  cat ~/.ssh/id_ed25519.pub
  ```
  Paste that public key into GitHub → Settings → SSH and GPG keys.

Do not use your account password — GitHub stopped accepting it for git
operations years ago. If you get "Support for password authentication was
removed", this is why.

---

## 0.5 Wire up the local repo

Unzip the scaffold I sent you somewhere sensible (e.g. `~/dev/atlas-liquidity-platform`),
open that folder in VS Code, then in the VS Code terminal:

```bash
git init
git add .
git commit -m "chore: scaffold multi-module maven reactor and reference-data-service"
git branch -M main
git remote add origin git@github.com:<your-username>/atlas-liquidity-platform.git
git push -u origin main
```

Use the `https://github.com/...` URL instead of `git@github.com:...` if you set
up `gh auth login` rather than an SSH key.

---

## 0.6 Turn on branch protection

GitHub → your repo → Settings → Branches → Add branch ruleset:

- Target branch: `main`
- Require a pull request before merging
- Require status checks to pass (we will add the check itself in Layer 12)

From Layer 2 onward you will work on feature branches and merge via PR, even
though you are the only contributor. This is deliberate: it is exactly how you
will work at Citi, and the PR history becomes a portfolio of your reasoning.

**Branch naming we will use:**

```
feat/L02-domain-model
feat/L04-kafka-backbone
fix/L05-projection-replay-ordering
```

**Commit style — Conventional Commits:**

```
feat(position): add intraday liquidity ladder projection
fix(kafka): make consumer idempotent on redelivery
test(refdata): cover currency validation edge cases
docs(adr): record decision to use transactional outbox
```

---

## 0.7 Definition of done for Layer 0

- [ ] `java -version` → 17 or 21
- [ ] `mvn -v` → 3.9.x
- [ ] `docker run --rm hello-world` succeeds
- [ ] VS Code has the Java + Spring Boot extension packs installed
- [ ] `git config user.email` returns your email
- [ ] Empty GitHub repo `atlas-liquidity-platform` exists
- [ ] You can `git push` without being prompted for a password

Report back with anything from this list that failed, and paste the exact
error. Then we start Layer 1.
