package com.atlas.liquidity.refdata.domain;

/**
 * A regulatory jurisdiction, and the data-residency region its records must
 * stay inside.
 *
 * <p>This tiny enum is doing something the job description calls out explicitly:
 * "data sovereignty compliance ... across jurisdictions with differing
 * regulatory requirements". By Layer 11, {@link #residencyRegion()} becomes the
 * routing key that decides which regional cluster is allowed to hold an
 * account's position data. Modelling it in the domain from Layer 1 - rather than
 * treating residency as a deployment concern discovered late - is the whole
 * point.
 */
public enum Jurisdiction {

    US("us-east", "United States"),
    UK("uk-south", "United Kingdom"),
    EU("eu-central", "European Union"),
    SG("apac-southeast", "Singapore"),
    HK("apac-east", "Hong Kong"),
    IN("in-west", "India");

    private final String residencyRegion;
    private final String displayName;

    Jurisdiction(String residencyRegion, String displayName) {
        this.residencyRegion = residencyRegion;
        this.displayName = displayName;
    }

    /** The region whose data centres are permitted to persist this jurisdiction's data. */
    public String residencyRegion() {
        return residencyRegion;
    }

    public String displayName() {
        return displayName;
    }
}
