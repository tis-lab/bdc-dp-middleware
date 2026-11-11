package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.search;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SearchProperties.class)
public class SearchConfiguration {
    @Bean public ElasticsearchTransport elasticsearchTransport(SearchProperties properties) { return new ElasticsearchTransport(properties); }

    @Bean
    public MetadataSearch metadataSearch(ElasticsearchTransport transport) {
        return new ElasticsearchMetadataSearch(transport);
    }
}
