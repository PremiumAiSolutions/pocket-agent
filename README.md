# Pocket Agent

A native Android app (Kotlin, Jetpack Compose) that drives a coding agent from your phone.
You tell it what to change, the agent edits the repo on your PC, and you review and approve
from the app.

The agent is [OpenCode](https://github.com/anomalyco/opencode) running in server mode. The app
talks to its v2 HTTP API. Server-side revisions will live in a fork of OpenCode; this repo is
the phone client plus setup scripts.

## What v0 does

- Connect to the server with its address and password
- List sessions, start a new one in any project the server knows
- Chat with live streaming text and a row for each tool call
- Attach screenshots or photos to a message so the model can see what you mean
- Approve or reject permission requests (once, always, reject)
- Stop a running task
- Switch the model per session (Venice, Gemini, anything the server has configured)
- Settings: paste API keys for Venice, Gemini or any other provider, and pick the default model
- Project browser: every folder inside the server's start folder, plus your GitHub repos (cloned to the PC on first open)
- Commit and push from the Changes screen
- Create: generate images and videos with any Venice media model, keep them in the app, save them to the phone
- Publish on Lovable through the Lovable API, which does not spend AI credits (needs a Lovable API key)
- View the file changes a session made

## Run the server on your PC

```powershell
$env:VENICE_API_KEY = "your-venice-key"
.\server\start-server.ps1 -Project C:\repos\your-project
```

The script installs OpenCode if needed, asks for a server password, prints the address to
type into the app, and starts the server on port 4096. Windows will ask to allow it through
the firewall the first time; allow it on private networks.

Point `-Project` at the folder that holds your projects. Each subfolder shows up in the app.

GitHub features use the GitHub CLI on the PC. Install it from https://cli.github.com and run
`gh auth login` once. The app then lists your repos, clones them, and commits and pushes with
that sign-in. No GitHub token is stored on the phone.

### Away from home

The server speaks plain http, so do not forward the port on your router. Install
[Tailscale](https://tailscale.com) on the PC and the phone and use the PC's Tailscale address
in the app instead.

## Get the app

Every push to `main` builds an APK with GitHub Actions and publishes it under
**Releases > Latest build**. Open that page on your phone, download `pocket-agent.apk`, and
install it (Android will ask to allow installs from your browser once).

To build locally: open the folder in Android Studio, or run `./gradlew assembleDebug`.

## Notes

- The server password is stored in the app's private storage on the phone.
- The Venice key (for image and video) and the Lovable key are also stored in the app's private storage,
  because the phone calls those services directly.
- `app/debug.keystore` is a throwaway test key so new builds install over old ones. It is not
  a release key.

## Roadmap

1. Fork OpenCode and add server-side changes (GitHub repo cloning from the app, PR creation)
2. Lovable publish button using the Lovable API (no AI credits)
3. Push notifications when a task finishes or needs approval
4. Local model routing (PC model for cheap steps, Venice for hard ones)
