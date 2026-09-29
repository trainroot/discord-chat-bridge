# Discord Client Bridge 1.1.0 — Fabric 1.21.11

Build the installable JAR using the instructions below; this source archive does
not bundle a prebuilt mod JAR. References below to installing the included JAR
apply to the original binary release; for this package, use your build output.

A client-only, one-way bridge: Minecraft chat → a Discord server text channel.
The topic shows estimated TPS, the names in your tab list, tab-list ping, a readable
update time, and whether the Minecraft bridge is connected, paused, or disconnected.

## Upgrading

1. Close Minecraft completely.
2. Remove the old `discord-client-bridge-x.x.x.jar` from your instance's `mods` folder.
3. Install the included `discord-client-bridge-1.1.0.jar`. Do not keep both versions.
4. Keep your existing JSON configuration and bot-token file, then restart Minecraft.

New features work with an existing config. The old `includePlayerNames` setting is
ignored: names are now always included in the topic. You can add the new settings
shown below, but do not replace your channel ID, server allowlist or token with the
example values. `/dbridge reload` reloads settings, not a replacement JAR.

## First-time installation

1. Install Fabric Loader **0.18.4+ for Minecraft 1.21.11**, Java **21+**, and Fabric
   API **0.141.6+1.21.11** or a later compatible 1.21.11 release.
2. Put `discord-client-bridge-1.1.0.jar` alongside Fabric API in your instance's
   `mods` folder. Do not install a sources JAR or the ZIP itself.
3. Launch Minecraft once to create `config/discord-client-bridge.json`.
4. Create a Discord application/bot at https://discord.com/developers/applications.
   Install it in your Discord server using the `bot` scope. Give it **View Channel**,
   **Send Messages**, and **Manage Channels** in a dedicated ordinary text channel.
   Administrator and Message Content Intent are unnecessary.
5. Save only the bot token in `config/discord-client-bridge-token.txt`, with no quotes
   or `Bot` prefix. Ensure Windows has not added a second `.txt` extension.
   Alternatively set `DISCORD_BRIDGE_BOT_TOKEN` in the launcher's environment; that
   takes precedence. Never share the token or distribute it with a modpack.
6. Enable Discord Developer Mode and copy the target channel's ID. Edit the generated
   JSON: set `enabled` to `true`, set `channelId`, and set `allowedServers` to the
   address entered in your Minecraft server list.
7. Join the allowed server and run `/dbridge reload`, then `/dbridge status`.

The bot can appear offline in Discord: it uses HTTP, not the presence gateway. No
separate bot process, server plugin, server command or server permission is needed.
The Minecraft client must be running and connected to receive fresh data.

## Configuration

```json
{
  "enabled": true,
  "channelId": "REPLACE_WITH_CHANNEL_ID",
  "allowedServers": ["play.example.net:25565"],
  "serverLabel": "Minecraft",
  "forwardSystemMessages": true,
  "formatParsedMessages": true,
  "updateChannelTopic": true,
  "announceDisconnects": true,
  "timestampZone": "system",
  "topicUpdateSeconds": 300,
  "maxQueuedMessages": 500,
  "messageMaxAgeSeconds": 120,
  "excludeMessageRegex": []
}
```

- `allowedServers`: exact addresses, case-insensitive, with omitted default port
  treated as `25565`. Uses the address entered in Minecraft, not resolved IPs or SRV
  destinations. Empty means none; `"*"` permits all multiplayer servers. No singleplayer.
- `serverLabel`: optional topic label. The actual last bridged server address is also
  included. The default `Minecraft` label displays just the server address.
- `timestampZone`: `"system"` uses the computer's timezone. Use `"America/Chicago"`
  for Central time (DST-aware), or `"UTC"`. Invalid zones are rejected.
- `formatParsedMessages`: enable bold names/prefixes/suffixes and italic join/leave
  notices. Set to `false` to relay original text with Discord escaping.
- `announceDisconnects`: enable the disconnect warning in the channel.
- `updateChannelTopic`: this replaces the entire topic. Disabling it leaves the last
  topic in place; edit the channel manually if needed.
- `topicUpdateSeconds`: periodic topic interval, minimum 300 seconds. Connection,
  disconnection and pause/resume transitions request an earlier update, but always
  respect Discord's actual rate limits. Normal chat does not wait for this interval.

Keep one bridge running per channel to avoid duplicate messages and competing topics.

## Chat presentation and parsing

The Java `MessageParser.extractMessageInfo` follows the supplied JavaScript patterns:
Java chat, ranked/badged chat, dotted Bedrock names, `Bed` prefixes, incoming `From`
messages, and Java/Bedrock join and leave notices. `Bed` is checked before generic
badges so it is not mistaken for the username. Dotted names retain their dot.

| Minecraft input | Discord appearance |
| --- | --- |
| `<+Player ASP> hello` | **+Player ASP**: hello |
| `<Bed +Player ASP> hello` | **Bed +Player ASP**: hello |
| `<.Player [VIP]> hello` | **.Player [VIP]**: hello |
| `From Player: hello` | From **Player**: hello |
| `Player joined the game` | ***Player*** *joined the game* |
| `.Player left the game` | ***.Player*** *left the game* |

All header content inside recognized `<...>` formats is retained and bolded,
including prefixes, usernames, and suffixes. Message bodies remain plain text;
player-supplied Markdown is escaped. Mentions and link previews are suppressed.
Names follow the supplied word-character convention (letters, digits, underscore).
Unknown formats are forwarded as escaped original text, not discarded. This is
text parsing, not verified sender identification.

Any received line containing a standalone `/bed` token (case-insensitive, including
arguments or quoted echoes) is excluded. `/bedrock` and `/bedtime` are not matched.
This filter also hides ordinary received discussion containing that exact token.
It does not intercept, execute, or change `/bed` in Minecraft. Outgoing typed commands
are never captured by this bridge. Server replies with no `/bed` text cannot be
reliably identified as replies to that command; add a format-specific exclusion if
needed. No server queries are used to determine which command produced a reply.

`excludeMessageRegex` uses Java regex `find()` on cleaned received text before
formatting. `forwardSystemMessages` includes non-action-bar messages sent by plugins.
**Those can include private messages, command replies, coordinates and login prompts.**
The parser recognizing a private message does not block it. For the known incoming
`From Name:` format, use this config entry to exclude it:

```json
"excludeMessageRegex": ["^From \\+?\\.?\\w+:"]
```

That pattern is not a universal private-message filter. Setting
`forwardSystemMessages` to `false` excludes the entire category, which may also
exclude public chat on plugin-based servers.

## Topic and disconnect behavior

Example (Central timezone):

> play.example.net | Connected as BridgeBot | TPS (est.): 19.9 | Ping: 42 ms | Online (tab): Alex, Sam, .Steve | Updated: Sep 27, 2026 at 1:24:54 PM CDT

All tab-list profile names are sorted alphabetically and included when they fit.
There is no arbitrary 40-player cap. Discord limits text-channel topics to 1,024
characters: larger lists end with `… (+N more)`, preserving complete names and the
timestamp. A server can hide players, add fake entries, or show proxy-wide names;
this is the client's tab list, not a guaranteed backend-server roster.

On an observed disconnect, the bridge queues:

> **BridgeBot** has disconnected from **play.example.net**.

Here `BridgeBot` is the Minecraft account running the bridge, not the Discord bot's
profile name. The remembered address is the most recent allowed server bridged.
The topic becomes `Disconnected`, with TPS and online names marked unavailable
instead of leaving stale statistics. Duplicate disconnect/closing callbacks produce
one notice. A fast reconnect keeps the warning queued while the topic reflects the
latest connection. Pause updates the topic to `Bridge paused`; while paused, chat
and disconnect announcements are suppressed.

Notifications are prioritized over ordinary queued chat and retained for up to five
minutes. They are best effort, not guaranteed delivery. A normal game exit allows
up to three seconds for the warning/topic to send. A crash, forced termination,
Discord outage, lost internet access, or rate limit can prevent delivery; the last
visible timestamp helps identify a stale topic. No remote watchdog is used.

## Local commands and status

| Command | Effect |
| --- | --- |
| `/dbridge` or `/dbridge status` | Shows forwarding state, separate chat/topic results and timestamps, sent/waiting/dropped counts. |
| `/dbridge help` | Shows the short command list. |
| `/dbridge off` | Pauses this session, clears ordinary queued chat, updates the topic. |
| `/dbridge on` | Resumes on an allowed server. |
| `/dbridge reload` | Validates and reloads config/token; clears ordinary queued chat and resumes. Invalid settings leave an existing working configuration running. |

Commands are registered only with Fabric's **client command API**, and feedback is
written directly into the local chat HUD. They are not sent to the Minecraft server.
Reloading with the same token/channel preserves Discord rate-limit deadlines and
retries previously disabled routes. A request already in flight cannot be recalled.

Example status:

```text
[Bridge] Forwarding — play.example.net
[Bridge] Chat: Sent (HTTP 200) — Sep 27, 2026 at 1:24:54 PM CDT
[Bridge] Topic: Updated (HTTP 200) — Sep 27, 2026 at 1:20:00 PM CDT
[Bridge] Sent: 23 | Waiting: 0 | Dropped: 0
```

`Sent` counts successfully delivered message lines, including bridge notices, for
the current worker. A topic success no longer overwrites the last chat result.
HTTP 401 means invalid token; 403 generally means missing permissions/access; 404
commonly means wrong channel ID or no access. Fix settings and reload. Topic errors
do not disable chat unless the token is invalid or a Discord global limit applies.

## Client-only measurement and networking

- TPS compares world-age changes in incoming time-update packets with monotonic
  elapsed local time, using up to 60 intervals. No FPS, ping-based TPS, `/tps`, server
  list polling, status query, custom packet, or additional Minecraft connection.
- Estimates cap at 20 TPS; custom higher tick rates are outside this estimator's
  scope. Network jitter, proxies, and client freezes can affect the result. A
  dimension/respawn resets sampling. `N/A` means insufficient samples or no fresh
  time packet for more than 30 seconds, not a measured zero.
- Player names and ping are read from the existing client tab-list data.
- This mod adds only requests to `https://discord.com/api/v10/`. Normal Minecraft
  gameplay and keep-alive traffic continue unchanged. No telemetry/update checks.
- Chat is batched approximately every half second plus network/request time.
  Queue sizes and ages are bounded; rate limits and outages can delay/drop messages.
  Stable nonces reduce duplicates after ambiguous HTTP retries.

## Build and validate

With a Java 21 JDK and `JAVA_HOME` set:

```sh
# Windows Command Prompt
gradlew build
# Windows PowerShell:
.\gradlew.bat build
```

Output: `build/libs/discord-client-bridge-1.1.0.jar`. Tests use a local mock Discord
server with a fake token. No actual bot token is required for tests/builds. See
`VALIDATION.md` for this package's results and build-environment details.

Live verification: join an allowed server; check one public chat message, a badge,
a Bedrock name, and join/leave notices; check the topic's names/time; test `/dbridge
off` and `on`; disconnect while keeping Minecraft open, then reconnect quickly.
Confirm the warning appears once and the topic eventually reflects the current
state. Verify `/bed` echoes do not appear and `/bedrock` text is not filtered.

## API references

- Fabric client commands: https://maven.fabricmc.net/docs/fabric-api-0.141.6+1.21.11/net/fabricmc/fabric/api/client/command/v2/ClientCommandManager.html
- Fabric chat events: https://maven.fabricmc.net/docs/fabric-api-0.141.6+1.21.11/net/fabricmc/fabric/api/client/message/v1/ClientReceiveMessageEvents.html
- Discord topics: https://docs.discord.com/developers/resources/channel
- Discord rate limits: https://docs.discord.com/developers/topics/rate-limits
