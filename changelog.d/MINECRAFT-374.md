bump: patch

### Fixed

- Grappling hooks no longer attach past their tier's own rope-length cap (iron 16 / diamond 32 /
  netherite 64) at near-max raycast range: the initial rest length computed from the attach
  distance and anchor slack is now hard-clamped on the public hook attach path, before the
  pay-out gate ever runs.
