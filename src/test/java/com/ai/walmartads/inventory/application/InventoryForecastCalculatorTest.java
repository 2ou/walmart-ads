package com.ai.walmartads.inventory.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryForecastCalculatorTest {

    @Test
    void separatesForecastSuggestionFromAvailableProductionAndTransitStock() {
        InventoryForecastCalculator.Forecast forecast = InventoryForecastCalculator.calculate(
                70,
                7,
                8,
                100,
                40,
                20,
                0,
                30
        );

        assertThat(forecast.forecastDailyUnits()).isEqualTo(9.2);
        assertThat(forecast.totalSupply()).isEqualTo(160);
        assertThat(forecast.targetDemand()).isEqualTo(276);
        assertThat(forecast.forecastSuggestedUnits()).isEqualTo(116);
        assertThat(forecast.confidence()).isEqualTo("MEDIUM");
    }

    @Test
    void weightsReportDailyRateMoreWhenRecentSalesSampleIsSmall() {
        InventoryForecastCalculator.Forecast forecast = InventoryForecastCalculator.calculate(
                20,
                2,
                4,
                500,
                0,
                0,
                0,
                45
        );

        assertThat(forecast.recentDailyUnits()).isEqualTo(10);
        assertThat(forecast.forecastDailyUnits()).isEqualTo(5.8);
        assertThat(forecast.confidence()).isEqualTo("LOW");
    }

    @Test
    void marksSkuOutOfStockEvenWhenStockIsInTransit() {
        InventoryForecastCalculator.Forecast forecast = InventoryForecastCalculator.calculate(
                14,
                7,
                2,
                0,
                30,
                0,
                0,
                45
        );

        assertThat(forecast.risk()).isEqualTo("OUT_OF_STOCK");
    }

    @Test
    void returnsNoDemandWithoutInventingAReplenishmentQuantity() {
        InventoryForecastCalculator.Forecast forecast = InventoryForecastCalculator.calculate(
                0,
                14,
                0,
                100,
                20,
                10,
                0,
                45
        );

        assertThat(forecast.forecastSuggestedUnits()).isZero();
        assertThat(forecast.projectedDaysSupply()).isNull();
        assertThat(forecast.risk()).isEqualTo("NO_DEMAND");
    }
}
