bump: minor

### Added
- Iron, Diamond and Netherite grappling hooks (16/32/64-block rope length tiers): one shared
  `HookItem`/`HookLogic` implementation, right-click to anchor to the looked-at block and again to
  detach, jump to reel in and shift to pay out up to the tier's own cap, built entirely on the
  existing rope core (`RopeManager`).
- Recipes for all three tiers (Iron from iron ingots/tripwire hook/lead/string, Diamond as an Iron
  Hook + diamonds upgrade, Netherite via the vanilla smithing-table netherite upgrade pattern).
- `docs/hooks.md`: design rationale, recipe rationale, and the lifecycle/multiplayer-sync coverage
  table for the three hooks.
