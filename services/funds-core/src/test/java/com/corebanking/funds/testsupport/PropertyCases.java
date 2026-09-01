package com.corebanking.funds.testsupport;

import java.util.SplittableRandom;
import java.util.stream.LongStream;

/**
 * Deterministic amount generators for the property and state-machine tests. Every generator is
 * driven by a caller-supplied seed or SplittableRandom and never by system randomness, so a
 * failure reproduces from the seed alone. Random amounts stay in [1, 1_000_000_000] minor units;
 * the fixed boundary sets add the edges that a uniform draw would practically never hit.
 */
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
    // Omits Long.MAX_VALUE - 1: state-machine amounts accumulate on the same accounts across a
    // history, and Long.MAX_VALUE / 2 already reaches the MONETARY_OVERFLOW outcome (two of them
    // plus the smaller boundaries exceed the signed range).
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

    /**
     * The seven fixed boundaries first, then {@code randomCases} draws from a fresh
     * SplittableRandom(seed); the same seed and count always yield the same sequence.
     */
    public static LongStream positiveMinorUnits(long seed, int randomCases) {
        return LongStream.concat(
            LongStream.of(POSITIVE_MINOR_UNIT_BOUNDARIES),
            new SplittableRandom(seed).longs(randomCases, 1, 1_000_000_001L));
    }

    /**
     * Amount for one step of a generated history. Cycles through the boundary set by sample index
     * and takes a random draw on every seventh slot, so the edge amounts recur throughout every
     * history instead of depending on the seed. Boundary slots do not consume from {@code random};
     * the caller shares that stream with its operation-choice draws.
     */
    public static long stateMachineMinorUnits(SplittableRandom random, long sampleIndex) {
        int slot = Math.floorMod(sampleIndex, STATE_MACHINE_MINOR_UNIT_BOUNDARIES.length + 1);
        if (slot < STATE_MACHINE_MINOR_UNIT_BOUNDARIES.length) {
            return STATE_MACHINE_MINOR_UNIT_BOUNDARIES[slot];
        }
        return random.nextLong(1, 1_000_000_001L);
    }
}
