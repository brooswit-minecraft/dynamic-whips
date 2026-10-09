bump: patch

### Fixed
- Rope core (MINECRAFT-85 final fixes): rewrote the stale comment above
  `RopeGameTests#catchOnObstruction`'s `required = true` — it claimed the rope "demonstrably
  catches" and is "a real, trustworthy CI gate again," both falsified by run 37901908128
  attempt 1's genuine `clippedWithPost=true`. Now says what is true: the earlier frozen results
  were the `updatePose()` read bug (fixed), the test has since failed once in four runs with a
  real tunnelling event, and `required = true` is an epic decision made because of that
  observation, not a stability claim. No assertion touched; `catchOnObstruction` stays
  `required = true`, `tunnellingThreshold` stays `required = false`.

### Docs
- `docs/rope-core.md` sections 2 and 9: records the epic's ruling (MINECRAFT-85 comment 32500)
  that `catchOnObstruction` stays `required = true`, closing the "stay required=true or revert"
  question the doc previously still posed as open.
