package cn.court.mediation.enums;

/** 调解资源类型：线下调解室 / 线上视频会议（线上调解室） */
public enum ResourceType {
    OFFLINE_ROOM("线下调解室"),
    ONLINE_MEETING("线上视频会议");

    private final String label;

    ResourceType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
