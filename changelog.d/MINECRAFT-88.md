bump: minor

### Added

- Harpoon: a thrown spear that ropes a struck living entity on a successful hit (12-block
  effective range, 5.0 damage, 4s cooldown, 32 durability — a committed, weapon-first throw, not a
  traversal tool). Reeling in with jump/shift, exactly like the grappling hooks, normally drags the
  target to the player; a sufficiently heavy and/or grounded/braced target instead pulls the player
  toward it, or neither end moves far at all — derived purely from the target's own bounding-box
  volume and grounded state (`HarpoonLogic#pullBalance`), never a per-mob list.
- Crafting recipe: 2 iron ingots, a stick, a lead and string — priced slightly above the Iron Hook
  (which skips the extra iron ingot and stick) since the harpoon is also a direct combat weapon,
  not just a utility anchor.

### Changed

- Rope core: ropes anchored to a moving entity can now optionally be configured (
  `RopeManager#attachToEntityWithPull`) so the anchor entity itself absorbs part of the rope's taut
  correction instead of only ever being read, never moved — the harpoon's own gap that
  `PlayerRope#tick` had no mechanism for before this change. Every existing caller
  (`RopeManager#attachToEntity`, every whip/hook `WorldPoint` anchor) is unaffected: the new split
  only activates when a nonzero yield fraction is explicitly set.
