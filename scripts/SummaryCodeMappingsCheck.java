package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.summary;

import java.util.*;

/** Schema mapping/query-shape checks; real Painless execution must also be tested in ES. */
public class SummaryCodeMappingsCheck {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        for (String field : List.of("condition.relationship_to_participant", "demography.sex", "demography.race", "demography.ethnicity")) {
            var terms = SummaryCodeMappings.terms(field,100);
            var script = (Map<String,Object>)terms.get("script");
            var params = (Map<String,Object>)script.get("params");
            var aliases = (Map<String,String>)params.get("aliases");
            check(params.get("field").equals(field));
            for (var alias : aliases.entrySet()) {
                var predicate = SummaryCodeMappings.predicate(field,alias.getValue());
                check(predicate.equals(SummaryCodeMappings.predicate(field,alias.getKey())));
                var values = (List<?>)((Map<?,?>)predicate.get("terms")).get(field);
                check(values.contains(alias.getKey()) && values.contains(alias.getValue()));
                System.out.println(field + "\t" + alias.getValue() + "\t" + alias.getKey());
            }
        }
        check(SummaryCodeMappings.predicate("condition.relationship_to_participant", "ONESELF")
                .equals(Map.of("term",Map.of("condition.relationship_to_participant","ONESELF"))));
        check(SummaryCodeMappings.terms("condition.condition_concept",100).containsKey("field"));
    }
}
