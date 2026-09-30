package cn.court.mediation.service;

import cn.court.mediation.common.BizException;
import cn.court.mediation.entity.MediationCase;
import cn.court.mediation.entity.MediationRoom;
import cn.court.mediation.entity.MediationSchedule;
import cn.court.mediation.entity.RescheduleProposal;
import cn.court.mediation.entity.User;
import cn.court.mediation.enums.*;
import cn.court.mediation.repository.CaseRepository;
import cn.court.mediation.repository.RescheduleProposalRepository;
import cn.court.mediation.repository.RoomRepository;
import cn.court.mediation.repository.ScheduleRepository;
import cn.court.mediation.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ScheduleService {

    /** 有效（会产生占用）的排期状态 */
    private static final List<ScheduleStatus> ACTIVE_STATUSES = List.of(ScheduleStatus.PLANNED);
    /** 候选起始时间对齐网格（分钟） */
    private static final int GRID_MINUTES = 30;
    /** 单次重匹配最多返回候选方案数 */
    private static final int MAX_OPTIONS = 20;

    private final ScheduleRepository scheduleRepository;
    private final CaseRepository caseRepository;
    private final UserRepository userRepository;
    private final RoomRepository roomRepository;
    private final RescheduleProposalRepository proposalRepository;
    private final TimelineService timelineService;

    public ScheduleService(ScheduleRepository scheduleRepository, CaseRepository caseRepository,
                           UserRepository userRepository, RoomRepository roomRepository,
                           RescheduleProposalRepository proposalRepository, TimelineService timelineService) {
        this.scheduleRepository = scheduleRepository;
        this.caseRepository = caseRepository;
        this.userRepository = userRepository;
        this.roomRepository = roomRepository;
        this.proposalRepository = proposalRepository;
        this.timelineService = timelineService;
    }

    // ==================== 调解员推荐 ====================

    /**
     * 推荐调解员：
     * 1) 案由与专长匹配；2) 外地当事人+愿意线上时优先线上渠道；
     * 3) 在办量未超 capacity；4) 指定时间段无冲突。按在办量升序返回推荐列表。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> recommendMediators(Long caseId, LocalDateTime start, LocalDateTime end) {
        MediationCase c = caseRepository.findById(caseId).orElseThrow(() -> new BizException("案件不存在"));
        List<User> mediators = userRepository.findByRole(Role.MEDIATOR);
        Map<Long, Long> activeCount = activeCountMap(mediators);

        return mediators.stream()
                .map(m -> {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("id", m.getId());
                    view.put("realName", m.getRealName());
                    view.put("expertise", m.getExpertise());
                    view.put("mediationRoom", m.getMediationRoom());
                    view.put("capacity", m.getCapacity());
                    long active = activeCount.getOrDefault(m.getId(), 0L);
                    view.put("activeCount", active);

                    boolean expertiseMatch = m.getExpertise() != null
                            && m.getExpertise().contains(c.getCaseType().name());
                    view.put("expertiseMatch", expertiseMatch);

                    boolean withinCapacity = active < (m.getCapacity() == null ? 20 : m.getCapacity());
                    view.put("withinCapacity", withinCapacity);

                    boolean timeFree = true;
                    if (start != null && end != null) {
                        timeFree = mediatorFree(m.getId(), start, end, null);
                    }
                    view.put("timeFree", timeFree);

                    long score = (expertiseMatch ? 3 : 0) + (withinCapacity ? 1 : 0) + (timeFree ? 2 : 0) * 10 - active;
                    view.put("score", score);
                    return view;
                })
                .sorted(Comparator.comparingLong(v -> -((Number) v.get("score")).longValue()))
                .collect(Collectors.toList());
    }

    private Map<Long, Long> activeCountMap(List<User> mediators) {
        Map<Long, Long> activeCount = new HashMap<>();
        for (User m : mediators) {
            long n = caseRepository.findByMediatorId(m.getId()).stream()
                    .filter(x -> x.getStatus() != CaseStatus.PERFORMED_DONE
                            && x.getStatus() != CaseStatus.TRANSFERRED
                            && x.getStatus() != CaseStatus.CLOSED
                            && x.getStatus() != CaseStatus.MEDIATION_FAILED)
                    .count();
            activeCount.put(m.getId(), n);
        }
        return activeCount;
    }

    // ==================== 创建排期 ====================

    /** 创建排期 */
    @Transactional
    public MediationSchedule create(Long caseId, Map<String, Object> body, User operator) {
        MediationCase c = caseRepository.findById(caseId).orElseThrow(() -> new BizException("案件不存在"));
        Long mediatorId = body.get("mediatorId") != null
                ? Long.valueOf(body.get("mediatorId").toString()) : c.getMediatorId();
        if (mediatorId == null) {
            throw new BizException("请先指定调解员");
        }
        User mediator = userRepository.findById(mediatorId)
                .filter(u -> u.getRole() == Role.MEDIATOR)
                .orElseThrow(() -> new BizException("调解员不存在"));
        LocalDateTime start = LocalDateTime.parse(body.get("startTime").toString());
        LocalDateTime end = LocalDateTime.parse(body.get("endTime").toString());
        if (!end.isAfter(start)) {
            throw new BizException("结束时间必须晚于开始时间");
        }
        Channel channel = Channel.valueOf(body.getOrDefault("channel",
                c.getChannel() == null ? Channel.ONLINE.name() : c.getChannel().name()).toString());

        if (!mediatorFree(mediatorId, start, end, null)) {
            throw new BizException("调解员该时段已有排期冲突，请改约其他时间或发起平台重匹配");
        }

        // 资源分配：指定 roomId 则校验占用；否则自动挑选空闲调解室/线上会议
        Long roomId = body.get("roomId") != null ? Long.valueOf(body.get("roomId").toString()) : null;
        MediationRoom room = null;
        if (roomId != null) {
            room = roomRepository.findById(roomId).orElseThrow(() -> new BizException("调解资源不存在"));
            if (room.getChannel() != channel) {
                throw new BizException("所选资源与调解方式不一致");
            }
            if (!roomFree(roomId, start, end, null)) {
                throw new BizException("该时段调解室/线上会议已被占用，请选择其他资源");
            }
        } else {
            room = firstFreeRoom(channel, start, end, null);
        }
        String location = (String) body.get("location");
        if ((location == null || location.isBlank()) && room != null) {
            location = room.getChannel() == Channel.ONLINE
                    ? room.getName() + "（" + nz(room.getMeetingUrl(), "平台视频会议") + "）"
                    : room.getName();
        }
        if (location == null || location.isBlank()) {
            location = channel == Channel.ONLINE ? "线上调解室（法院视频调解平台）" : "院部第一调解室";
        }

        long round = scheduleRepository.findByCaseIdOrderByStartTimeDesc(caseId).size() + 1;
        MediationSchedule s = new MediationSchedule();
        s.setCaseId(caseId);
        s.setMediatorId(mediatorId);
        s.setChannel(channel);
        s.setLocation(location);
        s.setRoomId(room == null ? null : room.getId());
        s.setStartTime(start);
        s.setEndTime(end);
        s.setStatus(ScheduleStatus.PLANNED);
        s.setRoundNo((int) round);
        s.setNote((String) body.get("note"));
        scheduleRepository.save(s);

        c.setMediatorId(mediatorId);
        if (c.getChannel() == null) c.setChannel(channel);
        if (c.getStatus() == CaseStatus.TRIAGED || c.getStatus() == CaseStatus.PENDING_TRIAGE) {
            c.setStatus(CaseStatus.SCHEDULED);
        }
        caseRepository.save(c);

        timelineService.record(caseId, "SCHEDULE", "第" + round + "次调解已排期",
                String.format("时间：%s 至 %s；方式：%s；地点/链接：%s；调解员：%s。%s",
                        start, end, channel.getLabel(), location, mediator.getRealName(),
                        body.get("note") == null ? "" : "备注：" + body.get("note")),
                operator);
        return s;
    }

    // ==================== 直接改期（无冲突/工作人员调整） ====================

    /**
     * 改期：原排期置为 RESCHEDULED 并保留（含取消原因与发起方），另排新期，累计改期次数。
     * 当事人临时变更（PARTY_TEMP）标记 partyCaused，绩效不计入工作人员延误。
     */
    @Transactional
    public MediationSchedule reschedule(Long scheduleId, Map<String, Object> body, User operator) {
        MediationSchedule old = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BizException("排期记录不存在"));
        if (old.getStatus() != ScheduleStatus.PLANNED) {
            throw new BizException("仅待开始的排期可以改期");
        }
        String reason = (String) body.getOrDefault("reason", "双方时间冲突");
        String changeSource = (String) body.getOrDefault("changeSource", "PARTY_TEMP");
        boolean partyCaused = !"STAFF".equals(changeSource) && !"PLATFORM".equals(changeSource);

        LocalDateTime start = LocalDateTime.parse(body.get("startTime").toString());
        LocalDateTime end = LocalDateTime.parse(body.get("endTime").toString());
        if (!end.isAfter(start)) {
            throw new BizException("结束时间必须晚于开始时间");
        }

        Long mediatorId = body.get("mediatorId") != null
                ? Long.valueOf(body.get("mediatorId").toString()) : old.getMediatorId();
        Channel channel = body.get("channel") != null
                ? Channel.valueOf(body.get("channel").toString()) : old.getChannel();
        User mediator = userRepository.findById(mediatorId)
                .filter(u -> u.getRole() == Role.MEDIATOR)
                .orElseThrow(() -> new BizException("调解员不存在"));

        if (!mediatorFree(mediatorId, start, end, scheduleId)) {
            throw new BizException("调解员新时段仍有排期冲突，请使用「冲突重匹配」由平台重新匹配");
        }
        Long roomId = body.get("roomId") != null ? Long.valueOf(body.get("roomId").toString()) : null;
        MediationRoom room = null;
        if (roomId != null) {
            room = roomRepository.findById(roomId).orElseThrow(() -> new BizException("调解资源不存在"));
            if (!roomFree(roomId, start, end, null)) {
                throw new BizException("该时段调解室/线上会议已被占用");
            }
        } else if (old.getRoomId() != null && roomFree(old.getRoomId(), start, end, scheduleId)
                && channel == old.getChannel()) {
            room = roomRepository.findById(old.getRoomId()).orElse(null);
        } else {
            room = firstFreeRoom(channel, start, end, null);
        }
        String location = room != null
                ? (room.getChannel() == Channel.ONLINE
                    ? room.getName() + "（" + nz(room.getMeetingUrl(), "平台视频会议") + "）" : room.getName())
                : old.getLocation();

        applyCancelledOldSchedule(old, reason, changeSource, partyCaused);
        MediationSchedule fresh = insertNewSchedule(old, mediator, channel, room, location, start, end,
                "由第" + old.getRoundNo() + "次改期而来；发起方：" + changeSourceLabel(changeSource));

        MediationCase c = caseRepository.findById(old.getCaseId()).orElseThrow();
        c.setRescheduleCount((c.getRescheduleCount() == null ? 0 : c.getRescheduleCount()) + 1);
        caseRepository.save(c);

        recordRescheduleTimeline(old.getCaseId(), c.getRescheduleCount(), reason, changeSource, partyCaused,
                start, end, mediator.getRealName(), operator);
        return fresh;
    }

    /** 将原排期标记为改期并保留取消原因、发起方 */
    private void applyCancelledOldSchedule(MediationSchedule old, String reason, String changeSource, boolean partyCaused) {
        old.setStatus(ScheduleStatus.RESCHEDULED);
        old.setCancelReason(reason);
        old.setChangeSource(changeSource);
        old.setPartyCaused(partyCaused);
        old.setNote((old.getNote() == null ? "" : old.getNote() + " | ") + "改期原因：" + reason
                + "（发起方：" + changeSourceLabel(changeSource) + "）");
        scheduleRepository.save(old);
    }

    private MediationSchedule insertNewSchedule(MediationSchedule old, User mediator, Channel channel,
                                                MediationRoom room, String location,
                                                LocalDateTime start, LocalDateTime end, String note) {
        MediationSchedule fresh = new MediationSchedule();
        fresh.setCaseId(old.getCaseId());
        fresh.setMediatorId(mediator.getId());
        fresh.setChannel(channel);
        fresh.setRoomId(room == null ? null : room.getId());
        fresh.setLocation(location);
        fresh.setStartTime(start);
        fresh.setEndTime(end);
        fresh.setStatus(ScheduleStatus.PLANNED);
        fresh.setRoundNo(old.getRoundNo() + 1);
        fresh.setNote(note);
        // 新安排需要双方重新确认出席
        fresh.setApplicantConfirmed(false);
        fresh.setRespondentConfirmed(false);
        return scheduleRepository.save(fresh);
    }

    private void recordRescheduleTimeline(Long caseId, int count, String reason, String changeSource,
                                          boolean partyCaused, LocalDateTime start, LocalDateTime end,
                                          String mediatorName, User operator) {
        String warn = count >= 2
                ? "【多次改期预警：已累计" + count + "次，已进入调解效率风险与案件分流建议，待窗口人员决定是否继续调解】"
                : "";
        String liability = partyCaused
                ? "本次为当事人/双方临时变更，保留取消原因与双方确认，不计入调解员工作人员延误。"
                : "本次为工作人员原因改期，计入调解员排班延误记录。";
        timelineService.record(caseId, "RESCHEDULE",
                "第" + count + "次改期",
                String.format("取消原因：%s（发起方：%s）；新排期：%s 至 %s，调解员：%s。%s %s",
                        reason, changeSourceLabel(changeSource), start, end, mediatorName, liability, warn),
                operator);
    }

    // ==================== 冲突检测 ====================

    /** 检测给定时段调解员与调解资源冲突（改期弹窗实时提示） */
    @Transactional(readOnly = true)
    public Map<String, Object> checkConflict(Long scheduleId, LocalDateTime start, LocalDateTime end,
                                             Channel channel, Long roomId) {
        MediationSchedule old = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BizException("排期记录不存在"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scheduleId", scheduleId);

        boolean mediatorConflict = !mediatorFree(old.getMediatorId(), start, end, scheduleId);
        m.put("mediatorConflict", mediatorConflict);
        m.put("mediatorId", old.getMediatorId());

        Long checkRoom = roomId;
        if (checkRoom == null) checkRoom = old.getRoomId();
        boolean roomConflict = checkRoom != null && !roomFree(checkRoom, start, end, scheduleId);
        m.put("roomConflict", roomConflict);
        m.put("roomId", checkRoom);

        // 同时给出该时段可用的同类资源
        Channel ch = channel != null ? channel : old.getChannel();
        List<Map<String, Object>> freeRooms = new ArrayList<>();
        for (MediationRoom r : roomRepository.findByChannelAndEnabledTrueOrderByIdAsc(ch)) {
            if (roomFree(r.getId(), start, end, scheduleId)) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("id", r.getId());
                v.put("name", r.getName());
                v.put("channel", r.getChannel().name());
                v.put("meetingUrl", r.getMeetingUrl());
                freeRooms.add(v);
            }
        }
        m.put("freeRooms", freeRooms);
        m.put("needRematch", mediatorConflict);
        return m;
    }

    // ==================== 平台重匹配 ====================

    /**
     * 双方临时改期导致调解员排班冲突时的平台重匹配：
     * 在申请人、被申请人填报可用时间段的交集（且落在资源开放时段）内，
     * 组合空闲调解员与空闲调解室/线上会议，按综合分返回候选方案。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> rematchOptions(Long caseId, Map<String, Object> body) {
        MediationCase c = caseRepository.findById(caseId).orElseThrow(() -> new BizException("案件不存在"));
        Long scheduleId = body.get("scheduleId") != null ? Long.valueOf(body.get("scheduleId").toString()) : null;
        MediationSchedule old = scheduleId == null ? latestPlanned(caseId)
                : scheduleRepository.findById(scheduleId).orElseThrow(() -> new BizException("排期记录不存在"));
        if (old == null) {
            throw new BizException("当前没有待开始的排期，无法重匹配");
        }

        List<TimeRange> applicantSlots = parseSlots(body.get("applicantSlots"));
        List<TimeRange> respondentSlots = parseSlots(body.get("respondentSlots"));
        if (applicantSlots.isEmpty() || respondentSlots.isEmpty()) {
            throw new BizException("请先填写双方可用时间段");
        }
        int duration = body.get("durationMinutes") != null
                ? Integer.parseInt(body.get("durationMinutes").toString())
                : (int) java.time.Duration.between(old.getStartTime(), old.getEndTime()).toMinutes();
        if (duration <= 0) duration = 60;
        Channel preferred = body.get("channel") != null
                ? Channel.valueOf(body.get("channel").toString()) : old.getChannel();

        // 双方可用时间交集
        List<TimeRange> intersections = intersect(applicantSlots, respondentSlots);

        List<User> mediators = userRepository.findByRole(Role.MEDIATOR);
        Map<Long, Long> activeCount = activeCountMap(mediators);
        List<MediationRoom> rooms = roomRepository.findByEnabledTrueOrderByChannelAscIdAsc();

        List<Map<String, Object>> options = new ArrayList<>();
        for (TimeRange win : intersections) {
            // 同一调解时长下，按资源开放时段逐日裁剪、按 30 分钟网格枚举起点
            for (MediationRoom room : rooms) {
                if (room.getChannel() != preferred) continue;
                for (LocalDateTime s : candidateStarts(win, room.getOpenHours(), duration)) {
                    LocalDateTime e = s.plusMinutes(duration);
                    for (User m : mediators) {
                        if (!mediatorFree(m.getId(), s, e, old.getId())) continue;
                        if (!roomFree(room.getId(), s, e, old.getId())) continue;
                        options.add(buildOption(c, m, room, s, e, activeCount.getOrDefault(m.getId(), 0L),
                                old.getMediatorId(), preferred));
                        if (options.size() >= MAX_OPTIONS * 3) break;
                    }
                }
            }
        }
        // 去重（同调解员+同资源+同开始时间）并取分最高的前 N 个
        List<Map<String, Object>> top = options.stream()
                .collect(Collectors.toMap(
                        o -> o.get("mediatorId") + ":" + o.get("roomId") + ":" + o.get("startTime"),
                        o -> o, (a, b) -> ((Number) a.get("score")).longValue() >= ((Number) b.get("score")).longValue() ? a : b,
                        LinkedHashMap::new))
                .values().stream()
                .sorted(Comparator.comparingLong(v -> -((Number) v.get("score")).longValue()))
                .limit(MAX_OPTIONS)
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scheduleId", old.getId());
        result.put("intersections", intersections.stream().map(r -> {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("start", r.start);
            v.put("end", r.end);
            return v;
        }).collect(Collectors.toList()));
        result.put("options", top);
        if (top.isEmpty()) {
            result.put("message", intersections.isEmpty()
                    ? "双方填报的可用时间没有交集，请补充可用时间段后重新匹配"
                    : "交集时段内没有同时空闲的调解员与调解资源，请扩大时间范围或改约其他日期");
        }
        return result;
    }

    private Map<String, Object> buildOption(MediationCase c, User m, MediationRoom room,
                                            LocalDateTime s, LocalDateTime e, long active,
                                            Long currentMediatorId, Channel preferred) {
        boolean expertiseMatch = m.getExpertise() != null && m.getExpertise().contains(c.getCaseType().name());
        boolean withinCapacity = active < (m.getCapacity() == null ? 20 : m.getCapacity());
        boolean sameMediator = m.getId().equals(currentMediatorId);
        boolean onlineFit = room.getChannel() == Channel.ONLINE
                && (Boolean.TRUE.equals(c.getRespondentOutOfTown()) || Boolean.TRUE.equals(c.getOnlineWilling()));

        int score = (expertiseMatch ? 30 : 0) + (withinCapacity ? 10 : 0)
                + (sameMediator ? 20 : 0) + (onlineFit ? 5 : 0) - (int) active;

        List<String> notes = new ArrayList<>();
        if (sameMediator) notes.add("沿用原调解员");
        if (expertiseMatch) notes.add("案由专长匹配");
        if (onlineFit) notes.add("外地/线上意愿适配");
        notes.add("在办" + active + "件");

        Map<String, Object> o = new LinkedHashMap<>();
        o.put("mediatorId", m.getId());
        o.put("mediatorName", m.getRealName());
        o.put("roomId", room.getId());
        o.put("roomName", room.getName());
        o.put("channel", room.getChannel().name());
        o.put("meetingUrl", room.getMeetingUrl());
        o.put("location", room.getChannel() == Channel.ONLINE
                ? room.getName() + "（" + nz(room.getMeetingUrl(), "平台视频会议") + "）" : room.getName());
        o.put("startTime", s);
        o.put("endTime", e);
        o.put("score", score);
        o.put("matchNote", String.join("、", notes));
        o.put("preferred", preferred == room.getChannel());
        return o;
    }

    /** 提交重匹配建议（调解员/窗口填报），等待窗口人员确认分流 */
    @Transactional
    public RescheduleProposal propose(Long caseId, Map<String, Object> body, User operator) {
        MediationCase c = caseRepository.findById(caseId).orElseThrow(() -> new BizException("案件不存在"));
        Long scheduleId = Long.valueOf(body.get("scheduleId").toString());
        MediationSchedule old = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BizException("排期记录不存在"));
        if (old.getStatus() != ScheduleStatus.PLANNED) {
            throw new BizException("仅待开始的排期可以发起改期重匹配");
        }
        LocalDateTime start = LocalDateTime.parse(body.get("startTime").toString());
        LocalDateTime end = LocalDateTime.parse(body.get("endTime").toString());
        Long mediatorId = Long.valueOf(body.get("mediatorId").toString());
        Long roomId = Long.valueOf(body.get("roomId").toString());
        Channel channel = Channel.valueOf(body.get("channel").toString());
        if (!mediatorFree(mediatorId, start, end, scheduleId)) {
            throw new BizException("该候选调解员时段已变化，请重新匹配");
        }
        if (!roomFree(roomId, start, end, scheduleId)) {
            throw new BizException("该候选调解资源时段已被占用，请重新匹配");
        }

        String cancelReason = (String) body.getOrDefault("cancelReason", "双方临时改期");
        String changeSource = (String) body.getOrDefault("changeSource", "PARTY_TEMP");

        RescheduleProposal p = new RescheduleProposal();
        p.setCaseId(caseId);
        p.setFromScheduleId(scheduleId);
        p.setCancelReason(cancelReason);
        p.setChangeSource(changeSource);
        p.setMediatorId(mediatorId);
        p.setRoomId(roomId);
        p.setChannel(channel);
        p.setLocation((String) body.get("location"));
        p.setStartTime(start);
        p.setEndTime(end);
        p.setScore(body.get("score") != null ? Integer.valueOf(body.get("score").toString()) : 0);
        p.setMatchNote((String) body.get("matchNote"));
        p.setStatus("PROPOSED");
        p.setProposedBy(operator.getId());
        proposalRepository.save(p);

        timelineService.record(caseId, "RESCHEDULE", "平台完成改期重匹配，待窗口确认分流",
                String.format("因%s（发起方：%s）调解员排班冲突，平台在双方可用时间交集内重新匹配：%s %s 至 %s（%s），待窗口人员确认。",
                        cancelReason, changeSourceLabel(changeSource),
                        body.getOrDefault("mediatorName", "调解员"), channel.getLabel(), start, end,
                        body.getOrDefault("roomName", p.getLocation())),
                operator);
        return p;
    }

    @Transactional(readOnly = true)
    public List<RescheduleProposal> caseProposals(Long caseId) {
        return proposalRepository.findByCaseIdOrderByProposedAtDesc(caseId);
    }

    /** 窗口人员确认分流：按平台建议落定新排期，原排期保留取消原因，双方确认留痕，累计改期 */
    @Transactional
    public RescheduleProposal confirmProposal(Long proposalId, User operator) {
        RescheduleProposal p = proposalRepository.findById(proposalId)
                .orElseThrow(() -> new BizException("重匹配建议不存在"));
        if (!"PROPOSED".equals(p.getStatus())) {
            throw new BizException("该建议已处理");
        }
        MediationSchedule old = scheduleRepository.findById(p.getFromScheduleId())
                .orElseThrow(() -> new BizException("原排期不存在"));
        if (old.getStatus() != ScheduleStatus.PLANNED) {
            throw new BizException("原排期状态已变化，不能按该建议改期");
        }
        if (!mediatorFree(p.getMediatorId(), p.getStartTime(), p.getEndTime(), old.getId())) {
            p.setStatus("EXPIRED");
            p.setRejectReason("确认时调解员时段已被占用");
            proposalRepository.save(p);
            throw new BizException("候选调解员时段刚刚被占用，建议已失效，请重新匹配");
        }
        MediationRoom room = roomRepository.findById(p.getRoomId()).orElse(null);
        if (room == null || !roomFree(room.getId(), p.getStartTime(), p.getEndTime(), old.getId())) {
            p.setStatus("EXPIRED");
            p.setRejectReason("确认时调解资源已被占用");
            proposalRepository.save(p);
            throw new BizException("候选调解资源刚刚被占用，建议已失效，请重新匹配");
        }

        boolean partyCaused = !"STAFF".equals(p.getChangeSource()) && !"PLATFORM".equals(p.getChangeSource());
        applyCancelledOldSchedule(old, p.getCancelReason(), p.getChangeSource(), partyCaused);
        old.setWindowConfirmed(true);
        scheduleRepository.save(old);

        User mediator = userRepository.findById(p.getMediatorId()).orElseThrow();
        String location = p.getLocation() != null ? p.getLocation()
                : (room.getChannel() == Channel.ONLINE
                    ? room.getName() + "（" + nz(room.getMeetingUrl(), "平台视频会议") + "）" : room.getName());
        MediationSchedule fresh = insertNewSchedule(old, mediator, p.getChannel(), room, location,
                p.getStartTime(), p.getEndTime(),
                "平台重匹配第" + (old.getRoundNo() + 1) + "次调解；窗口确认分流；双方已确认改期意向");

        MediationCase c = caseRepository.findById(p.getCaseId()).orElseThrow();
        c.setMediatorId(mediator.getId());
        c.setChannel(p.getChannel());
        c.setRescheduleCount((c.getRescheduleCount() == null ? 0 : c.getRescheduleCount()) + 1);
        caseRepository.save(c);

        p.setStatus("CONFIRMED");
        p.setConfirmedBy(operator.getId());
        p.setConfirmedAt(LocalDateTime.now());
        p.setNewScheduleId(fresh.getId());
        proposalRepository.save(p);

        String liability = partyCaused
                ? "本次改期源于当事人/双方临时变更，调解员绩效不计工作人员延误。"
                : "本次改期源于工作人员安排调整，计入调解员排班延误。";
        String warn = c.getRescheduleCount() >= 2
                ? "案件已累计改期" + c.getRescheduleCount() + "次，进入调解效率风险分流建议。"
                : "";
        timelineService.record(p.getCaseId(), "RESCHEDULE",
                "窗口确认分流，第" + c.getRescheduleCount() + "次改期完成",
                String.format("窗口人员%s确认平台重匹配方案：%s %s 至 %s，地点/会议：%s；"
                                + "原排期取消原因「%s」与双方改期确认均已保留。%s %s",
                        operator.getRealName(), mediator.getRealName(), p.getStartTime(), p.getEndTime(), location,
                        p.getCancelReason(), liability, warn),
                operator);
        return p;
    }

    /** 窗口驳回重匹配建议（需重新匹配或另行安排） */
    @Transactional
    public RescheduleProposal rejectProposal(Long proposalId, String reason, User operator) {
        RescheduleProposal p = proposalRepository.findById(proposalId)
                .orElseThrow(() -> new BizException("重匹配建议不存在"));
        if (!"PROPOSED".equals(p.getStatus())) {
            throw new BizException("该建议已处理");
        }
        p.setStatus("REJECTED");
        p.setRejectReason(reason);
        proposalRepository.save(p);
        timelineService.record(p.getCaseId(), "RESCHEDULE", "窗口驳回改期重匹配建议",
                "驳回原因：" + nz(reason, "未填写") + "，可重新发起匹配或线下协调。", operator);
        return p;
    }

    // ==================== 出席确认 ====================

    /** 当事人确认出席（改期后的新安排需双方重新确认） */
    @Transactional
    public void confirmAttend(Long scheduleId, String party, User operator) {
        MediationSchedule s = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BizException("排期不存在"));
        if ("APPLICANT".equals(party)) {
            s.setApplicantConfirmed(true);
            s.setApplicantConfirmedAt(LocalDateTime.now());
        } else {
            s.setRespondentConfirmed(true);
            s.setRespondentConfirmedAt(LocalDateTime.now());
        }
        scheduleRepository.save(s);
        timelineService.record(s.getCaseId(), "SCHEDULE",
                ("APPLICANT".equals(party) ? "申请人" : "被申请人") + "确认出席",
                "确认参加 " + s.getStartTime() + " 的调解（" + nz(s.getLocation(), "地点待定") + "）。", operator);
    }

    @Transactional(readOnly = true)
    public List<MediationSchedule> caseSchedules(Long caseId) {
        return scheduleRepository.findByCaseIdOrderByStartTimeDesc(caseId);
    }

    // ==================== 工具方法 ====================

    private boolean mediatorFree(Long mediatorId, LocalDateTime start, LocalDateTime end, Long excludeScheduleId) {
        List<MediationSchedule> conflict = scheduleRepository
                .findByMediatorIdAndStatusInAndStartTimeLessThanAndEndTimeGreaterThan(
                        mediatorId, ACTIVE_STATUSES, end, start);
        conflict.removeIf(x -> excludeScheduleId != null && x.getId().equals(excludeScheduleId));
        return conflict.isEmpty();
    }

    private boolean roomFree(Long roomId, LocalDateTime start, LocalDateTime end, Long excludeScheduleId) {
        List<MediationSchedule> conflict = scheduleRepository
                .findByRoomIdAndStatusInAndStartTimeLessThanAndEndTimeGreaterThan(
                        roomId, ACTIVE_STATUSES, end, start);
        conflict.removeIf(x -> excludeScheduleId != null && x.getId().equals(excludeScheduleId));
        return conflict.isEmpty();
    }

    private MediationRoom firstFreeRoom(Channel channel, LocalDateTime start, LocalDateTime end, Long excludeScheduleId) {
        for (MediationRoom r : roomRepository.findByChannelAndEnabledTrueOrderByIdAsc(channel)) {
            if (roomFree(r.getId(), start, end, excludeScheduleId)) return r;
        }
        return null;
    }

    private MediationSchedule latestPlanned(Long caseId) {
        return scheduleRepository.findByCaseIdOrderByStartTimeDesc(caseId).stream()
                .filter(s -> s.getStatus() == ScheduleStatus.PLANNED)
                .findFirst().orElse(null);
    }

    private List<TimeRange> parseSlots(Object raw) {
        List<TimeRange> list = new ArrayList<>();
        if (raw instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> m && m.get("start") != null && m.get("end") != null) {
                    LocalDateTime s = LocalDateTime.parse(m.get("start").toString());
                    LocalDateTime e = LocalDateTime.parse(m.get("end").toString());
                    if (e.isAfter(s)) list.add(new TimeRange(s, e));
                }
            }
        }
        return list;
    }

    /** 两组时间段的交集 */
    private List<TimeRange> intersect(List<TimeRange> a, List<TimeRange> b) {
        List<TimeRange> out = new ArrayList<>();
        for (TimeRange x : a) {
            for (TimeRange y : b) {
                LocalDateTime s = x.start.isAfter(y.start) ? x.start : y.start;
                LocalDateTime e = x.end.isBefore(y.end) ? x.end : y.end;
                if (e.isAfter(s)) out.add(new TimeRange(s, e));
            }
        }
        return out;
    }

    /**
     * 在交集窗口内，按资源开放时段逐日裁剪，并按 30 分钟网格枚举能容纳 duration 的起点。
     */
    private List<LocalDateTime> candidateStarts(TimeRange win, String openHours, int durationMinutes) {
        List<LocalDateTime> starts = new ArrayList<>();
        String[] parts = openHours == null ? new String[]{"08:30", "17:30"} : openHours.split("-");
        LocalTime open = LocalTime.parse(parts[0].trim());
        LocalTime close = LocalTime.parse(parts[1].trim());
        LocalDate d = win.start.toLocalDate();
        LocalDate last = win.end.toLocalDate();
        int guard = 0;
        while (!d.isAfter(last) && guard++ < 14) {
            LocalDateTime dayStart = win.start.toLocalDate().equals(d) ? win.start : d.atTime(open);
            LocalDateTime dayEnd = win.end.toLocalDate().equals(d) ? win.end : d.atTime(close);
            if (dayStart.toLocalTime().isBefore(open)) dayStart = d.atTime(open);
            if (dayEnd.toLocalTime().isAfter(close)) dayEnd = d.atTime(close);
            LocalDateTime s = roundUp(dayStart);
            while (!s.plusMinutes(durationMinutes).isAfter(dayEnd) && starts.size() < 12) {
                if (!s.isBefore(dayStart)) starts.add(s);
                s = s.plusMinutes(GRID_MINUTES);
            }
            d = d.plusDays(1);
        }
        return starts;
    }

    private LocalDateTime roundUp(LocalDateTime t) {
        int m = t.getMinute();
        int add = m == 0 ? 0 : GRID_MINUTES - (m % GRID_MINUTES);
        LocalDateTime r = t.plusMinutes(add).withSecond(0).withNano(0);
        if (t.getSecond() == 0 && t.getNano() == 0 && m % GRID_MINUTES == 0) r = t.withSecond(0).withNano(0);
        return r;
    }

    private String nz(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }

    static String changeSourceLabel(String src) {
        return switch (src == null ? "" : src) {
            case "PARTY_TEMP" -> "当事人/双方临时变更";
            case "STAFF" -> "工作人员（调解员/窗口）";
            case "PLATFORM" -> "平台";
            default -> "当事人/双方临时变更";
        };
    }

    private record TimeRange(LocalDateTime start, LocalDateTime end) {
    }
}
