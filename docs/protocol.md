# SoundStream Wire Protocol v1

Channel: `soundstream:main`

All integers are big-endian. The server sends the complete encoded song in DATA frames. Chunking is transport-only; the client allocates `totalBytes`, appends every frame, and can start progressive playback after a short prebuffer. The complete file is retained for looping and final validation.

## Header

Every frame starts with:

| Field | Type |
|---|---|
| magic | `u16` = `0x5353` |
| version | `u8` = `1` |
| type | `u8` |
| streamId | `i32` (`0` for handshake messages) |

Strings are `u16` byte length plus UTF-8, maximum 256 bytes. Implementations must reject truncated frames, unknown types, unknown flags, oversized strings/chunks, and trailing bytes.

## Client to server

| Type | Name | Body |
|---|---|---|
| `0x01` | HELLO | `u32 windowBytes`, `u16 maxChunkBytes`, `u8 maxStreams`, `u32 fullBufferBytes`, `u32 loopCacheBytes`, `string clientVersion` |
| `0x02` | CREDIT | `u32 bytes` (reserved; current clients do not send it) |
| `0x03` | DONE | `u8 reason`, `string message` |

`windowBytes`, `fullBufferBytes`, and CREDIT are reserved for compatibility. The v1 server sends the whole song without waiting for credit.

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

Codecs: `1` = Ogg Vorbis, `2` = MP3. Flags: bit 0 positional, bit 1 loop. Data sequences start at 0 and increment by one.

## Handshake

Paper only sends server-to-client plugin messages after it has seen the client's `minecraft:register` channel list. Fabric invokes its JOIN callback before sending that registration, so a client must delay HELLO by a couple of client ticks. The reference client retries every five seconds until HELLO_ACK arrives.

1. Client sends HELLO.
2. Server replies HELLO_ACK.
3. Server may send HELLO_QUERY after a reload; the client answers with HELLO.
4. Server sends OPEN, DATA frames, then END.
5. Client validates codec, sequence, and exact byte count; playback may begin before END after the prebuffer.
6. Client continues decoding while the rest of the file arrives.
7. Client sends DONE when playback finishes, is stopped, or fails.

## Transfer behavior

- Server chunk size is negotiated down to the client's maximum (default 32 KiB).
- The server sends up to 1 MiB per server tick, so a large song does not block the main thread in one burst.
- The client buffers the entire file in memory and begins playback after a short lead-in.
- END is sent only after the final byte.
- Server-initiated CLOSE stops playback immediately.
- DONE releases the server session and stream slot.

## Validation

- Only `.ogg` (Vorbis) and `.mp3` are accepted.
- Ogg Opus and non-Vorbis Ogg are rejected.
- Extension and container magic must agree.
- Paths must resolve inside the configured sounds directory, including real-path checks for symlink escapes.
- Client enforces maximum file size, chunk size, sequence, total byte count, concurrent stream count, and loop-cache limit.
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
