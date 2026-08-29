package eu.inqudium.limesium.benchmarks;

import eu.inqudium.limesium.reactive.logging.HeaderLogProperties;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import kotlin.Pair;

/**
 * The CANDIDATE of finding #1 (PERF_ANALYSIS-2026-08-29T22-31-30, plan M3): the semantics of
 * {@code HeaderLogProperties.select} with every configuration-derived structure (lowercased
 * exclude/mask sets, the wildcard flags) computed ONCE at construction instead of per call.
 * Lives in the benchmark module only - production code is never touched in a verification
 * session. Output equality with the baseline is asserted in the benchmark's setup.
 */
final class PrecomputedHeaderSelect {

    private final List<String> includes;
    private final Set<String> excludedLower;
    private final Set<String> maskedLower;
    private final boolean maskAll;
    private final boolean wildcardInclude;

    PrecomputedHeaderSelect(List<String> includes, List<String> excludes, List<String> masked) {
        this.includes = List.copyOf(includes);
        this.excludedLower = toLowerSet(excludes);
        this.maskedLower = toLowerSet(masked);
        this.maskAll = masked.contains(HeaderLogProperties.WILDCARD);
        this.wildcardInclude = includes.contains(HeaderLogProperties.WILDCARD);
    }

    private static Set<String> toLowerSet(List<String> names) {
        Set<String> lower = new HashSet<>(names.size() * 2);
        for (String name : names) {
            lower.add(name.toLowerCase(Locale.ROOT));
        }
        return lower;
    }

    List<Pair<String, String>> select(Collection<String> availableNames, Function<String, String> valueOf) {
        if (includes.isEmpty()) {
            return List.of();
        }
        Collection<String> names;
        if (wildcardInclude) {
            // distinctBy lowercase, first spelling wins - the baseline's wildcard semantics.
            Map<String, String> distinct = new LinkedHashMap<>();
            for (String name : availableNames) {
                distinct.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
            }
            names = distinct.values();
        } else {
            names = includes;
        }
        List<Pair<String, String>> result = new ArrayList<>(names.size());
        for (String name : names) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (excludedLower.contains(lower)) {
                continue;
            }
            String value = valueOf.apply(name);
            if (value == null) {
                continue;
            }
            if (maskAll || maskedLower.contains(lower)) {
                value = HeaderLogProperties.Companion.mask(value);
            }
            result.add(new Pair<>(name, value));
        }
        return result;
    }
}
