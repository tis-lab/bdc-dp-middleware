package org.biodatacatalyst.middleware.summary;

import java.util.*;
import org.springframework.stereotype.Service;
import org.biodatacatalyst.middleware.shared.ApiException;
import org.biodatacatalyst.middleware.summary.SummaryModels.*;
import static org.biodatacatalyst.middleware.shared.ApiException.Code.BAD_USER_INPUT;

@Service
public class SummaryService {
    private final SummarySource source;
    public SummaryService(SummarySource source) { this.source = source; }
    public List<String> sources() { return source.sourceIds(); }
    public List<SummaryTerm> concepts(String sourceId) { return summary(sourceId).terms(); }
    public SummaryResponse summary(String sourceId) {
        if (sourceId == null) throw new ApiException(BAD_USER_INPUT, "sourceId is required.");
        return source.find(sourceId).orElseThrow(() -> new ApiException(BAD_USER_INPUT, "Unknown summary source. Query summarySources for available sources."));
    }

    public SummaryResponse summary(String sourceId, List<String> include, List<String> exclude) {
        if (include == null || exclude == null || (long) include.size() + exclude.size() > 50)
            throw bad("include and exclude must be lists with at most 50 entries in total.");
        var original = summary(sourceId);
        Set<String> catalog = new HashSet<>();
        original.terms().forEach(term -> catalog.add(term.curie()));
        Set<String> included = normalize(include, catalog);
        Set<String> excluded = normalize(exclude, catalog);
        if (!Collections.disjoint(included, excluded)) throw bad("A concept cannot appear in both include and exclude.");
        if (included.isEmpty() && excluded.isEmpty()) return original;
        Set<String> hidden = descendants(excluded, original.terms());
        Set<String> visible = descendants(included, original.terms());
        java.util.function.Predicate<String> selected = concept ->
                (included.isEmpty() || visible.contains(concept)) && !hidden.contains(concept);
        var studies = original.studies().stream().map(study -> new StudySummary(
                study.research_study(), study.participant_count(), study.participants(), study.demographics(),
                study.conditions().stream().filter(c -> selected.test(c.condition_concept())).toList(),
                study.procedures().stream().filter(c -> selected.test(c.procedure_concept())).toList(),
                study.drug_exposures().stream().filter(c -> selected.test(c.drug_concept())).toList(),
                study.measurements().stream().filter(c -> selected.test(measurementConcept(c.observation_type()))).toList()
        )).toList();
        return new SummaryResponse(original.release(), original.seed(), original.filter(), original.min_count(),
                original.terms(), original.relations(), original.research_studies(), studies);
    }

    private static Set<String> normalize(List<String> values, Set<String> catalog) {
        Set<String> normalized = new HashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) throw bad("Concept IDs must not be blank.");
            String id = value.strip();
            if (id.length() > 200 || !catalog.contains(id))
                throw bad("A selected concept is not in this source. Query summaryConcepts for available IDs.");
            normalized.add(id);
        }
        return normalized;
    }

    private static Set<String> descendants(Set<String> roots, List<SummaryTerm> terms) {
        Set<String> result = new HashSet<>(roots);
        boolean changed;
        do {
            changed = false;
            for (var term : terms) if (!Collections.disjoint(term.parents(), result))
                changed |= result.add(term.curie());
        } while (changed); // Bounded by the catalog, including if a source contains a cycle.
        return result;
    }

    private static String measurementConcept(String localKey) {
        return "FAST_GLUC_BLD".equals(localKey) ? "OMOP:4156660" : localKey;
    }

    private static ApiException bad(String message) { return new ApiException(BAD_USER_INPUT, message); }
}
