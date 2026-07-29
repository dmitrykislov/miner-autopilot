package io.dmitrykislov.miner;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Test-only Spring Boot application root for the {@code adapters} module. The real
 * {@code MinerControllerApplication} lives in the {@code launcher} module (which depends on this
 * one, not the other way round), so the {@code @WebFluxTest} controller slices here have no
 * application class to anchor to. This provides that anchor.
 *
 * <p>It is a full {@code @SpringBootApplication} (hence {@code @ComponentScan} over
 * {@code io.dmitrykislov.miner}) on purpose: {@code @WebFluxTest(controllers = X.class)} works by
 * <em>filtering</em> the scanned controllers down to {@code X}, so without a component scan the
 * slice would register no controllers and every route would 404. The slice's own type filters still
 * keep each test to just its controller plus the collaborators it mocks.
 */
@SpringBootApplication
public class AdapterTestApplication {
}
