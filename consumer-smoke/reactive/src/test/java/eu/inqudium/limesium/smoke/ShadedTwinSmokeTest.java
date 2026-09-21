package eu.inqudium.limesium.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import eu.inqudium.limesium.reactive.logging.RequestLoggingWebFilter;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;

/**
 * Exercises the shaded reactive jar from the consumer's side of the Shade boundary (ADR-0003): the
 * inlined common classes, Boot's auto-configuration through the jar's own imports file, and one
 * exchange line for one request against the real embedded server. Everything asserted here is
 * invisible to the reactor's own tests, which run before packaging against the limesium-common
 * module. One consumer per twin: a host carries exactly one limesium module.
 */
@SpringBootTest(
        classes = SmokeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ShadedTwinSmokeTest {
    private static final String SHARED_CLASS =
            "eu/inqudium/limesium/common/EndpointLoggingMetrics.class";
    private static final String EXCHANGE_LOGGER = "endpoint-http-exchange";

    /** The bound on every cross-thread wait here: the awaited line and the surplus check. */
    private static final Duration AWAIT = Duration.ofSeconds(5);

    /** How long a surplus line gets to show up after the expected one. */
    private static final Duration SETTLE = Duration.ofMillis(200);

    @Autowired private ApplicationContext context;
    @LocalServerPort private int port;

    private final AwaitingAppender captured = new AwaitingAppender();

    @BeforeEach
    void captureExchangeLogger() {
        captured.start();
        exchangeLogger().addAppender(captured);
    }

    @AfterEach
    void releaseExchangeLogger() {
        exchangeLogger().detachAppender(captured);
        captured.stop();
    }

    private static Logger exchangeLogger() {
        return (Logger) LoggerFactory.getLogger(EXCHANGE_LOGGER);
    }

    private static Object fieldOf(ILoggingEvent event, String key) {
        return event.getKeyValuePairs().stream()
                .filter(pair -> key.equals(pair.key))
                .map(pair -> pair.value)
                .findFirst()
                .orElse(null);
    }

    @Test
    void should_carry_the_shared_classes_in_the_twin_jar_and_nowhere_else() throws IOException {
        // What is tested: where the JVM finds the inlined common classes - the resource must
        //   resolve from exactly the one twin jar, and from no limesium-common artifact.
        // Success criteria: one location, the twin jar; it does not mention limesium-common.
        // Why it matters: a broken artifactSet or a dependency-reduced POM that still names the
        //   unpublished module surfaces here, not at the first consumer's NoClassDefFoundError.
        // Given/When
        List<String> jars =
                Collections.list(getClass().getClassLoader().getResources(SHARED_CLASS)).stream()
                        .map(URL::toString)
                        .toList();

        // Then
        assertThat(jars).hasSize(1);
        assertThat(jars.get(0)).contains("limesium-reactive-logging").doesNotContain("limesium-common");
    }

    @Test
    void should_auto_configure_the_twin_from_the_shaded_jar() {
        // What is tested: Boot's auto-configuration import of the twin - the imports file and the
        //   configuration classes must be present and loadable in the shaded jar.
        // Success criteria: the filter bean exists, exactly once.
        // Why it matters: a consumer adds the artifact and expects logging without configuration; a
        //   missing or filtered META-INF entry would ship a silent no-op.
        // Given/When/Then
        assertThat(context.getBeansOfType(RequestLoggingWebFilter.class)).hasSize(1);
    }

    @Test
    void should_log_one_exchange_line_for_one_request_against_the_real_server()
            throws IOException, InterruptedException {
        // What is tested: the end-to-end path through the product jar - Boot registers the filter,
        //   the server dispatches to the host's own endpoint, the filter observes one request.
        // Success criteria: exactly one exchange event, `-> 200` with endpoint_outcome=success -
        //   and no second line follows.
        // Why it matters: it is the one place the shaded runtime is executed as a consumer executes
        //   it. The line is awaited, not read: the emission runs on the server's thread after the
        //   response reached the client.
        // Given
        HttpClient client = HttpClient.newBuilder().connectTimeout(AWAIT).build();
        HttpRequest request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/things"))
                        .timeout(AWAIT)
                        .GET()
                        .build();

        // When
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        // Then
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("served");
        ILoggingEvent event = captured.awaitEvents(1).get(0);
        assertThat(event.getFormattedMessage()).contains("GET /things -> 200");
        assertThat(fieldOf(event, "endpoint_outcome")).isEqualTo("success");
        // And: one request, one line
        assertThat(captured.noEventWithin(SETTLE))
                .as("a second exchange event after the expected one")
                .isTrue();
    }

    /**
     * Collects the exchange logger's events and lets the test WAIT for a count: the line is written
     * on a server thread after the client already holds the response, so a plain list read right
     * after the call races the emission. Events are pinned with prepareForDeferredProcessing() for
     * the same cross-thread reason.
     */
    private static final class AwaitingAppender extends AppenderBase<ILoggingEvent> {
        private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();
        private final Semaphore arrivals = new Semaphore(0);

        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            events.add(event);
            arrivals.release();
        }

        List<ILoggingEvent> awaitEvents(int count) throws InterruptedException {
            assertThat(arrivals.tryAcquire(count, AWAIT.toMillis(), TimeUnit.MILLISECONDS))
                    .as("%d exchange events within %s, got %d", count, AWAIT, events.size())
                    .isTrue();
            return List.copyOf(events);
        }

        /** True when nothing arrived within {@code settle}: the bounded check for a surplus. */
        boolean noEventWithin(Duration settle) throws InterruptedException {
            return !arrivals.tryAcquire(settle.toMillis(), TimeUnit.MILLISECONDS);
        }
    }
}
