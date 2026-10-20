# SoundStream Wire Protocol v3

Channel: `soundstream:main`

All integers are big-endian. The server streams the complete encoded song in DATA frames. Chunking is transport-only; the client keeps a bounded encoded buffer (the negotiated window), grants CREDIT as the decoder consumes bytes, and can start progressive playback after a short prebuffer. Files up to 1 GiB are supported; the client never allocates the whole song. Looped streams are re-sent continuously, one pass at a time, separated by LOOP markers; they use the same bounded buffer and have no size limit.

## Header

Every frame starts with:

| Field | Type |
|---|---|
| magic | `u16` = `0x5353` |
| version | `u8` = `3` |
| type | `u8` |
| streamId | `i32` (`0` for handshake messages) |

Strings are `u16` byte length plus UTF-8, maximum 256 bytes. Implementations must reject truncated frames, unknown types, unknown flags, oversized strings/chunks, and trailing bytes.

The Paper plugin accepts frames with version `1`, `2`, or `3` and replies with the version the client completed its handshake with. Version `1` clients receive the whole file with no CREDIT; version `2` clients use CREDIT; only version `3` clients use LOOP boundaries and continuous loop streaming.

## Client to server

| Type | Name | Body |
|---|---|---|
| `0x01` | HELLO | `u32 windowBytes`, `u16 maxChunkBytes`, `u8 maxStreams`, `u32 fullBufferBytes`, `u32 loopCacheBytes`, `string clientVersion` |
| `0x02` | CREDIT | `u32 bytes` |
| `0x03` | DONE | `u8 reason`, `string message` |

`fullBufferBytes` and `loopCacheBytes` are reserved. `windowBytes` is the maximum encoded audio the client buffers for one stream; the server grants that much initial credit and refills it from CREDIT messages. The server sends DATA only while credit is available.

## Server to client

| Type | Name | Body |
|---|---|---|
| `0x81` | HELLO_ACK | `u16 chunkBytes`, `u32 windowBytes`, `u8 maxStreams`, `string serverVersion` |
| `0x82` | HELLO_QUERY | empty |
| `0x10` | OPEN | `u8 codec`, `u8 flags`, `u32 totalBytes`, `f32 volume`, `f32 range`, `f32 x`, `f32 y`, `f32 z` |
| `0x11` | DATA | `u32 sequence`, `u16 length`, `byte[length]` |
| `0x12` | END | empty |
| `0x13` | UPDATE | `f32 volume`, `f32 x`, `f32 y`, `f32 z` |
| `0x14` | CLOSE | `u8 reason`, `string message` |
| `0x15` | LOOP | empty; marks the end of one loop pass |

Codecs: `1` = Ogg Vorbis, `2` = MP3. Flags: bit 0 positional, bit 1 loop. Data sequences start at 0 and increment by one for the lifetime of the stream.

## Handshake

Paper only sends server-to-client plugin messages after it has seen the client's `minecraft:register` channel list. Fabric invokes its JOIN callback before sending that registration, so a client must delay HELLO by a couple of client ticks. The reference client retries every five seconds until HELLO_ACK arrives.

1. Client sends HELLO.
2. Server replies HELLO_ACK.
3. Server may send HELLO_QUERY after a reload; the client answers with HELLO.
4. Server sends OPEN, then DATA frames while the stream has credit.
5. Non-loop streams end with END; loop streams send LOOP after every complete pass and continue with the next pass.
6. Client validates codec, sequence, and exact byte count; playback may begin before the end after the prebuffer.
7. Client sends CREDIT as the decoder consumes bytes so the server can continue sending.
8. Client sends DONE when playback finishes, is stopped, or fails.

## Transfer behavior

- Server chunk size is negotiated down to the client's maximum (default 32 KiB).
- The server sends at most `send-bytes-per-tick` per stream (1 MiB by default), and only while the stream has credit.
- The client starts with the negotiated window as its credit, then sends CREDIT for bytes the decoder has consumed. This bounds memory independently of file size.
- Loop streams are continuously re-sent: after `totalBytes` of DATA the server sends LOOP, rewinds the file, and sends the next pass without closing the stream.
- The client begins playback after a short lead-in.
- END is sent only for non-loop streams after the final byte.
- Server-initiated CLOSE stops playback immediately.
- DONE releases the server session and stream slot.

## Validation

- Only `.ogg` (Vorbis) and `.mp3` are accepted.
- Ogg Opus and non-Vorbis Ogg are rejected.
- Extension and container magic must agree.
- Paths must resolve inside the configured sounds directory, including real-path checks for symlink escapes.
- Client enforces maximum file size (1 GiB hard cap), chunk size, sequence, per-pass byte count, concurrent stream count.
- Client enforces the negotiated encoded window; a server that exceeds it fails the stream as a protocol error.
- A LOOP marker must be preceded by exactly `totalBytes` of DATA for that pass.
- Non-finite volume, range, or coordinates are rejected.

## Reasons

| Code | Meaning |
|---|---|
| 0 | finished |
| 1 | stopped |
| 2 | decode error |
| 3 | audio unavailable |
| 4 | protocol error |
| 5 | unsupported |
| 6 | file error |
| 7 | too many streams |
| 8 | client unsupported |
| 9 | timeout |
