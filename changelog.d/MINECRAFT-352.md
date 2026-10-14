bump: patch

### Fixed
- Rope core (MINECRAFT-352): defer structural rope-point promotion to the server tick after
  pay-out creates its physical first-segment stub, so the solver can establish usable WorldPoint
  reach without changing the measured five-tick native mutation pacing.
