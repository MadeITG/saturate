# Saturate

Client-side Fabric mod that shows health, armor, food and, above all, saturation above players, with an estimation engine for servers that don't send it.

---

## ⚠️ Important Note on Multiplayer & Estimation

In vanilla Minecraft multiplayer, the server **never transmits food level or saturation values of other players** over the network. 

Unless a server runs a custom companion plugin or you are viewing your own stats in singleplayer, remote players' hunger and saturation are **client-side estimates**. Saturate employs an advanced simulation engine (tracking consumption packets, regeneration intervals, movement exhaustion, and combat cooldowns) to provide the closest possible reconstruction, but it does not claim false precision. When viewing conditions lack definitive evidence or when AntiHealthIndicator packet obfuscation is active, confidence intervals and indicators adapt transparently.

---

## ✨ Features

- **Multi-Element Floating HUD**:
  - 🍖 **Saturation**: Displays remote and local player saturation points with 0.5-point accuracy.
  - 🍗 **Food Level (Hunger)**: Accurate hunger drumstick indicators with natural regeneration & starvation tracking.
  - ❤️ **Health & Absorption**: Classic vanilla hearts or compact numeric indicators with absorption stacking.
  - 🛡️ **Armor Points**: Visual shields displaying computed armor defense.
- **Advanced Estimation Engine (AntiHealthIndicator & Paper PvP Resilience)**:
  - Predicts saturation, hunger, and health on servers (such as Paper/Spigot or AntiHealthIndicator) that do not broadcast other players' food data.
  - **Ground-Truth Respawn**: Detects death events and synchronizes respawns (configurable via `/sat respawnsat`).
  - **Player View-Distance Caching**: Preserves saturation and food states across entity unload/reload cycles when players move out and back into render range.
  - **Accurate Movement Exhaustion**: Faithfully adheres to vanilla movement mechanics (sprint exhaustion of 0.1/block applies exclusively while grounded).
  - **Attack Cooldown & Weapon Tracking**: Calculates weapon swing cooldown factors (`0.2 + s² * 0.8`), critical strikes, and sprint hits.
  - **Server Difficulty Awareness**: Adapts damage scaling and starvation floors to Peaceful, Easy, Normal, and Hard.
  - **Zero Estimation on Vanilla/Integrated Servers**: Directly renders genuine server values when available, with an explicit `/sat mode never` option.
- **In-Game Configuration**:
  - Full in-game configuration screen powered by **YetAnotherConfigLib (YACL)** and integrated with **ModMenu**.
  - Customizable scale, vertical offset, opacity, through-wall rendering, and element toggles.

---

## 💻 Commands

| Command | Arguments | Description |
| :--- | :--- | :--- |
| `/saturate` | — | Opens the in-game configuration screen (YACL). |
| `/sat debug` | — | Displays a real-time diagnostic report in chat (active targets, confidence, server difficulty). |
| `/sat debug log` | `on` \| `off` | Toggles timestamped estimation telemetry logging to `logs/saturate-debug.log`. |
| `/sat mode` | `auto` \| `always` \| `never` | Sets the estimation mode (`auto` by default; `never` disables estimation and displays raw server data). |
| `/sat respawnsat` | `<0-20>` \| `unknown` | Sets the baseline saturation assumed when remote players respawn (`5.0` default; `unknown` / `-1`). |

---

## 📦 Compatibility & Dependencies

- **Environment**: Client-side only.
- **Platform**: Fabric Loader (`>=0.19.3`)
- **Supported Minecraft Versions**:
  - `26.1.2`
  - `26.2`
  - `26.3`
- **Required Dependencies**:
  - [Fabric API](https://modrinth.com/mod/fabric-api)
  - [YetAnotherConfigLib (YACL)](https://modrinth.com/mod/yacl)
- **Optional Dependencies**:
  - [Mod Menu](https://modrinth.com/mod/modmenu) (optional; supported for in-game config button)

---

## 🔄 Changes

Compared to the original Health Indicators mod, Saturate introduces extensive modifications and additions:
- **Saturation & Hunger Prediction**: Full client-side simulation engine to track and deduce remote players' saturation and hunger from eating actions, item consumption, and health regeneration cadences (10-tick fast saturation regen, 80-tick slow hunger regen, 90-tick quiet window).
- **Exact Respawn Calibration**: Tracks player deaths and applies known respawn state (food 20, saturation 5.0, exhaustion 0) within a 600-tick window upon reappearing at full health.
- **Configurable Respawn Saturation**: Added `/sat respawnsat` to customize expected respawn saturation for servers with custom mechanics.
- **Grounded Sprint Exhaustion**: Fixed distance-based movement exhaustion to count sprinting exhaustion (0.1 per block) only when `packetOnGround` is true, matching vanilla Minecraft behavior.
- **View-Distance State Caching**: Stores a snapshot of player saturation, hunger interval, exhaustion, and certainty flags for up to 60 seconds (1200 ticks) when players exit render distance, restoring their state seamlessly upon re-entry.
- **Server Difficulty Integration**: Automatically tracks world difficulty from game packets to scale mob damage and enforce difficulty-dependent starvation health floors.
- **AntiHealthIndicator (AHI) Fingerprinting & Floor-Hit Safeguards**: Detects packet spoofing anomalies, dynamically prevents health collapse on lethal hits, and supports confidence intervals with translucent rendering.
- **Real-Time Debug Tools & Diagnostics**: Added `/sat debug` command and timestamped TSV logger (`/sat debug log`).
- **New Identity & Brand**: Rebranded as Saturate with streamlined commands and configuration files.

---

## 📜 Credits

Saturate is a fork of Health Indicators by AdyTech99 (https://github.com/AdyTech99/HealthIndicators), licensed under LGPL-3.0-only.

---

## 📄 License

This project is licensed under the terms of the [GNU Lesser General Public License v3.0 (LGPL-3.0-only)](LICENSE).

See [`NOTICE`](NOTICE) for copyright attribution.
