"""Loopback-only StatsForecast service for the replenishment module."""

from __future__ import annotations

from datetime import date, timedelta
from math import sqrt
from typing import Optional

import numpy as np
import pandas as pd
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from statsforecast import StatsForecast
from statsforecast.models import AutoETS


app = FastAPI(title="Local replenishment forecast service", version="1.0.0")


class Observation(BaseModel):
    ds: date
    y: Optional[float] = Field(default=None, ge=0)


class SeriesRequest(BaseModel):
    unique_id: str = Field(min_length=1)
    history: list[Observation]


class ForecastRequest(BaseModel):
    horizon: int = Field(default=60, ge=1, le=180)
    series: list[SeriesRequest]


class Backtest(BaseModel):
    model_wmape: Optional[float]
    baseline_wmape: Optional[float]
    selected_model: bool


class ForecastResult(BaseModel):
    unique_id: str
    p50: list[float]
    p90: list[float]
    model: str
    confidence: str
    backtest: Backtest


class ForecastResponse(BaseModel):
    results: list[ForecastResult]


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "binding": "127.0.0.1"}


@app.post("/forecast", response_model=ForecastResponse)
def forecast(request: ForecastRequest) -> ForecastResponse:
    if not request.series:
        raise HTTPException(status_code=400, detail="At least one series is required")
    return ForecastResponse(results=[_forecast_one(item, request.horizon) for item in request.series])


def _forecast_one(request: SeriesRequest, horizon: int) -> ForecastResult:
    if not request.history:
        return _baseline_result(request.unique_id, [], horizon, "LOW", None, None)

    history = sorted(request.history, key=lambda item: item.ds)[-365:]
    observed = [item.y for item in history if item.y is not None]
    confidence = "HIGH" if len(observed) >= 90 else "MEDIUM" if len(observed) >= 28 else "LOW"
    baseline = _baseline_daily(history, horizon)
    baseline_p90 = _upper_quantile(baseline)
    if len(observed) < 28:
        return ForecastResult(
            unique_id=request.unique_id,
            p50=baseline,
            p90=baseline_p90,
            model="BASELINE_RECENT_28",
            confidence=confidence,
            backtest=Backtest(model_wmape=None, baseline_wmape=None, selected_model=False),
        )

    holdout = min(28, max(7, len(history) // 5))
    train = history[:-holdout]
    actual = [item.y for item in history[-holdout:] if item.y is not None]
    try:
        model_p50, model_p90 = _statsforecast_daily(train, horizon)
        backtest_p50, _ = _statsforecast_daily(train, holdout)
        model_wmape = _wmape(actual, backtest_p50[: len(actual)])
        baseline_wmape = _wmape(actual, _baseline_daily(train, len(actual)))
        selected = model_wmape is not None and (baseline_wmape is None or model_wmape < baseline_wmape)
        if selected:
            return ForecastResult(
                unique_id=request.unique_id,
                p50=model_p50,
                p90=model_p90,
                model="STATSFORECAST_AUTO_ETS",
                confidence=confidence,
                backtest=Backtest(model_wmape=model_wmape, baseline_wmape=baseline_wmape, selected_model=True),
            )
        return ForecastResult(
            unique_id=request.unique_id,
            p50=baseline,
            p90=baseline_p90,
            model="BASELINE_RECENT_28",
            confidence=confidence,
            backtest=Backtest(model_wmape=model_wmape, baseline_wmape=baseline_wmape, selected_model=False),
        )
    except Exception:
        # A local model outage must never prevent the Java application from using
        # its deterministic baseline and marking the result for review.
        return ForecastResult(
            unique_id=request.unique_id,
            p50=baseline,
            p90=baseline_p90,
            model="BASELINE_SERVICE_FALLBACK",
            confidence="LOW",
            backtest=Backtest(model_wmape=None, baseline_wmape=None, selected_model=False),
        )


def _statsforecast_daily(history: list[Observation], horizon: int) -> tuple[list[float], list[float]]:
    frame = _model_frame(history)
    forecast = StatsForecast(models=[AutoETS(season_length=7)], freq="D", n_jobs=1).forecast(
        df=frame, h=horizon, level=[80]
    )
    mean = _nonnegative(forecast["AutoETS"].tolist())
    upper_column = "AutoETS-hi-80"
    upper = _nonnegative(forecast[upper_column].tolist()) if upper_column in forecast.columns else _upper_quantile(mean)
    return mean, [max(value, upper[index]) for index, value in enumerate(mean)]


def _model_frame(history: list[Observation]) -> pd.DataFrame:
    start = history[0].ds
    end = history[-1].ds
    supplied = {item.ds: item.y for item in history}
    rows: list[dict[str, object]] = []
    cursor = start
    last_observed = 0.0
    while cursor <= end:
        value = supplied.get(cursor)
        if value is None:
            # Stockout-censored days are missing demand, not zero demand.  A local
            # carry-forward avoids fabricating a zero while preserving daily cadence.
            value = last_observed
        else:
            last_observed = max(0.0, value)
        rows.append({"unique_id": "series", "ds": pd.Timestamp(cursor), "y": max(0.0, value)})
        cursor += timedelta(days=1)
    return pd.DataFrame(rows)


def _baseline_daily(history: list[Observation], horizon: int) -> list[float]:
    values = [item.y for item in history if item.y is not None][-28:]
    daily = sum(values) / len(values) if values else 0.0
    return [round(max(0.0, daily), 4) for _ in range(horizon)]


def _upper_quantile(p50: list[float]) -> list[float]:
    # A Poisson-style approximation supplies the P90 safety margin for the
    # deterministic baseline when a fitted model is not selected.
    return [round(max(0.0, value + 1.2816 * sqrt(max(value, 1.0))), 4) for value in p50]


def _wmape(actual: list[Optional[float]], predicted: list[float]) -> Optional[float]:
    pairs = [(value, predicted[index]) for index, value in enumerate(actual) if value is not None]
    denominator = sum(value for value, _ in pairs)
    if not pairs or denominator <= 0:
        return None
    return round(sum(abs(value - prediction) for value, prediction in pairs) / denominator, 6)


def _nonnegative(values: list[float]) -> list[float]:
    return [round(max(0.0, float(value)), 4) for value in values]


def _baseline_result(
    unique_id: str,
    history: list[Observation],
    horizon: int,
    confidence: str,
    model_wmape: Optional[float],
    baseline_wmape: Optional[float],
) -> ForecastResult:
    p50 = _baseline_daily(history, horizon)
    return ForecastResult(
        unique_id=unique_id,
        p50=p50,
        p90=_upper_quantile(p50),
        model="BASELINE_RECENT_28",
        confidence=confidence,
        backtest=Backtest(model_wmape=model_wmape, baseline_wmape=baseline_wmape, selected_model=False),
    )
