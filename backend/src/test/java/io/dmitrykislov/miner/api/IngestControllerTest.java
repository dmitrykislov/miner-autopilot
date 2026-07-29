package io.dmitrykislov.miner.api;

import io.dmitrykislov.miner.port.ConsumptionSource;
import io.dmitrykislov.miner.port.PowerReading;
import io.dmitrykislov.miner.port.SolarSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Unit tests for the HTTP ingest controller: it forwards readings/clears to the source ports. */
class IngestControllerTest {

    private final SolarSource solar = mock(SolarSource.class);
    private final ConsumptionSource consumption = mock(ConsumptionSource.class);
    private final IngestController controller = new IngestController(solar, consumption);

    @Test void solarPostPublishesAServerStampedReading() {
        controller.solar(4200);
        var cap = ArgumentCaptor.forClass(PowerReading.class);
        verify(solar).publish(cap.capture());
        assertThat(cap.getValue().watts()).isEqualTo(4200);
        assertThat(cap.getValue().at()).isNotNull(); // stamped server-side, not by the caller
    }

    @Test void consumptionPostPublishesAReading() {
        controller.consumption(900);
        var cap = ArgumentCaptor.forClass(PowerReading.class);
        verify(consumption).publish(cap.capture());
        assertThat(cap.getValue().watts()).isEqualTo(900);
    }

    @Test void clearSolarClearsTheSolarPort() {
        controller.clearSolar();
        verify(solar).clear();
    }

    @Test void clearConsumptionClearsTheConsumptionPort() {
        controller.clearConsumption();
        verify(consumption).clear();
    }
}
