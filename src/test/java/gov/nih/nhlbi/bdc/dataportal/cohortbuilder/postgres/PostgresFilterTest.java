package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.postgres;

import java.util.*;
import org.junit.jupiter.api.Test;
import gov.nih.nhlbi.bdc.dataportal.cohortbuilder.shared.ApiException;
import static org.assertj.core.api.Assertions.*;

class PostgresFilterTest {
    static Map<String,Object> condition(String concept,String selection) {
        return Map.of("condition",Map.of("concept",concept,"selection",selection));
    }
    @Test void conditionIsBoundAndNeverInterpolated() {
        var compiled=PostgresFilter.compile(condition("ATC:C03","INCLUDE"));
        assertThat(compiled.sql()).contains("EXISTS", "e.source_class='Condition'").doesNotContain("ATC:C03");
        assertThat(compiled.parameters()).containsExactly("ATC:C03","ONESELF","PRESENT");
        assertThat(PostgresFilter.compile(condition("UPHENO:7000207","EXCLUDE")).parameters())
                .containsExactly("UPHENO:7000207","ONESELF","ABSENT");
    }
    @Test void requiredIdsFollowBooleanBranches() {
        var a=condition("HP:1","INCLUDE"); var b=condition("HP:2","EXCLUDE");
        assertThat(PostgresFilter.compile(Map.of("and",List.of(a,b))).requiredIds()).hasSize(2);
        assertThat(PostgresFilter.compile(Map.of("or",List.of(a,b))).requiredIds()).isEmpty();
        assertThat(PostgresFilter.compile(Map.of("or",List.of(a,Map.of("and",List.of(a,b))))).requiredIds()).hasSize(1);
    }
    @Test void rejectsMalformedAndOverlyComplexFilters() {
        for (Map<String,Object> bad:List.<Map<String,Object>>of(Map.of(),Map.of("or",List.of()),
                Map.of("condition",Map.of("concept","HP:1")),
                Map.of("condition",Map.of("concept","HP:1","selection","INCLUDE","status","ABSENT")),
                Map.of("summaryId","bad"),Map.of("unsupported","x")))
            assertThatThrownBy(()->PostgresFilter.compile(bad)).isInstanceOf(ApiException.class);
        Map<String,Object> nested=condition("HP:1","INCLUDE");
        for(int i=0;i<8;i++) nested=Map.of("and",List.of(nested));
        var tooDeep=nested;
        assertThatThrownBy(()->PostgresFilter.compile(tooDeep)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->PostgresFilter.compile(Map.of("or",Collections.nCopies(100,condition("HP:1","INCLUDE")))))
                .isInstanceOf(ApiException.class);
    }
    @Test void defaultsToNoSelectionAndValidatesFocus() {
        assertThat(PostgresFilter.compile(null).sql()).isEqualTo("TRUE");
        assertThat(PostgresFilter.focus(List.of("HP:1","HP:1"))).containsExactly("HP:1");
        assertThatThrownBy(()->PostgresFilter.focus(List.of("not a curie"))).isInstanceOf(ApiException.class);
    }
    @Test void disabledModeDoesNotConnect() {
        var settings=new PostgresProperties(false,false,"","","","",0,15,10000);
        assertThatThrownBy(()->new PostgresSearchService(settings).search(null,null))
                .isInstanceOf(ApiException.class).hasMessage("PostgreSQL search is disabled.");
        assertThatThrownBy(()->new PostgresProperties(true,false,"jdbc:postgresql://localhost/test","x","x","r",1,15,10000))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
