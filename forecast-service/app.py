"""Loopback-only StatsForecast service for the replenishment module.

Guardrails (see README/CHANGELOG of this folder for the incident that motivated
them): the raw AutoETS output is only ever *one* input to the replenishment
quantity.  A 365-day series that is mostly pre-launch zeros followed by a short
ramp makes AutoETS extrapolate an undamped trend, which produced 5-7x demand for
recently launched styles.  Four independent protections are applied here:

  A. Extrapolation guardrail - the fitted daily level may not exceed
     ``GUARDRAIL_MULTIPLE`` x the observed 28-day level.
  C. Sample profile - confidence counts the days *since the first sale*, not the
     ``365 - censored`` days that include pre-launch zeros.
  D. Error gate - a backtest WMAPE above ``MODEL_WMAPE_LIMIT`` is never used to
     size a factory order; the deterministic baseline is returned for review.
  E. Cold start - styles younger than ``COLD_START_DAYS`` skip ETS entirely and
     use a recency-weighted 14-day level with a capped ramp (<= 1.3x).

All thresholds are environment-overridable so they can be tuned without a code
change (``FORECAST_GUARDRAIL_MULTIPLE``, ``FORECAST_WMAPE_LIMIT``,
``FORECAST_COLD_START_DAYS``, ``FORECAST_COLD_START_RAMP``,
``FORECAST_ETS_TOLERANCE``).
"""

from __future__ import annotations

from datetime import date, timedelta
from math import sqrt
from os import environ
from typing import Optional

import logging
import pandas as pd
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from statsforecast import StatsForecast
from statsforecast.models import AutoETS


logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger("forecast")


def _env_float(name: str, default: float) -> float:
    try:
        return float(environ.get(name, "") or default)
    except ValueError:
        return default


def _env_int(name: str, default: int) -> int:
    try:
        return int(float(environ.get(name, "") or default))
    except ValueError:
        return default


# AutoETS replaces the simple 28-day mean baseline only when its holdout WMAPE
# is no worse than the baseline. Intermittent demand (many zero days) makes the
# ETS fit a near-constant that is marginally worse than the mean in backtest yet
# still captures trend/seasonality for forward projection, so we accept it within
# this tolerance band instead of rejecting every close call.
ETS_SELECTION_TOLERANCE = _env_float("FORECAST_ETS_TOLERANCE", 0.05)
# (D) A holdout WMAPE this large means the fit is unusable for sizing an order,
# no matter how much better than the flat mean it happens to be.
MODEL_WMAPE_LIMIT = _env_float("FORECAST_WMAPE_LIMIT", 0.5)
# (A) Upper bound on the projected daily level, expressed against the observed
# 28-day level.  A ramp of a few weeks must not compound into months of growth.
GUARDRAIL_MULTIPLE = _env_float("FORECAST_GUARDRAIL_MULTIPLE", 2.0)
# (E) Styles younger than this are still ramping; their series cannot separate
# "launch" from "trend", so they are forecast from the recent pace instead.
COLD_START_DAYS = _env_int("FORECAST_COLD_START_DAYS", 60)
COLD_START_RAMP = _env_float("FORECAST_COLD_START_RAMP", 1.3)
COLD_START_WINDOW = 14

app = FastAPI(title="Local replenishment forecast service", version="1.1.0")


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


logger.info(
    "config | guardrail_multiple=%.2f wmape_limit=%.2f cold_start_days=%d cold_start_ramp=%.2f ets_tolerance=%.2f",
    GUARDRAIL_MULTIPLE,
    MODEL_WMAPE_LIMIT,
    COLD_START_DAYS,
    COLD_START_RAMP,
    ETS_SELECTION_TOLERANCE,
)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "binding": "127.0.0.1", "version": "1.1.0"}


@app.post("/forecast", response_model=ForecastResponse)
def forecast(request: ForecastRequest) -> ForecastResponse:
    if not request.series:
        raise HTTPException(status_code=400, detail="At least one series is required")
    return ForecastResponse(results=[_forecast_one(item, request.horizon) for item in request.series])


def _forecast_one(request: SeriesRequest, horizon: int) -> ForecastResult:
    if not request.history:
        return _baseline_result(request.unique_id, [], horizon, "LOW", None, None)

    history = sorted(request.history, key=lambda item: item.ds)[-365:]
    first_sale = _first_selling_index(history)
    confidence, active_days, selling_days = _sample_profile(history, first_sale)
    baseline = _baseline_daily(history, horizon)
    baseline_p90 = _upper_quantile(baseline)

    if first_sale is None:
        # No sale anywhere in the window: there is nothing to extrapolate, and a
        # fitted model would only be fitting censored zeros.
        return _decide(
            request.unique_id,
            baseline,
            baseline_p90,
            "BASELINE_RECENT_28",
            "LOW",
            None,
            None,
            None,
            horizon,
            note=f"no_sale active={active_days}",
        )

    age = len(history) - first_sale
    # (E) Cold start: too young for a seasonal model to mean anything.
    if age < COLD_START_DAYS:
        cold_p50, ramp = _cold_start_daily(history, horizon)
        return _decide(
            request.unique_id,
            cold_p50,
            _upper_quantile(cold_p50),
            "COLD_START_WEIGHTED_14",
            confidence,
            None,
            None,
            None,
            horizon,
            note=f"cold_start age={age}d ramp={ramp:.2f} active={active_days} selling={selling_days}",
        )

    # (C) The ETS gate uses the post-launch sample, not the padded window: a
    # style that has sold for 20 days has 20 usable observations, not 345.
    if active_days < 28:
        return _decide(
            request.unique_id,
            baseline,
            baseline_p90,
            "BASELINE_RECENT_28",
            confidence,
            None,
            None,
            None,
            horizon,
            note=f"thin_sample active={active_days} age={age}d",
        )

    holdout = min(28, max(7, len(history) // 5))
    train = history[:-holdout]
    actual = [item.y for item in history[-holdout:] if item.y is not None]
    try:
        model_p50, model_p90 = _statsforecast_daily(train, horizon)
        backtest_p50, _ = _statsforecast_daily(train, holdout)
        model_wmape = _wmape(actual, backtest_p50[: len(actual)])
        baseline_wmape = _wmape(actual, _baseline_daily(train, len(actual)))
        # Relax the strict "<" to a tolerance band so near-tie ETS fits are
        # used instead of always falling back to the flat 28-day mean.
        selected = model_wmape is not None and (
            baseline_wmape is None or model_wmape <= baseline_wmape * (1 + ETS_SELECTION_TOLERANCE)
        )
        if selected and model_wmape is not None and model_wmape > MODEL_WMAPE_LIMIT:
            # (D) Better than the mean, still far worse than tolerable: keep the
            # deterministic baseline and surface the error for manual review.
            return _decide(
                request.unique_id,
                baseline,
                baseline_p90,
                "BASELINE_WMAPE_REVIEW",
                "LOW",
                model_wmape,
                baseline_wmape,
                False,
                horizon,
                note=f"wmape_gate mW=%.3f bW=%.3f limit=%.2f"
                % (model_wmape, baseline_wmape if baseline_wmape is not None else float("nan"), MODEL_WMAPE_LIMIT),
            )
        if not selected:
            return _decide(
                request.unique_id,
                baseline,
                baseline_p90,
                "BASELINE_RECENT_28",
                confidence,
                model_wmape,
                baseline_wmape,
                False,
                horizon,
                note=f"ets_rejected mW=%.3f bW=%.3f" % (
                    model_wmape if model_wmape is not None else float("nan"),
                    baseline_wmape if baseline_wmape is not None else float("nan"),
                ),
            )
        # (A) Cap the accepted fit against the observed recent level.
        level = _observed_level(history, 28) or _observed_level(history, 90) or _observed_level(history, 365)
        guarded_p50, guarded_p90, capped = _apply_guardrail(model_p50, model_p90, level)
        return _decide(
            request.unique_id,
            guarded_p50,
            guarded_p90,
            "STATSFORECAST_AUTO_ETS_CAPPED" if capped else "STATSFORECAST_AUTO_ETS",
            confidence,
            model_wmape,
            baseline_wmape,
            True,
            horizon,
            note=f"ets_ok mW=%.3f bW=%.3f level28=%.2f cap=%.2f capped=%s"
            % (model_wmape, baseline_wmape if baseline_wmape is not None else float("nan"), level, level * GUARDRAIL_MULTIPLE, capped),
        )
    except Exception:
        # A local model outage must never prevent the Java application from using
        # its deterministic baseline and marking the result for review.
        return _decide(
            request.unique_id,
            baseline,
            baseline_p90,
            "BASELINE_SERVICE_FALLBACK",
            "LOW",
            None,
            None,
            False,
            horizon,
            note="model_exception",
        )


def _decide(
    unique_id: str,
    p50: list[float],
    p90: list[float],
    model: str,
    confidence: str,
    model_wmape: Optional[float],
    baseline_wmape: Optional[float],
    selected: Optional[bool],
    horizon: int,
    note: str,
) -> ForecastResult:
    """Build the response and emit one auditable decision line per series."""
    logger.info(
        "forecast %s | %s | model=%s conf=%s | mW=%s bW=%s | p50=%s p90=%s",
        unique_id,
        note,
        model,
        confidence,
        "nan" if model_wmape is None else "%.4f" % model_wmape,
        "nan" if baseline_wmape is None else "%.4f" % baseline_wmape,
        round(sum(p50), 1),
        round(sum(p90), 1),
    )
    return ForecastResult(
        unique_id=unique_id,
        p50=p50,
        p90=p90,
        model=model,
        confidence=confidence,
        backtest=Backtest(
            model_wmape=model_wmape,
            baseline_wmape=baseline_wmape,
            selected_model=bool(selected),
        ),
    )


def _first_selling_index(history: list[Observation]) -> Optional[int]:
    for index, item in enumerate(history):
        if item.y is not None and item.y > 0:
            return index
    return None


def _sample_profile(
    history: list[Observation],
    first_sale: Optional[int],
) -> tuple[str, int, int]:
    """(confidence, observed days since launch, selling days).

    Counting every non-censored day let a 39-day-old style report HIGH because
    326 pre-launch zeros looked like observations.  The sample only starts at the
    first real sale.
    """
    if first_sale is None:
        return "LOW", 0, 0
    active = [item.y for item in history[first_sale:] if item.y is not None]
    selling_days = sum(1 for value in active if value and value > 0)
    if len(active) >= 90 and selling_days >= 30:
        return "HIGH", len(active), selling_days
    if len(active) >= 28:
        return "MEDIUM", len(active), selling_days
    return "LOW", len(active), selling_days


def _observed_level(history: list[Observation], window: int) -> float:
    values = [item.y for item in history[-window:] if item.y is not None]
    if not values:
        return 0.0
    return sum(values) / len(values)


def _apply_guardrail(
    p50: list[float],
    p90: list[float],
    level: float,
) -> tuple[list[float], list[float], bool]:
    """Clamp the projection to ``level * GUARDRAIL_MULTIPLE`` per day.

    Returns the (possibly clamped) paths plus whether anything was clamped, so
    the model name can carry the fact into the UI.
    """
    ceiling = level * GUARDRAIL_MULTIPLE
    if ceiling <= 0:
        return p50, p90, False
    capped = any(value > ceiling for value in p50) or any(value > ceiling for value in p90)
    if not capped:
        return p50, p90, False
    clamped_p50 = _nonnegative([min(value, ceiling) for value in p50])
    clamped_p90 = []
    for index, value in enumerate(p90):
        # Keep a Poisson-style margin above the clamped mean so the capped path
        # still widens with volume instead of flattening p90 onto p50.
        floor_value = clamped_p50[index] + 1.2816 * sqrt(max(clamped_p50[index], 1.0))
        clamped_p90.append(round(max(0.0, min(value, ceiling), floor_value), 4))
    return clamped_p50, clamped_p90, True


def _cold_start_daily(history: list[Observation], horizon: int) -> tuple[list[float], float]:
    """Recency-weighted 14-day level with a capped ramp (<= COLD_START_RAMP)."""
    recent = [item.y for item in history[-COLD_START_WINDOW:] if item.y is not None]
    if not recent:
        return [0.0] * horizon, 1.0
    weights = list(range(1, len(recent) + 1))
    base = sum(value * weight for value, weight in zip(recent, weights)) / sum(weights)
    ramp = 1.0
    last_week = [value for value in recent[-7:]]
    prior_week = [value for value in recent[-14:-7]]
    if last_week and prior_week:
        last_mean = sum(last_week) / len(last_week)
        prior_mean = sum(prior_week) / len(prior_week)
        if prior_mean > 0:
            ramp = min(COLD_START_RAMP, max(1.0, last_mean / prior_mean))
        elif last_mean > 0:
            ramp = COLD_START_RAMP
    span = max(1, horizon - 1)
    levels = [base * (1 + (ramp - 1) * index / span) for index in range(horizon)]
    return _nonnegative(levels), ramp


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
    # Anchor the 7-day seasonal cycle to a Saturday->Friday week. US promotion
    # weeks run Saturday through Friday, so the weekly seasonality must be
    # phase-aligned to that cadence instead of to whichever weekday the first
    # real observation happens to fall on. Prepend up to 6 leading days so the
    # first row lands on a Saturday.
    lead = (start.weekday() - 5) % 7  # Monday=0 ... Saturday=5 -> 0 when start is Sat
    first_real = next((item.y for item in history if item.y is not None), 0.0)
    lead_value = max(0.0, first_real)
    rows: list[dict[str, object]] = []
    cursor = start - timedelta(days=lead)
    last_observed = lead_value
    while cursor <= end:
        if cursor < start:
            # Padded lead days carry the first real value backward in time so the
            # seasonal phase is anchored to Saturday without fabricating a zero dip.
            value = lead_value
        else:
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
