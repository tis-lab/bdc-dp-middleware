package org.biodatacatalyst.middleware.summary;

import java.math.BigDecimal;
import java.util.List;

public record CohortQuery(List<Criterion> criteria) {

    public List<Criterion> criteria() {
        return criteria == null ? List.of() : criteria;
    }

    public record Criterion(String concept, String occurrence, ValueFilter value) {

        static final String PRESENT = "PRESENT";

        public String occurrence() {
            return occurrence == null ? PRESENT : occurrence;
        }

        boolean isPresent() {
            return PRESENT.equals(occurrence());
        }
    }

    public record ValueFilter(BigDecimal min, BigDecimal max, String unit, String equals) {

        boolean isEmpty() {
            return min == null && max == null && equals == null;
        }

        /** For a notice naming what was ignored. */
        String describe() {
            if (equals != null) {
                return "= " + equals;
            }
            String bounds = (min == null ? "" : ">= " + min) + (min != null && max != null ? " and " : "")
                    + (max == null ? "" : "< " + max);
            return unit == null ? bounds : bounds + " " + unit;
        }
    }
}
