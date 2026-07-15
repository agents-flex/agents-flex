/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.core.model.image;

/**
 * 图片上的矩形框选区域，坐标使用输入图片的绝对像素值。
 * <p>坐标原点位于图片左上角，X 轴向右、Y 轴向下。左上角为 {@code (x1, y1)}，
 * 右下角为 {@code (x2, y2)}；合法区域要求右下角严格位于左上角的右下方。</p>
 */
public class ImageBoundingBox {
    /** 矩形左边界的 X 坐标，单位为像素。 */
    private int x1;
    /** 矩形上边界的 Y 坐标，单位为像素。 */
    private int y1;
    /** 矩形右边界的 X 坐标，单位为像素。 */
    private int x2;
    /** 矩形下边界的 Y 坐标，单位为像素。 */
    private int y2;

    /** 创建一个坐标均为 0 的空区域，通常供反序列化框架使用。 */
    public ImageBoundingBox() {}

    /**
     * 使用四个边界坐标创建矩形区域。
     *
     * @param x1 左边界 X 坐标
     * @param y1 上边界 Y 坐标
     * @param x2 右边界 X 坐标
     * @param y2 下边界 Y 坐标
     */
    public ImageBoundingBox(int x1, int y1, int x2, int y2) {
        this.x1 = x1;
        this.y1 = y1;
        this.x2 = x2;
        this.y2 = y2;
    }

    /**
     * 创建矩形区域的便捷工厂方法。
     *
     * @return 包含给定坐标的新区域对象
     */
    public static ImageBoundingBox of(int x1, int y1, int x2, int y2) {
        return new ImageBoundingBox(x1, y1, x2, y2);
    }

    /**
     * 判断坐标是否构成非空矩形。
     *
     * @return 坐标均非负且 {@code x2 > x1}、{@code y2 > y1} 时返回 {@code true}
     */
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
