package org.biodatacatalyst.middleware.harmonized;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@code harmonized.*} so {@link HarmonizedVariableLibrary} can be built from configuration. */
@Configuration
@EnableConfigurationProperties(HarmonizedVariableProperties.class)
public class HarmonizedVariableConfiguration {}
