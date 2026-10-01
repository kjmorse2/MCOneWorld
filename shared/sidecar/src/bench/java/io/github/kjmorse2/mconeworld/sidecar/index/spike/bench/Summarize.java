// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index.spike.bench;

import io.github.kjmorse2.mconeworld.fixtures.index.ChunkDatasets;
import io.github.kjmorse2.mconeworld.sidecar.index.spike.Candidate;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Turns raw result lines into Markdown: the environment, a budget verdict table (B1 to B12 from
 * {@code budgets.md}), and one detail table per dataset. Later lines for the same (candidate,
 * dataset, metric) replace earlier ones, so several result files can be combined in order.
 *
 * <p>Usage: {@code Summarize <results.jsonl>... > summary.md}
 */
public final class Summarize {
  private static final Pattern MEMBER =
      Pattern.compile("\"([A-Za-z0-9.]+)\":(\"((?:[^\"\\\\]|\\\\.)*)\"|[-0-9.eE+]+|null)");

  /** candidate → dataset → metric → fields */
  private final Map<String, Map<String, Map<String, Map<String, String>>>> data =
      new LinkedHashMap<>();

  private final List<Map<String, String>> environments = new ArrayList<>();

  private Summarize() {}

  public static void main(String[] args) throws Exception {
    Summarize s = new Summarize();
    for (String file : args) {
      for (String line : Files.readAllLines(Path.of(file), StandardCharsets.UTF_8)) {
        if (!line.isBlank()) {
          s.add(parse(line));
        }
      }
    }
    System.out.print(s.render());
  }

  static Map<String, String> parse(String line) {
    Map<String, String> fields = new LinkedHashMap<>();
    Matcher m = MEMBER.matcher(line);
    while (m.find()) {
      String value = m.group(3) != null ? m.group(3).replace("\\\"", "\"") : m.group(2);
      fields.put(m.group(1), value);
    }
    return fields;
  }

  private void add(Map<String, String> f) {
    if ("env".equals(f.get("phase"))) {
      environments.add(f);
      return;
    }
    String candidate = f.getOrDefault("candidate", "?");
    String dataset = f.getOrDefault("dataset", "?");
    String metric = f.getOrDefault("metric", "?");
    if (metric.equals("case.status")) {
      metric = "status." + f.getOrDefault("phase", "?");
    }
    data.computeIfAbsent(candidate, k -> new LinkedHashMap<>())
        .computeIfAbsent(dataset, k -> new LinkedHashMap<>())
        .put(metric, f);
  }

  private @Nullable String field(String candidate, String dataset, String metric, String name) {
    Map<String, String> f =
        data.getOrDefault(candidate, Map.of()).getOrDefault(dataset, Map.of()).get(metric);
    return f == null ? null : f.get(name);
  }

  private double number(String candidate, String dataset, String metric, String name) {
    String v = field(candidate, dataset, metric, name);
    if (v == null || v.equals("null")) {
      return Double.NaN;
    }
    try {
      return Double.parseDouble(v);
    } catch (NumberFormatException e) {
      return Double.NaN;
    }
  }

  /** Bytes written to disk per applied update mutation (after − before, over 25 000 mutations). */
  private void derive() {
    for (var byDataset : data.values()) {
      for (var metrics : byDataset.values()) {
        Map<String, String> before = metrics.get("disk.bytes");
        Map<String, String> after = metrics.get("disk.afterUpdates.bytes");
        if (before != null && after != null) {
          double growth =
              (Double.parseDouble(after.getOrDefault("value", "0"))
                      - Double.parseDouble(before.getOrDefault("value", "0")))
                  / 25_000.0;
          metrics.put("disk.growthPerMutation", Map.of("value", Double.toString(growth)));
        }
      }
    }
  }

  private String render() {
    derive();
    StringBuilder out = new StringBuilder();
    for (Map<String, String> env : environments) {
      out.append("- Run `")
          .append(env.get("run"))
          .append("`: commit `")
          .append(env.get("commit"))
          .append("`")
          .append("yes".equals(env.get("dirty")) ? " (uncommitted changes)" : "")
          .append(", ")
          .append(env.get("java"))
          .append(", ")
          .append(env.get("os"))
          .append(", ")
          .append(env.get("cpu"))
          .append(" (")
          .append(env.get("processors"))
          .append(" threads), median 4 KiB fsync ")
          .append(env.get("fsyncMillisP50"))
          .append(" ms\n");
    }
    out.append("\n### Budget verdicts\n\n");
    out.append(
        "Worst value across datasets unless the budget names one. ✅ pass, ❌ fail, — not measured.\n\n");
    List<String> candidates = new ArrayList<>();
    for (Candidate c : Candidate.values()) {
      if (data.containsKey(c.id())) {
        candidates.add(c.id());
      }
    }
    out.append("| Budget |");
    candidates.forEach(c -> out.append(" `").append(c).append("` |"));
    out.append("\n|---|");
    candidates.forEach(c -> out.append("---|"));
    out.append('\n');
    verdictRow(
        out,
        candidates,
        "B1 `millions` under -Xmx256m (gate)",
        c -> {
          String b = field(c, "millions", "status.build", "value");
          String q = field(c, "millions", "status.query", "value");
          if (b == null) {
            return null;
          }
          boolean ok = "ok".equals(b) && "ok".equals(q);
          return verdict(ok, ok ? "completed" : abbreviate(b.equals("ok") ? String.valueOf(q) : b));
        });
    verdictRow(
        out,
        candidates,
        "B2 corruption: silent wrong reads (gate)",
        c -> {
          double silent = number(c, "friend-server", "corruption.read.silent", "value");
          if (Double.isNaN(silent)) {
            return null;
          }
          double trials = number(c, "friend-server", "corruption.trials", "value");
          double verifySilent = number(c, "friend-server", "corruption.verify.silent", "value");
          return verdict(
              silent == 0,
              fmt(silent) + "/" + fmt(trials) + " read, " + fmt(verifySilent) + " after verify");
        });
    verdictRow(
        out,
        candidates,
        "B3 crash: inconsistent or unopenable (gate)",
        c -> {
          double bad =
              number(c, "friend-server", "crash.inconsistent", "value")
                  + number(c, "friend-server", "crash.openFailed", "value");
          if (Double.isNaN(bad)) {
            return null;
          }
          return verdict(
              bad == 0, fmt(bad) + "/" + fmt(number(c, "friend-server", "crash.trials", "value")));
        });
    verdictRow(
        out,
        candidates,
        "B4 lookup p50 ≤ 20 µs, p99 ≤ 1 ms (uniform)",
        c -> worstLatency(c, "lookup.uniform", 20_000, 1_000_000));
    verdictRow(
        out,
        candidates,
        "B4 lookup p50 ≤ 20 µs, p99 ≤ 1 ms (local)",
        c -> worstLatency(c, "lookup.local", 20_000, 1_000_000));
    verdictRow(
        out,
        candidates,
        "B5 16×16 scan p50 ≤ 2 ms",
        c -> worstLatency(c, "scan.rect16", 2_000_000, Double.POSITIVE_INFINITY));
    verdictRow(
        out,
        candidates,
        "B6 scans ≥ 500 000 records/s",
        c -> {
          double worst = Double.POSITIVE_INFINITY;
          String where = "";
          for (String d : ChunkDatasets.NAMES) {
            for (String m : List.of("scan.rect512", "scan.season", "scan.full")) {
              double v = number(c, d, m, "value");
              if (!Double.isNaN(v) && v < worst) {
                worst = v;
                where = m.substring(5) + " on " + d;
              }
            }
          }
          return worst == Double.POSITIVE_INFINITY
              ? null
              : verdict(worst >= 500_000, rate(worst) + " (" + where + ")");
        });
    verdictRow(
        out,
        candidates,
        "B7 season envelope on `millions` ≤ 2 s",
        c -> {
          double max = number(c, "millions", "envelope.season", "max");
          return Double.isNaN(max) ? null : verdict(max <= 2e9, ms(max) + " max");
        });
    verdictRow(
        out,
        candidates,
        "B8 1 000-mutation commit p50 ≤ 100 ms, p99 ≤ 500 ms",
        c -> worstLatency(c, "update.batch1000", 100e6, 500e6));
    verdictRow(
        out,
        candidates,
        "B9 bulk build ≥ 100 000 records/s (datasets ≥ 100 000 records)",
        c -> {
          double worst = Double.POSITIVE_INFINITY;
          String where = "";
          for (String d : ChunkDatasets.NAMES) {
            double records = number(c, d, "build.records", "value");
            double v = number(c, d, "build.rate", "value");
            if (!Double.isNaN(v) && records >= 100_000 && v < worst) {
              worst = v;
              where = d;
            }
          }
          return worst == Double.POSITIVE_INFINITY
              ? null
              : verdict(worst >= 100_000, rate(worst) + " (" + where + ")");
        });
    verdictRow(
        out,
        candidates,
        "B10 cold open on `millions` ≤ 1 s",
        c -> {
          double v = number(c, "millions", "open.cold", "value");
          return Double.isNaN(v)
              ? null
              : verdict(v <= 1000, String.format(Locale.ROOT, "%.0f ms", v));
        });
    verdictRow(
        out,
        candidates,
        "B11 ≤ 24 bytes/record (sparse, dense)",
        c -> {
          double a = number(c, "sparse-exploration", "disk.bytesPerRecord", "value");
          double b = number(c, "dense-square", "disk.bytesPerRecord", "value");
          if (Double.isNaN(a) && Double.isNaN(b)) {
            return null;
          }
          double worst = Math.max(Double.isNaN(a) ? 0 : a, Double.isNaN(b) ? 0 : b);
          return verdict(worst <= 24, String.format(Locale.ROOT, "%.1f / %.1f", a, b));
        });
    verdictRow(
        out,
        candidates,
        "B12 files ≤ regions + 16 (`millions`)",
        c -> {
          double files = number(c, "millions", "disk.files", "value");
          double regions = number(c, "millions", "world.regions", "value");
          return Double.isNaN(files) ? null : verdict(files <= regions + 16, fmt(files) + " files");
        });

    for (String dataset : ChunkDatasets.NAMES) {
      if (candidates.stream().noneMatch(c -> data.getOrDefault(c, Map.of()).containsKey(dataset))) {
        continue;
      }
      out.append("\n### `").append(dataset).append("`\n\n");
      String[][] columns = {
        {"records", "build.records", "value", "n"},
        {"build", "build.rate", "value", "rate"},
        {"B/rec", "disk.bytesPerRecord", "value", "1"},
        {"files", "disk.files", "value", "n"},
        {"cold open", "open.cold", "value", "msv"},
        {"lookup p50/p99 uniform", "lookup.uniform", "p50", "lat"},
        {"lookup p50/p99 local", "lookup.local", "p50", "lat"},
        {"16² scan p50", "scan.rect16", "p50", "ns"},
        {"512² scan", "scan.rect512", "value", "rate"},
        {"season scan", "scan.season", "value", "rate"},
        {"full scan", "scan.full", "value", "rate"},
        {"envelope max", "envelope.season", "max", "ns"},
        {"commit p50/p99", "update.batch1000", "p50", "lat"},
        {"growth/update", "disk.growthPerMutation", "value", "1"},
        {"checkpoint", "checkpoint.seconds", "value", "s"},
        {"verify", "verify.seconds", "value", "s"},
        {"index heap", "heap.indexRetained", "value", "mib"},
        {"RSS peak", "rss.peak", "value", "mib"},
      };
      out.append("| candidate |");
      for (String[] col : columns) {
        out.append(' ').append(col[0]).append(" |");
      }
      out.append("\n|---|");
      for (int i = 0; i < columns.length; i++) {
        out.append("--:|");
      }
      out.append('\n');
      for (String c : candidates) {
        if (!data.getOrDefault(c, Map.of()).containsKey(dataset)) {
          continue;
        }
        out.append("| `").append(c).append("` |");
        for (String[] col : columns) {
          out.append(' ').append(cell(c, dataset, col)).append(" |");
        }
        out.append('\n');
      }
      for (String c : candidates) {
        for (String phase : List.of("build", "query")) {
          String status = field(c, dataset, "status." + phase, "value");
          if (status != null && !status.equals("ok")) {
            out.append("\n`")
                .append(c)
                .append("` ")
                .append(phase)
                .append(": ")
                .append(abbreviate(status))
                .append('\n');
          }
        }
      }
    }
    return out.toString();
  }

  private String cell(String c, String d, String[] col) {
    double v = number(c, d, col[1], col[2]);
    if (Double.isNaN(v)) {
      return "—";
    }
    return switch (col[3]) {
      case "n" -> fmt(v);
      case "rate" -> rate(v);
      case "1" -> String.format(Locale.ROOT, "%.1f", v);
      case "msv" -> String.format(Locale.ROOT, "%.0f ms", v);
      case "lat" -> ns(v) + " / " + ns(number(c, d, col[1], "p99"));
      case "ns" -> ns(v);
      case "s" -> String.format(Locale.ROOT, "%.1f s", v);
      case "mib" -> String.format(Locale.ROOT, "%.0f MiB", v / (1 << 20));
      default -> String.valueOf(v);
    };
  }

  private @Nullable String worstLatency(String c, String metric, double p50Limit, double p99Limit) {
    double worst50 = -1;
    double worst99 = -1;
    String where50 = "";
    for (String d : ChunkDatasets.NAMES) {
      double p50 = number(c, d, metric, "p50");
      double p99 = number(c, d, metric, "p99");
      if (!Double.isNaN(p50) && p50 > worst50) {
        worst50 = p50;
        where50 = d;
      }
      if (!Double.isNaN(p99)) {
        worst99 = Math.max(worst99, p99);
      }
    }
    if (worst50 < 0) {
      return null;
    }
    return verdict(
        worst50 <= p50Limit && worst99 <= p99Limit,
        ns(worst50) + " / " + ns(worst99) + " (" + where50 + ")");
  }

  private interface Verdict {
    @Nullable String of(String candidate);
  }

  private static void verdictRow(
      StringBuilder out, List<String> candidates, String name, Verdict v) {
    out.append("| ").append(name).append(" |");
    for (String c : candidates) {
      String text = v.of(c);
      out.append(' ').append(text == null ? "—" : text).append(" |");
    }
    out.append('\n');
  }

  private static String verdict(boolean ok, String detail) {
    return (ok ? "✅ " : "❌ ") + detail;
  }

  private static String abbreviate(String s) {
    String flat = s.replace('|', '/');
    return flat.length() > 120 ? flat.substring(0, 117) + "…" : flat;
  }

  static String fmt(double v) {
    return String.format(Locale.ROOT, "%,.0f", v);
  }

  static String rate(double perSecond) {
    return perSecond >= 1e6
        ? String.format(Locale.ROOT, "%.1f M/s", perSecond / 1e6)
        : String.format(Locale.ROOT, "%.0f k/s", perSecond / 1e3);
  }

  static String ms(double nanos) {
    return String.format(Locale.ROOT, "%.0f ms", nanos / 1e6);
  }

  static String ns(double nanos) {
    if (nanos >= 1e9) {
      return String.format(Locale.ROOT, "%.2f s", nanos / 1e9);
    }
    if (nanos >= 1e6) {
      return String.format(Locale.ROOT, "%.1f ms", nanos / 1e6);
    }
    if (nanos >= 1e3) {
      return String.format(Locale.ROOT, "%.1f µs", nanos / 1e3);
    }
    return String.format(Locale.ROOT, "%.0f ns", nanos);
  }
}
