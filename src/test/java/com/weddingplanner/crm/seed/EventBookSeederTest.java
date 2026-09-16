package com.weddingplanner.crm.seed;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The two judgements the book makes about a wedding it did not author: what the estimate's comments
 * may still say once the prices are no longer the example's, and where the wedding stands.
 */
class EventBookSeederTest {

    /** A comment that opens by quoting the example's venue and price cannot be salvaged. */
    @Test
    void aCommentLedByTheExamplesOwnPriceIsDropped() {
        assertThat(EventBookSeeder.covers("""
                VILLA PIZZO 2026: 40.000 EURO for 1 day event
                Check-in at 10am on the first day
                Source value: 200.000 €""")).isNull();
        assertThat(EventBookSeeder.covers("10 per person, pre ceremony welcome drink")).isNull();
        assertThat(EventBookSeeder.covers("Source value: 0 €")).isNull();
    }

    /** A descriptive comment survives; only the lines carrying the old figures are taken out. */
    @Test
    void aDescriptiveCommentKeepsEverythingExceptTheOldFigures() {
        assertThat(EventBookSeeder.covers("""
                Wedding reception including aperitif with finger food, tempura, corners
                price starts from 300 Euro per person
                Source value: 26.250 €""")).isEqualTo("Wedding reception including aperitif with finger food, tempura, corners");

        assertThat(EventBookSeeder.covers("""
                TOP Filmmaker for 1 day
                Source value: 35.000 €""")).isEqualTo("TOP Filmmaker for 1 day");
    }

    /** A list whose priced bullets have been removed is not a list any more, so it goes entirely. */
    @Test
    void aCommentWhoseMiddleWasPricedIsDroppedRatherThanLeftInPieces() {
        assertThat(EventBookSeeder.covers("""
                EXTRAS:
                Electricity for the main event - €7.500 (it does not include wiring)
                Extra hour for setup/dismantling
                Source value: 20.000 €""")).isNull();
    }

    @Test
    void aCommentThatIsOnlyItsOwnPriceLeavesNothingBehind() {
        assertThat(EventBookSeeder.covers("Respecting all musicians tech riders and venues rules"))
                .isEqualTo("Respecting all musicians tech riders and venues rules");
        assertThat(EventBookSeeder.covers("   ")).isNull();
        assertThat(EventBookSeeder.covers(null)).isNull();
    }

    /** A wedding's stage is its date: it has happened, it is this year's work, or it is not yet. */
    @Test
    void stageFollowsTheDateRatherThanAFieldSomebodyUpdates() {
        var today = java.time.LocalDate.now();
        assertThat(EventBookSeeder.stage(today.minusDays(1)))
                .isEqualTo(com.weddingplanner.crm.events.domain.EventStage.COMPLETED);
        assertThat(EventBookSeeder.stage(today.plusMonths(3)))
                .isEqualTo(com.weddingplanner.crm.events.domain.EventStage.CONFIRMED);
        assertThat(EventBookSeeder.stage(today.plusYears(2)))
                .isEqualTo(com.weddingplanner.crm.events.domain.EventStage.PLANNING);
    }
}
