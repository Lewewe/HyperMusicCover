package com.os4.musiccover;

/** A queue prediction belongs to one push generation and its still-confirmed outgoing song. */
final class ArtworkPredictionPolicy {
    private ArtworkPredictionPolicy() {}

    static boolean canPublish(int generation, int currentGeneration, boolean confirmedTrack,
                              boolean predictedTrack, boolean outgoingTrack) {
        return generation == currentGeneration
                && (confirmedTrack || predictedTrack && outgoingTrack);
    }
}
