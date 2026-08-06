package io.dmitrykislov.miner.powerswitch;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration for the mains outlet the miner is plugged into (bound from {@code house.power-switch.*}).
 *
 * <p>The commands are run as-is, so they can be any executable — a Tapo/Kasa CLI, a curl to a local
 * hub, a GPIO relay script. Leaving either blank disables the feature and the autopilot behaves
 * exactly as it did before.
 */
@ConfigurationProperties(prefix = "house.power-switch")
public record PowerSwitchProperties(
        // Master switch. Also treated as off when either command is blank.
        Boolean enabled,
        // Command that energises the outlet, e.g. "/home/pi/tapo/venv/bin/python /home/pi/tapo/tapo-on".
        String onCommand,
        // Command that de-energises the outlet.
        String offCommand,
        // How long the miner must have been off before power is cut. Guards against cutting power
        // mid-shutdown and against flapping when the surplus is hovering at the start threshold.
        long offDelayMs,
        // How long to wait after energising the outlet before expecting the miner's API to answer.
        long bootDelayMs,
        // Hard cap on how long a switch command may run, so a hung CLI can't stall the caller.
        long commandTimeoutMs) {

    public PowerSwitchProperties {
        if (enabled == null) enabled = false;             // opt-in: it cuts mains power
        if (onCommand == null) onCommand = "";
        if (offCommand == null) offCommand = "";
        if (offDelayMs <= 0) offDelayMs = 60_000;         // 1 minute
        if (bootDelayMs <= 0) bootDelayMs = 30_000;
        if (commandTimeoutMs <= 0) commandTimeoutMs = 20_000;
    }

    /** True only when switching is on AND both commands are actually configured. */
    public boolean configured() {
        return enabled && !onCommand.isBlank() && !offCommand.isBlank();
    }

    public Duration offDelay() {
        return Duration.ofMillis(offDelayMs);
    }

    public Duration bootDelay() {
        return Duration.ofMillis(bootDelayMs);
    }
}
