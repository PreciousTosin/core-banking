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

    private PropertyCases() {
    }

    public static LongStream positiveMinorUnits(long seed, int randomCases) {
        return LongStream.concat(
            LongStream.of(POSITIVE_MINOR_UNIT_BOUNDARIES),
            new SplittableRandom(seed).longs(randomCases, 1, 1_000_000_001L));
    }
}
