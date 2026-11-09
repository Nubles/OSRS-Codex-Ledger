# Replays

Each `.replay` file here is a scripted stretch of play, tick by tick, played against the whole
plugin in CI by `ReplayTest`: started as RuneLite starts it, and sent the game's events. The game
client is a stand-in (`com.codex.replay.FakeGame`) that the script drives: where the player
stands, skills, varbits, what is carried, chat, and the NPCs and objects that come and go. The
plugin reads it through RuneLite's own `Client` interface, so its code runs unchanged.

`smoke.replay` is an ordinary session from login to logout, with the plugin turned off and on
again at the end. It expects nothing of what the plugin shows; it fails if anything throws.

A script can also say what the plugin must show (`expect`), must never show (`never`) and must
always show (`always`), read out of the plugin with `watch NAME PATH`. The full command list is
at the top of `src/test/java/com/codex/replay/Replay.java`; `PluginHost.java` adds `config`,
`saved`, `watch`, `call`, `login`, `shutdown` and `startup`. The `com.codex.replay` files are
shared by the Codex plugins; keep them identical.

Rules for new scripts:

- Write the game facts a script relies on (levels, unlocks, ids) from the OSRS wiki or RuneLite's
  source, and cite the page in a comment. Ids are written by their RuneLite names.
- Scenes are schematic (the shape that matters, not the real map) and say so.
- Every player-reported bug gets a replay named after what went wrong, with the issue number in
  its first comment, before the fix is merged.
