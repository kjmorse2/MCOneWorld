// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index.spike.bench;

import java.util.Arrays;

/** Latency samples in nanoseconds, with nearest-rank percentiles. */
final class Stats {
  private long[] samples = new long[1024];
  private int size;

  void add(long nanos) {
    if (size == samples.length) {
      samples = Arrays.copyOf(samples, size * 2);
    }
    samples[size++] = nanos;
  }

  int count() {
    return size;
  }

  long total() {
    long sum = 0;
    for (int i = 0; i < size; i++) {
      sum += samples[i];
    }
    return sum;
  }

  long percentile(double p) {
    if (size == 0) {
      return 0;
    }
    long[] sorted = Arrays.copyOf(samples, size);
    Arrays.sort(sorted);
    int rank = (int) Math.ceil(p / 100.0 * size);
    return sorted[Math.max(0, Math.min(size - 1, rank - 1))];
  }

  double mean() {
    return size == 0 ? 0 : (double) total() / size;
  }
}
