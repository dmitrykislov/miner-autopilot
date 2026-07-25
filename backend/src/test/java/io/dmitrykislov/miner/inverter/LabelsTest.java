package io.dmitrykislov.miner.inverter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LabelsTest {

    @Test
    void resolvesKnownMetricLabelsAndCategories() {
        assertThat(Labels.label("I18N_COMMON_TOTAL_ACTIVE_POWER")).isEqualTo("Active Power");
        assertThat(Labels.category("I18N_COMMON_TOTAL_ACTIVE_POWER")).isEqualTo("power");
        assertThat(Labels.label("I18N_COMMON_DAILY_POWER_YIELD")).isEqualTo("Daily Yield");
        assertThat(Labels.category("I18N_COMMON_DAILY_POWER_YIELD")).isEqualTo("energy");
        assertThat(Labels.category("I18N_COMMON_GRID_FREQUENCY")).isEqualTo("grid");
        assertThat(Labels.category("I18N_COMMON_BUS_VOLTAGE")).isEqualTo("dc");
        assertThat(Labels.category("I18N_COMMON_RUNNING_STATE")).isEqualTo("status");
    }

    @Test
    void unknownKeyFallsBackToOtherCategory() {
        assertThat(Labels.category("I18N_CONFIG_KEY_1003332")).isEqualTo("other");
    }

    @Test
    void mapsStateValueKeysToFriendlyText() {
        assertThat(Labels.value("I18N_COMMON_STANDBY")).isEqualTo("Standby");
        assertThat(Labels.value("I18N_COMMON_RUNNING")).isEqualTo("Running");
        assertThat(Labels.value("I18N_COMMON_FAULT")).isEqualTo("Fault");
    }

    @Test
    void passesThroughPlainValues() {
        assertThat(Labels.value("40.9")).isEqualTo("40.9");
        assertThat(Labels.value("--")).isEqualTo("--");
        assertThat(Labels.value(null)).isNull();
    }

    @Test
    void prettifiesUnknownI18nKeys() {
        assertThat(Labels.prettify("I18N_CONFIG_KEY_1003332")).isEqualTo("Key 1003332");
        assertThat(Labels.prettify("I18N_COMMON_SOME_NEW_FIELD")).isEqualTo("Some New Field");
        assertThat(Labels.prettify("")).isEmpty();
    }
}
