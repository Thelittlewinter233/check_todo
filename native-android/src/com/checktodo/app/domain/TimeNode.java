package com.checktodo.app.domain;

/** 单节时间：节次号与起止时间，时间格式 HH:mm。 */
public final class TimeNode {
    public final int node;
    public final String startTime;
    public final String endTime;

    public TimeNode(int node, String startTime, String endTime) {
        this.node = node;
        this.startTime = startTime;
        this.endTime = endTime;
    }
}
