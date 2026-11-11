import java.util.*;
import java.nio.charset.StandardCharsets;
import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.summary.SummaryFilterCompiler;
import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.shared.ApiException;

/** Dependency-free execution of the production compiler against synthetic participant records. */
public class SummaryFilterCompilerCheck {
    static Map<String,Object> leaf(String value) {
        return Map.of("summaryId", "urn:bdc:summary:" + Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
    }
    static void check(boolean result) { if (!result) throw new AssertionError(); }
    static void invalid(Map<String,Object> input) {
        try { SummaryFilterCompiler.compile(input); throw new AssertionError("Accepted invalid filter"); }
        catch (ApiException e) { check(e.code() == ApiException.Code.BAD_USER_INPUT); }
    }
    @SuppressWarnings("unchecked")
    static boolean matches(Map<String,Object> query, Map<String,Object> doc) {
        if (query.containsKey("match_all")) return true;
        if (query.containsKey("term")) {
            var term = ((Map<String,Object>)query.get("term")).entrySet().iterator().next();
            Object value = doc.get(term.getKey());
            return value instanceof List<?> values ? values.contains(term.getValue()) : Objects.equals(value, term.getValue());
        }
        if (query.containsKey("terms")) {
            var clause = ((Map<String,Object>)query.get("terms")).entrySet().iterator().next();
            var accepted = (List<?>)clause.getValue();
            Object value = doc.get(clause.getKey());
            return value instanceof List<?> values ? values.stream().anyMatch(accepted::contains) : accepted.contains(value);
        }
        if (query.containsKey("exists")) return doc.containsKey(((Map<?,?>)query.get("exists")).get("field"));
        if (query.containsKey("nested")) {
            var nested = (Map<String,Object>)query.get("nested");
            var records = (List<Map<String,Object>>)doc.getOrDefault(nested.get("path"), List.of());
            return records.stream().anyMatch(record -> matches((Map<String,Object>)nested.get("query"), record));
        }
        var bool = (Map<String,Object>)query.get("bool");
        if (bool.containsKey("filter")) return ((List<Map<String,Object>>)bool.get("filter")).stream().allMatch(q -> matches(q,doc));
        check(Objects.equals(bool.get("minimum_should_match"), 1));
        return ((List<Map<String,Object>>)bool.get("should")).stream().anyMatch(q -> matches(q,doc));
    }
    static Map<String,Object> condition(String concept, String selection) {
        return Map.of("condition", Map.of("concept", concept, "selection", selection));
    }
    static Map<String,Object> record(String concept, String status, String relationship) {
        return Map.of("condition.condition_concept",concept,"condition.condition_status",status,
                "condition.relationship_to_participant",relationship);
    }
    static void sourceCodeAliasesMatch() {
        var family = SummaryFilterCompiler.compile(Map.of("condition", Map.of("concept", "HP:0000822", "selection", "INCLUDE", "relationship", "NATURAL_FATHER")));
        check(matches(family.query(), Map.of("condition", List.of(record("HP:0000822", "PRESENT", "OMOP:4321888")))));
        check(!matches(family.query(), Map.of("condition", List.of(record("HP:0000822", "PRESENT", "OMOP:4277283")))));
        var female = SummaryFilterCompiler.compile(leaf("demography|sex|FEMALE"));
        check(matches(female.query(), Map.of("demography", List.of(Map.of("demography.sex", "OMOP:8532")))));
        check(!matches(female.query(), Map.of("demography", List.of(Map.of("demography.sex", "OMOP:8507")))));
    }
    static void futureCriteriaAreRejected() {
        sourceCodeAliasesMatch();
        for (String field : List.of("measurement", "procedure", "temporal", "not", "stages", "occurrences", "trend")) {
            var unsupported = Map.<String,Object>of(field, Map.of("concept", "HP:0000822"));
            invalid(unsupported);
            invalid(Map.of("and", List.of(condition("HP:0000822", "INCLUDE"), unsupported)));
            invalid(Map.of("or", List.of(condition("HP:0000822", "INCLUDE"), unsupported)));
        }
        invalid(Map.of("condition", Map.of("concept", "HP:0000822", "selection", "INCLUDE", "lastMonths", 12)));
        System.out.println("PASS: unsupported future criteria fail at root and inside AND/OR; no silent weakening");
    }
    static void conceptChecks() {
        futureCriteriaAreRejected();
        String a="HP:0000822", b="MONDO:0005015", c="HP:0000819";
        var includeA=condition(a,"INCLUDE"); var includeB=condition(b,"INCLUDE");
        var excludeA=condition(a,"EXCLUDE"); var excludeB=condition(b,"EXCLUDE"); var excludeC=condition(c,"EXCLUDE");
        var first=SummaryFilterCompiler.compile(Map.of("or",List.of(Map.of("and",List.of(includeA,includeB)),excludeC)));
        var second=SummaryFilterCompiler.compile(Map.of("or",List.of(includeA,Map.of("and",List.of(excludeA,excludeB)))));
        // Exhaust all present/absent/unknown/missing states for three concepts: 64 combinations.
        for (int av=0;av<4;av++) for(int bv=0;bv<4;bv++) for(int cv=0;cv<4;cv++) {
            var records=new ArrayList<Map<String,Object>>();
            int[] states={av,bv,cv}; String[] concepts={a,b,c};
            for(int j=0;j<3;j++) if(states[j]<3) records.add(record(concepts[j],List.of("PRESENT","ABSENT","UNKNOWN").get(states[j]),"ONESELF"));
            var doc=Map.<String,Object>of("condition",records);
            check(matches(first.query(),doc)==((av==0 && bv==0)||cv==1));
            check(matches(second.query(),doc)==(av==0 || (av==1 && bv==1)));
        }
        var inc=SummaryFilterCompiler.compile(includeA);
        check(inc.query().equals(SummaryFilterCompiler.compile(leaf("condition|"+a+"|ONESELF|PRESENT")).query()));
        check(inc.query().equals(SummaryFilterCompiler.compile(Map.of("condition",Map.of("concept",a,"status","PRESENT"))).query()));
        check(!matches(inc.query(),Map.of("condition",List.of(record(a,"PRESENT","NATURAL_MOTHER")))));
        check(!matches(inc.query(),Map.of("condition",List.of(record(a,"ABSENT","ONESELF"),record(b,"PRESENT","ONESELF")))));
        var family=SummaryFilterCompiler.compile(Map.of("condition",Map.of("concept",a,"selection","INCLUDE","relationship","NATURAL_MOTHER")));
        check(matches(family.query(),Map.of("condition",List.of(record(a,"PRESENT","NATURAL_MOTHER")))));
        for(var input:List.of(Map.<String,Object>of("concept",a),Map.<String,Object>of("concept",a,"selection","EXCLUDE","status","PRESENT"),
                Map.<String,Object>of("concept","HP:*","selection","INCLUDE"),Map.<String,Object>of("concept",a,"status","UNKNOWN"),
                Map.<String,Object>of("concept",a,"selection","INCLUDE","relationship","OTHER"))) invalid(Map.of("condition",input));
        invalid(Map.of("condition",Map.of("concept",a,"selection","INCLUDE"),"and",List.of(includeB)));
        System.out.println("PASS: concept selections; 64 exhaustive status combinations for both requested expressions; default/explicit relationship; same-record semantics; invalid input");
    }
    static void vocabularyChecks() {
        for (String concept : List.of(
                "MONDO:0005044",
                "HP:0000822",
                "UPHENO:7000207",
                "ATC:C03",
                "EXAMPLE:future-concept")) {

            var include = SummaryFilterCompiler.compile(condition(concept, "INCLUDE"));
            var exclude = SummaryFilterCompiler.compile(condition(concept, "EXCLUDE"));

            var present = Map.<String,Object>of(
                    "condition", List.of(record(concept, "PRESENT", "ONESELF")));
            var absent = Map.<String,Object>of(
                    "condition", List.of(record(concept, "ABSENT", "ONESELF")));

            check(matches(include.query(), present));
            check(!matches(include.query(), absent));
            check(matches(exclude.query(), absent));
            check(!matches(exclude.query(), present));
            check(!matches(include.query(), Map.of()));
            check(!matches(exclude.query(), Map.of()));

            check(include.query().equals(
                    SummaryFilterCompiler.compile(
                            leaf("condition|" + concept + "|ONESELF|PRESENT")
                    ).query()));
        }

        var both = SummaryFilterCompiler.compile(Map.of("and", List.of(
                condition("UPHENO:7000207", "INCLUDE"),
                condition("MONDO:0005044", "INCLUDE"))));

        check(matches(both.query(), Map.of("condition", List.of(
                record("UPHENO:7000207", "PRESENT", "ONESELF"),
                record("MONDO:0005044", "PRESENT", "ONESELF")))));
        check(!matches(both.query(), Map.of("condition", List.of(
                record("UPHENO:7000207", "PRESENT", "ONESELF")))));

        for (String concept : List.of(
                "", "UPHENO", ":7000207", "ATC:", "ATC:C 03",
                "HP:*", "ATC:C?", "ATC:C03|PRESENT",
                "ATC:C03\n", "EXAMPLE:" + "x".repeat(512))) {
            invalid(condition(concept, "INCLUDE"));
        }
    }
    public static void main(String[] args) {
        vocabularyChecks();
        conceptChecks();
        var diabetes = leaf("condition|MONDO:0005015|ONESELF|PRESENT");
        var hypertension = leaf("condition|HP:0000822|ONESELF|PRESENT");
        var female = leaf("demography|sex|FEMALE");
        var selection = Map.<String,Object>of("and",List.of(female,Map.of("or",List.of(diabetes,hypertension))));
        var result = SummaryFilterCompiler.compile(selection);
        check(result.requiredIds().equals(List.of(female.get("summaryId"))));
        var present = Map.<String,Object>of("condition.condition_concept","MONDO:0005015","condition.relationship_to_participant","ONESELF","condition.condition_status","PRESENT");
        var absent = Map.<String,Object>of("condition.condition_concept","MONDO:0005015","condition.relationship_to_participant","ONESELF","condition.condition_status","ABSENT");
        var other = Map.<String,Object>of("condition.condition_concept","OTHER:1","condition.relationship_to_participant","ONESELF","condition.condition_status","PRESENT");
        var woman = List.of(Map.<String,Object>of("demography.sex","FEMALE"));
        check(matches(result.query(),Map.of("condition",List.of(present),"demography",woman)));
        check(!matches(result.query(),Map.of("condition",List.of(present),"demography",List.of(Map.of("demography.sex","MALE")))));
        check(!matches(result.query(),Map.of("condition",List.of(absent,other),"demography",woman)));
        check(matches(SummaryFilterCompiler.compile(null).query(),Map.of()));
        check(!matches(SummaryFilterCompiler.compile(diabetes).query(),Map.of()));
        check(matches(SummaryFilterCompiler.compile(leaf("demography|race|ASIAN")).query(),Map.of("demography",List.of(Map.of("demography.race",List.of("ASIAN","WHITE"))))));
        check(SummaryFilterCompiler.compile(Map.of("or",List.of(diabetes,hypertension))).requiredIds().isEmpty());
        for (var bad : List.of(Map.<String,Object>of(), Map.<String,Object>of("or",List.of()),
                Map.<String,Object>of("and",List.of(female),"or",List.of(female)),
                Map.<String,Object>of("summaryId","bad"), leaf("measurement|x"), leaf("demography|untrusted_field|x"),
                Map.<String,Object>of("or",Collections.singletonList(null)))) invalid(bad);
        Map<String,Object> deep = female;
        for(int i=0;i<8;i++) deep=Map.of("and",List.of(deep));
        invalid(deep);
        invalid(Map.of("and",Collections.nCopies(100,female)));
        System.out.println("PASS: AND/OR population semantics, same-record matching, multivalued race, match_all, required IDs, invalid inputs and limits");
    }
}
