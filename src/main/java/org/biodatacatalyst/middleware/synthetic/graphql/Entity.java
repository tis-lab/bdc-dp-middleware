package org.biodatacatalyst.middleware.synthetic.graphql;


public enum Entity {

    persons("persons"),
    participants("participants"),
    demography("demography"),
    conditions("conditions"),
    visits("visits"),
    drugExposures("drug-exposures"),
    measurements("measurements"),
    measurementSets("measurement-sets");

    private final String key;

    Entity(String key) {
        this.key = key;
    }

    /** The entity key used by the study services internally. */
    public String key() {
        return key;
    }
}
