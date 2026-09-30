#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""调解改期冲突 / 平台重匹配 / 绩效归责 / 窗口分流建议 端到端验证"""
import json
import urllib.request
import urllib.error
from datetime import datetime, timedelta

BASE = "http://localhost:8080/api"
PASS = 0
FAIL = 0


def call(method, path, token=None, payload=None):
    url = BASE + path
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode())


def check(cond, msg):
    global PASS, FAIL
    if cond:
        PASS += 1
        print("  ✓", msg)
    else:
        FAIL += 1
        print("  ✗ FAIL:", msg)


def ok_data(st, body):
    return st == 200 and body.get("code") == 0, body.get("data")


def login(u, p):
    st, b = call("POST", "/auth/login", payload={"username": u, "password": p})
    assert st == 200 and b["code"] == 0, b
    return b["data"]["token"]


def iso(dt):
    return dt.strftime("%Y-%m-%dT%H:%M:%S")


# 以运行时刻派生“唯一未来日期”，避免与种子/历史运行的排期在同一时段相撞，保证脚本可重复执行
now0 = datetime.now()
_unique_day = (now0 + timedelta(days=3 + (now0.minute * 7 + now0.second) % 20)).replace(
    hour=0, minute=0, second=0, microsecond=0)
tomorrow = _unique_day
t08 = iso(tomorrow.replace(hour=8)); t10 = iso(tomorrow.replace(hour=10)); t11 = iso(tomorrow.replace(hour=11))
t14 = iso(tomorrow.replace(hour=14)); t15 = iso(tomorrow.replace(hour=15)); t20 = iso(tomorrow.replace(hour=20))

print("== 1. 登录与鉴权 ==")
win = login("window", "window123")
zhang_tok = login("zhang", "mediator123")
li_tok = login("li", "mediator123")
zhao_tok = login("zhao", "mediator123")
admin = login("admin", "admin123")
check(bool(win and zhang_tok and li_tok and zhao_tok and admin), "窗口/三调解员/管理员登录成功")
st, b = call("GET", "/cases")
check(st == 401, "无令牌访问被拦截 401")

print("== 2. 调解室 / 线上视频会议资源 ==")
st, b = call("GET", "/meeting-resources", win)
ok, rooms = ok_data(st, b)
check(ok and len(rooms) >= 4, f"资源列表返回 {len(rooms) if ok else 0} 项（≥4）")
st, b = call("GET", "/meeting-resources?type=ONLINE_MEETING", win)
ok, online = ok_data(st, b)
check(ok and all(r["type"] == "ONLINE_MEETING" for r in online) and len(online) >= 2, "线上视频会议资源筛选正确")
st, b = call("GET", "/meeting-resources?type=OFFLINE_ROOM", win)
ok, offline = ok_data(st, b)
room1, room2 = offline[0], offline[1]
check(ok and all(r["type"] == "OFFLINE_ROOM" for r in offline), "线下调解室资源筛选正确")

print("== 3. 新建案件 + 分流 + 双方可用时间 ==")
payload = {
    "caseType": "NEIGHBOR", "urgency": "NORMAL",
    "applicantName": "测试申请人甲", "applicantPhone": "13800009001",
    "respondentName": "测试被申请人乙", "respondentPhone": "13800009002",
    "respondentAddress": "测试路1号", "claimAmount": 5000,
    "claimSummary": "端到端：改期冲突验证", "evidenceList": "照片", "onlineWilling": False,
}
st, b = call("POST", "/cases", win, payload)
ok, case = ok_data(st, b)
check(ok, "新建案件成功")
cid = case["id"]

# 分流给张调解员，线下
st, b = call("POST", f"/cases/{cid}/triage", win,
             {"mediatorId": None, "channel": "OFFLINE", "triageOpinion": "e2e分流"})
# 取张调解员 id
st, b = call("GET", "/mediators", win)
meds = {m["realName"]: m["id"] for m in b["data"]}
zhang_id, li_id = meds["张调解员"], meds["李调解员"]
st, b = call("POST", f"/cases/{cid}/triage", win,
             {"mediatorId": zhang_id, "channel": "OFFLINE", "triageOpinion": "e2e分流", "evidenceComplete": True})
ok, c2 = ok_data(st, b)
check(ok and c2["status"] == "TRIAGED" and c2["mediatorId"] == zhang_id, "窗口分流至张调解员（线下）")

# 双方可用时间（明天 08:00-20:00）
for party in ("APPLICANT", "RESPONDENT"):
    st, b = call("POST", f"/cases/{cid}/availability", win,
                 {"party": party, "windows": [{"startTime": t08, "endTime": t20}]})
    check(st == 200 and b["code"] == 0, f"{party} 登记可调解时间成功")
st, b = call("GET", f"/cases/{cid}/availability", win)
avail = b["data"]
check(len(avail["APPLICANT"]) >= 1 and len(avail["RESPONDENT"]) >= 1, "双方可调解时间已保存")

print("== 4. 排期：平台自动分配资源 + 资源占用冲突拦截 ==")
st, b = call("POST", f"/cases/{cid}/schedules", win,
             {"mediatorId": zhang_id, "channel": "OFFLINE", "resourceId": "AUTO",
              "startTime": t10, "endTime": t11})
ok, s1 = ok_data(st, b)
check(ok and s1["resourceId"] == room1["id"] and s1["status"] == "PLANNED",
      "首次排期自动分配第一调解室 room1")
s1_id = s1["id"]

# 第二个案件分流给李调解员，同时段占用 room1 应被拦截（李本人空闲，仅资源冲突）
st, b = call("POST", "/cases", win, {**payload, "applicantName": "测试丙", "respondentName": "测试丁",
                                     "claimSummary": "资源冲突验证"})
cid2 = b["data"]["id"]
st, b = call("POST", f"/cases/{cid2}/triage", win,
             {"mediatorId": li_id, "channel": "OFFLINE", "triageOpinion": "e2e", "evidenceComplete": True})
st, b = call("POST", f"/cases/{cid2}/schedules", win,
             {"mediatorId": li_id, "channel": "OFFLINE", "resourceId": room1["id"],
              "startTime": t10, "endTime": t11})
check(b["code"] != 0 and "占用" in b["message"], f"调解室被占用时拦截：{b.get('message')}")
st, b = call("POST", f"/cases/{cid2}/schedules", win,
             {"mediatorId": li_id, "channel": "OFFLINE", "resourceId": "AUTO",
              "startTime": t10, "endTime": t11})
ok, s2 = ok_data(st, b)
check(ok and s2["resourceId"] == room2["id"], "资源冲突后平台自动改配第二调解室 room2")

# 调解员排班冲突：给张同时段再排应拦截
st, b = call("POST", f"/cases/{cid2}/schedules", win,
             {"mediatorId": zhang_id, "channel": "OFFLINE", "resourceId": room2["id"],
              "startTime": t10, "endTime": t11})
check(b["code"] != 0 and "调解员" in b["message"], f"调解员排班冲突拦截：{b.get('message')}")

print("== 5. 改期：当事人（被申请人）发起，平台自动重匹配 ==")
st, b = call("POST", f"/schedules/{s1_id}/reschedule", win,
             {"reason": "被申请人务工外出", "initiator": "RESPONDENT", "channel": "OFFLINE",
              "autoRematch": True})
ok, r1 = ok_data(st, b)
check(ok, f"第1次改期成功（平台自动重匹配），新时间 {r1['startTime'] if ok else '-'}")
if ok:
    check(r1["id"] != s1_id and r1["status"] == "PLANNED", "生成新排期且为待开始")
    check(r1["resourceId"] is not None and r1["mediatorId"] == zhang_id, "重新匹配到可用调解室，默认保留原调解员")
    check(r1["applicantConfirmed"] is False and r1["respondentConfirmed"] is False, "改期后双方确认已重置")
    check(r1["rescheduleFromId"] == s1_id, "新排期关联来源排期")
st, b = call("GET", f"/cases/{cid}", win)
d = b["data"]
old_rows = [x for x in d["schedules"] if x["id"] == s1_id]
check(old_rows and old_rows[0]["status"] == "RESCHEDULED", "原排期保留并置为已改期")
check(old_rows and old_rows[0]["cancelReason"] == "被申请人务工外出", "原排期保留取消原因")
check(old_rows and old_rows[0]["initiator"] == "RESPONDENT", "原排期记录发起方=被申请人")
check(d["case"]["rescheduleCount"] == 1, "案件改期次数累计为 1")

print("== 6. 双方重新确认出席 ==")
st, b = call("POST", f"/schedules/{r1['id']}/confirm", win, {"party": "APPLICANT"})
check(b["code"] == 0, "窗口代登记申请人确认")
st, b = call("POST", f"/schedules/{r1['id']}/confirm", win, {"party": "RESPONDENT"})
check(b["code"] == 0, "窗口代登记被申请人确认")
st, b = call("GET", f"/cases/{cid}", win)
cur = [x for x in b["data"]["schedules"] if x["id"] == r1["id"]][0]
check(cur["applicantConfirmed"] and cur["respondentConfirmed"], "新排期双方均已确认")

print("== 7. 第2次改期（申请人）→ 效率风险 + 进入分流建议 ==")
st, b = call("POST", f"/schedules/{r1['id']}/reschedule", win,
             {"reason": "申请人身体不适", "initiator": "APPLICANT", "channel": "OFFLINE",
              "autoRematch": True})
ok, r2 = ok_data(st, b)
check(ok, "第2次改期成功")
st, b = call("GET", f"/cases/{cid}", win)
d = b["data"]
check(d["case"]["rescheduleCount"] == 2, "改期次数累计为 2")
check(d["case"]["rescheduleRiskLevel"] == 2, "效率风险等级=2（黄色）")
check(d["case"]["continuationReviewed"] is False, "多次改期待窗口研判")
check("REPEAT_RESCHEDULE" in d["risks"] and "DIVERSION_PENDING" in d["risks"], "案件风险含多次改期/待窗口决定")

st, b = call("GET", "/divert-suggestions", win)
sug = b["data"]["suggestions"]
mine = [x for x in sug if x["caseId"] == cid]
check(len(mine) == 1 and mine[0]["decision"] is None, "案件进入分流建议且待窗口决定")
check(mine and mine[0]["lastInitiator"] == "APPLICANT", "分流建议展示最近发起方=申请人")

st, b = call("GET", "/todos", win)
acts = [t["action"] for t in b["data"]["todos"] if t["caseId"] == cid]
check(any("待决定继续/分流" in a for a in acts), "窗口工作台出现“多次改期，待决定继续/分流”待办")

print("== 8. 窗口决定继续调解 → 可正常排期；再确认分流 → 禁止排期/改期 ==")
st, b = call("POST", f"/cases/{cid}/continuation-review", win,
             {"decision": "CONTINUE", "note": "双方仍有调解意愿，继续"})
ok, cc = ok_data(st, b)
check(ok and cc["continuationReviewed"] and cc["continuationDecision"] == "CONTINUE", "窗口决定继续调解")

st, b = call("POST", f"/cases/{cid}/continuation-review", win,
             {"decision": "DIVERT", "note": "反复改期，效率风险高，分流转立案"})
ok, cc = ok_data(st, b)
check(ok and cc["continuationDecision"] == "DIVERT", "窗口确认分流")
st, b = call("POST", f"/cases/{cid}/schedules", win,
             {"mediatorId": zhang_id, "channel": "OFFLINE", "startTime": t14, "endTime": t15})
check(b["code"] != 0 and "分流" in b["message"], f"确认分流后禁止再排期：{b.get('message')}")
st, b = call("POST", f"/schedules/{r2['id']}/reschedule", win,
             {"reason": "x", "initiator": "APPLICANT", "channel": "OFFLINE", "autoRematch": True})
check(b["code"] != 0 and "分流" in b["message"], "确认分流后禁止改期")

# 改回继续，便于后续校验
st, b = call("POST", f"/cases/{cid}/continuation-review", win,
             {"decision": "CONTINUE", "note": "复核后继续"})
check(b["code"] == 0, "可重新决定继续调解")

print("== 9. 调解员原因改期计入工作人员延误（种子案件 c13/李） ==")
st, b = call("GET", "/mediator-performance", win)
perf = {x["mediatorId"]: x for x in b["data"]}
li_perf, zhang_perf = perf[li_id], perf[zhang_id]
check(li_perf["staffSideReschedules"] >= 1, f"李调解员工作人员原因改期 {li_perf['staffSideReschedules']} 次（计入延误）")
check(zhang_perf["partySideReschedules"] >= 2, f"张调解员当事人原因改期 {zhang_perf['partySideReschedules']} 次（不计延误）")
check(zhang_perf["staffSideReschedules"] == 0, "本次当事人两次改期均未计入张调解员延误")
st, b = call("GET", f"/mediator-performance/{li_id}", win)
detail = b["data"]
staff_records = [x for x in detail["rescheduleRecords"] if x["countsAsStaffDelay"]]
party_records = [x for x in detail["rescheduleRecords"] if not x["countsAsStaffDelay"]]
check(len(staff_records) >= 1 and staff_records[0]["initiator"] == "MEDIATOR", "绩效明细中调解员原因改期标记为计入延误")
check(all(not x["countsAsStaffDelay"] for x in party_records), "当事人发起的改期明细均标记为不计延误")

print("== 10. 平台重匹配预检（候选调解员×资源 + 双方时间交集 + 替代时段） ==")
st, b = call("POST", f"/schedules/{s2['id']}/rematch-options", win,
             {"channel": "OFFLINE", "startTime": t10, "endTime": t11})
opt = b["data"]
check("candidates" in opt and "intersection" in opt and "alternativeSlots" in opt, "预检返回候选/时间交集/替代时段字段")
check(isinstance(opt["requestedFeasible"], bool), "期望时段可行性为布尔值")
check(len(opt["candidates"]) >= 3 and all("options" in x for x in opt["candidates"]), "每位调解员都给出各资源占用情况")
# 用案件 C（已登记双方时间）的当前排期做预检，交集应≥1
st, b = call("GET", f"/cases/{cid}", win)
cur_planned = [x for x in b["data"]["schedules"] if x["status"] == "PLANNED"][0]
st, b = call("POST", f"/schedules/{cur_planned['id']}/rematch-options", win, {"channel": "OFFLINE"})
check(b["code"] == 0 and len(b["data"]["intersection"]) >= 1, "无期望时间时返回双方共同可约时段交集")

print("== 11. 看板风险统计新增字段 ==")
st, b = call("GET", "/dashboard", win)
risks = b["data"]["risks"]
check("diversionPending" in risks and "diversionDecided" in risks, "看板含分流待决/已决统计")
check(risks["partySideReschedules"] >= 4, f"全平台当事人原因改期 {risks['partySideReschedules']} 次（不计延误）")
check(risks["staffSideReschedules"] >= 1, f"全平台工作人员原因改期 {risks['staffSideReschedules']} 次（计入延误）")

print("== 12. 连续过程材料卷含改期取消原因/发起方/归责/双方确认 ==")
import urllib.request as ur
req = ur.Request(BASE + f"/cases/{cid}/dossier", headers={"Authorization": "Bearer " + win})
raw = ur.urlopen(req).read().decode()
check("排期与历次改期记录" in raw, "材料卷含【排期与历次改期记录】章节")
check("取消原因" in raw and "被申请人务工外出" in raw and "申请人身体不适" in raw, "材料卷含各次取消原因")
check("不计入调解员（工作人员）延误" in raw, "材料卷注明当事人改期不计工作人员延误")
check("双方确认" in raw, "材料卷含双方确认情况")

print("== 13. 种子数据：c5 多次改期待研判 / c9 已分流 / c13 工作人员延误 ==")
st, b = call("GET", "/divert-suggestions", win)
sug = b["data"]["suggestions"]
check(any(x["rescheduleCount"] >= 2 and x["decision"] is None for x in sug), "种子案件 c5 多次改期且待窗口研判")
check(any(x["decision"] == "DIVERT" for x in sug), "种子案件 c9 已由窗口确认分流")

print("\n================ 结果 ================")
print(f"通过 {PASS} 项，失败 {FAIL} 项")
print("ALL_DONE:", "true" if FAIL == 0 else "false")
