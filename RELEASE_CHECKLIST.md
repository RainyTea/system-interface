# System Interface Release Checklist

Use this before any RuneLite hub-facing polish or submission attempt.

## Code And Metadata

- `README.md` describes System Interface, not the example plugin template.
- `runelite-plugin.properties` has real display name, author, description, tags, version,
  plugin class, and build type.
- `build.gradle` targets Java 11 and uses the System Interface group/project metadata.
- `settings.gradle` project name is `system-interface`.
- No `com.example`, `ExamplePlugin`, `ExampleConfig`, example config group, build artifacts,
  `.class` files, `out/`, or `.tmp` directories are included.
- License posture is explicit and permissive.

## Policy Review

- Appraise entries use `MenuAction.RUNELITE` only.
- No input injection, chatbox automation, external process execution, reflection, JNI/JNA,
  dynamic classloading, Java serialization, or native memory access.
- No combat prediction, prayer advice, attack counters, projectile landing markers, automatic
  stand-here indicators, boss mechanic warnings, or PvP target manipulation.
- No Construction, Blackjacking, or PvP menu modifications.
- No crowdsourcing or HTTP exposure of other players' data.
- Any config item that submits user IP to a third-party server is disabled by default and has
  the RuneLite warning text from `AGENTS.md`.

## Verification

- Run `./gradlew test --offline --rerun-tasks`.
- Start a development client with `./gradlew run` only for user-driven manual validation.
- The user logs in by following:
  https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts
- Record manual QA results in `HANDOVER.md` or the active SDLC brief.

## Manual QA Focus

- Login, logout, relog, and world hop do not show stale overlays or stale profile state.
- NPC, item, player, object, and fishing-spot Appraise entries stay client-side and preserve
  native safety-critical menu options.
- Fishing, Mining, and Woodcutting only credit gathered resources when supported by action
  provenance.
- Woodcutting `Bird nest` tracking appears as one tree-resource-scoped `1/256` row in the
  Skilling section and, when configured, Target Status overlay mode while actively chopping
  eligible nest-dropping trees. Seed, ring, and egg nest item pickups should count toward
  that single row, not separate statistical rows. Regular trees and redwoods should not show
  the normal Bird nest row, but they should still show Woodcutting resource context and pet
  odds when their object ID is known.
- Appraising an oak tree shows oak-specific Beaver odds using base chance `361,146` and the
  player's current Woodcutting level.
- Interacting with oak, willow, maple, yew, and magic trees shows the matching leaves as a
  conditional `1/4` reward only when a forestry kit or forestry basket is equipped or in
  inventory.
- Without a forestry kit/basket, named trees such as oak, maple, and yew still show their
  base log row and eligible non-conditional statistical rows; conditional leaf filtering must
  not hide the whole tracking table.
- Tree aliases from the OSRS Wiki pages resolve for normal trees, oak, willow, maple, yew,
  magic, teak, mahogany, arctic pine, redwood, and Sulliuscep. Confirm at least one alternate
  oak/maple/yew/redwood object if available in the test route.
- Thieving stall source views show the correct starter reward table for Bakery/Baker's,
  Silk, Fur, Silver, Fish, Seed, and Gem stalls after a `Steal-from` interaction.
- Bank withdrawals, GE collects, and trades do not inflate gathered totals.
- Combat same-item loot from different NPC sources does not cross-credit or cross-debit.
- Dropping and re-picking player-owned loot/resources restores kept state without increasing
  gross totals.
- Bury/eat/drink/empty-style removals finalize kept deductions where applicable.
- Manually rearranging identical same-ID loot stacks is treated as a known attribution
  limitation and should be described in release notes if still present.
