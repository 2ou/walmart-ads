package com.ai.walmartads.inventory.application;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Client for the loopback-only Python StatsForecast process. */
@Component
public class StatsForecastClient {

    private final String baseUrl;
    private volatile RestClient restClient;
    private volatile String lastFailureReason;
    private final ConcurrentMap<String, ForecastResult> lastSuccessful = new ConcurrentHashMap<>();

    public StatsForecastClient(
            @Value("${walmart.ads.forecast-service-url:http://127.0.0.1:8091}") String baseUrl
    ) {
        this.baseUrl = baseUrl;
    }

    public Map<String, ForecastResult> forecast(Map<String, List<Observation>> historyByParent, int horizon) {
        if (historyByParent.isEmpty()) {
            return Map.of();
        }
        List<SeriesRequest> series = historyByParent.entrySet().stream()
                .map(entry -> new SeriesRequest(
                        entry.getKey(),
                        entry.getValue().stream()
                                .map(observation -> new ObservationPayload(
                                        observation.ds().toString(), observation.y()))
                                .toList()
                ))
                .toList();
        try {
            ForecastResponse response = restClient().post()
                    .uri("/forecast")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ForecastRequest(horizon, series))
                    .retrieve()
                    .body(ForecastResponse.class);
            if (response == null || response.results() == null) {
                throw new IllegalStateException("Forecast service returned an empty response");
            }
            Map<String, ForecastResult> result = new LinkedHashMap<>();
            response.results().forEach(item -> result.put(item.uniqueId(), item.toForecastResult()));
            if (result.size() != historyByParent.size()) {
                throw new IllegalStateException("Forecast service did not return every parent style");
            }
            lastFailureReason = null;
            result.forEach((parentSku, forecast) -> {
                if (forecast.model() != null && !forecast.model().contains("SERVICE_FALLBACK")) {
                    lastSuccessful.put(parentSku, forecast);
                }
            });
            return result;
        } catch (RuntimeException exception) {
            lastFailureReason = exception.getClass().getSimpleName() + ": " + conciseMessage(exception.getMessage());
            Map<String, ForecastResult> fallback = new LinkedHashMap<>();
            historyByParent.forEach((parentSku, history) -> {
                ForecastResult previous = lastSuccessful.get(parentSku);
                fallback.put(
                        parentSku,
                        previous != null && previous.p50().size() >= horizon && previous.p90().size() >= horizon
                                ? new ForecastResult(
                                        previous.p50(), previous.p90(), "PREVIOUS_SUCCESSFUL_" + previous.model(),
                                        previous.confidence(), previous.modelSelected(),
                                        previous.modelWmape(), previous.baselineWmape()
                                )
                                : baseline(history, horizon, "BASELINE_SERVICE_FALLBACK", "LOW")
                );
            });
            return fallback;
        }
    }

    public static ForecastResult baseline(List<Observation> history, int horizon, String model, String confidence) {
        List<Double> observed = history.stream()
                .map(Observation::y)
                .filter(value -> value != null)
                .toList();
        int fromIndex = Math.max(0, observed.size() - 28);
        double mean = observed.subList(fromIndex, observed.size()).stream().mapToDouble(Double::doubleValue).average().orElse(0);
        List<Double> p50 = new ArrayList<>(horizon);
        List<Double> p90 = new ArrayList<>(horizon);
        for (int index = 0; index < horizon; index++) {
            p50.add(round(mean));
            p90.add(round(mean + 1.2816 * Math.sqrt(Math.max(mean, 1))));
        }
        return new ForecastResult(p50, p90, model, confidence, false, null, null);
    }

    public String lastFailureReason() {
        return lastFailureReason;
    }

    private static double round(double value) {
        return Math.round(Math.max(0, value) * 10000d) / 10000d;
    }

    private static String conciseMessage(String message) {
        if (message == null || message.isBlank()) {
            return "no detail";
        }
        String normalized = message.replaceAll("\\s+", " ");
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240) + "...";
    }

    private RestClient restClient() {
        RestClient current = restClient;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (restClient == null) {
                // Use HttpURLConnection rather than the JDK HttpClient so the
                // loopback-only sidecar also works in restricted local runtimes.
                restClient = RestClient.builder()
                        .baseUrl(baseUrl)
                        .requestFactory(new SimpleClientHttpRequestFactory())
                        .build();
            }
            return restClient;
        }
    }

    public record Observation(LocalDate ds, Double y) {
    }

    public record ForecastResult(
            List<Double> p50,
            List<Double> p90,
            String model,
            String confidence,
            boolean modelSelected,
            Double modelWmape,
            Double baselineWmape
    ) {
    }

    private record ForecastRequest(int horizon, List<SeriesRequest> series) {
    }

    private record SeriesRequest(@JsonProperty("unique_id") String uniqueId, List<ObservationPayload> history) {
    }

    private record ObservationPayload(String ds, Double y) {
    }

    private record ForecastResponse(List<RemoteResult> results) {
    }

    private record RemoteResult(
            @JsonProperty("unique_id") String uniqueId,
            List<Double> p50,
            List<Double> p90,
            String model,
            String confidence,
            Backtest backtest
    ) {
        private ForecastResult toForecastResult() {
            return new ForecastResult(
                    p50 == null ? List.of() : List.copyOf(p50),
                    p90 == null ? List.of() : List.copyOf(p90),
                    model,
                    confidence,
                    backtest != null && backtest.selectedModel(),
                    backtest == null ? null : backtest.modelWmape(),
                    backtest == null ? null : backtest.baselineWmape()
            );
        }
    }

    private record Backtest(
            @JsonProperty("model_wmape") Double modelWmape,
            @JsonProperty("baseline_wmape") Double baselineWmape,
            @JsonProperty("selected_model") boolean selectedModel
    ) {
    }
}
