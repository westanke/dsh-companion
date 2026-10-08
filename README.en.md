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

> **DSH 0.2.x compatibility**: this fork also carries the upstream fix for an enum-contract mismatch.
> DSH 0.2.x no longer returns `trust` on `AgentPreset`, while the client's strict decoder requires it,
> so the Agent preset picker failed entirely with
> `Field 'trust' is required ... missing at path: $.items[0]`. The fix adds defaults to optional
> fields so a single missing field can no longer break the whole list.

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

Download from this repository's GitHub Releases:

```text
DeepSeek-Harness-companion.apk
```

Requirements:

- Android 8.0 (API 26) or newer
- A running DeepSeek Harness WebUI
- `dsh-workspace` v1.0.0 or a compatible version installed in the WebUI
- At least one authorized root added in the plugin, with remote access enabled
- The phone can reach the IP, domain, and port configured in the plugin

Release APKs are signed with the **same** certificate as upstream, and the `applicationId` is also kept
identical (`io.github.hakunm.deepseekharness`). You can therefore **install directly over an upstream
build**; saved connections are migrated automatically into the new multi-address structure on first
launch, with no re-pairing.

## Screenshots

<p align="center">
  <img src="./assets/screenshots/app-navigation.jpg" width="47%" alt="App navigation drawer">
  <img src="./assets/screenshots/chat-demo.jpg" width="47%" alt="Chat and live responses">
</p>
<p align="center">
  <img src="./assets/screenshots/file-browser.jpg" width="47%" alt="Authorized-root file browser">
  <img src="./assets/screenshots/file-editor.jpg" width="47%" alt="Text editor with line numbers and zoom">
</p>
<p align="center">
  <img src="./assets/screenshots/workspace-create.jpg" width="47%" alt="Create a workspace and start a session">
  <img src="./assets/screenshots/model-providers.jpg" width="47%" alt="Model provider settings">
</p>

## Download

Download the latest release from [GitHub Releases](https://github.com/Hakunm/dsh-android-app/releases/latest):

```text
DeepSeek-Harness-v1.0.0.apk
```

Requirements:

- Android 8.0 / API 26 or newer
- A running DeepSeek Harness WebUI installation
- `dsh-workspace` v1.0.0 or a compatible release installed in DSH WebUI
- At least one authorized root and remote access enabled in the plugin
- Network reachability from the phone to the configured IP/domain and port

The production APK uses a dedicated release certificate, not the Android debug key. Future releases must use the same certificate for in-place upgrades.

## Connect to DSH

### On the server

1. Open **Workspace settings** for `dsh-workspace` in DSH WebUI.
2. Add at least one authorized root.
3. Enter the bind IP and port under **Remote access**, then save the listener settings.
4. Select **Enable and create pairing** to receive a ten-minute one-time code.

### On the phone

1. Enter the server address, such as `http://192.168.1.20:3090` or `https://dsh.example.com`.
2. Enter the pairing code and a device name.
3. Select **Pair and connect**.

The device token is stored using Android Keystore-backed encrypted storage. Disconnecting clears the local token. Revoking the device in DSH WebUI invalidates it immediately.

## Chat and task control

- View authorized DSH sessions and live run state.
- Stream assistant text and reasoning, then reconcile with server history on completion.
- Render Markdown headings, lists, quotes, code blocks, and tables.
- Send messages, steer a running task, or cancel it.
- Follow DSH TODO progress and the currently active item.
- Type `/` to browse and run host-provided slash commands.
- Switch between Read only, Workspace write, and Full access permission modes.
- Review redacted approval details and choose Allow once or Deny.
- Select a model and reasoning effort. Empty sessions can also select an Agent before the first message.

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

- Version: `v1.0.0`
- Package: `io.github.hakunm.deepseekharness`
- Author: [Github@Hakunm](https://github.com/Hakunm)
- License: [GNU Affero General Public License v3.0](./LICENSE)
- Server plugin: [dsh-workspace](https://github.com/Hakunm/dsh-workspace)
