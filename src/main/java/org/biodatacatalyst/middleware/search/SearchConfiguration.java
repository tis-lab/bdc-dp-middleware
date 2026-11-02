package org.biodatacatalyst.middleware.search;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SearchProperties.class)
public class SearchConfiguration {
    @Bean
    public MetadataSearch metadataSearch(SearchProperties properties) {
        return new ElasticsearchMetadataSearch(properties);
    }
}
