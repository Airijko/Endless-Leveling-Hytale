# Endless Leveling - Update Log

## 2026-03-01

### Highlights

- Added a full augment progression flow with tiered unlock milestones.
- Expanded mob blacklist configuration to support exact IDs, keywords, and wildcard matching.
- Added support for no-default race/class setups using `"None"`.
- Improved UI handling for unselected race/class states.

### Augments

Augments are a player-bound progression layer. They work similarly to race/class passives in mechanics (passive-style bonuses and scaling), but they are attached to each player profile after selection from unlock offers.

#### Unlock Milestones

- ELITE: levels [10], [25]
- MYTHIC: levels [50]

#### Available Augments

##### ELITE

- Arcane Instability
- Arcane Mastery
- Blood Frenzy
- Critical Guard
- Drain
- Executioner
- First Strike
- Fortress
- Mana Infusion
- Overdrive
- Predator
- Rebirth
- Soul Reaver
- Supersonic
- Taunt
- Titan's Might
- Vampirism
- Wither

##### MYTHIC

- Blood Echo
- Blood Surge
- Giant Slayer
- Raging Momentum
- Reckoning
- Sniper's Reach
- Undying Rage
- Vampiric Strike

### Config & Gameplay Changes

#### Mob Blacklist

- `Mob_Leveling.Blacklist_Mob_Types` now supports:
  - `ids` for exact ID matching
  - `keywords` for keyword/substring matching
  - wildcard `*` patterns in both (examples: `Undead_*`, `*_Horse`, `*fish*`)
- Added more blacklisted mob IDs in `leveling.yml`.

#### Race/Class Defaults

- You can now set these to `"None"`:
  - `default_race`
  - `default_primary_class`
  - `default_secondary_class`
- When set to `"None"`, new players start with no default race/class selected.

### Fixes

- Fixed profile page null-value issues when race is unselected.
- Updated HUD behavior so unselected race is shown as "No Race" instead of unknown.
