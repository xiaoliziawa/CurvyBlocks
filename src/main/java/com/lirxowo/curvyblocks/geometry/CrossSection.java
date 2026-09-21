package com.lirxowo.curvyblocks.geometry;

public enum CrossSection {
    ROUND,
    SQUARE;

    public double area(double diameter) {
        double square = diameter * diameter;
        return this == ROUND ? square * Math.PI / 4.0 : square;
    }

    public double outerRadius(double diameter) {
        return diameter * 0.5 * (this == SQUARE ? Math.sqrt(2.0) : 1.0);
    }

    public String translationKey() {
        return this == ROUND ? "curvyblocks.shape.round" : "curvyblocks.shape.square";
    }
}
