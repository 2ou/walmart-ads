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
