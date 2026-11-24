from argparse import ArgumentParser
from datetime import timezone, timedelta
import gzip
from html import escape, unescape
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from ipaddress import ip_address, ip_network
import os
import re
from secrets import token_urlsafe
from urllib.parse import parse_qs, quote, urlencode, urlsplit

from store import connect, HERE, DATABASE, ITEM_PAGE


STYLE = (HERE / "portal.css").read_text(encoding="utf-8")
NAV_STYLE = (HERE / "report-nav.css").read_text(encoding="utf-8")
CATEGORY_CONTROLS = (f'<style>{(HERE / "report-category.css").read_text(encoding="utf-8")}</style>'
                     f'<script>{(HERE / "report-category.js").read_text(encoding="utf-8")}</script>').encode("utf-8")
CHINA = timezone(timedelta(hours=8))
NETWORK = ip_network("192.168.1.0/24")
PLUS_REPORT = "brand-Fantaslook-plus-size-report.html"
PLUS_PATH = "/Fantaslook/plus-size.html"
SIZE_FILTERS = {"3xl", "plus-only"}


def layout(title, content):
    return ('<!doctype html><html lang="zh-CN"><meta charset="utf-8">'
            '<meta name="viewport" content="width=device-width,initial-scale=1">'
            f'<title>{escape(title)}</title><style>{STYLE}{NAV_STYLE}</style><main>{content}</main></html>')


def navigation(back, *, placement=""):
    return (f'<nav class="inventory-report-nav {placement}" aria-label="库存报告导航">'
            f'<a href="{escape(back)}"><span aria-hidden="true">←</span> 返回</a>'
            '<a href="/">首页</a></nav>')


def category_return(back, referrer, *alternate_paths):
    source, _, query = referrer.partition("?")
    if source.lower() in {path.lower() for path in (back, *alternate_paths)}:
        supplied = parse_qs(query)
        filters = {}
        category = supplied.get("category", [""])[0]
        if 0 < len(category) <= 120:
            filters["category"] = category
        size = supplied.get("size", [""])[0]
        if back == PLUS_PATH and size in SIZE_FILTERS:
            filters["size"] = size
        if filters:
            back += "?" + urlencode(filters) + "#inventory-style-list"
    return back


def dashboard(cursor):
    cursor.execute("""
        SELECT b.slug, b.name, p.updated_at, COUNT(d.filename)
        FROM brands b JOIN report_pages p ON b.slug=p.brand_slug AND p.kind='brand'
        LEFT JOIN report_pages d ON b.slug=d.brand_slug AND d.kind='item'
        GROUP BY b.slug,b.name,p.updated_at ORDER BY b.name
        """)
    brands = cursor.fetchall()
    cursor.execute("SELECT 1 FROM report_pages WHERE brand_slug='fantaslook' AND kind='edition' LIMIT 1")
    has_edition = bool(cursor.fetchone())
    cards = []
    for slug, name, updated, count in brands:
        time = updated.replace(tzinfo=timezone.utc).astimezone(CHINA).strftime("%m-%d %H:%M")
        if slug == "fantaslook" and has_edition:
            cards.append(f'<div class="card has-edition">'
                         f'<a class="card-title" href="/{quote(name)}/"><strong>{escape(name)}</strong></a>'
                         f'<small>{count:,} 个商品详情 · 报告更新 {time}</small>'
                         f'<a class="edition-link" href="/Fantaslook/plus-size.html">大码专属报告 →</a></div>')
        else:
            cards.append(f'<a class="card" href="/{quote(name)}/"><strong>{escape(name)}</strong>'
                         f'<small>{count:,} 个商品详情 · 报告更新 {time}</small></a>')
    total = sum(row[3] for row in brands)
    cursor.execute("SELECT filename,title,updated_at FROM report_pages WHERE kind='keyword' ORDER BY updated_at DESC")
    keywords = cursor.fetchall()
    keyword_cards = []
    for filename, title, updated in keywords:
        time = updated.replace(tzinfo=timezone.utc).astimezone(CHINA).strftime("%m-%d %H:%M")
        keyword_cards.append(f'<a class="card" href="/keywords/{quote(filename)}">'
                             f'<strong>{escape(unescape(title or filename))}</strong>'
                             f'<small>关键词榜单库存 · 更新 {time}</small></a>')
    keyword_section = ('<h2>关键词市场</h2><div class="grid">' + "".join(keyword_cards) + '</div>'
                       if keyword_cards else "")
    return layout("品牌库存大盘", f'<header><h1>品牌库存大盘</h1><p class="meta">{len(brands)} 个品牌 · '
                  f'{total:,} 个品牌商品详情 · {len(keywords)} 个关键词市场报告</p></header>'
                  '<h2>品牌报告</h2><div class="grid">' + "".join(cards) + '</div>' + keyword_section)


def keyword_list(cursor):
    cursor.execute("SELECT filename,title,updated_at FROM report_pages WHERE kind='keyword' ORDER BY updated_at DESC")
    rows = cursor.fetchall()
    links = []
    for filename, title, updated in rows:
        time = updated.replace(tzinfo=timezone.utc).astimezone(CHINA).strftime("%m-%d %H:%M")
        links.append(f'<a class="result" href="/keywords/{quote(filename)}">'
                     f'<strong>{escape(unescape(title or filename))}</strong><small>更新 {time}</small></a>')
    return layout("关键词市场", navigation("/") + '<header><h1>关键词市场</h1>'
                  f'<p class="meta">{len(rows)} 份关键词榜单库存报告</p></header>'
                  '<div class="results">' + "".join(links) + '</div>')


def single_list(cursor):
    cursor.execute("SELECT filename,title FROM report_pages WHERE brand_slug='items' AND kind='item' ORDER BY title")
    rows = cursor.fetchall()
    links = [f'<a class="result" href="/items/{quote(filename)}"><strong>{escape(unescape(title or filename))}</strong>'
             f'<small>Item ID {filename.removeprefix("item-").removesuffix("-report.html")}</small></a>'
             for filename, title in rows]
    return layout("单款库存报告", navigation("/") + '<header><h1>单款库存报告</h1>'
                  f'<p class="meta">{len(rows)} 份报告</p></header>'
                  '<input id="filter" class="search" type="search" aria-label="查找报告" placeholder="搜索商品名称或 Item ID">'
                  '<div id="results" class="results">' + "".join(links) + '</div>'
                  '<script>document.querySelector("#filter").addEventListener("input",ev=>{'
                  'const query=ev.target.value.trim().toLowerCase();for(const row of document.querySelectorAll(".result"))'
                  'row.hidden=!row.textContent.toLowerCase().includes(query)})</script>')


def report(cursor, slug, name, back):
    cursor.execute("SELECT kind,html_gzip FROM report_pages WHERE brand_slug=%s AND filename=%s", (slug, name))
    stored = cursor.fetchone()
    if not stored:
        return 404, b"Report not found", None
    kind, payload = stored
    html = gzip.decompress(payload)
    placement = "inventory-report-nav--item" if kind == "item" else "inventory-report-nav--brand"
    controls = f'<style>{NAV_STYLE}</style>{navigation(back, placement=placement)}'.encode("utf-8")
    if kind in {"brand", "edition"} and b'<!-- inventory-size-filters:start -->' not in html:
        controls += CATEGORY_CONTROLS
    opening = re.search(rb"<body\b[^>]*>", html, re.IGNORECASE)
    start = opening.end() if opening else 0
    return 200, html[:start] + controls + html[start:], None


def route(cursor, url, referrer=""):
    if url == "/":
        return 200, dashboard(cursor).encode("utf-8"), None
    if not url.startswith("/"):
        return 404, b"Not found", None
    parts = url[1:].split("/")
    if len(parts) > 2:
        return 404, b"Not found", None
    slug = parts[0].lower()
    if slug == "items":
        if len(parts) == 1:
            return 308, b"", "/items/"
        if parts[1] == "":
            return 200, single_list(cursor).encode("utf-8"), None
        if ITEM_PAGE.fullmatch(parts[1]):
            return report(cursor, slug, parts[1], "/items/")
        return 404, b"Not found", None
    if slug == "keywords":
        if len(parts) == 1:
            return 308, b"", "/keywords/"
        if parts[1] == "":
            return 200, keyword_list(cursor).encode("utf-8"), None
        return report(cursor, slug, parts[1], "/keywords/")
    if slug == "plussizepajamas":
        return 308, b"", "/keywords/"
    if slug == "fantaslook" and parts[1:] == ["plus-size.html"]:
        return report(cursor, slug, PLUS_REPORT, "/" if referrer == "/" else "/Fantaslook/")
    cursor.execute("SELECT name FROM brands WHERE slug=%s", (slug,))
    brand = cursor.fetchone()
    if brand:
        brand_path = "/" + quote(brand[0]) + "/"
        if len(parts) == 1:
            return 308, b"", brand_path
        brand_report = f"brand-{brand[0]}-report.html"
        filename = parts[1] or brand_report
        if filename == brand_report:
            return report(cursor, slug, filename, "/")
        if ITEM_PAGE.fullmatch(filename):
            from_edition = slug == "fantaslook" and referrer.split("?", 1)[0].lower() == PLUS_PATH.lower()
            back = (category_return(PLUS_PATH, referrer) if from_edition
                    else category_return(brand_path, referrer, brand_path + quote(brand_report)))
            return report(cursor, slug, filename, back)
    # Keep pre-dashboard Showmall detail links working.
    if len(parts) == 1 and (parts[0] == "brand-Showmall-report.html" or ITEM_PAGE.fullmatch(parts[0])):
        back = category_return("/Showmall/", referrer, "/brand-Showmall-report.html") if ITEM_PAGE.fullmatch(parts[0]) else "/"
        return report(cursor, "showmall", parts[0], back)
    return 404, b"Not found", None


class ReportsHandler(BaseHTTPRequestHandler):
    def referrer_path(self):
        try:
            source = urlsplit(self.headers.get("Referer", ""))
        except ValueError:
            return ""
        if source.scheme == "http" and source.netloc.lower() == self.headers.get("Host", "").lower():
            return source.path + ("?" + source.query if source.query else "")
        return ""

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        peer = ip_address(self.client_address[0])
        if peer not in NETWORK and not peer.is_loopback:
            return self.send_error(403)
        try:
            with connect() as db, db.cursor() as cursor:
                status, body, location = route(cursor, urlsplit(self.path).path, self.referrer_path())
        except Exception as error:
            print(f"Report request failed: {type(error).__name__}", flush=True)
            status, body, location = 503, b"Report data unavailable", None
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8" if status == 200 else "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Vary", "Referer")
        self.send_header("X-Content-Type-Options", "nosniff")
        if location:
            self.send_header("Location", location)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def log_request(self, code="-", size="-"):
        if str(code).startswith("5"):
            super().log_request(code, size)


def provision_reader():
    user = "inventory_report_viewer"
    password = token_urlsafe(30)
    with connect(admin=True) as db, db.cursor() as cursor:
        account = "'" + user + "'@'127.0.0.1'"
        cursor.execute("CREATE USER IF NOT EXISTS " + account + " IDENTIFIED BY %s", (password,))
        cursor.execute("ALTER USER " + account + " IDENTIFIED BY %s", (password,))
        cursor.execute(f"GRANT SELECT ON {DATABASE}.* TO {account}")
        db.commit()
    os.environ["INVENTORY_REPORT_DB_USER"] = user
    os.environ["INVENTORY_REPORT_DB_PASSWORD"] = password
    with connect() as db, db.cursor() as cursor:
        cursor.execute("SELECT COUNT(*) FROM report_pages")
        print(f"MySQL report pages: {cursor.fetchone()[0]}", flush=True)


if __name__ == "__main__":
    cli = ArgumentParser()
    cli.add_argument("--host", choices=["192.168.1.118", "127.0.0.1"], default="192.168.1.118")
    cli.add_argument("--port", type=int, default=8789)
    args = cli.parse_args()
    provision_reader()
    with ThreadingHTTPServer((args.host, args.port), ReportsHandler) as server:
        print(f"MySQL inventory portal: http://{args.host}:{args.port}/", flush=True)
        server.serve_forever()
