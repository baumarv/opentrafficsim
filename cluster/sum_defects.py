"""Sum the per-run DefectDiagnostics CSVs of one campaign into a single table.

The counters are process-wide and written by a shutdown hook, so each run leaves its own file and
the campaign total is their sum. Rates are reported against the counter they are a rate of, not
against the number of runs -- what matters is "of the times this was asked, how often did it fire".

Usage:  python cluster/sum_defects.py <dir-with-run_*.csv>

Copyright (c) 2026 Marvin Baumann / KIT. All rights reserved.
BSD-style license. See OpenTrafficSim License.
"""
import glob
import os
import sys

if len(sys.argv) < 2:
    sys.exit("usage: sum_defects.py <dir>")

directory = sys.argv[1]
files = sorted(glob.glob(os.path.join(directory, "run_*.csv")))
if not files:
    sys.exit("no run_*.csv in " + directory)

totals = {}
details = {}
for path in files:
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            parts = line.rstrip("\n").split(",", 3)
            if len(parts) < 3 or parts[0] == "counter":
                continue
            counter, site, value = parts[0], parts[1], parts[2]
            detail = parts[3] if len(parts) > 3 else ""
            key = (counter, site)
            try:
                totals[key] = totals.get(key, 0.0) + float(value)
            except ValueError:
                continue
            if detail:
                for item in detail.split("/"):
                    if "=" in item:
                        name, count = item.rsplit("=", 1)
                        try:
                            bucket = details.setdefault(key, {})
                            bucket[name] = bucket.get(name, 0) + int(count)
                        except ValueError:
                            pass


def get(counter, site):
    return totals.get((counter, site), 0.0)


def rate(numerator, denominator):
    return "n/a" if denominator == 0 else "%.4f %%" % (100.0 * numerator / denominator)


print("=" * 78)
print("DefectDiagnostics, summed over %d runs in %s" % (len(files), directory))
print("=" * 78)

calls = get("lookahead", "calls")
print()
print("-- look-ahead (InfrastructureContext.distanceToLaneChangeExtendedLookahead)")
print("   calls                     %12d" % calls)
print("   leaked (parameter kept)   %12d   %s" % (get("lookahead", "leaked"),
                                                  rate(get("lookahead", "leaked"), calls)))
print("   answered from a warm memo %12d   %s" % (get("lookahead", "cacheWarm"),
                                                  rate(get("lookahead", "cacheWarm"), calls)))
print("   -> a warm memo means the raised LOOKAHEAD had no effect on that call.")

evaluations = get("speedLimitTransition", "evaluations")
binding = get("speedLimitTransition", "binding")
print()
print("-- speed-limit transition term (LongitudinalControl)")
print("   evaluations               %12d" % evaluations)
print("   finite candidate          %12d   %s" % (get("speedLimitTransition", "finite"),
                                                  rate(get("speedLimitTransition", "finite"), evaluations)))
print("   binding                   %12d   %s" % (binding, rate(binding, evaluations)))
print("   effect > 0.01 m/s2        %12d   %s" % (get("speedLimitTransition", "material"),
                                                  rate(get("speedLimitTransition", "material"), evaluations)))
print("   largest single effect     %12.4f m/s2" % max(
    (v for (c, s), v in totals.items() if c == "speedLimitTransition" and s == "maxEffectSi"), default=0.0))
print("   -> all zero settles E.5: curvature and bumps leave the contract.")

swallowed = sorted(((site, value) for (counter, site), value in totals.items() if counter == "swallowed"),
                   key=lambda row: -row[1])
print()
print("-- swallowed exceptions, by site (%d sites fired)" % len(swallowed))
if not swallowed:
    print("   none")
for site, value in swallowed:
    causes = details.get(("swallowed", site), {})
    rendered = "/".join("%s=%d" % (name, count) for name, count in sorted(causes.items()))
    print("   %-62s %10d   %s" % (site, value, rendered))
print()
