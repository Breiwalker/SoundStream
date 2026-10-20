package dev.breiwalker.soundstream.paper.audio;

public record SoundAsset(String id, byte[] data, int codec) {
    public SoundAsset {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Sound data is empty");
        }
    }
}
