package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.summary;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;
import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.shared.ApiException;
import static gov.nih.nhlbi.bdc.dataportal.cohortbuilder.shared.ApiException.Code.BAD_USER_INPUT;

/** Concepts to describe, independent of the participant-selection filter. */
final class SummaryFocus {
    private static final Pattern CURIE = Pattern.compile("[A-Za-z][A-Za-z0-9._-]*:[^\\s\\p{Z}\\p{C}|*?]+");
    private SummaryFocus() {}

    static List<String> validate(List<String> focus) {
        if (focus == null) return List.of();
        if (focus.size() > 100) throw new ApiException(BAD_USER_INPUT, "focus accepts at most 100 concepts.");
        var concepts = new LinkedHashSet<String>();
        for (String concept : focus) {
            if (concept == null || concept.length() > 512 || !CURIE.matcher(concept).matches())
                throw new ApiException(BAD_USER_INPUT, "focus must contain exact CURIEs without whitespace, control characters, pipes or wildcards.");
            concepts.add(concept);
        }
        return List.copyOf(concepts);
    }
}
