# Modrinth Project Metadata: Saturate

## Project Settings
- **Name**: Saturate
- **Slug**: `saturate`
- **Project Type**: Mod
- **Environment**: Client
- **Categories**: Utility, Quality of Life
- **License**: LGPL-3.0-only
- **Source URL**: `https://github.com/push0dev/saturate`
- **Issues URL**: `https://github.com/push0dev/saturate/issues`

---

## Short Summary (<= 256 chars)
Client-side Fabric mod displaying health, armor, food, and saturation above players, featuring an advanced client-side estimation engine for servers that don't send hunger data.

---

## Dependencies
- **Fabric Loader**: `>=0.19.3` (Required)
- **Fabric API**: Required
- **YetAnotherConfigLib (YACL)**: Required
- **Mod Menu**: Optional (adds configuration button in the mod list)

---

## Description (for Modrinth Body)

```markdown
# Saturate

**Saturate** is a lightweight, client-side Fabric mod that renders health, absorption, armor, food level (hunger), and saturation directly above players' heads.

---

### 🍖 Key Features
- **Accurate Floating Indicators**: Renders saturation, hunger drumsticks, vanilla health hearts, absorption, and armor defense points with clean textures.
- **Advanced Estimation Engine**: 
  - On multiplayer servers (e.g. Paper, Spigot, or servers with AntiHealthIndicator), the server never transmits other players' food data.
  - Saturate reconstructs remote players' saturation and hunger client-side by tracking eating actions, movement exhaustion (grounded sprinting), attack cooldowns, and regeneration rates (10-tick fast saturation regen, 80-tick slow hunger regen).
  - Adapts to world difficulty (Peaceful, Easy, Normal, Hard) and dynamically prevents health collapses on lethal hits through floor-hit safeguards.
  - Caches players exiting and re-entering view distance for seamless continuous tracking.
- **Zero-Estimation Mode**: When playing on vanilla/integrated servers or when raw server values are preferred, `/sat mode never` turns off estimation completely.
- **In-Game Configuration**: Full configuration screen powered by YetAnotherConfigLib (YACL) and accessible via Mod Menu or `/saturate`.

---

### 💻 Client Commands

| Command | Arguments | Description |
| :--- | :--- | :--- |
| `/saturate` | — | Opens the in-game configuration interface. |
| `/sat debug` | — | Shows a real-time tracking diagnostics summary in chat. |
| `/sat debug log` | `on` \| `off` | Toggles timestamped estimation logging to `logs/saturate-debug.log`. |
| `/sat mode` | `auto` \| `always` \| `never` | Switch between auto-detection, forced estimation, or pure raw server data. |
| `/sat respawnsat` | `<0-20>` \| `unknown` | Sets the baseline saturation assumed when remote players respawn (`5.0` default). |

---

### 💡 Good to Know (Multiplayer & AntiHealthIndicator)
Vanilla Minecraft protocol does **not** send hunger or saturation of other players to your client. Unless a server has a companion mod, remote values will always be mathematical estimates. Saturate's estimation engine is built to give the highest fidelity possible without claiming false precision: uncertain or unverified values render with adaptive transparency.

---

### 📦 Requirements & Compatibility
- **Minecraft Versions**: 26.1.2, 26.2, 26.3
- **Platform**: Fabric Loader (`>=0.19.3`)
- **Required Mods**: [Fabric API](https://modrinth.com/mod/fabric-api), [YetAnotherConfigLib (YACL)](https://modrinth.com/mod/yacl)
- **Optional**: [Mod Menu](https://modrinth.com/mod/modmenu)

---

### 📜 Credits & License
Saturate is a fork of [Health Indicators](https://github.com/AdyTech99/HealthIndicators) by AdyTech99.
Licensed under the [GNU Lesser General Public License v3.0 (LGPL-3.0-only)](https://www.gnu.org/licenses/lgpl-3.0.html).
Source code available at: `https://github.com/push0dev/saturate`.
```

---

## Release Versions (Jars in `dist/`)

### Version 1: Minecraft 26.1.2
- **Version Name**: `Saturate 1.0 (Minecraft 26.1.2)`
- **Version Number**: `1.0`
- **Supported Game Versions**: `26.1`, `26.1.1`, `26.1.2`
- **Loaders**: `Fabric`
- **Jar File**: `dist/saturate-1.0.jar`
- **Changelog**:
  ```markdown
  ### Initial Release 1.0 (Minecraft 26.1.2)
  - Rebranded as Saturate with full LGPL-3.0 compliance.
  - Added remote saturation and hunger prediction engine for multiplayer servers.
  - Ground-truth respawn synchronization (food 20, configurable saturation via `/sat respawnsat`).
  - Sprint exhaustion (0.1/block) faithfully restricted to grounded movement (`packetOnGround`).
  - View-distance state caching (preserves player estimates for up to 60s when exiting render range).
  - Added real-time diagnostics (`/sat debug`) and TSV logger (`/sat debug log`).
  ```

### Version 2: Minecraft 26.2
- **Version Name**: `Saturate 1.0 (Minecraft 26.2)`
- **Version Number**: `1.0+26.2`
- **Supported Game Versions**: `26.2`
- **Loaders**: `Fabric`
- **Jar File**: `dist/saturate-1.0+26.2.jar`
- **Changelog**:
  ```markdown
  ### Initial Release 1.0 (Minecraft 26.2)
  - Full compatibility release for Minecraft 26.2 with updated Fabric & YACL mappings.
  - Includes all Saturate 1.0 features: saturation estimation, respawn tracking, view-distance caching.
  ```

### Version 3: Minecraft 26.3
- **Version Name**: `Saturate 1.0 (Minecraft 26.3)`
- **Version Number**: `1.0+26.3`
- **Supported Game Versions**: `26.3`
- **Loaders**: `Fabric`
- **Jar File**: `dist/saturate-1.0+26.3.jar`
- **Changelog**:
  ```markdown
  ### Initial Release 1.0 (Minecraft 26.3)
  - Full compatibility release for Minecraft 26.3 with updated Fabric & YACL mappings.
  - Includes all Saturate 1.0 features: saturation estimation, respawn tracking, view-distance caching.
  ```

