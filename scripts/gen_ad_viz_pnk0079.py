# -*- coding: utf-8 -*-
"""生成 PNK0079 广告优化可视化页面（纯 HTML+CSS，零依赖、离线可开）。"""
import csv, html, math
from collections import defaultdict

ITEM = r"C:/Users/Administrator/Desktop/ItemKeywordPerformance_2026_08_02_19_42_08.csv"
KW   = r"C:/Users/Administrator/Desktop/KeywordPerformance_2026_08_02_23_44_23.csv"
OUT  = r"D:/Tools/Code/walmart-ads/design-plans/ad-viz-pnk0079.html"
ROAS_LINE = 4

def fnum(x):
    try: return float(x)
    except: return 0.0

# ---------------- 读取 + 聚合 ----------------
auto_rows, man_rows = [], []
with open(ITEM, encoding="utf-8-sig", newline="") as f:
    for r in csv.DictReader(f):
        if "PNK0079" in (r.get("Campaign Name") or ""):
            auto_rows.append(r)
with open(KW, encoding="utf-8-sig", newline="") as f:
    for r in csv.DictReader(f):
        if "PNK0079" in (r.get("Campaign Name") or ""):
            man_rows.append(r)

def totals(rows, cols):
    t = defaultdict(float)
    for r in rows:
        for c in cols:
            t[c] += fnum(r.get(c))
    return t

auto_t = totals(auto_rows, ["Impressions","Clicks","Ad Spend","Orders","Total Attributed Sales"])
man_t  = totals(man_rows,  ["Impressions","Clicks","Ad Spend","Orders","Total Attributed Sales"])
auto_roas = auto_t["Total Attributed Sales"]/auto_t["Ad Spend"]
man_roas  = man_t["Total Attributed Sales"]/man_t["Ad Spend"]
auto_acos = auto_t["Ad Spend"]/auto_t["Total Attributed Sales"]*100
man_acos  = man_t["Ad Spend"]/man_t["Total Attributed Sales"]*100

# 手动关键词聚合（按 关键词+匹配类型 归并，记录代表性出价）
man_agg = {}
man_bid = {}
for r in man_rows:
    key = (r["Bidded Keyword"].strip(), r["Match Type"].strip())
    man_agg.setdefault(key, defaultdict(float))
    a = man_agg[key]
    a["imp"]   += fnum(r["Impressions"])
    a["clk"]   += fnum(r["Clicks"])
    a["spend"] += fnum(r["Ad Spend"])
    a["ord"]   += fnum(r["Orders"])
    a["sales"] += fnum(r["Total Attributed Sales"])
    a["units"] += fnum(r["Units Sold"])
    b = fnum(r["Bid"])
    if b > 0 and key not in man_bid:
        man_bid[key] = b

man_list = []
for (kw, mt), a in man_agg.items():
    if a["spend"] <= 0 and a["ord"] <= 0:
        continue
    roas = a["sales"]/a["spend"] if a["spend"] > 0 else 0
    if a["ord"] > 0 and roas >= ROAS_LINE: cls = "win"
    elif a["spend"] > 0 and a["ord"] == 0:    cls = "lose"
    else:                                     cls = "mid"
    man_list.append({"kw":kw,"mt":mt,"bid":man_bid.get((kw,mt),0.0),"imp":a["imp"],"clk":a["clk"],
                     "spend":a["spend"],"ord":a["ord"],"sales":a["sales"],
                     "roas":roas,"cls":cls})
man_list.sort(key=lambda x: -x["spend"])

# 自动搜索词聚合
auto_agg = defaultdict(lambda: defaultdict(float))
for r in auto_rows:
    kw = (r.get("Searched Keyword") or "").strip()
    a = auto_agg[kw]
    a["imp"]   += fnum(r["Impressions"])
    a["clk"]   += fnum(r["Clicks"])
    a["spend"] += fnum(r["Ad Spend"])
    a["ord"]   += fnum(r["Orders"])
    a["sales"] += fnum(r["Total Attributed Sales"])
auto_list = []
for kw, a in auto_agg.items():
    if a["spend"] <= 0 and a["ord"] <= 0:
        continue
    roas = a["sales"]/a["spend"] if a["spend"] > 0 else 0
    auto_list.append({"kw":kw,"ord":a["ord"],"spend":a["spend"],"sales":a["sales"],"roas":roas})
auto_win = [x for x in auto_list if x["ord"] > 0 and x["roas"] >= ROAS_LINE]
auto_win.sort(key=lambda x: -x["roas"])

# 集中度：手动花费最高的关键词
top_spend = man_list[0]
two_piece_share = top_spend["spend"]/man_t["Ad Spend"]

# ---------------- HTML 拼装 ----------------
def esc(s): return html.escape(str(s))
C = {"win":"#16a34a","mid":"#f59e0b","lose":"#dc2626","auto":"#94a3b8","manual":"#2563eb"}

def bar_row(label, val, maxv, color, sub=""):
    w = max(2.0, val/maxv*100) if maxv > 0 else 0
    return (f'<div class="brow"><span class="blab">{esc(label)}</span>'
            f'<div class="btrack"><div class="bfill" style="width:{w:.1f}%;background:{color}"></div></div>'
            f'<span class="bval">{esc(sub)}</span></div>')

CSS = """
:root{--bg:#f6f8fb;--card:#fff;--ink:#1e293b;--mut:#64748b;--line:#e2e8f0;}
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,"Segoe UI","Microsoft YaHei",sans-serif;background:var(--bg);color:var(--ink);padding:28px 18px 60px;line-height:1.5}
.wrap{max-width:1080px;margin:0 auto}
header{margin-bottom:22px}
h1{font-size:24px;font-weight:700;letter-spacing:.5px}
.sub{color:var(--mut);font-size:13px;margin-top:6px}
.badge{display:inline-block;background:#eef2ff;color:#4338ca;border:1px solid #c7d2fe;border-radius:999px;padding:3px 11px;font-size:12px;margin-top:10px}
.cards{display:grid;grid-template-columns:repeat(4,1fr);gap:14px;margin:20px 0}
.card{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:16px 18px;box-shadow:0 1px 3px rgba(0,0,0,.04)}
.card .k{font-size:12px;color:var(--mut)}
.card .v{font-size:26px;font-weight:700;margin-top:4px}
.card .x{font-size:12px;color:var(--mut);margin-top:2px}
.v.green{color:#16a34a}.v.blue{color:#2563eb}
section{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:20px 22px;margin:16px 0;box-shadow:0 1px 3px rgba(0,0,0,.04)}
section h2{font-size:16px;margin-bottom:14px;display:flex;align-items:center;gap:8px}
section h2 .dot{width:9px;height:9px;border-radius:50%;background:#2563eb}
.grid2{display:grid;grid-template-columns:1fr 1fr;gap:24px}
.brow{display:flex;align-items:center;gap:10px;margin:7px 0}
.blab{width:200px;font-size:12.5px;color:var(--ink);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;flex:none}
.btrack{flex:1;background:#eef2f7;border-radius:6px;height:16px;position:relative;overflow:hidden}
.bfill{height:100%;border-radius:6px}
.bval{width:96px;text-align:right;font-size:12px;color:var(--mut);flex:none}
.ring-wrap{display:flex;align-items:center;gap:22px}
.ring{width:170px;height:170px;border-radius:50%;flex:none;position:relative}
.ring::after{content:"";position:absolute;width:104px;height:104px;background:var(--card);border-radius:50%;top:33px;left:33px}
.ring-txt{position:absolute;width:100%;text-align:center;top:64px;z-index:2}
.ring-txt b{font-size:24px}.ring-txt small{display:block;color:var(--mut);font-size:11px}
.legend{font-size:13px}
.legend div{margin:6px 0;display:flex;align-items:center;gap:8px}
.lg{width:12px;height:12px;border-radius:3px;flex:none}
.mark{display:inline-block;width:9px;height:9px;border-radius:2px;margin-right:5px;vertical-align:middle}
.tag{font-size:11px;padding:1px 7px;border-radius:6px;color:#fff;margin-left:6px}
.tag.win{background:#16a34a}.tag.mid{background:#f59e0b}.tag.lose{background:#dc2626}
.note{font-size:12.5px;color:var(--mut);margin-top:12px;line-height:1.6}
.action{display:grid;grid-template-columns:repeat(3,1fr);gap:14px}
.ac{border-radius:12px;padding:14px 16px;border:1px solid var(--line)}
.ac h3{font-size:14px;margin-bottom:10px}
.ac.up{border-left:4px solid #16a34a}.ac.down{border-left:4px solid #f59e0b}.ac.neg{border-left:4px solid #dc2626}
.chip{display:inline-block;background:#f1f5f9;border-radius:6px;padding:3px 8px;font-size:11.5px;margin:3px 3px 0 0;color:#334155}
.chip b{color:#0f172a}
footer{text-align:center;color:var(--mut);font-size:12px;margin-top:30px}
@media(max-width:760px){.cards{grid-template-columns:repeat(2,1fr)}.grid2,.action{grid-template-columns:1fr}.blab{width:120px}}
"""

# Hero 数字卡
spend_total = auto_t["Ad Spend"]+man_t["Ad Spend"]
sales_total = auto_t["Total Attributed Sales"]+man_t["Total Attributed Sales"]
hero = (f'<div class="cards">'
        f'<div class="card"><div class="k">自动广告 RoAS</div><div class="v green">{auto_roas:.2f}</div><div class="x">ACOS {auto_acos:.1f}%</div></div>'
        f'<div class="card"><div class="k">手动广告 RoAS</div><div class="v blue">{man_roas:.2f}</div><div class="x">ACOS {man_acos:.1f}%</div></div>'
        f'<div class="card"><div class="k">总广告花费</div><div class="v">${spend_total:.2f}</div><div class="x">自动 ${auto_t["Ad Spend"]:.2f} + 手动 ${man_t["Ad Spend"]:.2f}</div></div>'
        f'<div class="card"><div class="k">总销售额</div><div class="v">${sales_total:.2f}</div><div class="x">自动 ${auto_t["Total Attributed Sales"]:.0f} + 手动 ${man_t["Total Attributed Sales"]:.0f}</div></div>'
        f'</div>')

# 效率对比（RoAS / ACOS 达标条）
def gauge(label, val, vmax, line, color):
    w = min(100, val/vmax*100)
    lw = line/vmax*100
    return (f'<div class="brow"><span class="blab">{label}</span>'
            f'<div class="btrack"><div class="bfill" style="width:{w:.1f}%;background:{color}"></div>'
            f'<div style="position:absolute;left:{lw:.1f}%;top:-3px;bottom:-3px;width:2px;background:#ef4444"></div></div>'
            f'<span class="bval">{val:.2f}</span></div>')
eff = (f'<div class="grid2">'
        f'<div><h2><span class="dot"></span>RoAS（红线=判断线 4）</h2>'
        + gauge("自动", auto_roas, 8, 4, C["auto"]) + gauge("手动", man_roas, 8, 4, C["manual"]) + '</div>'
        f'<div><h2><span class="dot"></span>ACOS（红线=判断线 25%）</h2>'
        + gauge("自动", auto_acos, 50, 25, C["auto"]) + gauge("手动", man_acos, 50, 25, C["manual"]) + '</div>'
        f'</div>')

# 五指标对比
metrics = [("曝光","Impressions"),("点击","Clicks"),("花费 $","Ad Spend"),("订单","Orders"),("销售额 $","Total Attributed Sales")]
mrows = ""
for lab, key in metrics:
    mx = max(auto_t[key], man_t[key]) or 1
    mrows += bar_row("自动 "+lab, auto_t[key], mx, C["auto"], f'{auto_t[key]:.0f}')
    mrows += bar_row("手动 "+lab, man_t[key], mx, C["manual"], f'{man_t[key]:.0f}')
metric_block = f'<div>{mrows}</div>'

# 集中度环形
ring_pct = two_piece_share*100
ring = (f'<div class="ring-wrap"><div class="ring" style="background:conic-gradient({C["manual"]} 0 {ring_pct:.1f}%, #e2e8f0 {ring_pct:.1f}% 100%)">'
        f'<div class="ring-txt"><b>{ring_pct:.0f}%</b><small>{esc(top_spend["kw"])}</small></div></div>'
        f'<div class="legend"><div><span class="lg" style="background:{C["manual"]}"></span> {esc(top_spend["kw"])} 占手动花费 {ring_pct:.0f}%</div>'
        f'<div><span class="lg" style="background:#e2e8f0"></span> 其余 {len(man_list)-1} 个关键词占 {100-ring_pct:.0f}%</div>'
        f'<div class="note">该词同时占手动销售额约 79%。单点依赖 → 需拆散（见优化动作·拆集中度）。</div></div></div>')

# 关键词罗盘：花费榜 Top10 + RoAS榜 Top10
def cls_color(c): return C[c]
spend_top = man_list[:10]
roas_top = sorted([m for m in man_list if m["ord"]>0], key=lambda x:-x["roas"])[:10]
maxsp = max(m["spend"] for m in spend_top) or 1
maxro = min(12, max(m["roas"] for m in roas_top)) or 1
spend_bars = ""
for m in spend_top:
    spend_bars += (f'<div class="brow"><span class="blab">{esc(m["kw"])}<span class="tag {m["cls"]}">{m["mt"]}</span></span>'
                   f'<div class="btrack"><div class="bfill" style="width:{m["spend"]/maxsp*100:.1f}%;background:{cls_color(m["cls"])}"></div></div>'
                   f'<span class="bval">${m["spend"]:.2f}</span></div>')
roas_bars = ""
for m in roas_top:
    rv = min(m["roas"], 12)
    roas_bars += (f'<div class="brow"><span class="blab">{esc(m["kw"])}<span class="tag {m["cls"]}">{m["mt"]}</span></span>'
                  f'<div class="btrack"><div class="bfill" style="width:{rv/maxro*100:.1f}%;background:{cls_color(m["cls"])}"></div></div>'
                  f'<span class="bval">RoAS {m["roas"]:.1f} · {m["ord"]:.0f}单</span></div>')
compass = (f'<div class="grid2"><div><h2><span class="dot"></span>花费榜 Top 10</h2>{spend_bars}</div>'
           f'<div><h2><span class="dot"></span>RoAS 榜 Top 10（截断12）</h2>{roas_bars}</div></div>'
           f'<div class="note"><span class="mark" style="background:{C["win"]}"></span>赢家(RoAS≥4) '
           f'<span class="mark" style="background:{C["mid"]}"></span>临界(RoAS&lt;4有单) '
           f'<span class="mark" style="background:{C["lose"]}"></span>亏损(有花费0单)</div>')

# 自动收割靶：高 RoAS 搜索词 Top12
harvest = auto_win[:12]
maxh = min(50, max(h["roas"] for h in harvest)) or 1
hv = ""
for h in harvest:
    rv = min(h["roas"], 50)
    hv += (f'<div class="brow"><span class="blab">{esc(h["kw"])}</span>'
           f'<div class="btrack"><div class="bfill" style="width:{rv/maxh*100:.1f}%;background:{C["win"]}"></div></div>'
           f'<span class="bval">RoAS {h["roas"]:.1f} · {h["ord"]:.0f}单</span></div>')
harvest_block = f'<div>{hv}</div><div class="note">这些是自动广告里「有转化且高 RoAS」的搜索词，建议 harvest 为手动 exact 锁定（起始 bid $0.40），形成「自动发现→手动收割」闭环。</div>'

# 优化动作
raise_list = [m for m in man_list if m["kw"] in ("travel outfit","matching set summer","2 piece travel set")]
lower_list = [m for m in man_list if m["kw"] in ("two piece","woman outfit 2 piece","summer outfit")]
def chip_raise(m):
    to = {"travel outfit":0.50,"matching set summer":0.45,"2 piece travel set":0.50}[m["kw"]]
    return f'<span class="chip"><b>{esc(m["kw"])}</b> ${m["bid"]:.2f}→${to:.2f}</span>'
def chip_lower(m):
    to = 0.30
    note = "（手动最低）" if m["bid"]<=0.30 else ""
    return f'<span class="chip"><b>{esc(m["kw"])}</b> ${m["bid"]:.2f}→${to:.2f}{note}</span>'
def chip_neg(kw): return f'<span class="chip">{esc(kw)}</span>'
neg_examples = ["two piece set summer","2 piece set","2 piece set pants","matching set","wide leg lounge set","woman summer outfit","two piece pants set"]
actions = (f'<div class="action">'
           f'<div class="ac up"><h3>① 提 bid · 赢家（绿）</h3>'+''.join(chip_raise(m) for m in raise_list)+
           f'<div class="note">RoAS≥4，加预算抢量。</div></div>'
           f'<div class="ac down"><h3>② 降 bid · 临界（橙）</h3>'+''.join(chip_lower(m) for m in lower_list)+
           f'<div class="note">手动最低 $0.30，降不动的靠精确否定控面。</div></div>'
           f'<div class="ac neg"><h3>③ 加否定 · 止损（红）</h3>'+''.join(chip_neg(k) for k in neg_examples)+
           f'<div class="note">+ 其余长尾共 25 个亏损词，总浪费约 $28。</div></div>'
           f'</div>')

html_doc = f"""<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>PNK0079 广告优化可视化</title>
<style>{CSS}</style></head>
<body><div class="wrap">
<header>
  <h1>PNK0079 · 沃尔玛广告优化可视化</h1>
  <div class="sub">数据窗口：报表导出 2026-08-02（自动 ZDGG / 手动 SDEX·SDGF·SDPH）</div>
  <span class="badge">判断线 RoAS ≥ 4（等价 ACOS ≤ 25%）· 出价下限 自动≥$0.20 / 手动≥$0.30</span>
</header>
{hero}
<section><h2><span class="dot"></span>效率对比</h2>{eff}</section>
<section><h2><span class="dot"></span>五指标：自动 vs 手动</h2>{metric_block}</section>
<section><h2><span class="dot"></span>手动广告集中度风险</h2>{ring}</section>
<section><h2><span class="dot"></span>关键词罗盘</h2>{compass}</section>
<section><h2><span class="dot"></span>自动广告 · 收割靶（高 RoAS 搜索词）</h2>{harvest_block}</section>
<section><h2><span class="dot"></span>优化动作三步走</h2>{actions}</section>
<footer>纯前端可视化 · 数据来自两份沃尔玛广告 API 导出 CSV · 生成于 gen_ad_viz_pnk0079.py</footer>
</div></body></html>"""

with open(OUT, "w", encoding="utf-8") as f:
    f.write(html_doc)
print("OK ->", OUT)
print(f"auto_roas={auto_roas:.2f} man_roas={man_roas:.2f} spend={spend_total:.2f} sales={sales_total:.2f}")
print(f"manual keywords={len(man_list)} auto_terms={len(auto_list)} auto_win={len(auto_win)}")
print(f"top keyword={top_spend['kw']} share={ring_pct:.1f}%")
