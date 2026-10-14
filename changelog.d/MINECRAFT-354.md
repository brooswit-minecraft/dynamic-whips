bump: patch

### Fixed

- Restored required grappling-hook payout/reel and fall-arrest GameTest coverage after reconciling
  the current rope-core safety guard. The hook rig now exercises the real public input path inside
  Rapier's safe coordinate envelope, and swing correction ignores Sable attachment bookkeeping
  points when enforcing the logical rope length.
