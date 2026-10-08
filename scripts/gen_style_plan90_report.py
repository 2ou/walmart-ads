# -*- coding: utf-8 -*-
"""根据 outputs/<款号>_plan90_summary.json 生成离线单文件 HTML 报告（ECharts 全内联）。

用法：
  python scripts/gen_style_plan90_report.py PNK366066
"""
import json
import os
import sys
from collections import defaultdict

ECHARTS = r'D:\Tools\Code\GoodJob\node_modules\echarts\dist\echarts.min.js'

STATUS_LABEL = {
    'PLANNED': '已排产', 'PLANNED_LATE': '已排产(晚)', 'PARTIALLY_COVERED': '部分覆盖',
    'BED_NOT_COVERED': '裁床未覆盖', 'MANUAL_REVIEW': '需人工复核', 'NOT_NEEDED': '无需做货',
    'NEW_NO_HISTORY': '新品无历史', 'CLEARANCE': '清货款', 'RETIRED': '已停产',
    'MISSING_COLOR': '缺颜色', 'MISSING_CUTTING_YIELD': '缺裁床数', 'LOW_SAMPLE_REVIEW': '小样本复核',
}
SIZE_ORDER = {'S': 0, 'M': 1, 'L': 2, 'XL': 3, '2XL': 4, '3XL': 5, '4XL': 6, '5XL': 7}


def size_key(x):
    return SIZE_ORDER.get(x, 99)


def main():
    parent = sys.argv[1] if len(sys.argv) > 1 else 'PNK366066'
    base = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    src = os.path.join(base, 'outputs', f'{parent.lower()}_plan90_summary.json')
    with open(src, encoding='utf-8') as fh:
        s = json.load(fh)

    rows = s['rows']
    dom = s['domesticShipment']
    prod = s['production']
    raw = s['rawProduction']
    surplus = s['cuttingSurplus']
    demand = s['totalDemand']
    wfs = s['totalWfs']
    domex = s['totalDomestic']
    wip = s['totalWip']
    beds = round(prod / 100)

    # 需做货 & 未覆盖
    need = [r for r in rows if (r['productionUnits'] or 0) > 0]
    need.sort(key=lambda r: -(r['productionUnits'] or 0))
    uncovered = [r for r in rows if r['status'] == 'BED_NOT_COVERED']

    # 颜色汇总
    color_rows = []
    for c, v in s['byColor'].items():
        supply = v['wfs'] + v['dom'] + v['wip']
        after = supply + v['prod']
        cover = (after / v['demand'] * 100) if v['demand'] > 0 else None
        color_rows.append(dict(color=c, demand=v['demand'], wfs=v['wfs'], dom=v['dom'],
                               wip=v['wip'], prod=v['prod'], supply=supply, after=after,
                               gap=v['raw'], cover=cover, stockout=v['stockout']))
    color_rows.sort(key=lambda r: -r['prod'])

    size_rows = []
    for sz, v in s['bySize'].items():
        supply = v['wfs'] + v['dom'] + v['wip']
        size_rows.append(dict(size=sz, demand=v['demand'], wfs=v['wfs'], dom=v['dom'],
                              wip=v['wip'], prod=v['prod'], supply=supply,
                              cover=((supply + v['prod']) / v['demand'] * 100) if v['demand'] > 0 else None))
    size_rows.sort(key=lambda r: size_key(r['size']))

    # SKU 覆盖天数（按 P90 日均）
    def cover_days(r):
        d = r['p90DemandUnits'] or 0
        if d <= 0:
            return None
        daily = d / 90.0
        supply = (r['wfsUnits'] or 0) + (r['domesticAvailableUnits'] or 0) + (r['wipUnits'] or 0) + (r['productionUnits'] or 0)
        return supply / daily if daily > 0 else None

    def fmt(v, nd=0):
        if v is None:
            return '—'
        return f'{v:,.{nd}f}'

    # ---------- 图表数据 ----------
    chart_colors = [r['color'] for r in color_rows][::-1]
    series_supply = {
        'WFS可售': [r['wfs'] for r in color_rows][::-1],
        '国内仓': [r['dom'] for r in color_rows][::-1],
        '做货中': [r['wip'] for r in color_rows][::-1],
        '本次建议做货': [r['prod'] for r in color_rows][::-1],
    }
    demand_marker = [r['demand'] for r in color_rows][::-1]

    size_cats = [r['size'] for r in size_rows]
    size_demand = [r['demand'] for r in size_rows]
    size_supply = [r['supply'] for r in size_rows]
    size_prod = [r['prod'] for r in size_rows]

    payload = dict(
        parent=parent, snapshot=s['snapshotDate'], horizon=s['horizonDays'], model=s['model'],
        demand=demand, wfs=wfs, domex=domex, dom=dom, wip=wip, prod=prod, raw=raw, surplus=surplus,
        beds=beds, colors=chart_colors, seriesSupply=series_supply, demandMarker=demand_marker,
        sizeCats=size_cats, sizeDemand=size_demand, sizeSupply=size_supply, sizeProd=size_prod,
    )

    def color_table():
        out = []
        for r in color_rows:
            cover = '—' if r['cover'] is None else f"{r['cover']:.0f}%"
            cover_cls = ''
            if r['cover'] is not None:
                cover_cls = 'ok' if r['cover'] >= 100 else ('warn' if r['cover'] >= 85 else 'bad')
            badge = 'ok' if r['prod'] > 0 else 'muted'
            out.append(f"""<tr>
<td class="l"><span class="chip">{r['color']}</span></td>
<td class="num">{fmt(r['demand'])}</td>
<td class="num">{fmt(r['wfs'])}</td>
<td class="num">{fmt(r['dom'])}</td>
<td class="num">{fmt(r['wip'])}</td>
<td class="num mut">{fmt(r['gap'])}</td>
<td class="num"><span class="pill {badge}">{fmt(r['prod'])}</span></td>
<td class="num {cover_cls}">{cover}</td>
<td class="c">{r['stockout'] or '—'}</td>
</tr>""")
        return '\n'.join(out)

    def size_table():
        out = []
        for r in size_rows:
            cover = '—' if r['cover'] is None else f"{r['cover']:.0f}%"
            cover_cls = 'ok' if (r['cover'] or 0) >= 100 else ('warn' if (r['cover'] or 0) >= 85 else 'bad')
            badge = 'ok' if r['prod'] > 0 else 'muted'
            out.append(f"""<tr>
<td class="l"><strong>{r['size']}</strong></td>
<td class="num">{fmt(r['demand'])}</td>
<td class="num">{fmt(r['wfs'])}</td>
<td class="num">{fmt(r['dom'])}</td>
<td class="num">{fmt(r['wip'])}</td>
<td class="num"><span class="pill {badge}">{fmt(r['prod'])}</span></td>
<td class="num {cover_cls}">{cover}</td>
</tr>""")
        return '\n'.join(out)

    def need_table():
        out = []
        for r in need:
            cd = cover_days(r)
            cd_txt = '—' if cd is None else f'{cd:.0f} 天'
            out.append(f"""<tr>
<td class="l mono">{r['sku']}</td>
<td class="l"><span class="chip sm">{r['color']}</span></td>
<td class="c">{r['size']}</td>
<td class="num">{fmt(r['p90DemandUnits'])}</td>
<td class="num">{fmt(r['wfsUnits'])}</td>
<td class="num">{fmt(r['domesticAvailableUnits'])}</td>
<td class="num">{fmt(r['wipUnits'])}</td>
<td class="num mut">{fmt(r['rawProductionUnits'])}</td>
<td class="num"><strong>{fmt(r['productionUnits'])}</strong></td>
<td class="num">{cd_txt}</td>
<td class="c">{r['expectedStockoutDate'] or '—'}</td>
</tr>""")
        return '\n'.join(out)

    def uncovered_table():
        out = []
        for r in uncovered:
            out.append(f"""<tr>
<td class="l mono">{r['sku']}</td>
<td class="l"><span class="chip sm">{r['color']}</span></td>
<td class="c">{r['size']}</td>
<td class="num">{fmt(r['p90DemandUnits'])}</td>
<td class="num mut">{fmt(r['rawProductionUnits'])}</td>
<td class="c">{r['expectedStockoutDate'] or '—'}</td>
</tr>""")
        return '\n'.join(out)

    status_items = ' · '.join(f'{STATUS_LABEL.get(k, k)} {v}' for k, v in s['statusCount'].items())

    html = f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{parent} · 90 天做货需求分析</title>
<style>
*{{box-sizing:border-box}}
body{{margin:0;background:#f5f6f8;color:#1a1d23;
 font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif;
 font-size:13px;line-height:1.55}}
.wrap{{max-width:1240px;margin:0 auto;padding:26px 22px 60px}}
header{{background:linear-gradient(135deg,#1e293b,#334155);color:#fff;border-radius:14px;padding:22px 26px}}
header h1{{margin:0 0 6px;font-size:21px;letter-spacing:.4px}}
header .sub{{opacity:.82;font-size:12.5px}}
header .tags{{margin-top:12px;display:flex;gap:8px;flex-wrap:wrap}}
.tag{{background:rgba(255,255,255,.14);border:1px solid rgba(255,255,255,.2);
 border-radius:999px;padding:3px 11px;font-size:11.5px}}
.kpis{{display:grid;grid-template-columns:repeat(auto-fit,minmax(158px,1fr));gap:12px;margin:16px 0}}
.kpi{{background:#fff;border:1px solid #e6e8ec;border-radius:12px;padding:14px 16px}}
.kpi .lb{{font-size:11.5px;color:#6b7280;letter-spacing:.3px}}
.kpi .vl{{font-size:23px;font-weight:700;margin-top:4px;font-variant-numeric:tabular-nums}}
.kpi .ft{{font-size:11px;color:#9aa1ad;margin-top:2px}}
.kpi.hi .vl{{color:#dc2626}} .kpi.ok .vl{{color:#059669}} .kpi.bl .vl{{color:#2563eb}}
.card{{background:#fff;border:1px solid #e6e8ec;border-radius:12px;padding:18px 20px;margin-top:14px}}
.card h2{{margin:0 0 4px;font-size:15px}}
.card .desc{{color:#6b7280;font-size:12px;margin-bottom:12px}}
.verdict{{display:flex;align-items:center;gap:16px;flex-wrap:wrap;margin-top:14px;
 background:#fff5f5;border:1px solid #fecaca;border-left:4px solid #dc2626;border-radius:12px;padding:16px 20px}}
.verdict .big{{font-size:30px;font-weight:800;color:#dc2626;font-variant-numeric:tabular-nums}}
.verdict .txt{{font-size:13px;color:#7f1d1d}}
table{{width:100%;border-collapse:collapse;font-variant-numeric:tabular-nums}}
th,td{{padding:7px 9px;border-bottom:1px solid #eef0f3;white-space:nowrap}}
th{{background:#f8f9fb;color:#4b5563;font-weight:600;font-size:11.5px;text-align:right;
 position:sticky;top:0;z-index:2}}
th.l,td.l{{text-align:left}} th.c,td.c{{text-align:center}} .num{{text-align:right}}
td.mono{{font-family:ui-monospace,Consolas,monospace;font-size:11.5px}}
td.mut{{color:#9aa1ad}} .ok{{color:#059669;font-weight:600}} .warn{{color:#d97706;font-weight:600}}
.bad{{color:#dc2626;font-weight:600}}
.chip{{background:#eef2ff;color:#3730a3;border-radius:6px;padding:2px 8px;font-size:11.5px;font-weight:600}}
.chip.sm{{font-size:11px;padding:1px 7px}}
.pill{{border-radius:999px;padding:2px 10px;font-weight:700;font-size:11.5px}}
.pill.ok{{background:#ecfdf5;color:#047857}} .pill.muted{{background:#f3f4f6;color:#9aa1ad}}
.scroll{{overflow:auto;max-height:520px;border:1px solid #eef0f3;border-radius:8px}}
.charts{{display:grid;grid-template-columns:1fr 1fr;gap:14px}}
@media(max-width:900px){{.charts{{grid-template-columns:1fr}}}}
.chart{{width:100%;height:360px}}
.note{{background:#fffbeb;border:1px solid #fde68a;border-radius:10px;padding:14px 18px;margin-top:14px;font-size:12.5px}}
.note b{{color:#92400e}}
.note ul{{margin:8px 0 0;padding-left:20px}} .note li{{margin:4px 0}}
.meta{{color:#9aa1ad;font-size:11.5px;margin-top:20px;text-align:center}}
</style>
</head>
<body>
<div class="wrap">
<header>
  <h1>{parent} · 预测做货需求（覆盖 90 天）</h1>
  <div class="sub">口径与线上「需求分析 · 60 天计划」完全一致，仅把覆盖窗口改为 90 天 · WFS 快照 {s['snapshotDate']}</div>
  <div class="tags">
    <span class="tag">覆盖 {s['horizonDays']} 天</span>
    <span class="tag">预测模型 {s['model']}</span>
    <span class="tag">在售 SKU {len(rows)}</span>
    <span class="tag">裁床 100 件/床</span>
  </div>
</header>

<div class="kpis">
  <div class="kpi bl"><div class="lb">90 天 P90 需求</div><div class="vl">{demand:,.0f}</div><div class="ft">件（含安全余量）</div></div>
  <div class="kpi"><div class="lb">WFS 可售</div><div class="vl">{wfs:,.0f}</div><div class="ft">当前在美可售</div></div>
  <div class="kpi"><div class="lb">国内仓现有</div><div class="vl">{domex:,.0f}</div><div class="ft">其中建议发货 {dom:,.0f}</div></div>
  <div class="kpi"><div class="lb">做货中（WIP）</div><div class="vl">{wip:,.0f}</div><div class="ft">工厂在产</div></div>
  <div class="kpi hi"><div class="lb">净缺口</div><div class="vl">{raw:,.0f}</div><div class="ft">需求 − 现有供应</div></div>
  <div class="kpi hi"><div class="lb">建议做货</div><div class="vl">{prod:,.0f}</div><div class="ft">{beds} 个裁床 · 溢出 {surplus:,.0f}</div></div>
</div>

<div class="verdict">
  <div class="big">{prod:,.0f} 件</div>
  <div class="txt">
    <b>需新开 {beds} 个裁床</b>（每床 100 件），集中在 Bleach White / Black / Oatmeal / Pumpkin / Royal Blue / Wine Red 六个颜色。<br>
    另有 {len(uncovered)} 个 SKU 存在缺口但<strong>不足半个裁床</strong>（合计 {sum(r['rawProductionUnits'] or 0 for r in uncovered):,.0f} 件），本轮到量后顺延下一轮。
  </div>
</div>

<div class="card">
  <h2>按颜色：需求 vs 可用供应 vs 本次做货</h2>
  <div class="desc">堆叠条 = 现有可用供应（WFS + 国内仓 + 做货中 + 本次建议做货），红点 = 90 天 P90 需求；红点在条内 = 已覆盖，超出 = 仍有缺口。</div>
  <div id="chartColor" class="chart" style="height:420px"></div>
</div>

<div class="charts">
  <div class="card">
    <h2>按尺码：需求与做货分布</h2>
    <div class="desc">本次做货明显偏向 M / L / 3XL，源于这几位码的 WFS 可售偏低。</div>
    <div id="chartSize" class="chart"></div>
  </div>
  <div class="card">
    <h2>尺码汇总</h2>
    <div class="scroll">
      <table>
        <thead><tr><th class="l">尺码</th><th>P90需求</th><th>WFS</th><th>国内仓</th><th>做货中</th><th>建议做货</th><th>覆盖率</th></tr></thead>
        <tbody>
{size_table()}
        </tbody>
      </table>
    </div>
  </div>
</div>

<div class="card">
  <h2>按颜色汇总</h2>
  <div class="desc">缺口 = P90 需求 − WFS − 国内仓 − 在途/做货中；覆盖率 = (可用供应 + 本次做货) ÷ 需求。</div>
  <div class="scroll">
    <table>
      <thead><tr><th class="l">颜色</th><th>P90需求</th><th>WFS</th><th>国内仓</th><th>做货中</th><th>缺口</th><th>建议做货</th><th>覆盖率</th><th class="c">最早断货</th></tr></thead>
      <tbody>
{color_table()}
      </tbody>
    </table>
  </div>
</div>

<div class="card">
  <h2>需做货 SKU 明细（{len(need)} 个）</h2>
  <div class="desc">按本次做货量降序；覆盖天数 = (WFS + 国内仓 + 做货中 + 本次做货) ÷ 该 SKU 的 P90 日均。</div>
  <div class="scroll">
    <table>
      <thead><tr><th class="l">SKU</th><th class="l">颜色</th><th class="c">尺码</th><th>P90需求</th><th>WFS</th><th>国内仓</th><th>做货中</th><th>缺口</th><th>建议做货</th><th>覆盖天数</th><th class="c">预计断货</th></tr></thead>
      <tbody>
{need_table()}
      </tbody>
    </table>
  </div>
</div>

<div class="card">
  <h2>裁床未覆盖 SKU（{len(uncovered)} 个，建议下一轮）</h2>
  <div class="desc">所在颜色总缺口不足半个裁床（&lt; 50 件），系统按规则顺延，避免为小缺口开整床。</div>
  <div class="scroll">
    <table>
      <thead><tr><th class="l">SKU</th><th class="l">颜色</th><th class="c">尺码</th><th>P90需求</th><th>缺口</th><th class="c">预计断货</th></tr></thead>
      <tbody>
{uncovered_table()}
      </tbody>
    </table>
  </div>
</div>

<div class="note">
  <b>读表提示与风险</b>
  <ul>
    <li><b>库存结构性错配</b>：Black 现有 WFS 453 + 国内仓 476 ≈ 929 件，总量远超其 90 天需求 580 件，但 L / M / XL / 2XL 已覆盖，<b>S 与 3XL 仍缺</b>——因此 Black 依旧要开 1 床补小码与加大码，而不是「Black 不用做」。</li>
    <li><b>跑率上行，基线可能偏低</b>：模型选用 28 天均值基线（16.2 件/天），但最近 7 天实际已到约 22 件/天、近 14 天约 19.9 件/天，需求处于爬坡。若按近 7 天跑率外推，90 天需求约 2,000+ 件（≈ 当前基线 +30%），建议把 Bleach White 与 M/L 的排产作为首批、并保留追加空间。</li>
    <li><b>4 个新花型（FP）无历史</b>：Blue Ethnic Feather / Gray Green Peony / Navy Blue Wheel / White Blue Flower 共 4 色 24 个 SKU，各 120 件做货中（合计 480 件）已覆盖首铺，系统标记「新品无历史」，未生成额外做货建议，需按上市后真实动销再决策。</li>
    <li><b>预测模型未选中 ETS</b>：本次回测 WMAPE 0.566 劣于基线 0.430，系统回落 28 天均值基线，属正常兜底；波动大时该款预测精度有限，建议结合下面的人工判断。</li>
    <li><b>状态分布</b>：{status_items}。</li>
  </ul>
</div>

<div class="meta">
  数据源：wm_ads 数据库（WFS 快照 {s['snapshotDate']}）· 计算接口 /api/forecast/recommendations?targetDays=90 ·
  生成时间 {__import__('datetime').datetime.now().strftime('%Y-%m-%d %H:%M')} · 离线单文件，可断网打开
</div>
</div>

<script>{open(ECHARTS, encoding='utf-8').read()}</script>
<script>
const P = {json.dumps(payload, ensure_ascii=False)};

const c1 = echarts.init(document.getElementById('chartColor'), null, {{renderer:'canvas'}});
c1.setOption({{
  tooltip:{{trigger:'axis',axisPointer:{{type:'shadow'}},valueFormatter:v=>v==null?'-':Math.round(v)}},
  legend:{{top:0,itemWidth:12,itemHeight:8,textStyle:{{fontSize:11.5}}}},
  grid:{{left:130,right:56,top:38,bottom:26}},
  xAxis:{{type:'value',splitLine:{{lineStyle:{{color:'#f0f2f5'}}}},axisLabel:{{fontSize:11}}}},
  yAxis:{{type:'category',data:P.colors,axisTick:{{show:false}},axisLine:{{show:false}},axisLabel:{{fontSize:11.5}}}},
  series:[
    {{name:'WFS可售',type:'bar',stack:'s',data:P.seriesSupply['WFS可售'],itemStyle:{{color:'#10b981'}},barWidth:'52%'}},
    {{name:'国内仓',type:'bar',stack:'s',data:P.seriesSupply['国内仓'],itemStyle:{{color:'#f59e0b'}}}},
    {{name:'做货中',type:'bar',stack:'s',data:P.seriesSupply['做货中'],itemStyle:{{color:'#8b5cf6'}}}},
    {{name:'本次建议做货',type:'bar',stack:'s',data:P.seriesSupply['本次建议做货'],itemStyle:{{color:'#2563eb'}}}},
    {{name:'P90 需求',type:'scatter',data:P.demandMarker,symbolSize:11,
      itemStyle:{{color:'#dc2626',borderColor:'#fff',borderWidth:1.5}},
      label:{{show:true,position:'right',fontSize:10.5,color:'#dc2626',
        formatter:o=>{{const v=Array.isArray(o.value)?o.value[0]:o.value;return Math.round(v);}}}}}}
  ]
}});

const c2 = echarts.init(document.getElementById('chartSize'), null, {{renderer:'canvas'}});
c2.setOption({{
  tooltip:{{trigger:'axis',axisPointer:{{type:'shadow'}},valueFormatter:v=>v==null?'-':Math.round(v)}},
  legend:{{top:0,itemWidth:12,itemHeight:8,textStyle:{{fontSize:11.5}}}},
  grid:{{left:8,right:16,top:38,bottom:8,containLabel:true}},
  xAxis:{{type:'category',data:P.sizeCats,axisTick:{{show:false}},axisLine:{{lineStyle:{{color:'#e5e7eb'}}}},axisLabel:{{fontSize:11.5}}}},
  yAxis:{{type:'value',splitLine:{{lineStyle:{{color:'#f0f2f5'}}}},axisLabel:{{fontSize:11}}}},
  series:[
    {{name:'P90 需求',type:'bar',data:P.sizeDemand,itemStyle:{{color:'#dc2626',borderRadius:[3,3,0,0]}},barWidth:'26%'}},
    {{name:'现有可用',type:'bar',data:P.sizeSupply,itemStyle:{{color:'#10b981',borderRadius:[3,3,0,0]}},barWidth:'26%'}},
    {{name:'本次做货',type:'bar',data:P.sizeProd,itemStyle:{{color:'#2563eb',borderRadius:[3,3,0,0]}},barWidth:'26%'}}
  ]
}});
window.addEventListener('resize',()=>{{c1.resize();c2.resize();}});
</script>
</body>
</html>"""

    out = os.path.join(base, 'outputs', f'{parent}_90天做货需求分析_{s["snapshotDate"]}.html')
    with open(out, 'w', encoding='utf-8') as fh:
        fh.write(html)
    print('已生成:', out)


if __name__ == '__main__':
    main()
