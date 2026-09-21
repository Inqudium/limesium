package eu.inqudium.limesium.smoke;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The smallest possible servlet host: Boot's auto-configuration scan and one endpoint. The twin must
 * find its way in through its own {@code META-INF/spring/...AutoConfiguration.imports} inside the
 * shaded jar.
 */
@SpringBootApplication
class SmokeApplication {
    @RestController
    static class ThingsController {
        @GetMapping("/things")
        String things() {
            return "served";
        }
    }
}
