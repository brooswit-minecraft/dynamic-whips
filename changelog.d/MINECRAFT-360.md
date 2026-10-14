bump: patch

### Fixed

- Prevented a paid-out WorldPoint rope from retaining both a preview stub and its promoted segment,
  so its physical reach matches the advertised rope length; increased the rope collision envelope
  to keep solver points out of one-block obstructions.
