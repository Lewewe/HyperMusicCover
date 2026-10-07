package com.os4.musiccover;

/** A queue prediction belongs to one push generation and its still-confirmed outgoing song. */
final class ArtworkPredictionPolicy {
    private ArtworkPredictionPolicy() {}

    static boolean canSpeculate(String packageName) {
        // Spotify can publish a queue whose active item still belongs to the previous song.
        return !"com.spotify.music".equals(packageName);
    }

    static boolean canConfirm(int generation, int currentGeneration,
                              boolean matchingPrediction, boolean matchingTrack) {
        return generation == currentGeneration && matchingPrediction && matchingTrack;
    }

    static boolean canPublish(int generation, int currentGeneration, boolean confirmedTrack,
                              boolean predictedTrack, boolean outgoingTrack) {
        return generation == currentGeneration
                && (confirmedTrack || predictedTrack && outgoingTrack);
    }
}
