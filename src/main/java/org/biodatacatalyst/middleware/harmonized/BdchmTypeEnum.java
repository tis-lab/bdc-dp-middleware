package org.biodatacatalyst.middleware.harmonized;

/** Values match LinkML BdchmTypeEnum, including its lowercase spelling. */
public enum BdchmTypeEnum {
    observation, condition, procedure, exposure, drugexposure;

    public static BdchmTypeEnum fromIndex(String value) {
        if (value == null) return null;
        try {
            return valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Unknown bdchmType in harmonized index: " + value
                    + "; regenerate with tools/build_harmonized_index.py", exception);
        }
    }
}
