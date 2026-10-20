# SoundStream API

SoundStream exposes a Bukkit service so other Paper plugins can stream `.ogg` and `.mp3` files to players.

The API classes are packaged inside `SoundStream-Paper-1.1.0.jar`. There is no separate API artifact.

## Requirements

- SoundStream plugin installed and enabled
- `depend: [SoundStream]` in your `plugin.yml`
- Compile against `SoundStream-Paper-1.1.0.jar`
- Do **not** shade or relocate the `dev.breiwalker.soundstream.paper.api` classes; the running SoundStream plugin provides them.

```yaml
name: MyPlugin
version: 1.0.0
main: com.example.MyPlugin
api-version: '26.1'
depend: [SoundStream]
```

```gradle
dependencies {
    compileOnly files('libs/SoundStream-Paper-1.1.0.jar')
}
```

## Getting the service

```java
import dev.breiwalker.soundstream.paper.api.SoundStreamApi;
import dev.breiwalker.soundstream.paper.api.SoundStreamService;

SoundStreamService soundStream = SoundStreamApi.require();
```

`SoundStreamApi.get()` returns `null` when SoundStream is unavailable. `require()` throws `IllegalStateException` instead.

You can also resolve it directly:

```java
SoundStreamService soundStream = getServer().getServicesManager()
        .getRegistration(SoundStreamService.class)
        .getProvider();
```

## File ids

All files live under:

```
plugins/SoundStream/sounds/
```

Pass a relative id:

```java
soundStream.play(player, "music/theme.ogg", PlayOptions.twoD());
soundStream.play(player, "effects/ding.mp3", PlayOptions.twoD());
```

Absolute paths, `..` traversal, symlink escapes, and non-`.ogg`/`.mp3` files are rejected.

Only Ogg Vorbis and MP3 are supported. Ogg Opus is rejected.

## Playing to one player

```java
soundStream.play(player, "music/theme.ogg", PlayOptions.twoD().volume(0.8f))
        .thenAccept(handle -> {
            player.sendMessage("Stream id: " + handle.id());
        })
        .exceptionally(throwable -> {
            player.sendMessage("Could not play audio: " + throwable.getCause().getMessage());
            return null;
        });
```

`play` is asynchronous. The future completes after the file is validated and the stream is opened. Callbacks run on the server thread.

## Playing to several players

```java
soundStream.play(List.of(alice, bob, charlie), "effects/alert.ogg",
        PlayOptions.twoD().volume(0.7f));
```

Players that fail individually are skipped. The returned future still succeeds and contains handles for the players that started successfully.

All online players:

```java
soundStream.playAll("music/event.ogg", PlayOptions.twoD().loop(true));
```

## 3D positional audio

```java
PlayOptions options = PlayOptions.positional(location)
        .volume(1.0f)
        .range(48.0f);

soundStream.play(player, "effects/explosion.ogg", options)
        .thenAccept(handle -> {
            // Later, while the stream is active:
            handle.moveTo(newLocation);
        });
```

The client attenuates by distance and pans relative to the camera. `moveTo` only affects streams created with `PlayOptions.positional(...)`.

## Stopping

```java
handle.stop();
soundStream.stop(player, streamId);
soundStream.stopAll(player);
soundStream.stopAll(Bukkit.getOnlinePlayers());
```

Stopping is immediate and releases the player's stream slot.

## `PlayOptions`

| Method | Meaning |
|---|---|
| `PlayOptions.twoD()` | Non-positional stream |
| `PlayOptions.positional(Location)` | Positional stream at a fixed location |
| `.volume(float)` | `0..1`, clamped |
| `.range(float)` | Audible range in blocks for 3D streams |
| `.loop(boolean)` | Loop the complete buffered file |

`PlayOptions` is mutable and chainable.

## `StreamHandle`

| Method | Meaning |
|---|---|
| `id()` | Server-assigned stream id |
| `stop()` | Stop immediately |
| `setVolume(float)` | Change volume for the rest of the stream |
| `moveTo(Location)` | Move a positional stream |
| `isActive()` | Whether the server still has an active session |

Methods are safe to call from any thread; the implementation schedules work on the server thread.

## Failure cases

The future from `play` completes exceptionally when:

- the player does not have the SoundStream client mod
- the player has reached the concurrent stream limit
- the file is missing or outside the sounds directory
- the extension/container is unsupported
- the file exceeds `max-file-bytes`

Use `isAvailable(player)` to check whether a client completed the SoundStream handshake:

```java
if (!soundStream.isAvailable(player)) {
    player.sendMessage("Install SoundStream to hear custom audio.");
    return;
}
```

## Threading and lifecycle

- `play`, `stop`, and `stopAll` may be called from any thread.
- The returned future and `StreamHandle` callbacks run on the server thread.
- Register the service after SoundStream has enabled; use `depend: [SoundStream]`.
- Streams are cleaned up when the player disconnects, the plugin disables, or the stream finishes.
- The API bypasses command permissions. Check your own permissions before calling it.

## Limits and behavior

- Whole files are streamed from disk and decoded progressively.
- Default maximum file size: 1 GiB (streamed; it does not consume that much memory).
- Client encoded-audio memory per stream is bounded by the negotiated window (4 MiB by default).
- Default maximum concurrent streams per player: 4.
- Looping re-streams each pass continuously, so there is no loop-size limit and no full-file client buffer.
- Server volume is multiplied by the client's Minecraft master volume.
- Clients without the mod can receive a configurable action bar instead.
- No pause, seek, queue, or crossfade API exists in 1.1.0.

## Complete example

```java
package com.example;

import dev.breiwalker.soundstream.paper.api.PlayOptions;
import dev.breiwalker.soundstream.paper.api.SoundStreamApi;
import dev.breiwalker.soundstream.paper.api.SoundStreamService;
import org.bukkit.plugin.java.JavaPlugin;

public final class ExamplePlugin extends JavaPlugin {
    private SoundStreamService soundStream;

    @Override
    public void onEnable() {
        soundStream = SoundStreamApi.require();
    }

    public void playJoinTheme(org.bukkit.entity.Player player) {
        if (!soundStream.isAvailable(player)) {
            return;
        }
        soundStream.play(player, "music/join.ogg",
                PlayOptions.twoD().volume(0.6f).loop(true));
    }
}
```

## Javadocs

Javadoc comments are present on the public API types. Build them with:

```powershell
.\gradlew.bat :paper:javadoc
```

Output: `paper/build/docs/javadoc/`


