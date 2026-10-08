bump: minor

### Added
- Rope core (MINECRAFT-85): player-attached Sable rope with a gravity-and-momentum swing
  constraint, server-to-client sync and rendering, airtight lifecycle (detach on request, death,
  logout, dimension change, server stop, anchor block broken, chunk unload), and an internal
  `RopeManager` API for the future whip anchor, grappling hook and harpoon. Headless GameTest
  coverage for the fall-arrest, catch-on-obstruction and lifecycle acceptance criteria; see
  `docs/rope-core.md` for results, the tunnelling-threshold measurement, packet design rationale
  and what remains a manual (not CI) procedure.
