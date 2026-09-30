package cn.court.mediation.entity;

import cn.court.mediation.enums.ResourceType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 调解资源：线下调解室或线上视频会议（线上调解室）。
 * 同一资源同一时段只能承载一场调解，排期/改期时参与冲突检测与平台重新匹配。
 */
@Getter
@Setter
@Entity
@Table(name = "meeting_resource", indexes = {
        @Index(name = "idx_resource_type", columnList = "type")
})
public class MeetingResource {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 资源名称，如：院部第一调解室、线上视频会议室A */
    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private ResourceType type;

    /** 容量/可容纳人数（线下）或并发路数（线上），仅展示用 */
    private Integer capacity = 10;

    /** 固定地点（线下）或线上平台说明（线上） */
    @Column(length = 200)
    private String address;

    /** 是否停用 */
    private Boolean enabled = true;

    private LocalDateTime createdAt = LocalDateTime.now();
}
