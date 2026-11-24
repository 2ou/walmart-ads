package com.ai.walmartads.selection;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SelectionCenterServiceTest {
    private static final LocalDate DATE = LocalDate.parse("2026-11-16");
    private SelectionCenterService service;

    @BeforeEach
    void setup() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V89__selection_center.sql")).execute(db);
        service = new SelectionCenterService(new SelectionCenterStore(new JdbcTemplate(db)),
                new ObjectMapper().findAndRegisterModules(), new DataSourceTransactionManager(db));
    }

    @Test
    void rankUsesOnlyAdjacentComparableSnapshotsAndDoesNotNetRestockingAgainstSales() {
        var first = snapshot(DATE, true, true, item("100", "verified", List.of(variant("100", 20, "offer-a"), variant("101", 10, "offer-b"))));
        assertThat(service.ingest(first).published()).isFalse();
        assertThat(service.ingest(first).duplicate()).isTrue();
        assertThat(service.board(null, "shopper")).containsEntry("status", "BASELINE");

        var next = snapshot(DATE.plusDays(1), true, true, item("100", "verified", List.of(variant("100", 15, "offer-a"), variant("101", 18, "offer-b"))));
        assertThat(service.ingest(next).published()).isTrue();
        var detail = service.detail("100", null, "shopper");
        assertThat(detail.item().rank()).isEqualTo(1);
        assertThat(detail.item().sales()).isEqualTo(5L);
        assertThat(detail.item().stockIncreaseUnits()).isEqualTo(8L);
        assertThat(detail.item().stockIncreaseSkus()).isEqualTo(1);
        assertThat(detail.variants().getLast().stockIncrease()).isEqualTo(8L);
        assertThat(detail.item().currentStockTotal()).isEqualTo(33L);
        assertThat(service.detail("100", DATE, "shopper").item().currentStockTotal()).isEqualTo(30L);

        service.ingest(snapshot(DATE.plusDays(2), false, false, item("100", null, List.of())));
        assertThat(service.board(null, "shopper")).containsEntry("date", DATE.plusDays(1))
                .containsEntry("latestDate", DATE.plusDays(2));
        assertThat(service.board(DATE.plusDays(2), "shopper")).containsEntry("status", "PARTIAL");
    }

    @Test
    void soldOutZeroCanCompareButOfferChangesAndDateGapsCannotInventSales() {
        service.ingest(snapshot(DATE, true, true, item("200", "verified", List.of(variant("200", 5, "offer-a"), variant("201", 3, "offer-b")))));
        assertThat(service.ingest(snapshot(DATE.plusDays(1), true, true, item("200", "verified", List.of(variant("200", 0, "offer-a"),
                variant("201", 1, "new-offer"))))).published()).isFalse();
        var detail = service.detail("200", DATE.plusDays(1), "shopper");
        assertThat(detail.item().comparableSkus()).isEqualTo(1);
        assertThat(detail.item().sales()).isNull();
        assertThat(detail.variants().getFirst().sales()).isEqualTo(5L);
        assertThat(detail.variants().getLast().sales()).isNull();
        assertThat(service.ingest(snapshot(DATE.plusDays(3), true, true,
                item("200", "verified", List.of(variant("200", 0, "offer-a"), variant("201", 0, "new-offer"))))).published()).isFalse();
    }

    @Test
    void verifiedMembershipJoinsCandidateHistoryAndMovesPersonalWatchToTheSharedStyle() {
        service.ingest(snapshot(DATE, true, false, item("300", null, List.of()), item("301", null, List.of())));
        assertThat(service.watch("shopper", "301", true)).isTrue();
        var grouped = snapshot(DATE.plusDays(1), true, false, item("300", "parent-a",
                List.of(variant("300", 20, "same"), variant("301", 12, "same"))), item("301", null, List.of()));
        assertThat(service.ingest(grouped).candidates()).isEqualTo(1);
        var detail = service.detail("300", null, "shopper");
        assertThat(detail.item().firstSeen()).isEqualTo(DATE);
        assertThat(detail.item().watched()).isTrue();
        assertThat(detail.history()).hasSize(2);
        assertThat(detail.variants()).hasSize(2);
        assertThat(service.detail("300", null, "another-user").item().watched()).isFalse();
    }

    @Test
    void currentStockSumsVerifiedCompleteRostersAndKeepsMissingOrConflictingAmountsUnknown() {
        var ambiguous = new SelectionSnapshot.Variant("403", null, "Blue", "M", 3, 2,
                "IN_STOCK", "same-seller", "same-offer", "WFS", null);
        var missing = new SelectionSnapshot.Variant("405", null, "Red", "L", null, null,
                "OUT_OF_STOCK", "same-seller", "same-offer", "WFS", null);
        service.ingest(snapshot(DATE, true, false,
                item("400", "verified-zero", List.of(variant("400", 0, "same-offer"), variant("401", 0, "same-offer"))),
                item("402", "verified-partial", List.of(variant("402", 6, "same-offer"), ambiguous, missing)),
                item("404", null, List.of(variant("404", 11, "same-offer")))));

        @SuppressWarnings("unchecked")
        var rows = (List<SelectionCenterService.ItemRow>) service.board(DATE, "shopper").get("items");
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).currentStockTotal()).isZero();
        assertThat(rows.get(0).currentStockKnownSkus()).isEqualTo(2);
        assertThat(rows.get(1).currentStockTotal()).isNull();
        assertThat(rows.get(1).currentStockKnownSkus()).isEqualTo(1);
        assertThat(rows.get(1).currentStockExpectedSkus()).isEqualTo(3);
        assertThat(rows.get(2).currentStockTotal()).isNull();
    }

    private SelectionSnapshot snapshot(LocalDate date, boolean complete, boolean inventory, SelectionSnapshot.Item... items) {
        return new SelectionSnapshot("https://www.walmart.com/shop/clothing-and-accessories/womens-new-arrivals?facet=category",
                "76088", date, Instant.parse(date + "T01:00:00Z"), 1, complete ? 1 : 0, complete,
                inventory, items.length, complete ? null : "部分页未取得", List.of(items));
    }

    private SelectionSnapshot.Item item(String id, String parent, List<SelectionSnapshot.Variant> variants) {
        return new SelectionSnapshot.Item(id, null, parent == null ? null : id, parent, "Test top", "Test brand", "Tops",
                "https://i5.walmartimages.com/test.jpg", BigDecimal.valueOf(19.98), 1, variants.size(), variants);
    }

    private SelectionSnapshot.Variant variant(String id, int quantity, String offer) {
        return new SelectionSnapshot.Variant(id, null, "Blue", "Size " + id, quantity, quantity,
                quantity == 0 ? "OUT_OF_STOCK" : "IN_STOCK", "same-seller", offer, "WFS", null);
    }
}
