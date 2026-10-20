# SoundStream

![SoundStream](thumbnails/modrinth-banner.png)

SoundStream lets a Paper server send `.ogg` and `.mp3` audio to Fabric clients. The client plays the song progressively as it downloads, uses a bounded memory buffer, and validates the container. No resource pack, HTTP server, or client-side sound registration is involved.

The full file is always sent. It is split into small custom-payload frames only because Minecraft limits plugin-message size; playback starts after a short prebuffer while the remaining frames continue to arrive. Files of hundreds of megabytes are supported: the server streams them from disk and the client keeps only a negotiated window in memory.

## Requirements

| Component | Supported versions |
|---|---|
| Minecraft client | **26.1.2 - 26.3** (one jar) |
| Fabric Loader | **0.20.0+** |
| Fabric API | **0.155.3+26.1.2**, **0.161.0+26.2**, **0.164.0+26.3** |
| Java | **25** |
| Paper server | **26.1.2 and newer** |

One Fabric jar supports Minecraft 26.1.2 through 26.3; it declares `minecraft >=26.1.2 <26.4`. Minecraft 26.4 is not listed by Fabric's launcher metadata yet, so it is not built. The Paper plugin is compiled against the 26.1.2 API and remains compatible with newer Paper releases.

The Paper plugin accepts SoundStream clients speaking protocol v1, v2, or v3, so older mod versions keep working. It adapts per player: v1 receives the whole file without credit; v2 uses the bounded credit window; v3 additionally uses continuous LOOP streaming for looping.

Vanilla clients are not kicked. They simply cannot hear streams, and the server can show them a configurable action bar message.

## Install

1. Put the Fabric mod jar in the client's `mods` folder.
2. Put the Paper jar in the server's `plugins` folder.
3. Start the server and place audio files under:

```
plugins/SoundStream/sounds/
```

Files are addressed by relative ID, for example `music/theme.ogg` or `effects/ding.mp3`. Absolute paths, `..` traversal, symlinks escaping the folder, and non-`.ogg`/`.mp3` files are rejected.

Only Ogg Vorbis and MPEG Layer III are accepted. Ogg Opus is rejected.

## Commands

```
/soundstream play <player> <sound> [--2d|--here|--at x y z] [--volume 0..1] [--loop]
/soundstream stop <player> [all|id]
/soundstream list
/soundstream reload
```

The default is a 2D stream. `--here` uses the sender's location; `--at` uses explicit coordinates. 3D streams are attenuated and panned client-side.

Permissions:

- `soundstream.play`
- `soundstream.stop`
- `soundstream.admin` (list/reload)
- `soundstream.receive` (default true)

## Missing-client action bar

The server can show a configurable action bar to players who do not have the mod:

```yaml
missing-client-actionbar: true
missing-client-message: "&eYou currently dont have SoundStream installed, means you cant hear the special music/sounds!"
missing-client-delay-ticks: 60
```

The join check waits for the handshake so modded clients are not falsely warned. The message is also shown when `/soundstream play` targets a player without the mod. Set `missing-client-actionbar: false` to turn it off. Legacy `&` color codes are supported.

If you change the message or toggle, run `/soundstream reload`.

## Other configuration

```yaml
sounds-directory: sounds
max-file-bytes: 1073741824
max-streams-per-player: 4
window-bytes: 4194304
chunk-bytes: 32768
send-bytes-per-tick: 1048576
default-volume: 1.0
default-range: 32.0
```

`max-file-bytes` accepts up to 1073741824 (1 GiB). Audio is streamed from disk, so the limit does not raise the plugin's memory use. `window-bytes` is the maximum encoded audio buffered per stream on the client; the server refills it as the client reports consumed bytes with CREDIT. `chunk-bytes` is the transport frame size and `send-bytes-per-tick` caps per-stream throughput.

## Mod Menu settings

If [Mod Menu](https://modrinth.com/mod/modmenu) is installed, SoundStream shows a settings screen there. Settings are saved to `config/soundstream.json`:

- **Playback** — enable or mute the client.
- **Client Volume** — extra multiplier on top of the server volume and Minecraft master volume.
- **Prebuffer** — 50, 100, 200, or 400 ms of PCM before playback starts. Lower starts faster; higher is safer on slow systems.

The prebuffer setting applies to newly started streams. Mod Menu is optional; without it, the defaults are used and the JSON file can still be edited manually.

## Plugin API

Full API documentation: [`docs/API.md`](docs/API.md).

Other plugins can stream audio to any online player. Add `depend: [SoundStream]` to your `plugin.yml`, then compile against the SoundStream plugin jar:

```gradle
dependencies {
    compileOnly files('libs/SoundStream-Paper-1.1.0.jar')
}
```

Look up the service through the helper or Bukkit:

```java
import dev.breiwalker.soundstream.paper.api.SoundStreamApi;

SoundStreamService service = SoundStreamApi.require();

// One player
service.play(player, "music/theme.ogg", PlayOptions.twoD().volume(0.8f))
        .thenAccept(handle -> {
            // handle.id(), handle.setVolume(...), handle.moveTo(...), handle.stop()
        });

// Several players
service.play(List.of(alice, bob), "effects/ding.ogg", PlayOptions.twoD());

// Everyone online
service.playAll("music/event.ogg", PlayOptions.twoD().loop(true));

// 3D
service.play(player, "effects/explosion.ogg",
        PlayOptions.positional(location).volume(1.0f).range(48.0f));
```

`play` returns a `CompletableFuture<StreamHandle>`; the future completes after the file loads and the stream is opened. `PlayOptions` supports `twoD()`, `positional(location)`, `range(...)`, `volume(...)`, and `loop(true)`. `moveTo` only affects positional streams.

The API classes live inside the SoundStream plugin jar. Do not shade or relocate them; the running SoundStream plugin provides them.

## Modrinth artwork

- Project icon: `thumbnails/modrinth-icon-512.png` (512Ã—512; use `modrinth-icon.png` for 1024Ã—1024)
- Gallery banner: `thumbnails/modrinth-banner.png` (1920Ã—1080)

## Build

Build the single Fabric client jar and the Paper plugin:

```powershell
.\build-all.bat
```

Outputs:

- `dist/SoundStream-1.1.0.jar` (Minecraft 26.1.2 - 26.3)
- `dist/SoundStream-Paper-1.1.0.jar` (plugin + API classes)
- `dist/SoundStream-Paper-1.1.0-javadoc.jar` (API Javadocs)

A normal `.\gradlew.bat build` builds the same client jar plus the Paper plugin. The client is compiled against Minecraft 26.3; the classes it uses are binary-identical across 26.1.2, 26.2, and 26.3, which is why one jar works for all three.

Run a development server for 26.3 with:

```powershell
.\gradlew.bat :paper:runServer "-PpaperTestVersion=26.3"
```

The default run target is Paper 26.1.2.

## Limits and behavior

- Progressive playback with a short lead-in buffer; large files never require a whole-file allocation.
- Maximum file size is server-configurable up to 1 GiB; the client enforces the same hard limit.
- At most four concurrent streams per player by default.
- Memory per stream is bounded by the negotiated `window-bytes`, regardless of file length or looping.
- Looping is continuous: the server re-streams each pass and sends LOOP boundaries, so even 1 GiB files loop with bounded client memory.
- 2D streams preserve up to two channels; 3D streams are downmixed to mono, then attenuated and panned.
- Audio does not touch Minecraft's OpenAL context or resource-pack sound engine.
- The client applies server volume multiplied by Minecraft's master volume.
- Custom payloads are unencrypted but travel inside the normal authenticated Minecraft connection.

## Changelog

### 1.1.0

- One Fabric jar covers Minecraft 26.1.2, 26.2, and 26.3 instead of three separate jars.
- Streams files of hundreds of megabytes (up to 1 GiB) without loading them into server or client heap.
- Faster time-to-first-sound: 200 ms PCM prebuffer, immediate start for cached sounds, and no extra server-tick deferral.
- Optional Mod Menu settings screen for playback, client volume, and prebuffer; settings persist in `config/soundstream.json`.
- Wire protocol v3: CREDIT is mandatory and bounds the client's encoded buffer; LOOP boundaries split continuously re-streamed loop passes.
- The Paper plugin still accepts protocol v1 and v2 clients and adapts credit and loop behavior per player.
- Looping is continuous streaming instead of retaining the whole file. The server re-sends each pass and marks pass boundaries; client memory stays bounded and the loop-size cap is gone.
- Server streams source audio from disk; `SoundLibrary` now caches only validated metadata.
- Client uses a bounded encoded buffer and grants CREDIT as playback consumes data, pacing transfer.

### 1.0.2

- Multi-version Fabric builds for Minecraft 26.1.2, 26.2, and 26.3.
- Progressive Spotify-style playback: decoding and playback start after a short prebuffer while the rest of the song downloads.
- The complete file is still retained for looping and final validation.
- Fixed stop handling so output halts immediately and the audio thread exits.

### 1.0.1

- Smoother playback for loud, dense tracks: prebuffered output, 20 ms declick fade-in, 8% headroom, larger Java Sound buffers, larger PCM queue, and higher-priority audio/decode threads.
- Faster transfer: 32 KiB frames, 1 MiB per-tick server burst, direct DATA encoding, and zero-copy DATA assembly on the client.
- Stop now halts the output thread and discards buffered audio immediately, including during prebuffering.
- API classes are included in the Paper plugin jar; multi-player `play` / `playAll` helpers are available.
- Removed `SNAPSHOT` from release versioning.

### 1.0.0

- Initial Fabric client and Paper plugin release.


