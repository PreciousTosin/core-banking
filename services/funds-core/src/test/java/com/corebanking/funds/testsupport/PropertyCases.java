package com.corebanking.funds.testsupport;

import java.util.SplittableRandom;
import java.util.stream.LongStream;

public final class PropertyCases {
    private static final long[] POSITIVE_MINOR_UNIT_BOUNDARIES = {
        1,
        2,
        99,
        100,
        1_000_000_000,
        Long.MAX_VALUE / 2,
        Long.MAX_VALUE - 1
    };
    private static final long[] STATE_MACHINE_MINOR_UNIT_BOUNDARIES = {
        1,
        2,
        99,
        100,
        1_000_000_000,
        Long.MAX_VALUE / 2
    };

    private PropertyCases() {
    }

    public static LongStream positiveMinorUnits(long seed, int randomCases) {
        return LongStream.concat(
            LongStream.of(POSITIVE_MINOR_UNIT_BOUNDARIES),
            new SplittableRandom(seed).longs(randomCases, 1, 1_000_000_001L));
    }

    public static long stateMachineMinorUnits(SplittableRandom random, long sampleIndex) {
        int slot = Math.floorMod(sampleIndex, STATE_MACHINE_MINOR_UNIT_BOUNDARIES.length + 1);
        if (slot < STATE_MACHINE_MINOR_UNIT_BOUNDARIES.length) {
            return STATE_MACHINE_MINOR_UNIT_BOUNDARIES[slot];
        }
        return random.nextLong(1, 1_000_000_001L);
    }
}
