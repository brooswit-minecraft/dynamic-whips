# dynamic-whips

Rope tools for Minecraft (NeoForge 1.21.1, Java 21): a leather whip, tiered
grappling hooks and a harpoon, built on [Sable](https://modrinth.com/mod/sable)
physics ropes. Mod ID: `dynamicwhips`. MIT licensed. Tracked in Jira as
MINECRAFT-67.

Status: scaffold plus a rope spike (`docs/rope-spike.md`). No gameplay items yet.

## Building

Requires a JDK 21 with `javac` on `JAVA_HOME`.

```sh
./gradlew build
```

The build downloads the pinned Sable jar (`sable_*` in `gradle.properties`,
the same file the Sickos pack pins) and verifies its sha512 before compiling
against it.

## Contributing

Every PR that changes `src/` adds one `changelog.d/<TICKET>.md` fragment; see
`changelog.d/README.md`. Do not edit the version by hand.
