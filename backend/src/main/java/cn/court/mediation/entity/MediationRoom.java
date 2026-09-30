package cn.court.mediation.entity;

import cn.court.mediation.enums.Channel;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * 调解资源：线下调解室或线上视频会议室。
 * 排班/重匹配时按时间段校验占用，避免调解员排班冲突叠加资源冲突。
 */
@Getter
@Setter
@Entity
@Table(name = "mediation_room", indexes = {
        @Index(name = "idx_room_channel", columnList = "channel"),
        @Index(name = "idx_room_enabled", columnList = "enabled")
})
public class MediationRoom {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 资源名称，如 院部第一调解室 / 线上视频会议室A */
    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(length = 10, nullable = false)
    private Channel channel;

    /** 容纳人数 */
    private Integer capacity = 10;

    /** 线上会议入口（线下为空） */
    @Column(length = 300)
    private String meetingUrl;

    /** 工作日可用时段（24小时制），如 08:30-17:30 */
    @Column(length = 20)
    private String openHours = "08:30-17:30";

    private Boolean enabled = true;

    private java.time.LocalDateTime createdAt = java.time.LocalDateTime.now();
}
