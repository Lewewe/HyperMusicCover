package com.os4.musiccover;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Standalone transport-isolated tests; only tools/run-online-lyric-upgrade-tests.py compiles these fixtures. */
public final class OnlineLyricsUpgradeTest {
    private static int checks;
    private static final List<LyricLine> BASELINE = lines(false, "Hear the prophecy", 0, "预言");
    private static final List<LyricLine> WORDS = lines(true, "Hear