# Backend configuration alignment

The backend default files are aligned to pinned VotingPlugin main
`834bcb84a59b6da40640eac83c20fd97ad1d64c9`: Config.yml, GUI.yml,
VoteSites.yml, SpecialRewards.yml and Shop.yml. Keys and non-item defaults match.
Item exceptions are WATCH instead of CLOCK, SIGN instead of OAK_SIGN, and
STAINED_GLASS_PANE with Data 15 instead of BLACK_STAINED_GLASS_PANE.
Non-BMP decorative characters were removed from comments because the actual
Spigot 1.8 YAML reader rejects them; no configuration value was changed for this.
AdvancedCore reward examples match pinned main
`6390c1cab41bd4d7683c7df88dd36537c8c7861e`. Removing the Repeat section from the
shipped example does not remove Repeat support from existing rewards.

Existing operator files are not replaced, rewritten or automatically stripped of
legacy keys. Explicit legacy JavaScript, MySQL, broadcast and VoteParty reminder
keys retain precedence. New installations get the aligned bundled templates.

## Reader compatibility

- Numeric delays keep their historical units; explicit ms/s/m/h/d duration text
  is accepted. Login/skull/click values remain milliseconds, update interval remains
  integer minutes rounded up, VoteDelay remains fractional hours. Invalid, negative
  or overflowing durations are rejected rather than silently becoming zero.
- Database replaces MySQL for native MYSQL storage when no legacy MySQL section
  exists. MYSQL and MARIADB are supported; POSTGRESQL is not silently treated as
  MySQL. Credentials/table prefix remain in the selected section.
- JavascriptEngine.Enabled and CommandEnabled map to existing JavaScript controls.
  JavascriptEngine.AutoDownload is NOT plugin update AutoDownload; the Java 8
  runtime uses its built-in engine and no new engine downloader is introduced.
- VoteBroadcast.Format.BroadcastMsg is read and %site% is supplied. NONE,
  EVERY_VOTE and EVERY_VOTE_ONLINE_ONLY are supported; other modern broadcast modes
  remain deferred and produce a warning.
- VoteParty.VoteReminder.Broadcast and AtVotes map to legacy reminder behavior.
- VoteShop DisplayItem subtrees are used by player/admin display and item editing;
  root-style legacy items remain valid. Category entries are excluded from the
  legacy purchase list so navigation entries cannot become free purchases.

## Schema alignment is not complete feature parity

The initial candidate still does not implement modern VoteReminders,
VoteMilestones, VoteStreaks groups, NameMC like rewards, DiscordSRV top displays,
Control management, database VoteLogging, Webhooks, category navigation or dialog
confirmation. VoteParty reminder commands and AutomaticTimeChanges filtering are
also not ported. Existing legacy reminders/streaks/reward systems remain usable
through their legacy keys. AdvancedCore's new example RandomMessage field is not
implemented; existing supported message rewards are unchanged.

Config load warns for enabled unported Control/Discord/VoteLogging/Webhooks and
modern reminders, unsupported broadcast modes and disabled automatic-time flags.
The remaining limitations above must not be presented as implemented merely
because their keys are included in matching templates. Proxy files/transports are
outside this alignment and have not been changed.

## Regression evidence

ModernBackendConfigurationTest loads all five bundled files through the real
Spigot 1.8 YAML reader and compares every non-item leaf/key against the pinned-main
JSON fixture. It checks modern/legacy duration values, missing/default behavior,
legacy precedence, nonmutation, VoteParty aliases, DisplayItem loading and category
purchase exclusion. AdvancedCore tests exercise options loading and exact native
Database/MySQL constructor section selection. Full coordinated builds use actual
Java 8 and the workspace-local Maven repository.
