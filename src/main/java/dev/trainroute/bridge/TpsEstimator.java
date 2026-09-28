package dev.trainroute.bridge;

import java.util.ArrayDeque;
import java.util.OptionalDouble;

/** Rolling weighted TPS from world-age deltas, never from the daylight clock or FPS. */
public final class TpsEstimator {
    private record Sample(long ticks, long nanos) { }
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private long lastTicks, lastNanos;
    private boolean initialized;

    public void reset() { samples.clear(); initialized = false; }

    public void record(long ticks, long nowNanos) {
        if (initialized) {
            long dt = ticks - lastTicks, dn = nowNanos - lastNanos;
            if (dt < 0 || dn <= 0 || dn > 120_000_000_000L) samples.clear();
            else {
                samples.addLast(new Sample(dt, dn));
                while (samples.size() > 60) samples.removeFirst();
            }
        }
        lastTicks = ticks;
        lastNanos = nowNanos;
        initialized = true;
    }

    public OptionalDouble estimate(long nowNanos) {
        if (!initialized || samples.isEmpty() || nowNanos - lastNanos > 30_000_000_000L)
            return OptionalDouble.empty();
        long ticks = 0, nanos = 0;
        for (Sample s : samples) { ticks += s.ticks; nanos += s.nanos; }
        // Conventional 20 TPS ceiling; custom /tick rates are outside this estimate's scope.
        return OptionalDouble.of(Math.min(20.0, ticks * 1_000_000_000.0 / nanos));
    }
}
