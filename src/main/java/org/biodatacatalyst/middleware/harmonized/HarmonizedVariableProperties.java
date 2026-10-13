package org.biodatacatalyst.middleware.harmonized;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("harmonized")
public record HarmonizedVariableProperties(

        @DefaultValue("true") boolean enabled,
        @DefaultValue("classpath*:harmonized/*-ingest/*.yaml") String ingest,
        @DefaultValue("classpath:harmonized/harmonized_vars.tsv") String variableTable,
        @DefaultValue("25") int maxMatches) {

    public HarmonizedVariableProperties {
        if (maxMatches < 1) {
            throw new IllegalArgumentException("harmonized.max-matches must be at least 1");
        }
    }
}
