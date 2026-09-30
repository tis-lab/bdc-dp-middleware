package org.biodatacatalyst.middleware.monarch;

import org.junit.jupiter.api.Test;

/**
 * JUnit entry point for {@link MonarchContractChecks}, which holds the assertions themselves so
 * they can also be run from a plain {@code java} command.
 */
class MonarchClientTest {

    @Test
    void verifiesRestContractAndFailures() throws Exception {
        MonarchContractChecks.run();
    }
}
