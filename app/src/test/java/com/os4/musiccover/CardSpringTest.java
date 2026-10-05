package com.os4.musiccover;

import org.junit.Test;

import static org.junit.Assert.*;

public class CardSpringTest {
    @Test public void transitionTreatmentTracksTheActualRebound() {
        CardSpring spring = new CardSpring();
        spring.snap(CardSpring.PAUSED);
        float previous = CardSpring.shrinkFraction(spring.value);
        assertEquals(1f, previous, 0.0001f);
        for (int i = 0; i < 120 && spring.value < CardSpring.PLAYING; i++) {
            spring.step(CardSpring.PLAYING, 1f / 120f);
            float treatment = CardSpring.shrinkFraction(spring.value);
            assertTrue(treatment <= previous);
            previous = treatment;
        }
        assertEquals(0f, previous, 0.0001f);
    }

    @Test public void transitionTreatmentDoesNotExceedItsRangeDuringSpringOvershoot() {
        assertEquals(1f, CardSpring.shrinkFraction(0.85f), 0f);
        assertEquals(0f, CardSpring.shrinkFraction(1.05f), 0f);
        assertEquals(0f, CardSpring.shrinkFraction(Float.NaN), 0f);
        assertEquals(0.5f, CardSpring.shrinkFraction(0.95f), 0.0001f);
    }

    @Test public void playbackHasSmallReboundAndSettles() {
        CardSpring spring = new CardSpring();
        float smallest = spring.value;
        for (int i = 0; i < 120; i++) {
            spring.step(CardSpring.PAUSED, 1f / 120f);
            smallest = Math.min(smallest, spring.value);
        }
        assertTrue(smallest < CardSpring.PAUSED);
        assertEquals(CardSpring.PAUSED, spring.value, 0.001f);

        float largest = spring.value;
        for (int i = 0; i < 120; i++) {
            spring.step(CardSpring.PLAYING, 1f / 120f);
            largest = Math.max(largest, spring.value);
        }
        assertTrue(largest > CardSpring.PLAYING);
        assertEquals(CardSpring.PLAYING, spring.value, 0.001f);
    }

    @Test public void movementIsIndependentOfDisplayRefreshRate() {
        CardSpring sixty = new CardSpring(), oneTwenty = new CardSpring();
        for (int i = 0; i < 24; i++) sixty.step(CardSpring.PAUSED, 1f / 60f);
        for (int i = 0; i < 48; i++) oneTwenty.step(CardSpring.PAUSED, 1f / 120f);
        assertEquals(sixty.value, oneTwenty.value, 0.0001f);
        assertEquals(sixty.velocity, oneTwenty.velocity, 0.0001f);
    }
}
