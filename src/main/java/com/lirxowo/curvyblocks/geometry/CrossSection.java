package com.lirxowo.curvyblocks.geometry;

public enum CrossSection {
    ROUND,
    SQUARE;

    public double area(double diameter) {
        double square = diameter * diameter;
        return this == ROUND ? square * Math.PI / 4.0 : square;
    }

    public String translationKey() {
        return this == ROUND ? "curvyblocks.shape.round" : "curvyblocks.shape.square";
    }
}
