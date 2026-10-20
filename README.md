# SoundStream

![SoundStream](thumbnails/modrinth-banner.png)

SoundStream lets a Paper server send `.ogg` and `.mp3` audio to Fabric clients. The client receives the complete song, verifies it, and plays it locally. No resource pack, HTTP server, or client-side sound registration is involved.

The full file is always sent. It is split into small custom-payload frames only because Minecraft limits plugin-message size; the client assembles the whole file before playback starts.

## Requirements

| Component | Supported version |
|---|---|
| Minecraft client | **26.3** (Fabric) |
| Fabric Loader | **0.20.0+** |
| Fabric API | **0.164.0+26.3** |
| Java | **25** |
| Paper server | **26.1.2 and newer** |

The client mod targets Minecraft 26.3 only. It will not load on 26.1.x or 26.2.x clients. The Paper plugin is compiled against the 26.1.2 API and remains compatible with newer Paper releases.

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
max-file-bytes: 33554432
max-loop-bytes: 8388608
max-streams-per-player: 4
window-bytes: 524288
chunk-bytes: 32768
default-volume: 1.0
default-range: 32.0
```

`window-bytes` is retained for protocol compatibility and no longer paces transfer. `chunk-bytes` is the transport frame size.

## Plugin API

Other plugins can stream audio to any online player. Add `depend: [SoundStream]` to your `plugin.yml`, then compile against the SoundStream plugin jar:

```gradle
dependencies {
    compileOnly files('libs/SoundStream-Paper-1.0.1.jar')
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

- Project icon: `thumbnails/modrinth-icon-512.png` (512×512; use `modrinth-icon.png` for 1024×1024)
- Gallery banner: `thumbnails/modrinth-banner.png` (1920×1080)

## Build

```powershell
.\gradlew.bat build
```

Outputs:

- `build/libs/SoundStream-<version>.jar` (Fabric client)
- `paper/build/libs/SoundStream-Paper-<version>.jar` (Paper plugin + API classes)

Run a development server for 26.3 with:

```powershell
.\gradlew.bat :paper:runServer "-PpaperTestVersion=26.3"
```

The default run target is Paper 26.1.2.

## Limits and behavior

- Whole-file transfer and whole-file validation before playback.
- Maximum file size is server-configurable; the client also enforces a hard limit.
- At most four concurrent streams per player by default.
- Looping reuses the complete buffered file and is limited by `max-loop-bytes`.
- 2D streams preserve up to two channels; 3D streams are downmixed to mono, then attenuated and panned.
- Audio does not touch Minecraft's OpenAL context or resource-pack sound engine.
- The client applies server volume multiplied by Minecraft's master volume.
- Custom payloads are unencrypted but travel inside the normal authenticated Minecraft connection.

## Changelog

### 1.0.1

- Smoother playback for loud, dense tracks: prebuffered output, 20 ms declick fade-in, 8% headroom, larger Java Sound buffers, larger PCM queue, and higher-priority audio/decode threads.
- Faster transfer: 32 KiB frames, 1 MiB per-tick server burst, direct DATA encoding, and zero-copy DATA assembly on the client.
- Stop now halts the output thread and discards buffered audio immediately, including during prebuffering.
- API classes are included in the Paper plugin jar; multi-player `play` / `playAll` helpers are available.
- Removed `SNAPSHOT` from release versioning.

### 1.0.0

- Initial Fabric client and Paper plugin release.

