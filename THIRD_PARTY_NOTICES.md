# Third-party notices

Air Client is licensed under the GNU Affero General Public License v3.0 only
(AGPL-3.0-only). It contains code derived from, or depends on, the following
projects. Their copyright notices and licenses continue to apply.

## Code derived from Coflnet projects

### SkyblockModFabric
- Copyright: Coflnet and contributors
- License: GNU Affero General Public License v3.0 (AGPL-3.0)
- Source: https://github.com/Coflnet/SkyblockModFabric
- Used as the base for the Fabric client bridge, auction/description handling,
  settings screens and related client code.

### SkyblockMod
- Copyright: Coflnet and contributors
- License: GNU Affero General Public License v3.0 (AGPL-3.0)
- Source: https://github.com/Coflnet/SkyblockMod
- Predecessor of the Fabric frontend; parts of the client behaviour are
  derived from it.

### CoflSkyCore
- Copyright: Coflnet and contributors
- License: GNU General Public License v3.0 (GPL-3.0)
- Source: https://github.com/Coflnet/CoflSkyCore
- Bundled version: commit `3406a83b4a`
- Used as a library for the websocket protocol, configuration handling,
  descriptions and event plumbing.

The AGPL-3.0 and GPL-3.0 components are combined under the compatibility rule
of GPLv3 section 13. The combined work is distributed under AGPL-3.0-only.
The corresponding source for the bundled CoflSkyCore version is available at
the source URL above; the exact commit is recorded here so the binary can be
reproduced.

## Runtime dependencies

The following libraries are bundled or loaded at runtime. Consult each
upstream project for the full license text.

- Gson 2.10.1 - Apache-2.0 - https://github.com/google/gson
- GreenRobot EventBus 3.3.1 - Apache-2.0 - https://github.com/greenrobot/EventBus
- nv-websocket-client 2.14 - Apache-2.0 - https://github.com/TakahikoKawasaki/nv-websocket-client
- Fabric API - Apache-2.0 - https://github.com/FabricMC/fabric
- RenderChest 1.0.3 - Apache-2.0 - https://github.com/AzureAaron/RenderChest
- Yet Another Config Lib (YACL) 3.9.4 - LGPL-3.0 - https://github.com/isXander/YetAnotherConfigLib
- Baritone 1.19.0 - LGPL-3.0 - https://github.com/cabaletta/baritone

## Minecraft

Minecraft is a trademark of Mojang Synergies AB. This project is not
affiliated with or endorsed by Mojang, Microsoft, Hypixel or Coflnet.
