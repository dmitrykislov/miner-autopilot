package io.dmitrykislov.miner.port;

/**
 * The mains outlet the miner is plugged into — an outbound (driven) port.
 *
 * <p>Setting a Braiins miner's power target to zero does not fully idle it: on the rig here the fans
 * spin up to 100% and stay there indefinitely. Until that firmware behaviour is understood, the
 * dependable way to have an off miner actually draw nothing is to cut power at the socket.
 *
 * <p>Both operations must be <b>idempotent and non-throwing</b>. They sit on the autopilot's control
 * path, and a failure to reach a smart plug must never take the control loop down with it — the
 * miner's own state, read over its API, remains the source of truth either way.
 */
public interface PowerSwitch {

    /** Energise the outlet. Called before starting a miner whose API is unreachable. */
    void on();

    /** De-energise the outlet. Called once the miner has been off long enough to be quiesced. */
    void off();

    /** False when no switch is configured, so callers can skip the wait-for-boot delay entirely. */
    default boolean isEnabled() {
        return true;
    }

    /**
     * How long the miner must have been off before its power is cut.
     *
     * <p>Lives on the port rather than in the engine's config because it is a property of the switch
     * being driven — how long that particular miner needs to quiesce — and because it keeps the engine
     * free of any adapter configuration, which the module boundary requires.
     */
    default java.time.Duration offDelay() {
        return java.time.Duration.ofSeconds(60);
    }
}
