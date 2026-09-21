package com.lirxowo.curvyblocks.placement;

public enum PlacementResult {
    OK("ok"),
    INVALID_SHAPE("invalid_shape"),
    TOO_LONG("too_long"),
    TOO_FAR("too_far"),
    OUTSIDE_WORLD("outside_world"),
    UNLOADED("unloaded"),
    INTERSECTS_BLOCK("intersects_block"),
    INTERSECTS_ENTITY("intersects_entity"),
    MISSING_MATERIAL("missing_material"),
    DENIED("denied"),
    CHUNK_LIMIT("chunk_limit"),
    NOT_FOUND("not_found"),
    NOT_OWNER("not_owner"),
    BUSY("busy"),
    ROUTING("routing"),
    ROUTE_NOT_FOUND("route_not_found"),
    ROUTE_LIMIT("route_limit");

    private final String translationKey;

    PlacementResult(String name) {
        translationKey = "curvyblocks.result." + name;
    }

    public String translationKey() {
        return translationKey;
    }
}
