# Local forecast service

This service is intentionally bound to the loopback interface.  It receives only
daily parent-style unit sales from the Java application and returns P50/P90 daily
forecasts for 60-day replenishment planning.

```powershell
cd D:\Tools\Code\walmart-ads\forecast-service
py -m pip install -r requirements.txt
py -m uvicorn app:app --host 127.0.0.1 --port 8091
```

The Java application falls back to its deterministic recent-sales baseline when
this service is unavailable.  No external AI or cloud endpoint is used.

## Guardrails (v1.1.0)

Raw AutoETS output is never used directly.  A style that launched a few weeks ago
leaves ~320 pre-launch zeros in the 365-day series, and AutoETS extrapolates the
short end-of-series ramp as an undamped trend — that produced 5-7x demand for
recent launches (PNK366089: 5457 P90 over 90 days for a style selling 12/day).
Four independent protections now apply:

| | Rule | Result |
|---|---|---|
| A | daily level capped at `2.0 x` the observed 28-day level | model name gets `_CAPPED` |
| C | confidence counts days **since the first sale**, not the padded window | pre-launch zeros no longer upgrade a 39-day-old style to HIGH |
| D | backtest WMAPE `> 0.5` is never used to size an order | returns the baseline as `BASELINE_WMAPE_REVIEW` + LOW |
| E | styles younger than `60` days skip ETS entirely | `COLD_START_WEIGHTED_14`: recency-weighted 14-day level, ramp capped at `1.3x` |

Every series logs one decision line to stderr, e.g.

```
forecast PNK366089 | cold_start age=40d ramp=1.00 active=38 selling=38 | model=COLD_START_WEIGHTED_14 conf=MEDIUM | ... p50=1105.7 p90=1510.0
```

Thresholds are environment-overridable, so tuning needs no code change:
`FORECAST_GUARDRAIL_MULTIPLE`, `FORECAST_WMAPE_LIMIT`, `FORECAST_COLD_START_DAYS`,
`FORECAST_COLD_START_RAMP`, `FORECAST_ETS_TOLERANCE`.  Set
`FORECAST_COLD_START_DAYS=0` to disable the cold-start path, etc.

`GET /health` reports `version` so a deploy can be verified without restarting.

Model names surface in the UI through `shortModelName()` in `index.html`:
`AutoETS·封顶`, `新品冷启动`, `基线28·误差复核`.
