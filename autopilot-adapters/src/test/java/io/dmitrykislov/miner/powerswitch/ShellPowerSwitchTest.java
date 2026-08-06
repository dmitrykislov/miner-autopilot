package io.dmitrykislov.miner.powerswitch;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * The adapter that drives the miner's mains outlet. The command is operator-configured, so the tests
 * assert exactly what would be executed, and that nothing here can throw into the control loop.
 */
class ShellPowerSwitchTest {

    private static final String ON = "/home/SGE/tapo/venv/bin/python /home/SGE/tapo/tapo-on";
    private static final String OFF = "/home/SGE/tapo/venv/bin/python /home/SGE/tapo/tapo-off";

    /** Records invocations instead of spawning a process. */
    private static final class Recorder implements ShellPowerSwitch.CommandRunner {
        final List<List<String>> calls = new ArrayList<>();
        int exit = 0;
        Exception boom;

        @Override public int run(List<String> command, long timeoutMs) throws Exception {
            calls.add(command);
            if (boom != null) throw boom;
            return exit;
        }
    }

    private static PowerSwitchProperties cfg(boolean enabled, String on, String off) {
        return new PowerSwitchProperties(enabled, on, off, 0, 0, 0);
    }

    @Test void onAndOffRunTheConfiguredCommands() {
        var rec = new Recorder();
        var sw = new ShellPowerSwitch(cfg(true, ON, OFF), rec);

        sw.on();
        sw.off();

        assertThat(rec.calls).hasSize(2);
        assertThat(rec.calls.get(0))
                .as("the on-command is executed argument by argument, with no shell in between")
                .containsExactly("/home/SGE/tapo/venv/bin/python", "/home/SGE/tapo/tapo-on");
        assertThat(rec.calls.get(1))
                .containsExactly("/home/SGE/tapo/venv/bin/python", "/home/SGE/tapo/tapo-off");
    }

    @Test void nothingRunsWhenTheSwitchIsDisabled() {
        var rec = new Recorder();
        var sw = new ShellPowerSwitch(cfg(false, ON, OFF), rec);

        sw.on();
        sw.off();

        assertThat(sw.isEnabled()).isFalse();
        assertThat(rec.calls).as("a disabled switch must never touch mains power").isEmpty();
    }

    @Test void enabledButUnconfiguredIsTreatedAsDisabled() {
        // Half-configured is the dangerous state: we could cut power and never be able to restore it.
        var rec = new Recorder();
        var sw = new ShellPowerSwitch(cfg(true, ON, "  "), rec);

        sw.on();
        sw.off();

        assertThat(sw.isEnabled()).isFalse();
        assertThat(rec.calls).isEmpty();
    }

    @Test void aFailingCommandDoesNotThrowIntoTheControlLoop() {
        var rec = new Recorder();
        rec.boom = new java.io.IOException("tapo cloud unreachable");
        var sw = new ShellPowerSwitch(cfg(true, ON, OFF), rec);

        // A smart plug is the least reliable thing in the system; the autopilot must survive it.
        assertThatNoException().isThrownBy(sw::on);
        assertThatNoException().isThrownBy(sw::off);
        assertThat(rec.calls).hasSize(2);   // it did try
    }

    @Test void aNonZeroExitDoesNotThrowEither() {
        var rec = new Recorder();
        rec.exit = 1;
        var sw = new ShellPowerSwitch(cfg(true, ON, OFF), rec);
        assertThatNoException().isThrownBy(sw::off);
    }

    @Test void theOffDelayComesFromConfigurationAndDefaultsToOneMinute() {
        assertThat(new ShellPowerSwitch(cfg(true, ON, OFF), new Recorder()).offDelay())
                .as("default when unset")
                .isEqualTo(java.time.Duration.ofMinutes(1));
        var custom = new PowerSwitchProperties(true, ON, OFF, 5_000, 0, 0);
        assertThat(new ShellPowerSwitch(custom, new Recorder()).offDelay())
                .isEqualTo(java.time.Duration.ofSeconds(5));
    }

    @Test void extraWhitespaceInTheCommandIsTolerated() {
        var rec = new Recorder();
        new ShellPowerSwitch(cfg(true, "  /bin/echo   hello   ", OFF), rec).on();
        assertThat(rec.calls.get(0)).containsExactly("/bin/echo", "hello");
    }

    @Test void reallyExecutesTheCommandWhenNoRunnerIsInjected() throws Exception {
        // The default runner spawns a real process — prove that end of the seam works too, otherwise
        // every test above could pass against an adapter that never executes anything.
        Path marker = Files.createTempFile("power-switch-", ".marker");
        Files.delete(marker);
        // /usr/bin/env keeps this portable — touch lives in /bin on Linux and /usr/bin on macOS.
        var sw = new ShellPowerSwitch(cfg(true, "/usr/bin/env touch " + marker, "/usr/bin/env true"));

        sw.on();

        assertThat(marker).as("the configured command really ran").exists();
        Files.deleteIfExists(marker);
    }
}
