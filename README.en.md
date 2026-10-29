# dsh-companion

<p align="center">
  <strong>Your own DeepSeek Harness, on Android.</strong>
</p>

<p align="center">
  <a href="./README.md">中文</a> · English
</p>

<p align="center">
  <img alt="Upstream" src="https://img.shields.io/badge/upstream-Hakunm%2Fdsh--android--app-555">
  <img alt="Android" src="https://img.shields.io/badge/Android-8.0%2B-3ddc84">
  <img alt="Jetpack Compose" src="https://img.shields.io/badge/UI-Jetpack_Compose_%2B_Material_3-6750a4">
  <img alt="License" src="https://img.shields.io/badge/license-AGPL--3.0-2da44e">
</p>

## About this repository

This is a **fork** of [Hakunm/dsh-android-app](https://github.com/Hakunm/dsh-android-app), continuing
development on top of upstream `v1.0.0`. The full upstream commit history is preserved, so provenance
is traceable commit by commit.

- **Based on**: upstream [Hakunm/dsh-android-app](https://github.com/Hakunm/dsh-android-app)
  (native Android client, Jetpack Compose + Material 3).
- **Why it exists**: upstream's connection model only supports "one computer = one address", which
  falls short in real mobile use. See the next section.
- **License**: upstream is released under **AGPL-3.0**; this fork keeps the same license
  (see [LICENSE](./LICENSE)). Upstream copyright notices, [NOTICE](./NOTICE) and
  [THIRD_PARTY_LICENSES](./THIRD_PARTY_LICENSES) are preserved unchanged.
- **What changed, why, and what was tested**: see [the development log](./docs/project/CHANGELOG-DEV.md).

## The problem this fork solves

Four problems surfaced in daily use. They look unrelated, but they share **one root cause**:
the old model welded "one computer" and "one address" into the same thing. Its data model was only:

```kotlin
data class StoredConnection(val endpoint: String, val token: String)
class DshClient(endpoint: String, private val token: String? = null)
```

`endpoint` is a **single string**. Consequently:

| What the user hit | The actual root cause |
|---|---|
| Only one path could be added | The model has exactly one `endpoint` slot |
| Cannot pair when already away from home | A pairing code must be generated on the computer — but you are no longer next to it |
| LAN / virtual net / WAN cannot coexist | Same: only one address slot |
| Files mentioned in a session cannot be opened | Absolute paths in session events are never matched against authorized roots |

### On the "away from home" scenario

This one deserves its own note, because it overturns an assumption in upstream. Upstream connects via a
**one-time pairing code** generated in the WebUI on the computer and then typed into the phone. That
flow assumes **you and the computer are together**.

The real scenario is the opposite: **you are already out, the computer is at home, and you cannot see
its screen**. "Go generate a pairing code on the computer" is then not inconvenient — it is impossible.
QR scanning has the same flaw: you cannot scan a screen you cannot see.

So this fork makes **configuration import** the primary path:

```
Generate one line of text on the computer → send it to yourself (WeChat / email / private note)
→ paste it into the app once you are out
```

No computer nearby, no camera. The pairing code is kept as a **secondary** entry (it is genuinely
faster when you *are* sitting at the computer), and the UI states that precondition explicitly.

## What this fork changes

### 1. One computer, many addresses

The connection layer is remodeled (`data/ConnectionModels.kt`):

```
Host          one computer: name, credential, N endpoints, last working endpoint
 ├─ Endpoint     one reachable address: label, baseUrl, kind, probe history
 └─ Credential   attached to the computer, not to an address
```

**This requires no server-side change at all.** DSH's `devices` table is
`id, name, token_hash, scopes_json, created_at, last_seen_at, revoked_at` — it **has no address
column**. A device token is inherently host-level and valid on any address. Multi-address was always
something the client could do; it simply had not been done.

On launch the app probes **all** endpoints of that computer concurrently (each with its own 1.5s
timeout), ranks them by latency with "LAN wins if close enough", and falls back automatically.
Also, **the app always lands on the connect page first**: a successful automatic reconnect does not take
you straight into the app; a user action (Enter app / import a configuration string / pair) lets you through.

### 2. Configuration import instead of QR scanning

`ConnectionShare.kt` defines the wire format `DSH1:<Base64URL(JSON)>`. Decoding tolerates real paste
conditions: newlines and zero-width characters inserted by WeChat, surrounding label text
(`Pixel 9 config: DSH1:xxx`), missing `=` padding, both standard and URL-safe Base64 alphabets, and
punctuation glued to the end. The generator is `tools/emit-config.mjs`.

### 3. Address diagnostics

When no address works, the app no longer just says "connection failed" — it lists each endpoint's result:

```
LAN        192.168.1.126:3090   Unreachable — timeout after 1500ms
Virtual    100.64.250.1:3090    Reachable · 38 ms
```

Previously the user had to guess which address to type. Now they can see.

### 4. Disconnect no longer erases the credential

Upstream tied "disconnect" and "delete token" to one button, so a network hiccup or a mistap forced a
fresh pairing — which in turn requires you to be next to the computer. Now `disconnect` only
disconnects; deleting a computer is a separate action (`removeHost`).

### 5. Automatic migration from the old format

Saved addresses and the device token are migrated into the new `Host` structure on first launch, with no
re-pairing. Migration is idempotent, and old tokens are decrypted with the **legacy Keystore alias**
before being carried over.

### 6. Plugin inventory page

A new "Plugins" tab shows what is installed on the machine, **which version is actually running**, and
whether anything is in a bad state.

This one **does require a server-side change**: `dsh-remote-bridge` (then named `dsh-workspace`)'s `/api/v1` had 10 endpoints
(`healthz` / `pairings/exchange` / `devices/self` / `roots` / `trash` / `chat/sessions` /
`chat/workspaces` / `chat/agent-presets` / `settings/models` / `settings/providers`), **none related to
plugins**, so `GET /api/v1/settings/plugins` was added to the plugin first.

It distinguishes four states, two of which can only be derived by comparing two sources
(`dependencies` = what is installed, `dsh.profile.bundles` = what is actually loaded):

| State | Meaning |
|---|---|
| `loaded` | Declared and present on disk |
| `runtime-provided` | An official package shipped with the DSH runtime (not in the profile's `node_modules`) — **normal** |
| `installed-not-loaded` | Installed but not enabled |
| `declared-missing` | A non-official package declared for loading but missing — usually means startup trouble |

The gap between "installed" and "running" is exactly the point — on the reference machine it revealed
that `dsh-hyperframes` and `dsh-remotion` are installed but not enabled.

> **DSH 0.2.x compatibility**: this fork also carries the upstream fix for an enum-contract mismatch.
> DSH 0.2.x no longer returns `trust` on `AgentPreset`, while the client's strict decoder requires it,
> so the Agent preset picker failed entirely with
> `Field 'trust' is required ... missing at path: $.items[0]`. The fix adds defaults to optional
> fields so a single missing field can no longer break the whole list.
>
> **Three-way version table (added 2026-10-20)**: this app reaches the DSH kernel **only through**
> `dsh-remote-bridge` (≥ 2.4.1) and never connects to the kernel directly. The verified combination is
> "App v2.2.46 ↔ bridge 2.4.3 ↔ DSH 0.2.0-rc.2 / 0.2.1-rc.2" — all 10 host-service `inject` entries of
> the bridge still exist with unchanged signatures on 0.2.1-rc.2 (see
> `dsh-remote-bridge/docs/project/DSH-0.2.1-ADAPTATION.md`). The kernel's new
> `session/working-directory/{get,set}` belongs to the **SDK profile**, which the bridge does not pass
> through yet, so this app remains **read-only** for a session's working directory (it can display it
> and use it as the `@` completion base, but cannot switch it inside a session).

### 7. Configurable send behaviour: queue vs. steer (v1.3.0)

What happens when you press send during a run used to be hard-coded. The settings page now offers radio
options, and while a run is active the send key shows the current default (`Enter=queue` / `Enter=steer`),
so you do not have to go back to settings to check.

The same release fixed a worse problem: **the agent could not send messages at all while running**. The
old code disabled the send key with a global `busy` flag that **any background refresh** would set — so
"cannot send while running" became "cannot send, ever". Files mentioned in a session also became openable
in this release.

### 8. Session images visible + messages copyable + links tappable + send image/file (v1.4.0)

"Images in a session are invisible" was **two problems stacked**; fixing either alone changed nothing:

1. an image-only message (no text) was judged an **empty message** and dropped entirely;
2. historical images are only an `attachmentId` **reference** in the event stream; the bytes need a
   separate request.

Messages are now copyable, links tappable, and the composer can send images and files (images inline as
base64, files exchanged for a receipt first). Attachment size is rejected **before sending**
(8 MiB images / 24 MiB files) with advice you can actually follow ("compress it and try again") rather
than a bare "file too large".

### 9. Native rendering of dsh-ui interactive cards (v1.5.0)

In the web UI the JSON inside a `dsh-ui` fence renders as real components. The app drew the fence as a
code block, so users saw a lump of **raw JSON** — the most concrete consequence being that **a
multiple-choice question the assistant raised on the web was impossible to answer on the phone**.

22 component types (card / table / list / button / option / progress / timeline …) now render natively.
A choice button in a card is equivalent to typing that line and sending it, through the same message path.

### 10. Session files listed at the end of the message stream (v1.6.0)

Matching the web UI: after a turn finishes, files produced by the session are listed automatically at the
end of the stream (count shown, collapsible, tap to view). The toolbar folder icon **stays**; the two do
not conflict.

Paths inside `bash` commands are deliberately **not** parsed — most of them are search scopes, variables,
or text that was merely `echo`ed, and **a false listing is worse than a missing one** (tapping a path that
will not open makes users think the feature is broken).

### 11. dsh-ui diagnostics switch (v1.7.0)

A new switch in the settings **Debug** group (off by default). When on, each assistant message shows a
grey line explaining why the card did not render: one result per stage —
**fence detection → JSON parsing → node dispatch** — tap to expand, long-press to copy.

"Fence not recognised", "JSON broken" and "unknown node type" all looked identical on screen, and in an
environment with no usable device that left guessing as the only option.

### 12. Failed sends no longer swallow text + attachment failures state the cause (v1.8.0)

- **Failed sends no longer swallow your text**: the body is put back into the input box. Previously only
  attachments came back and the text was gone — you typed a long message, the send failed, and the box
  was empty with nothing recoverable.
- **Attachment failures show the real cause**: 401 expired authorization / 403 missing `files.read` /
  404 the attachment is really gone are distinguished, instead of always saying "image unavailable (may
  have been cleaned up)". Authorization failures are retryable — previously one failure was remembered
  forever, so re-pairing could not heal it.

### 13. Long-press selects text + preview by file type (v1.9.0)

**Gesture split**: long-press is reserved for **text selection**, and copying a whole message moved to
**double-tap**. Long-press used to be consumed by "copy whole message", so the selection handles never
appeared and users could only copy the entire block. A one-time dismissible hint at the top of the message
stream explains the change.

**Preview by type**: `.md` renders as Markdown, `.html` goes through structural extraction
(headings / paragraphs / lists / quotes / code / links, with `<script>` and `<style>` **discarded
entirely**). WebView is deliberately **not** used — that would hand execution rights to any file that
happens to appear in a session.

### 14. The 2.0–2.2 line: the WebView chat page, and aligning the native one

Since 2.0.0 the session detail page has a second implementation — a **WebView** one (`assets/chat.html`,
letting the browser do Markdown and card rendering) — coexisting with the native Compose page and toggled
in Settings under "Chat page implementation" (**native stays the default**; it is the real-device-verified asset).
From 2.2.15 on, that web presentation was ported back into native. The main changes in this line up to 2.2.20:

- **The connect page is the entrance, not a fallback**: the app always lands there first (a process-level
  latch, `connectGate`, opened only by an explicit user action); even a successful cold-start
  reconnect stays on that page. **Import configuration** and **manual pairing** are both **collapsed** by
  default and open one at a time — you never get two forms at once.
- **Composer rebuilt to match the web page**: a single-row pill (`+` · input · send), with model / agent /
  permission mode / steer moved into a `⋯` menu in the top bar and attachments folded into `+`;
  **the input grows with its content** (44–120dp; it used to be a fixed 34dp, so the second line was clipped).
- **The thinking bar sits on one line above the composer**: during streaming it shows only the current
  reasoning, tail-truncated with an ellipsis; with no reasoning the bar is not rendered at all (zero height).
  Reasoning is no longer shown in history.
- **Tool calls read as action phrases** ("Ran code, Modified file") and **consecutive calls collapse into
  one row**; previously one call per row, so a busy turn filled the whole screen.
- **Context injections are hidden by default**: the run context / time context / background-task notices the
  system injects each turn are not displayed unless you turn them on in Settings.
- **Model providers became their own "Models" page** instead of a block inside Settings, which shrank from
  697 to 297 lines.
- **The settings switches share one shape and take effect immediately**: the three switches (Use the web
  chat page / Show context injections / dsh-ui card diagnostics) now use one `SwitchRow` and live in
  `HarnessState`. They used to read `SharedPreferences` directly — not Compose state — so a tap
  appeared to do nothing until you tapped something else.
- **Session deliverables come straight from a kernel event**: the footer and the toolbar folder sheet both
  read `deliverables/presented` (fired by the `present` tool, carrying an absolute path and a
  human-written description). Only three event types used to be recognised, so anything an agent delivered
  was invisible; both places now share one `sessionFileList()` and cannot drift apart.

Per-release rationale and the bugs behind it live in the version comments in `app/build.gradle.kts` and in
`docs/project/CHANGELOG-DEV.md`; the "not yet ported" list is in `docs/project/webview-port-todo.md`.

## Building

```bash
scripts/build.sh                            # defaults to assembleDebug
scripts/build.sh assembleRelease            # release package
scripts/build.sh :app:testDebugUnitTest     # unit tests
```

`scripts/build.sh` is not redundant wrapping. It exists for three concrete reasons:

1. **It bypasses a silent hang in the Gradle wrapper.** `distributionUrl` points at
   `services.gradle.org`, which is unreachable on some networks, and the wrapper **does not fail — it
   hangs** (measured: 0.3% CPU, no network connections, no daemon log, no output for over ten minutes).
   The script invokes the locally cached Gradle binary directly.
2. **Dependency mirroring.** `maven.google.com` may be unreachable; `scripts/init-mirrors.gradle`
   redirects to a mirror. Note it is the **project-local** copy: the toolchain one adds project-level
   repositories and collides with this project's `RepositoriesMode.FAIL_ON_PROJECT_REPOS`, failing the build.
3. **Serialization.** Gradle is mutually exclusive per project directory; the script uses `flock` so
   concurrent invocations do not fight over the lock.

## Download and install

Download from Releases. **Gitee is the primary host (shipped there first)**; AtomGit is the
in-China code mirror, and GitHub is an auto-synced mirror (pulled from Gitee every 3 hours,
so its Release assets may lag slightly):

- Gitee (recommended, fastest in mainland China): <https://gitee.com/westanke/dsh-companion/releases/latest>
- GitHub (mirror): <https://github.com/westanke/dsh-companion/releases/latest>
- AtomGit (code mirror only, no Release assets): <https://atomgit.com/westanke163/dsh-companion>

```text
DSH伴侣-companion-v2.2.57.apk
```

Requirements:

- Android 8.0 (API 26) or newer
- A running DeepSeek Harness WebUI
- `dsh-remote-bridge` **≥ 2.3.7** installed in the WebUI (formerly `dsh-workspace`; 2.0.0 / 2.0.1 are
  seriously broken: image reads fail entirely and the plugin cannot activate; **approval and question
  cards need 2.3.7+** — older versions show none of them on the phone. **Automatic gap resync, the
  subscription baseline, live deliverable cards and single-meaning `seq` need 2.3.8+** (missing them
  breaks nothing — you just do not get those features)
- At least one authorized root added in the plugin, with remote access enabled
- The phone can reach the IP, domain, and port configured in the plugin

**Signing status (important): since `v2.2.25` the APK is signed with a regenerated release certificate
(`CN=DSH Pocket Client`, same subject as the original).** The original certificate's **password has been
lost** (checked on this machine, in the repo, in CI and in session memory), so the current key is newly
generated: the subject was kept identical on purpose, but it is **not the old key**. A few consequences:

- **`v1.9.0` and earlier cannot be upgraded in place**: different signature, so uninstall first
  (which clears saved connections) and then pair again or paste a configuration string.
- **`v2.2.25` and later upgrade in place** (same certificate).
- **⚠️ The package name changes in `v2.2.41`** (`io.github.hakunm.deepseekharness` →
  `io.github.westanke.dshcompanion`): the app name changes to **DSH Companion** at the same time,
  to avoid the "DeepSeek" trademark. **A new `applicationId` means a different app** —
  `v2.2.40` and earlier **cannot be upgraded in place; uninstall first** (which also clears saved
  connections, so pair again). See [changelog v2.2.41](./docs/project/CHANGELOG-DEV.md) for why.
- The certificate also differs from upstream's original APK (`CN=Hakunm`), so switching from an upstream
  build requires an uninstall too.

Release history: `v1.1.0` / `v1.2.0` / `v1.4.0` / `v1.9.0` are tagged; `v1.5.0`–`v1.8.0` are not tagged
separately — their features shipped together in `v1.9.0`. The `v2.0.0`–`v2.2.19` line has no tags; from `v2.2.20`
onward tags are kept across Gitee / AtomGit / GitHub (GitHub is an auto-synced mirror); artifacts are on the Releases page.

## Screenshots

<!-- The navigation-drawer screenshot was removed: since v1.2.0 the drawer also lists
     "Plugins" and shows the current endpoint underneath, so the old image no longer matched.
     No Android device is available here to re-take it, so it is omitted rather than left wrong. -->
<p align="center">
  <img src="./assets/screenshots/chat-demo.jpg" width="47%" alt="Chat and live responses">
  <img src="./assets/screenshots/file-browser.jpg" width="47%" alt="Authorized-root file browser">
</p>
<p align="center">
  <img src="./assets/screenshots/file-editor.jpg" width="47%" alt="Text editor with line numbers and zoom">
  <img src="./assets/screenshots/workspace-create.jpg" width="47%" alt="Create a workspace and start a session">
</p>
<p align="center">
  <img src="./assets/screenshots/model-providers.jpg" width="47%" alt="Model provider settings">
</p>

## Connect to DSH

### On the server

1. Open **Workspace settings** for `dsh-remote-bridge` (formerly `dsh-workspace`) in DSH WebUI.
2. Add at least one authorized root.
3. Enter the bind IP and port under **Remote access**, then save the listener settings.
4. Select **Enable and create pairing** to receive a ten-minute one-time code.

### On the phone

The app opens on the **connect page**, which lists saved computers plus two entries that are **collapsed**
by default (tapping one opens it and closes the other, so both forms are never on screen at once):

1. **Import configuration from the computer** (primary path): paste the configuration string — this is the
   only route that works when you are already out and cannot see the computer's screen.
2. **Manual pairing**: enter the server address (for example `http://192.168.1.20:3090` or
   `https://dsh.example.com`), the pairing code and a device name, then select **Pair and connect**.
   Faster when you are sitting at the computer.

> **Never include a `/dsh-workspace-api` prefix in the address.** Port `3090` is the plugin's remote
> listener and its routes sit at the root; `/dsh-workspace-api` is DSH Web's mount path on `3080`, which
> binds loopback only and is unreachable from the phone. The plugin has been renamed `dsh-remote-bridge`,
> but the mount prefix keeps its historical name (hard-coded), so it is not a typo — and
> `/dsh-remote-bridge-api` does not exist either.

The device token is stored using Android Keystore-backed encrypted storage. Disconnecting only drops the
connection; the token stays until you delete the computer, and the app restores the connection after a
restart. Revoking the device in DSH WebUI invalidates it immediately.

> **Tokens do not expire on their own.** DSH's `devices` table has no expiry column, so once a
> configuration string is issued it stays valid until the device is revoked. Send it only to yourself,
> and if you suspect a leak, revoke that device on the computer
> (`curl -X DELETE http://127.0.0.1:3090/manage/devices/<id>`) instead of hoping it is unreadable.

## Chat and task control

- View authorized DSH sessions and live run state.
- Stream assistant text; reasoning shows on the single line above the composer, and everything reconciles
  with server history on completion.
- Render Markdown headings, lists, quotes, code blocks, and tables.
- Tool calls read as action phrases ("Ran code, Modified file"); a consecutive run collapses into one row
  that you can expand to see each call.
- Render the assistant's `dsh-ui` interactive cards natively (card / table / list / button / option /
  progress / timeline …); tapping a choice in a card is equivalent to sending it.
- Send messages, steer a running task, or cancel it.
- Send images and files (the `+` menu in the composer); whether a send during a run queues or steers is
  chosen in the `⋯` menu or Settings.
- Follow DSH TODO progress and the currently active item.
- Type `/` to browse and run host-provided slash commands; a `/skill-name` that is **not** a command is
  sent to the model verbatim, as the official UI does, and the host injects the skill body. Earlier
  versions treated any leading `/` as a command, so skills could not be invoked at all.
- **Answer the agent's questions**: when it asks mid-run the card appears right there — question,
  supporting detail, and each option's own description rendered separately; `multiSelect` decides
  single vs multiple choice, and a question with no options gets a free-text field. The card turns into
  "answered" once submitted. The desktop UI can answer the same question at the same time; **whichever
  answers first wins**. Requires `dsh-remote-bridge >= 2.2.2` on the computer.
- Switch between Read only, Workspace write, and Full access in the `⋯` menu. Full access asks for
  confirmation again.
- Review redacted approval details and choose Allow once or Deny.
- **Expand a tool call to see the details**: full input, output, success or failure, and duration. There
  is no terminal on a phone, so this is the only way to diagnose a run after the fact.
- **Preview images and PDFs** (both in the file browser and in session attachments): pinch to zoom, drag
  to pan, and page through a PDF.
- Select a model and reasoning effort in the `⋯` menu. Empty sessions can also select an Agent before
  the first message.
- The input grows with its content (44–120dp, roughly one to six lines).
- Double-tap to copy a whole message, long-press to select a fragment, and tap links.
- Files an agent delivered (via the `present` tool) are listed at the end of the message stream; tap one
  to read it. The toolbar folder button lists the same set.

## Workspaces and sessions

New sessions may use a workspace already registered in DSH WebUI. You may also pick an existing directory from an authorized root and register it as a new DSH workspace.

Workspace management includes listing, renaming, and removing registrations. Removing a registration does not delete its directory, files, or session logs.

Each session menu provides:

- **Rename** to change its DSH WebUI title.
- **Fork** to create a child session with the existing history, working directory, and model configuration.
- **Archive** to hide it from the default list while preserving its server log.

The app only shows workspaces and sessions whose working directory is inside a root granted to the device. Server absolute paths are not exposed through the public API.

## File management

- Lazy browsing of authorized roots and directories.
- Create folders/files, upload, download, and replace.
- Rename, move, and send entries to plugin trash.
- View and restore trash items without overwriting path conflicts.
- Edit UTF-8 text with synchronized line numbers.
- Switch between Markdown source and rendered preview.
- Zoom source and preview from `75%-250%` using controls or pinch gestures.
- Detect external changes with ETags and refuse silent overwrites.
- Treat binary files as metadata/download/replace only.

The app cannot add server roots or permanently purge plugin trash. Those high-privilege operations remain available only in the server's local DSH WebUI.

## Model providers

With `settings.read`, the app can view effective provider configuration, models, and whether credentials are present. API key values are never returned by the server.

With `settings.write`, it can also:

- edit provider names, Base URLs, protocols, models, and reasoning settings;
- write or clear provider credentials;
- discover models from compatible services;
- create fully custom DSH providers and model routes.

## Permissions

The server controls which features are available:

| Scope | App access |
| --- | --- |
| `chat.read` | Workspaces, sessions, messages, TODOs, commands, and approvals |
| `chat.write` | Manage workspaces/sessions, send, configure, run commands, and decide approvals |
| `files.read` | Browse, preview, download, and view trash |
| `files.write` | Create, edit, upload, move, and rename |
| `files.delete` | Move files or directories to plugin trash |
| `settings.read` | Provider and model configuration |
| `settings.write` | Provider changes, custom providers, and credential writes |

Every device also needs explicit root grants. A server administrator can change scopes, update root grants, or revoke the device at any time.

## HTTP warning

Both `http://` and `https://` are supported. Plain HTTP sends device tokens, chat, and file content in clear text. It is suitable only for a trusted LAN, VPN, or temporary test.

Use HTTPS through Caddy/Nginx, Tailscale/WireGuard, another VPN, or a trusted tunnel on public or untrusted networks. Do not share pairing codes, device tokens, or APK signing material.

## Privacy

- No advertising or third-party analytics SDKs.
- Device tokens use Android Keystore-backed encrypted storage.
- Provider secrets are written to the DSH credential store and never read back into the app.
- Chat and file data is sent only to the DSH address configured by the user.
- **Generated-content notice**: per Art. 4 of China's *Measures for Labelling AI-Generated Synthetic
  Content*, a persistent notice ("内容由 AI 生成，请甄别后使用") sits above the chat composer.
  You configure the model yourself and generation happens on your own DSH server; this app does not
  provide an AI generation service and sends nothing to any third party.

## Troubleshooting

**No workspace is available when creating a session**

Switch to **New workspace** and select a directory from an authorized root, or register a workspace in DSH WebUI first.

**Some actions are disabled**

The device lacks the required scope. Update it from the plugin's **Devices** page.

**The app cannot connect**

Confirm remote access is enabled, the firewall/security group allows the port, and the address is reachable from the phone. Do not enter the server's own `127.0.0.1` address.

**A session appears stuck**

Check the conversation for an approval panel. Operations that require permission are not approved in the background.

**A save reports a conflict**

Another process changed the server file. Reload, merge the desired content, and save again.

## Known limitations

**A systematic on-device UI pass has not been done yet.** Unit tests and the build artifacts are green, but
these have not been verified item by item on real hardware:

- the boundary between long-press selection and double-tap copy — the conflict with scrolling
  (a double-tap misfire while scrolling) needs a real device to judge;
- layout quality and button hit areas for the `dsh-ui` cards;
- layout quality of the structured HTML preview (styling is discarded; only structure is kept);
- where the composer's `⋯` menu lands on narrow screens and across input methods.

To be clear: the many fixes after 2.0 **were driven by real-device reports and screenshots** (see the
per-release comments in `app/build.gradle.kts`), but that is "fix what someone hit", not a systematic
on-device pass. The interface layer (pairing, connection, sessions, files, plugin inventory, session
deliverables) was exercised end to end against a local DSH WebUI during development.

### 15. Hardening pass: session-page comfort, file cards and reliability (since v2.2.42)

> This batch shipped in v2.2.42. What follows is what a user
> can actually notice.

- **Messages read better**: code blocks gain language labels, a copy button and highlighting;
  thinking / tool / context rows collapse into one group with a **duration summary**; each message
  carries a timestamp (clock time today, date for older ones). Long-press text selection is untouched —
  the new entry points deliberately avoid the long-press gesture.
- **Images open and save to your gallery**; files in the session file panel can be downloaded.
- **Failures can be retried**: a "Retry" button appears next to the error row (not shown for failures
  where retrying is pointless, such as a signed-out account).
- **Fork from a specific message**: each user message offers "Fork here", which creates and switches to
  a new session behind a confirmation dialog.
- **Archived sessions are visible**: the panel can switch to "Archived". **Restoring is not supported** —
  do it from the desktop WebUI: whether the kernel exposes an un-archive call cannot be confirmed from
  the client side, so we do not ship a button that might not work.
- **File cards are live**: a file the agent explicitly presents shows up in the session file panel
  **immediately**, instead of waiting for the next message.
- **Session stats and trajectory** (two new entries in the top-bar `⋯` menu): "Session stats" reports
  turns / assistant messages / tool calls / total events / up-to; "Trajectory" lays the history out as a
  per-turn timeline where **tapping a node reveals the raw event JSON** — for answering "why was this
  step slow, what did it actually call".
  **Token usage and context pressure are not shown**: the kernel exposes no usage data, and we will
  **not pass off a character-count estimate as one** (the error can be several-fold and would mislead
  your sense of the remaining budget).
- **Appearance is adjustable**: the settings page offers an interface style and a separate font scale
  (which can also follow the system).
- **Two changes you feel but do not see**: switching sessions no longer "flashes and jumps back"
  (a stale response can no longer overwrite the new session); after a reconnect, approval and question
  cards still arrive, and the server now guarantees that a fresh connection learns about everything
  still pending.
- This batch **needs the matching server plugin** (the sibling `dsh-remote-bridge` repo carries the
  corresponding changes: capability bits, the subscription baseline, gap detection, interaction replay,
  file-card events). Versions are per-repo — see each `CHANGELOG-DEV.md`.

## Build from source

Use JDK 17 and Android SDK 36:

```sh
./gradlew testDebugUnitTest lintDebug assembleRelease
```

Release builds require a private signing configuration:

```properties
storeFile=/absolute/path/to/release-signing.p12
storePassword=...
keyAlias=...
keyPassword=...
```

Pass it with `-PdshSigningProperties=/path/to/signing.properties`. Never commit signing files or passwords.

## Project

- Version: `v2.2.57` (`versionCode` 10082)
- Package: `io.github.westanke.dshcompanion` (since v2.2.41; `v2.2.40` and earlier used `io.github.hakunm.deepseekharness`)
- App name: **DSH Companion** (Chinese locale shows `DSH伴侣`; since v2.2.41, previously `DeepSeek Harness`)
- Unit tests: 80 test classes / 795 cases at the last run (0 failures). The counts move with the code, so
  for the current numbers run `scripts/build.sh :app:testDebugUnitTest`, or count the classes under
  `app/src/test/` and the `@Test` annotations
- Current APK: `DSH伴侣-companion-v2.2.57.apk`, **3,278,012 bytes**, SHA-256
  `d8c133b8a9f6dccb3dfe40218e24c5694c71c54d398f0b911b03da6d1f542973` (kept in sync in
  `artifacts/SHA256SUMS.txt`).   **Pending release on Gitee + GitHub** (tag `v2.2.57`) —
  what changed in this release: **aligns with bridge v2.8.0 — chunked upload, schedules and
  official speech, three new REST BFF surfaces**.
  **Why**: chunked upload, schedules and official speech lived **only in `/ws/mobile` frames**,
  and the app consumes `/api/v1` REST without a WS client, so they were unreachable. Bridge v2.8.0
  added **additive-only** REST endpoints for all three (contract: the bridge repo's
  `docs/project/BFF-REST-CONTRACT.md`), which is what lets the app reach the same capabilities
  without a WS transport.
  **What changed**:
  ① **Chunked upload**: above 4 MiB, when the capability bit is present, the app switches to
  `POST …/attachments/uploads` → `GET …/chunks/{i}` / `PUT …/chunks/{i}` → `POST …/complete`
  (failure → `DELETE …/chunks/{i}`), with 1 MiB slices, a 256 MiB per-file ceiling, resume across
  restarts, and a single 409 retry for a missing chunk; the UI shows "N / M chunks". Small
  attachments keep `POST …/attachments` (32 KiB JSON ceiling).
  ② **Schedules**: `…/schedules` GET / POST / PATCH / DELETE, five-field Vixie cron plus IANA
  time zones, entered from the chat toolbar's `⋯` menu. **"Disable" means delete** — the kernel has
  no pause semantics, so a confirmation is shown first that states it cannot be undone; "Enable"
  is PATCH `enabled:true` (409 `SCHEDULE_ENDED` gets its own message). `createdAt` is always
  `null`, so ordering uses `nextRunAt` only.
  ③ **Official speech**: `GET …/speech/catalog` plus `POST /chat/speech/transcriptions` — the
  microphone button in the composer records to PCM16, wraps it in WAV and uploads it, with a 180 s
  timeout and a 4 MiB post-decode ceiling. A `200 {available:false}` reply is shown as a normal
  result, strictly distinct from "the route does not exist".
  **Capability bits** (final single names): `attachment-chunked-upload-v1`, `chat-schedules-v1`,
  `speech-transcription-v1`; an older bridge that does not advertise them simply keeps the
  corresponding entries hidden.
  **Verified**: 80 test classes / 795 cases / 0 failures (the new `DshClientBffTest`,
  `ChunkPlanTest`, `CronHintTest`, `WavHeaderTest`, `BffCapabilitiesTest` and `SchedulePolicyTest`
  add six classes); `docsCheck` consistent in both directions; `lintDebug` reports 0 errors.
  **Not verified on a device**: recording compatibility, the three-way permission state on vivo
  ROMs, and chunked upload against a real bridge (the unit tests use MockWebServer).
  **Degradation pinned**: a 404 from an older bridge → `isRouteMissing()` → the entry stays hidden;
  `available:false` is a normal result, not an error.
  (The previous release, v2.2.56, is documented in `docs/project/CHANGELOG-DEV.md`: the model
  picker showing context capacity and New Session reusing the blank session. v2.2.55 shipped with
  its own release notes.)
- Signing: `CN=DSH Pocket Client` (release key, used since v2.2.25). The key did not change, but the
  **package name did in v2.2.41**, so `v2.2.40` and earlier still require an uninstall first
- Author: upstream [Hakunm](https://github.com/Hakunm); this fork maintained by [westanke](https://github.com/westanke)
- License: [GNU Affero General Public License v3.0](./LICENSE)
- Server plugin: [dsh-remote-bridge](https://github.com/westanke/dsh-remote-bridge) **≥ 2.3.7** (loading older messages needs 2.3.1+; the Plugins tab list and enable/disable need 2.3.2+ / 2.3.3+; **approval and question cards need 2.3.7+**; gap resync / subscription baseline / live deliverable cards need 2.3.8+; the "response truncated" notice needs bridge **≥ 2.4.1**; the background-jobs panel needs bridge **≥ 2.4.3**; the model page's catalog add and restore-default-models need bridge **≥ 2.5.0**; **showing the device name as "platform · model" needs bridge ≥ 2.6.1** (below that the app still works — the device table just keeps the placeholder name); **the model context capacity needs bridge ≥ 2.7.0** (below that the model row omits it); **"New Session reuses the blank session" needs bridge ≥ 2.7.0** (below that every new session adds another blank one); **chunked upload / schedules / official speech need bridge ≥ 2.8.0** (below that the corresponding entries stay hidden))
  (formerly [`dsh-workspace`](https://github.com/Hakunm/dsh-workspace))
