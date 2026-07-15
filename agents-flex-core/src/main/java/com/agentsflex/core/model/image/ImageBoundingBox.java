/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.core.model.image;

/**
 * A rectangular image region expressed in absolute source-image pixels.
 * The origin is the top-left corner of the image.
 */
public class ImageBoundingBox {
    private int x1;
    private int y1;
    private int x2;
    private int y2;

    public ImageBoundingBox() {}

    public ImageBoundingBox(int x1, int y1, int x2, int y2) {
        this.x1 = x1;
        this.y1 = y1;
        this.x2 = x2;
        this.y2 = y2;
    }

    public static ImageBoundingBox of(int x1, int y1, int x2, int y2) {
        return new ImageBoundingBox(x1, y1, x2, y2);
    }

    public boolean isValid() {
        return x1 >= 0 && y1 >= 0 && x2 > x1 && y2 > y1;
    }

    public int getX1() { return x1; }
    public void setX1(int x1) { this.x1 = x1; }
    public int getY1() { return y1; }
    public void setY1(int y1) { this.y1 = y1; }
    public int getX2() { return x2; }
    public void setX2(int x2) { this.x2 = x2; }
    public int getY2() { return y2; }
    public void setY2(int y2) { this.y2 = y2; }
}
