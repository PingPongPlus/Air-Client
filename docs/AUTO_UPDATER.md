# Air Client updates

The title screen displays the installed version from `fabric.mod.json`. At startup the
client checks the latest stable GitHub release of the repository set by
`update_repository` in `gradle.properties` (currently `AeroAlpine/Air-Client`).
If a newer version exists, an **Update** button appears without reopening the menu.
Click it to download the update. After verification, **Quit to apply update** closes
Minecraft normally. Launch it again through the usual launcher.

Downloads use the running instance's `mods` folder, never a hard-coded `.minecraft`
directory. A `.pending` file is ignored by Fabric. A bundled Java helper waits for the
game process to exit before replacing the exact installed JAR. The old version remains
as `<installed filename>.backup`, which Fabric also ignores. No GitHub credentials
are shipped in the client. Downloads are bounded and require GitHub's SHA-256 digest;
the JAR's mod ID, version, Minecraft, Java, Fabric Loader, and Fabric API requirements
are checked before staging an installation. Development runs can check releases but
cannot replace their class files or install updates.

## Versions and publishing

`mod_version` is the offline/local build version. It currently matches GitHub's
`v0.2.5` release. The JAR filename, embedded mod version, and menu all use that value:
`build/libs/Air-Client-0.2.5.jar`. Never label a running old build with the latest
remote version: that would hide available updates.

For a release, build with the tag as an explicit override:

```sh
./gradlew build -Prelease_version=0.1.4
```

The release workflow runs the checks, builds with the tag's version, and uploads
`Air-Client-0.1.4.jar`. Push a stable `v0.1.4` tag, or dispatch the workflow with an
existing tag. Update `mod_version` for subsequent local builds when preparing each release.
The updater also accepts the repository's existing `v0.1.3.jar` naming convention.
It rejects source JARs, ambiguous assets, prereleases, missing digests, incompatible
game versions, and JARs whose embedded version disagrees with the release tag.

The existing GitHub release does not gain updater support automatically. Install one
updater-enabled build manually; later releases can then be installed from its menu.

## Recovery and testing

Failed checks/downloads preserve the installed JAR. Failed replacements attempt to
restore the verified backup. If a new release crashes before startup, close Minecraft,
remove the new JAR and rename the `.backup` file back to the original `.jar` filename.
Do not keep two active copies of Air Client in `mods`.
Installer plans, logs, and `result.txt` are under `.air-client-updater/update-*` inside
the instance directory. A pre-existing `.pending` download is preserved for recovery;
if its helper is no longer running, remove it before retrying. Automatic recovery from
crashes before mod initialization requires a separate bootstrap and is not included.

```sh
./gradlew updaterTest
./gradlew build
./gradlew runClientGameTest -I tools/updater-menu-test.gradle
```

`updaterTest` checks release parsing, version ordering, allowed download locations,
response size limits, JAR compatibility, tampering, filesystem locks, backups, and a
real extracted helper process waiting for a simulated Minecraft process to terminate.
All file replacement tests use temporary directories. Cross-platform CI runs these
checks on Windows, macOS, and Linux.
