package org.biodatacatalyst.middleware.monarch;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MonarchProperties.class)
public class MonarchConfiguration {}
