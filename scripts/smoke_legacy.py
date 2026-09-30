import json, urllib.request, urllib.error
BASE="http://localhost:8080/api"
def call(m,p,tok=None,pl=None):
    r=urllib.request.Request(BASE+p,data=json.dumps(pl).encode() if pl is not None else None,method=m)
    r.add_header("Content-Type","application/json")
    if tok: r.add_header("Authorization","Bearer "+tok)
    try:
        x=urllib.request.urlopen(r); return x.status,json.loads(x.read().decode())
    except urllib.error.HTTPError as e: return e.code,json.loads(e.read().decode())
def login(u,p): return call("POST","/auth/login",pl={"username":u,"password":p})[1]["data"]["token"]
ok=0; bad=0
def ck(c,m):
    global ok,bad
    print(("  OK " if c else "  XX ")+m); ok+=c; bad+=(not c)
win=login("window","window123"); z=login("zhang","mediator123"); party=login("party","party123")
st,b=call("GET","/cases",win); ck(len(b["data"])>=13, f"案件列表正常（干净种子13件，实际{len(b['data'])}）")
st,b=call("GET","/dashboard",win); k=b["data"]["kpi"]
ck(all(x in k for x in ["total","pendingTriage","mediating","successRate","overdue","missing"]),"看板原有KPI字段齐全")
ck("performance" in b["data"] and "byType" in b["data"] and "transferReasons" in b["data"],"看板履行/案由/转立案图表数据在")
st,b=call("GET","/todos",party); ck(st==200,"当事人工作台正常")
st,b=call("GET","/mediators",win); ck(len(b["data"])==3,"调解员名册3人")
# 既有协议/笔录链路：取种子履行中案件 c6（party 可见）
st,b=call("GET","/cases",party); ids=[c["id"] for c in b["data"]]
ck(len(ids)>=1,"当事人可见自己案件")
# c12 已转立案、c10 司法确认审查中 状态保留
st,b=call("GET","/cases",win)
statuses={c["caseNo"]:c["status"] for c in b["data"]}
ck(any(s=="TRANSFERRED" for s in statuses.values()),"存在已转立案案件(c12)")
ck(any(s=="JUDICIAL_CONFIRMING" for s in statuses.values()),"存在司法确认审查中案件(c10)")
ck(any(s=="OVERDUE" for s in statuses.values()),"存在履行逾期案件(c7)")
# 智能推荐仍工作（既有接口）
cid=[c["id"] for c in b["data"] if c["status"]=="TRIAGED"][0]
st,b=call("GET",f"/cases/{cid}/mediator-suggest?start=2026-10-05T10:00:00&end=2026-10-05T11:00:00",win)
ck(st==200 and len(b["data"])==3 and all("score" in x for x in b["data"]),"调解员智能推荐接口正常")
# 绩效接口各角色可访问
st,b=call("GET","/mediator-performance",z); ck(st==200 and len(b["data"])==3,"调解员可访问绩效概览")
print(f"冒烟回归 通过{ok} 失败{bad}")
