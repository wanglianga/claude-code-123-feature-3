package cn.court.mediation.enums;

/**
 * 改期发起方（用于绩效归责）。
 * 当事人临时变更（申请人/被申请人/双方）不计入调解员等工作人员延误；
 * 调解员或院方原因则计入工作人员延误。
 */
public enum RescheduleInitiator {
    APPLICANT("申请人", true, "当事人临时变更"),
    RESPONDENT("被申请人", true, "当事人临时变更"),
    BOTH_PARTIES("双方当事人", true, "双方临时变更"),
    MEDIATOR("调解员", false, "调解员排班/工作原因"),
    COURT("院方/平台", false, "院方资源或工作原因");

    private final String label;
    /** 是否属于当事人临时变更（true 时不计工作人员延误） */
    private final boolean partySide;
    private final String reasonCategory;

    RescheduleInitiator(String label, boolean partySide, String reasonCategory) {
        this.label = label;
        this.partySide = partySide;
        this.reasonCategory = reasonCategory;
    }

    public String getLabel() {
        return label;
    }

    public boolean isPartySide() {
        return partySide;
    }

    public String getReasonCategory() {
        return reasonCategory;
    }
}
