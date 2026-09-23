import csv
import os
import statistics

base = r"C:\Users\zcdada\研究生\求值\javaProject\shortlink_zc\jmeter"
names = [
    "sync-result.jtl",
    "sync-result-2.jtl",
    "mq-result.jtl",
    "mq-result-2.jtl",
    "tuning-c8-p32.jtl",
    "tuning-c16-p32.jtl",
    "tuning-c16-p64.jtl",
]

for name in names:
    with open(os.path.join(base, name), encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle))
    elapsed = sorted(int(row["elapsed"]) for row in rows)
    starts = [int(row["timeStamp"]) for row in rows]
    ends = [start + value for start, value in zip(starts, [int(row["elapsed"]) for row in rows])]
    duration = (max(ends) - min(starts)) / 1000
    success = sum(row["success"] == "true" for row in rows)
    print(name, {
        "requests": len(rows),
        "success": success,
        "avg_ms": round(statistics.mean(elapsed), 2),
        "p99_ms": elapsed[int(0.99 * len(elapsed)) - 1],
        "window_qps": round(len(rows) / duration, 2),
        "duration_s": round(duration, 1),
    })
