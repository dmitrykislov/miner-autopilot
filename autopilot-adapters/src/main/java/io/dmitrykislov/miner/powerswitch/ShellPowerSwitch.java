package io.dmitrykislov.miner.powerswitch;

import io.dmitrykislov.miner.port.PowerSwitch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Drives the miner's mains outlet by running a configured command (a Tapo CLI, a curl, a GPIO
 * script — anything executable).
 *
 * <p>Nothing here throws. A smart plug is the least reliable thing in this system: it is on Wi-Fi,
 * it depends on a vendor cloud in some firmwares, and its CLI can hang. The autopilot must keep
 * running regardless, and it can: the miner's own API is the source of truth for whether it is
 * mining, so a failed switch shows up as "the miner didn't come up" and is retried by the normal
 * pending-start path rather than needing its own error handling.
 */
@Component
public class ShellPowerSwitch implements PowerSwitch {

    private static final Logger log = LoggerFactory.getLogger(ShellPowerSwitch.class);

    private final PowerSwitchProperties cfg;
    private final CommandRunner runner;

    /** Seam so tests can assert what was run without spawning a process. */
    @FunctionalInterface
    public interface CommandRunner {
        /** Run {@code command}, returning its exit code. Implementations may throw; callers catch. */
        int run(List<String> command, long timeoutMs) throws Exception;
    }

    @Autowired
    public ShellPowerSwitch(PowerSwitchProperties cfg) {
        this(cfg, ShellPowerSwitch::exec);
    }

    ShellPowerSwitch(PowerSwitchProperties cfg, CommandRunner runner) {
        this.cfg = cfg;
        this.runner = runner;
        if (cfg.enabled() && !cfg.configured()) {
            log.warn("Power switch is enabled but a command is missing — POWER_SWITCH_ON_COMMAND / "
                    + "POWER_SWITCH_OFF_COMMAND must both be set. Switching stays disabled.");
        } else if (cfg.configured()) {
            log.info("Power switch enabled — cutting mains {} after the miner has been off that long",
                    cfg.offDelay());
        }
    }

    @Override
    public boolean isEnabled() {
        return cfg.configured();
    }

    @Override
    public java.time.Duration offDelay() {
        return cfg.offDelay();
    }

    @Override
    public void on() {
        runQuietly(cfg.onCommand(), "on");
    }

    @Override
    public void off() {
        runQuietly(cfg.offCommand(), "off");
    }

    private void runQuietly(String command, String what) {
        if (!cfg.configured()) return;
        List<String> argv = split(command);
        if (argv.isEmpty()) return;
        try {
            int exit = runner.run(argv, cfg.commandTimeoutMs());
            if (exit == 0) {
                log.info("Power switch {} — command succeeded", what);
            } else {
                log.warn("Power switch {} — command exited {}: {}", what, exit, command);
            }
        } catch (Exception e) {
            // Deliberately swallowed: see the class doc. The miner's own reachability tells us
            // whether this actually worked, and the caller retries on that basis.
            log.warn("Power switch {} failed: {}", what, e.toString());
        }
    }

    /**
     * Split a command line on whitespace. Deliberately simple: the command is operator-supplied
     * configuration, not user input, and it is executed directly rather than through a shell — so
     * there is no shell to inject into, and quoting rules would be a trap rather than a feature.
     */
    static List<String> split(String command) {
        if (command == null || command.isBlank()) return List.of();
        return List.of(command.trim().split("\\s+"));
    }

    /** Real execution: no shell, inherited-null IO, hard timeout. */
    private static int exec(List<String> argv, long timeoutMs) throws Exception {
        Process p = new ProcessBuilder(argv).redirectErrorStream(true).start();
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("timed out after " + timeoutMs + "ms");
        }
        return p.exitValue();
    }
}
