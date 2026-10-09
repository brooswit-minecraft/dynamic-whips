bump: patch

### Fixed
- Rope core (MINECRAFT-85 review follow-up): `PlayerRope` now stores and uses the ACTUAL segment
  spacing Sable laid a rope out at (after the point-count clamp), not the originally requested
  spacing — `restLength()`, the swing constraint's `allowedRadius`, `payOut` and `adjustLength`
  were all silently working from the wrong number whenever a rope's length/slack combination hit
  `MIN_POINTS`/`MAX_POINTS`. `MAX_POINTS` raised 129 → 142 so the 64-block Netherite Hook at its
  documented default slack keeps its actual spacing at or under the tunnelling-safe value.
- `PlayerRope` (and the MINECRAFT-67 spike debug command) never called Sable's
  `RopePhysicsObject#updatePose()`, so every read of a rope's points — the sync packet, the swing
  pivot, and every GameTest assertion — was the rope's creation-time layout forever, regardless of
  whether Sable's own solver was stepping it. This was the real cause behind the "frozen rope"
  result the previous PR escalated as an open question about Sable's collision; fixed by calling
  `updatePose()` before every read.
- `tunnellingThreshold` no longer asserts the shipped spacing "tunnelled through, or clipped
  inside, the post" when the rope's points never moved at all — it now reports that distinctly, as
  `catchOnObstruction` already did.
- CI's GameTest step now asserts the expected test count from its own log output instead of
  trusting `runGameTestServer`'s exit code, which only counts failed *required* tests and can
  exit 0 with zero tests ever registered.

See `docs/rope-core.md` sections 1.6, 2, 3 and 3.5 for the full diagnosis and the manual procedure
for a human to follow up with if CI still can't settle criterion 2.
