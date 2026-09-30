package cn.court.mediation.service;

import cn.court.mediation.common.BizException;
import cn.court.mediation.entity.MediationCase;
import cn.court.mediation.entity.MediationSchedule;
import cn.court.mediation.entity.MeetingResource;
import cn.court.mediation.entity.PartyAvailability;
import cn.court.mediation.entity.User;
import cn.court.mediation.enums.*;
import cn.court.mediation.repository.CaseRepository;
import cn.court.mediation.repository.MeetingResourceRepository;
import cn.court.mediation.repository.PartyAvailabilityRepository;
import cn.court.mediation.repository.ScheduleRepository;
import cn.court.mediation.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ScheduleService {

    /** 多次改期风险阈值 */
    public static final int REPEAT_RESCHEDULE_THRESHOLD = 2;
    private static final int SLOT_STEP_MINUTES = 30;
    private static final int MAX_ALTERNATIVE_SLOTS = 8;

    private final ScheduleRepository scheduleRepository;
    private final CaseRepository caseRepository;
    private final UserRepository userRepository;
    private final MeetingResourceRepository resourceRepository;
    private final PartyAvailabilityRepository availabilityRepository;
    private final TimelineService timelineService;

    public ScheduleService(ScheduleRepository scheduleRepository, CaseRepository caseRepository,
                           UserRepository userRepository, MeetingResourceRepository resourceRepository,
                           PartyAvailabilityRepository availabilityRepository, TimelineService timelineService) {
        this.scheduleRepository = scheduleRepository;
        this.caseRepository = caseRepository;
        this.userRepository = userRepository;
        this.resourceRepository = resourceRepository;
        this.availabilityRepository = availabilityRepository;
        this.timelineService = timelineService;
    }

    // ============================== 调解资源 ==============================

    @Transactional(readOnly = true)
    public List<MeetingResource> listResources(String type) {
        if (type == null || type.isBlank()) {
            return resourceRepository.findByEnabledTrueOrderByTypeAscIdAsc();
        }
        return resourceRepository.findByTypeAndEnabledTrueOrderByIdAsc(ResourceType.valueOf(type));
    }

    // ============================== 双方可用时间 ==============================

    /** 登记某一方可用时间段（覆盖该方此前登记，保证与最新意愿一致） */
    @Transactional
    public List<PartyAvailability> saveAvailability(Long caseId, Map<String, Object> body, User operator) {
        caseRepository.findById(caseId).orElseThrow(() -> new BizException("案件不存在"));
        String party = (String) body.getOrDefault("party", "APPLICANT");
        if (!"APPLICANT".equals(party) && !"RESPONDENT".equals(party)) {
            throw new BizException("当事人方别仅支持 APPLICANT/RESPONDENT");
        }
        List<?> windows = (List<?>) body.get("windows");
        if (windows == null || windows.isEmpty()) {
            throw new BizException("请至少填写一个可用时间段");
        }
        availabilityRepository.deleteAll(availabilityRepository.findByCaseIdAndPartyOrderByStartTimeAsc(caseId, party));

        List<PartyAvailability> saved = new ArrayList<>();
        for (Object item : windows) {
            Map<?, ?> w = (Map<?, ?>) item;
            LocalDateTime s = LocalDateTime.parse(w.get("startTime").toString());
            LocalDateTime e = LocalDateTime.parse(w.get("endTime").toString());
            if (!e.isAfter(s)) throw new BizException("可用时间结束必须晚于开始");
            PartyAvailability pa = new PartyAvailability();
            pa.setCaseId(caseId);
            pa.setParty(party);
            pa.setStartTime(s);
            pa.setEndTime(e);
            pa.setNote(w.get("note") == null ? null : w.get("note").toString());
            pa.setCreatedBy(operator == null ? null : operator.getId());
            pa.setCreatedByName(operator == null ? null : operator.getRealName());
            saved.add(availabilityRepository.save(pa));
        }
        timelineService.record(caseId, "SCHEDULE",
                ("APPLICANT".equals(party) ? "申请人" : "被申请人") + "登记可调解时间",
                "共" + saved.size() + "个可用时间段，平台将据此与调解员排班、调解室/线上会议重新匹配。",
                operator);
        return saved;
    }

    @Transactional(readOnly = true)
    public Map<String, List<PartyAvailability>> caseAvailability(Long caseId) {
        Map<String, List<PartyAvailability>> m = new LinkedHashMap<>();
        m.put("APPLICANT", availabilityRepository.findByCaseIdAndPartyOrderByStartTimeAsc(caseId, "APPLICANT"));
        m.put("RESPONDENT", availabilityRepository.findByCaseIdAndPartyOrderByStartTimeAsc(caseId, "RESPONDENT"));
        return m;
    }

    // ============================== 调解员推荐 ==============================

    /**
     * 推荐调解员：
     * 1) 案由与专长匹配；2) 外地当事人+愿意线上时优先线上渠道；
     * 3) 在办量未超 capacity；4) 指定时间段无冲突。按在办量升序返回推荐列表。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> recommendMediators(Long caseId, LocalDateTime start, LocalDateTime end) {
        MediationCase c = caseRepository.findById(caseId).orElseThrow(() -> new BizException("案件不存在"));
        List<User> mediators = userRepository.findByRole(Role.MEDIATOR);

        Map<Long, Long> activeCount = activeCountByMediator(mediators);

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

    private Map<Long, Long> activeCountByMediator(List<User> mediators) {
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

    // ============================== 创建排期 ==============================

    /** 创建排期（调解员排班 + 调解室/线上会议 双重冲突检测，资源可自动分配） */
    @Transactional
    public MediationSchedule create(Long caseId, Map<String, Object> body, User operator) {
        MediationCase c = caseRepository.findById(caseId).orElseThrow(() -> new BizException("案件不存在"));
        if ("DIVERT".equals(c.getContinuationDecision())) {
            throw new BizException("该案件经窗口确认分流，不再安排调解；如需继续请先在「案件分流建议」中改为继续调解");
        }
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
            throw new BizException("调解员该时段已有排期冲突，可使用「平台重新匹配」改约其他时间/调解员");
        }

        // 资源：显式指定或平台自动分配
        MeetingResource resource = resolveResource(body.get("resourceId"), channel, start, end, null);
        String location = body.get("location") != null && !body.get("location").toString().isBlank()
                ? body.get("location").toString() : describeResource(resource);

        long round = scheduleRepository.findByCaseIdOrderByStartTimeDesc(caseId).size() + 1;
        MediationSchedule s = new MediationSchedule();
        s.setCaseId(caseId);
        s.setMediatorId(mediatorId);
        s.setResourceId(resource == null ? null : resource.getId());
        s.setChannel(channel);
        s.setLocation(location);
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
                String.format("时间：%s 至 %s；方式：%s；地点/会议：%s；调解员：%s。%s",
                        start, end, channel.getLabel(), location, mediator.getRealName(),
                        body.get("note") == null ? "" : "备注：" + body.get("note")),
                operator);
        return s;
    }

    // ============================== 改期 + 平台重新匹配 ==============================

    /**
     * 改期：原排期置 RESCHEDULED 并保留取消原因/发起方，平台按双方时间、调解员排班、
     * 可用调解室/线上会议重新匹配，另排新期并累计改期次数。改期后新排期需双方重新确认。
     */
    @Transactional
    public MediationSchedule reschedule(Long scheduleId, Map<String, Object> body, User operator) {
        MediationSchedule old = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BizException("排期记录不存在"));
        if (old.getStatus() != ScheduleStatus.PLANNED) {
            throw new BizException("仅待开始的排期可以改期");
        }
        MediationCase c = caseRepository.findById(old.getCaseId()).orElseThrow();
        if ("DIVERT".equals(c.getContinuationDecision())) {
            throw new BizException("该案件经窗口确认分流，不再改期；如需继续请先在「案件分流建议」中改为继续调解");
        }

        String reason = (String) body.getOrDefault("reason", "双方时间冲突");
        RescheduleInitiator initiator = parseInitiator(body.get("initiator"));
        Channel channel = body.get("channel") != null
                ? Channel.valueOf(body.get("channel").toString()) : old.getChannel();

        // 若随改期提交了双方最新可用时间，先按方别分组登记
        Object avail = body.get("availability");
        if (avail instanceof List<?> list && !list.isEmpty()) {
            Map<String, List<Map<String, Object>>> byParty = new LinkedHashMap<>();
            for (Object o : list) {
                Map<?, ?> w = (Map<?, ?>) o;
                String p = "RESPONDENT".equals(w.get("party")) ? "RESPONDENT" : "APPLICANT";
                byParty.computeIfAbsent(p, k -> new ArrayList<>())
                        .add(Map.of("startTime", w.get("startTime"), "endTime", w.get("endTime")));
            }
            byParty.forEach((p, wins) ->
                    saveAvailability(old.getCaseId(), Map.of("party", p, "windows", wins), operator));
        }

        Long targetMediatorId = old.getMediatorId();
        LocalDateTime start = body.get("startTime") != null ? LocalDateTime.parse(body.get("startTime").toString()) : null;
        LocalDateTime end = body.get("endTime") != null ? LocalDateTime.parse(body.get("endTime").toString()) : null;
        if (start != null && end != null && !end.isAfter(start)) {
            throw new BizException("结束时间必须晚于开始时间");
        }
        if (body.get("mediatorId") != null && !"".equals(body.get("mediatorId").toString())) {
            targetMediatorId = Long.valueOf(body.get("mediatorId").toString());
        }
        boolean autoRematch = !Boolean.FALSE.equals(body.get("autoRematch"));
        Long explicitResourceId = (body.get("resourceId") == null
                || "".equals(body.get("resourceId").toString())
                || "AUTO".equalsIgnoreCase(body.get("resourceId").toString()))
                ? null : Long.valueOf(body.get("resourceId").toString());

        // 平台重新匹配：确定 (调解员, 资源, 时段)
        RematchPlan plan = resolveRematchPlan(c, old, channel, targetMediatorId, explicitResourceId,
                start, end, autoRematch);

        // 原排期留痕
        old.setStatus(ScheduleStatus.RESCHEDULED);
        old.setCancelReason(reason);
        old.setInitiator(initiator);
        old.setNote(appendNote(old.getNote(), "改期原因：" + reason + "（发起方：" + initiator.getLabel() + "）"));
        scheduleRepository.save(old);

        // 累计改期、更新效率风险等级，多次改期需窗口重新决定是否继续
        int count = (c.getRescheduleCount() == null ? 0 : c.getRescheduleCount()) + 1;
        c.setRescheduleCount(count);
        c.setRescheduleRiskLevel(riskLevel(count));
        if (count >= REPEAT_RESCHEDULE_THRESHOLD) {
            c.setContinuationReviewed(false);
            c.setContinuationDecision(null);
            c.setContinuationReviewedAt(null);
            c.setContinuationReviewedBy(null);
        }
        caseRepository.save(c);

        // 新排期（双方确认重置）
        MediationSchedule fresh = new MediationSchedule();
        fresh.setCaseId(old.getCaseId());
        fresh.setMediatorId(plan.mediator.getId());
        fresh.setResourceId(plan.resource == null ? null : plan.resource.getId());
        fresh.setChannel(channel);
        fresh.setLocation(describeResource(plan.resource));
        fresh.setStartTime(plan.start);
        fresh.setEndTime(plan.end);
        fresh.setStatus(ScheduleStatus.PLANNED);
        fresh.setRoundNo(old.getRoundNo() + 1);
        fresh.setRescheduleFromId(old.getId());
        fresh.setApplicantConfirmed(false);
        fresh.setRespondentConfirmed(false);
        fresh.setNote("由第" + old.getRoundNo() + "次改期而来（发起方：" + initiator.getLabel() + "）");
        scheduleRepository.save(fresh);

        // 时间线：取消原因、发起方归责、平台重匹配、双方需重新确认
        String responsibility = initiator.isPartySide()
                ? "本次改期系" + initiator.getReasonCategory() + "，按规定不计入调解员（工作人员）延误。"
                : "本次改期系" + initiator.getReasonCategory() + "，计入工作人员排班延误统计。";
        String warn = count >= REPEAT_RESCHEDULE_THRESHOLD
                ? String.format("【调解效率风险%s：已累计%d次改期】系统已将案件列入分流建议，由窗口人员决定是否继续调解。",
                        count >= 3 ? "（红）" : "（黄）", count)
                : "";
        timelineService.record(old.getCaseId(), "RESCHEDULE",
                "第" + count + "次改期",
                String.format("原排期 %s 取消，取消原因：%s；发起方：%s。%s 平台重新匹配：调解员%s、%s、%s 至 %s（取双方共同可用时间）。改期后需双方重新确认出席。%s",
                        old.getStartTime(), reason, initiator.getLabel(), responsibility,
                        plan.mediator.getRealName(),
                        plan.resource == null ? "资源待定" : plan.resource.getName(),
                        plan.start, plan.end, warn),
                operator);
        return fresh;
    }

    /** 确定改期后的调解员/资源/时段；autoRematch=true 时平台自动在双方时间内寻找可行方案 */
    private RematchPlan resolveRematchPlan(MediationCase c, MediationSchedule old, Channel channel,
                                           Long preferredMediatorId, Long explicitResourceId,
                                           LocalDateTime start, LocalDateTime end,
                                           boolean autoRematch) {
        User preferred = userRepository.findById(preferredMediatorId)
                .filter(u -> u.getRole() == Role.MEDIATOR)
                .orElseThrow(() -> new BizException("调解员不存在"));

        // 1) 明确给定时间：先尝试原/指定调解员 + 指定/自动资源（旧排期资源视为可释放）
        if (start != null && end != null) {
            if (mediatorFree(preferredMediatorId, start, end, old.getId())) {
                MeetingResource res;
                if (explicitResourceId != null) {
                    res = resourceRepository.findById(explicitResourceId)
                            .filter(r -> r.getEnabled() && resourceFree(r.getId(), start, end, old.getId()))
                            .orElse(null);
                } else {
                    res = pickFreeResource(channel, start, end, old.getId());
                }
                if (res != null) {
                    return new RematchPlan(preferred, res, start, end, "manual");
                }
            }
            if (!autoRematch) {
                throw new BizException("新时段调解员排班或调解室/线上会议仍有冲突，请开启平台重新匹配");
            }
        }

        if (!autoRematch) {
            throw new BizException("请提供新的调解时间，或使用平台重新匹配");
        }

        // 2) 平台自动匹配：先读双方可用时间交集
        List<TimeWindow> intersection = partyIntersection(old.getCaseId());
        long durationMinutes = (start != null && end != null) ? java.time.Duration.between(start, end).toMinutes()
                : (old.getEndTime() != null && old.getStartTime() != null
                ? Math.max(30, java.time.Duration.between(old.getStartTime(), old.getEndTime()).toMinutes()) : 60);

        List<User> ranked = rankedMediators(c);
        // 优先保持原调解员
        List<User> priority = new ArrayList<>();
        priority.add(preferred);
        for (User u : ranked) {
            if (!u.getId().equals(preferred.getId())) priority.add(u);
        }

        for (User m : priority) {
            for (TimeWindow w : intersection) {
                for (LocalDateTime t = w.start; !t.plusMinutes(durationMinutes).isAfter(w.end);
                     t = t.plusMinutes(SLOT_STEP_MINUTES)) {
                    if (!t.isAfter(LocalDateTime.now())) continue;
                    final LocalDateTime st = t;
                    final LocalDateTime en = t.plusMinutes(durationMinutes);
                    if (mediatorFree(m.getId(), st, en, old.getId())) {
                        MeetingResource res;
                        if (explicitResourceId != null) {
                            final Long rid = explicitResourceId;
                            res = resourceRepository.findById(rid)
                                    .filter(r -> r.getEnabled() && resourceFree(r.getId(), st, en, old.getId()))
                                    .orElse(null);
                        } else {
                            res = pickFreeResource(channel, st, en, old.getId());
                        }
                        if (res != null) {
                            boolean mediatorChanged = !m.getId().equals(old.getMediatorId());
                            return new RematchPlan(m, res, st, en,
                                    "auto" + (mediatorChanged ? ":mediator" : ":keep") );
                        }
                    }
                }
            }
        }
        throw new BizException("双方共同可用时间内暂无调解员与调解室/线上会议同时空闲，请补充双方可用时间或改约其他日期");
    }

    /**
     * 平台重新匹配预检：返回目标时段候选（调解员×资源）以及在双方时间窗内的可行替代时段，
     * 供窗口/调解员确认后再提交改期。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> rematchOptions(Long scheduleId, Map<String, Object> body) {
        MediationSchedule old = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BizException("排期记录不存在"));
        MediationCase c = caseRepository.findById(old.getCaseId()).orElseThrow();
        Channel channel = body.get("channel") != null
                ? Channel.valueOf(body.get("channel").toString()) : old.getChannel();
        LocalDateTime start = body.get("startTime") != null ? LocalDateTime.parse(body.get("startTime").toString()) : null;
        LocalDateTime end = body.get("endTime") != null ? LocalDateTime.parse(body.get("endTime").toString()) : null;

        Map<Long, Long> activeCount = activeCountByMediator(userRepository.findByRole(Role.MEDIATOR));
        List<MeetingResource> resources = resourceRepository.findByTypeAndEnabledTrueOrderByIdAsc(
                channel == Channel.ONLINE ? ResourceType.ONLINE_MEETING : ResourceType.OFFLINE_ROOM);

        // 目标时段候选
        List<Map<String, Object>> candidates = new ArrayList<>();
        boolean requestedFeasible = false;
        if (start != null && end != null) {
            for (User m : rankedMediators(c)) {
                Map<String, Object> mv = new LinkedHashMap<>();
                mv.put("mediatorId", m.getId());
                mv.put("mediatorName", m.getRealName());
                boolean expertise = m.getExpertise() != null && m.getExpertise().contains(c.getCaseType().name());
                mv.put("expertiseMatch", expertise);
                mv.put("activeCount", activeCount.getOrDefault(m.getId(), 0L));
                mv.put("capacity", m.getCapacity());
                boolean mBusy = !mediatorFree(m.getId(), start, end, old.getId());
                mv.put("mediatorBusy", mBusy);

                List<Map<String, Object>> opts = new ArrayList<>();
                for (MeetingResource r : resources) {
                    boolean rBusy = !resourceFree(r.getId(), start, end, old.getId());
                    boolean feasible = !mBusy && !rBusy;
                    Map<String, Object> ov = new LinkedHashMap<>();
                    ov.put("resourceId", r.getId());
                    ov.put("resourceName", r.getName());
                    ov.put("resourceAddress", r.getAddress());
                    ov.put("resourceBusy", rBusy);
                    ov.put("feasible", feasible);
                    opts.add(ov);
                    if (feasible) requestedFeasible = true;
                }
                mv.put("options", opts);
                candidates.add(mv);
            }
        }

        // 双方时间窗内的可行替代时段
        List<Map<String, Object>> party = partyViews(old.getCaseId());
        List<TimeWindow> intersection = partyIntersection(old.getCaseId());
        long durationMinutes = (start != null && end != null) ? java.time.Duration.between(start, end).toMinutes()
                : Math.max(30, old.getStartTime() == null ? 60
                : java.time.Duration.between(old.getStartTime(), old.getEndTime()).toMinutes());
        List<Map<String, Object>> alternativeSlots = new ArrayList<>();
        if (!requestedFeasible) {
            alternativeSlots = findAlternativeSlots(c, channel, old.getId(), intersection, durationMinutes);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requestedStart", start);
        result.put("requestedEnd", end);
        result.put("channel", channel.name());
        result.put("partyWindows", party);
        result.put("intersection", intersection.stream().map(w -> Map.of("startTime", w.start, "endTime", w.end)).toList());
        result.put("candidates", candidates);
        result.put("requestedFeasible", requestedFeasible);
        result.put("alternativeSlots", alternativeSlots);
        return result;
    }

    private List<Map<String, Object>> findAlternativeSlots(MediationCase c, Channel channel, Long excludeScheduleId,
                                                           List<TimeWindow> windows, long durationMinutes) {
        List<Map<String, Object>> slots = new ArrayList<>();
        for (User m : rankedMediators(c)) {
            for (TimeWindow w : windows) {
                for (LocalDateTime t = w.start; !t.plusMinutes(durationMinutes).isAfter(w.end);
                     t = t.plusMinutes(SLOT_STEP_MINUTES)) {
                    final LocalDateTime slotStart = t;
                    final LocalDateTime slotEnd = t.plusMinutes(durationMinutes);
                    final User mediator = m;
                    if (slotStart.isAfter(LocalDateTime.now())
                            && mediatorFree(mediator.getId(), slotStart, slotEnd, excludeScheduleId)) {
                        MeetingResource res = pickFreeResource(channel, slotStart, slotEnd, excludeScheduleId);
                        if (res != null) {
                            Map<String, Object> slot = new LinkedHashMap<>();
                            slot.put("startTime", slotStart);
                            slot.put("endTime", slotEnd);
                            slot.put("mediatorId", mediator.getId());
                            slot.put("mediatorName", mediator.getRealName());
                            slot.put("resourceId", res.getId());
                            slot.put("resourceName", res.getName());
                            slot.put("expertiseMatch", mediator.getExpertise() != null
                                    && mediator.getExpertise().contains(c.getCaseType().name()));
                            boolean dup = slots.stream().anyMatch(x -> x.get("startTime").equals(slotStart)
                                    && x.get("mediatorId").equals(mediator.getId()));
                            if (!dup) slots.add(slot);
                            if (slots.size() >= MAX_ALTERNATIVE_SLOTS) return slots;
                        }
                    }
                }
            }
        }
        return slots;
    }

    // ============================== 出席确认 ==============================

    /** 当事人确认出席 */
    @Transactional
    public void confirmAttend(Long scheduleId, String party, User operator) {
        MediationSchedule s = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BizException("排期不存在"));
        if ("APPLICANT".equals(party)) {
            s.setApplicantConfirmed(true);
        } else {
            s.setRespondentConfirmed(true);
        }
        scheduleRepository.save(s);
        timelineService.record(s.getCaseId(), "SCHEDULE",
                ("APPLICANT".equals(party) ? "申请人" : "被申请人") + "确认出席",
                "确认参加 " + s.getStartTime() + " 的调解（" + s.getLocation() + "）。", operator);
    }

    @Transactional(readOnly = true)
    public List<MediationSchedule> caseSchedules(Long caseId) {
        return scheduleRepository.findByCaseIdOrderByStartTimeDesc(caseId);
    }

    // ============================== 辅助方法 ==============================

    private boolean mediatorFree(Long mediatorId, LocalDateTime start, LocalDateTime end, Long excludeScheduleId) {
        List<MediationSchedule> clash =
                scheduleRepository.findByMediatorIdAndStatusInAndStartTimeLessThanAndEndTimeGreaterThan(
                        mediatorId, List.of(ScheduleStatus.PLANNED), end, start);
        return clash.stream()
                .noneMatch(x -> excludeScheduleId == null || !x.getId().equals(excludeScheduleId));
    }

    private boolean resourceFree(Long resourceId, LocalDateTime start, LocalDateTime end, Long excludeScheduleId) {
        if (resourceId == null) return true;
        List<MediationSchedule> clash =
                scheduleRepository.findByResourceIdAndStatusInAndStartTimeLessThanAndEndTimeGreaterThan(
                        resourceId, List.of(ScheduleStatus.PLANNED), end, start);
        return clash.stream()
                .noneMatch(x -> excludeScheduleId == null || !x.getId().equals(excludeScheduleId));
    }

    private MeetingResource resolveResource(Object rawId, Channel channel, LocalDateTime start, LocalDateTime end,
                                            Long excludeScheduleId) {
        if (rawId != null && !"".equals(rawId.toString()) && !"AUTO".equalsIgnoreCase(rawId.toString())) {
            MeetingResource r = resourceRepository.findById(Long.valueOf(rawId.toString()))
                    .filter(MeetingResource::getEnabled)
                    .orElseThrow(() -> new BizException("调解室/线上会议资源不存在或已停用"));
            if (!resourceFree(r.getId(), start, end, excludeScheduleId)) {
                throw new BizException(r.getName() + " 该时段已被占用，请更换或由平台重新匹配");
            }
            return r;
        }
        MeetingResource auto = pickFreeResource(channel, start, end, excludeScheduleId);
        if (auto == null) {
            throw new BizException("该时段没有空闲的" + (channel == Channel.ONLINE ? "线上视频会议" : "线下调解室")
                    + "，请使用「平台重新匹配」");
        }
        return auto;
    }

    private MeetingResource pickFreeResource(Channel channel, LocalDateTime start, LocalDateTime end,
                                             Long excludeScheduleId) {
        ResourceType type = channel == Channel.ONLINE ? ResourceType.ONLINE_MEETING : ResourceType.OFFLINE_ROOM;
        return resourceRepository.findByTypeAndEnabledTrueOrderByIdAsc(type).stream()
                .filter(r -> resourceFree(r.getId(), start, end, excludeScheduleId))
                .findFirst().orElse(null);
    }

    private List<User> rankedMediators(MediationCase c) {
        Map<Long, Long> activeCount = activeCountByMediator(userRepository.findByRole(Role.MEDIATOR));
        return userRepository.findByRole(Role.MEDIATOR).stream()
                .sorted(Comparator.comparingLong((User m) -> {
                    boolean exp = m.getExpertise() != null && m.getExpertise().contains(c.getCaseType().name());
                    long active = activeCount.getOrDefault(m.getId(), 0L);
                    return -( (exp ? 30 : 0) - active);
                })).toList();
    }

    private List<Map<String, Object>> partyViews(Long caseId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String party : List.of("APPLICANT", "RESPONDENT")) {
            List<PartyAvailability> list = availabilityRepository.findByCaseIdAndPartyOrderByStartTimeAsc(caseId, party);
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("party", party);
            v.put("windows", list.stream().map(p -> {
                Map<String, Object> w = new LinkedHashMap<>();
                w.put("startTime", p.getStartTime());
                w.put("endTime", p.getEndTime());
                w.put("note", p.getNote());
                return w;
            }).toList());
            out.add(v);
        }
        return out;
    }

    /** 计算双方可用时间窗口的交集；任一方未登记以另一方为准，双方均未登记则回退未来工作日工作时段 */
    private List<TimeWindow> partyIntersection(Long caseId) {
        List<PartyAvailability> a = availabilityRepository.findByCaseIdAndPartyOrderByStartTimeAsc(caseId, "APPLICANT");
        List<PartyAvailability> r = availabilityRepository.findByCaseIdAndPartyOrderByStartTimeAsc(caseId, "RESPONDENT");
        List<TimeWindow> result = new ArrayList<>();
        if (a.isEmpty() && r.isEmpty()) {
            // 双方均未登记：默认未来 7 天每天 09:00-17:00 作为平台可协调范围
            LocalDate day = LocalDate.now().plusDays(1);
            for (int i = 0; i < 7; i++) {
                result.add(new TimeWindow(day.atTime(9, 0), day.atTime(17, 0)));
                day = day.plusDays(1);
            }
            return result;
        }
        if (a.isEmpty() || r.isEmpty()) {
            List<PartyAvailability> known = a.isEmpty() ? r : a;
            known.forEach(p -> result.add(new TimeWindow(p.getStartTime(), p.getEndTime())));
            return result;
        }
        for (PartyAvailability x : a) {
            for (PartyAvailability y : r) {
                LocalDateTime s = x.getStartTime().isAfter(y.getStartTime()) ? x.getStartTime() : y.getStartTime();
                LocalDateTime e = x.getEndTime().isBefore(y.getEndTime()) ? x.getEndTime() : y.getEndTime();
                if (e.isAfter(s)) result.add(new TimeWindow(s, e));
            }
        }
        return result.stream().sorted(Comparator.comparing(w -> w.start)).toList();
    }

    private String describeResource(MeetingResource r) {
        if (r == null) return null;
        if (r.getAddress() == null || r.getAddress().isBlank()) return r.getName();
        return r.getName() + "（" + r.getAddress() + "）";
    }

    private String appendNote(String oldNote, String add) {
        return (oldNote == null || oldNote.isBlank()) ? add : oldNote + " | " + add;
    }

    private RescheduleInitiator parseInitiator(Object raw) {
        if (raw == null || raw.toString().isBlank()) return RescheduleInitiator.BOTH_PARTIES;
        try {
            return RescheduleInitiator.valueOf(raw.toString());
        } catch (IllegalArgumentException ex) {
            return RescheduleInitiator.BOTH_PARTIES;
        }
    }

    private int riskLevel(int count) {
        if (count >= 3) return 3;
        if (count >= REPEAT_RESCHEDULE_THRESHOLD) return 2;
        if (count >= 1) return 1;
        return 0;
    }

    private record TimeWindow(LocalDateTime start, LocalDateTime end) {}

    private record RematchPlan(User mediator, MeetingResource resource, LocalDateTime start, LocalDateTime end,
                               String matchMode) {}
}
