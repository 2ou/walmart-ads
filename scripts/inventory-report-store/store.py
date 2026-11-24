import argparse
import base64
from datetime import datetime, timezone
import gzip
from hashlib import sha256
import json
import os
from pathlib import Path
import re
import subprocess
import sys

import pymysql

from report_sizes import embed_size_filters


HERE = Path(__file__).resolve().parent
SOURCE = Path("D:/WorkBuddyData/walmart-orderlimit")
DATABASE = "walmart_inventory_reports"
ITEM_PAGE = re.compile(r"item-(\d+)-report\.html")
HISTORY = re.compile(r"snapshot-(\d{8})-(\d+)-rows\.json")
KEYWORD_SLUG = "keywords"
KEYWORD_NAME = "关键词市场"
KEYWORD_BRAND = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]{0,99}")


def connect(*, admin=False, schema=True):
    if admin:
        config = (HERE.parents[1] / "src/main/resources/application.yml").read_text(encoding="utf-8")
        fallback = re.search(r"\$\{WALMART_ADS_DB_PASSWORD:([^}]*)\}", config)
        user = os.environ.get("WALMART_ADS_DB_USERNAME", "root")
        password = os.environ.get("WALMART_ADS_DB_PASSWORD", fallback.group(1) if fallback else "")
    else:
        user = os.environ["INVENTORY_REPORT_DB_USER"]
        password = os.environ["INVENTORY_REPORT_DB_PASSWORD"]
    return pymysql.connect(host="127.0.0.1", user=user, password=password,
                           database=DATABASE if schema else None, charset="utf8mb4",
                           binary_prefix=True, connect_timeout=5, read_timeout=25, write_timeout=25)


def to_json(value):
    return json.dumps(value, separators=(",", ":"), ensure_ascii=False)


def snapshot_digest(captured, rows):
    document = [captured.isoformat() if captured else None, rows]
    canonical = json.dumps(document, separators=(",", ":"), ensure_ascii=False, sort_keys=True)
    return sha256(canonical.encode("utf-8")).digest()


def parse_time(value):
    if not value:
        return None
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    return parsed.astimezone(timezone.utc).replace(tzinfo=None) if parsed.tzinfo else parsed


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def slug_for(brand):
    if brand == "其他单款":
        return "items"
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,99}", brand):
        raise ValueError("Invalid report brand name")
    return brand.lower()


def add_brand(cursor, brand):
    slug = slug_for(brand)
    cursor.execute("INSERT INTO brands (slug,name) VALUES (%s,%s) "
                   "ON DUPLICATE KEY UPDATE name=VALUES(name)", (slug, brand))
    return slug


def ensure_keyword_market(cursor):
    cursor.execute("INSERT INTO brands (slug,name) VALUES (%s,%s) "
                   "ON DUPLICATE KEY UPDATE name=VALUES(name)", (KEYWORD_SLUG, KEYWORD_NAME))
    return KEYWORD_SLUG


def create_schema(connection):
    with connection.cursor() as cursor:
        for statement in (HERE / "schema.sql").read_text(encoding="utf-8").split(";"):
            if statement.strip():
                cursor.execute(statement)
    connection.commit()


def store_style(cursor, slug, item_id, meta):
    cursor.execute("""
        INSERT INTO styles (brand_slug,item_id,title,seller,average_rating,review_count,rating_observed_at,metadata)
        VALUES (%s,%s,%s,%s,%s,%s,%s,%s)
        ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id),
            title=COALESCE(VALUES(title), title), seller=COALESCE(VALUES(seller), seller),
            average_rating=COALESCE(VALUES(average_rating), average_rating),
            review_count=COALESCE(VALUES(review_count), review_count),
            rating_observed_at=COALESCE(VALUES(rating_observed_at), rating_observed_at),
            metadata=CASE WHEN JSON_TYPE(JSON_EXTRACT(styles.metadata, '$.listing'))='OBJECT' THEN
                JSON_SET(VALUES(metadata), '$.listing', JSON_MERGE_PATCH(
                    JSON_EXTRACT(styles.metadata, '$.listing'),
                    CASE WHEN JSON_TYPE(JSON_EXTRACT(VALUES(metadata), '$.listing'))='OBJECT'
                        THEN JSON_EXTRACT(VALUES(metadata), '$.listing') ELSE JSON_OBJECT() END))
                ELSE VALUES(metadata) END
        """, (slug, item_id, meta.get("name"), meta.get("seller"), meta.get("rating"), meta.get("reviews"),
              parse_time(meta.get("ratingFetchedAt") or (meta.get("fetchedAt") if meta.get("rating") is not None else None)),
              to_json(meta)))
    return cursor.lastrowid


def store_snapshot(cursor, style_id, rows, meta, *, current, fallback=None):
    captured = parse_time(meta.get("fetchedAt")) or fallback
    digest = snapshot_digest(captured, rows)
    cursor.execute("""
        INSERT IGNORE INTO inventory_snapshots (style_id,captured_at,content_hash,capture_metadata)
        VALUES (%s,%s,%s,%s)
        """, (style_id, captured, digest, to_json(meta)))
    snapshot_id = cursor.lastrowid
    if cursor.rowcount:
        fields = ("usItemId", "productId", "dim", "value", "label", "size", "orderLimit",
                  "maxOrderQuantity", "orderMinLimit", "availabilityStatus", "price", "wasPrice", "sellerName")
        cursor.executemany("""
            INSERT INTO inventory_skus (snapshot_id,ordinal,item_id,product_id,variant_dimension,variant_value,
              variant_label,size_name,order_limit,max_order_quantity,minimum_order_quantity,availability,
              price,was_price,seller,source_row) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
            """, [(snapshot_id, pos, *(row.get(key) for key in fields), to_json(row))
                  for pos, row in enumerate(rows)])
    else:
        cursor.execute("SELECT id FROM inventory_snapshots WHERE style_id=%s AND content_hash=%s", (style_id, digest))
        snapshot_id = cursor.fetchone()[0]
    if current:
        cursor.execute("""
            UPDATE styles s LEFT JOIN inventory_snapshots previous ON previous.id=s.current_snapshot_id
            SET s.current_snapshot_id=%s WHERE s.id=%s AND
            (previous.captured_at IS NULL OR %s >= previous.captured_at)
            """, (snapshot_id, style_id, captured))
    return snapshot_id


def save_page(cursor, slug, filename, kind, packed, updated):
    raw = gzip.decompress(packed)
    if slug == "fantaslook" and kind == "edition":
        raw = embed_size_filters(cursor, slug, raw, HERE)
        packed = gzip.compress(raw)
    match = re.search(rb"<title[^>]*>(.*?)</title>", raw, re.S | re.I)
    title = match[1].decode("utf-8").strip() if match else filename
    cursor.execute("""
        INSERT INTO report_pages (brand_slug,filename,kind,title,updated_at,content_hash,html_gzip)
        VALUES (%s,%s,%s,%s,%s,%s,%s)
        ON DUPLICATE KEY UPDATE
            title=IF(VALUES(updated_at)>=updated_at,VALUES(title),title),
            content_hash=IF(VALUES(updated_at)>=updated_at,VALUES(content_hash),content_hash),
            html_gzip=IF(VALUES(updated_at)>=updated_at,VALUES(html_gzip),html_gzip),
            updated_at=GREATEST(updated_at,VALUES(updated_at))
        """, (slug, filename, kind, title, updated, sha256(raw).digest(), packed))


def import_reports(connection, source, renderer):
    counters = {"pages": 0, "styles": 0, "sku_rows": 0, "historical": 0}
    identities = {}
    editions = []
    with subprocess.Popen(["node", str(HERE / "render-source.mjs"), str(source), str(renderer)],
                          stdout=subprocess.PIPE, text=True, encoding="utf-8") as emitter, connection.cursor() as cursor:
        try:
            for line in emitter.stdout:
                page = json.loads(line)
                slug = add_brand(cursor, page["brand"])
                folder = Path(page["directory"])
                updated = parse_time(page["updatedAt"])
                if page["kind"] == "item":
                    item_id = page["itemId"]
                    meta = read_json(folder / f"meta-item-{item_id}.json")
                    rows = read_json(folder / f"item-{item_id}-rows.json")
                    style_id = store_style(cursor, slug, item_id, meta)
                    identities[(folder, item_id)] = style_id
                    store_snapshot(cursor, style_id, rows, meta, current=True, fallback=updated)
                    counters["styles"] += 1
                    counters["sku_rows"] += len(rows)
                record = (slug, page["filename"], page["kind"], base64.b64decode(page["htmlGzip"]), updated)
                if page["kind"] == "edition":
                    editions.append(record)
                else:
                    save_page(cursor, *record)
                counters["pages"] += 1
            if emitter.wait():
                raise RuntimeError("Failed to render original report HTML")
            for record in editions:
                save_page(cursor, *record)
            for folder in {folder for folder, _ in identities}:
                for path in folder.glob("snapshot-*-rows.json"):
                    match = HISTORY.fullmatch(path.name)
                    if not match or (folder, match[2]) not in identities:
                        continue
                    metadata = path.with_name(path.name.replace("-rows.json", "-meta.json"))
                    meta = read_json(metadata) if metadata.is_file() else {}
                    store_snapshot(cursor, identities[(folder, match[2])], read_json(path), meta,
                                   current=False, fallback=datetime.strptime(match[1], "%Y%m%d"))
                    counters["historical"] += 1
            connection.commit()
        except Exception:
            if emitter.poll() is None:
                emitter.terminate()
            connection.rollback()
            raise
    return counters


def publish(connection, record):
    brand, filename, kind = record["brand"], record["filename"], record["kind"]
    match = ITEM_PAGE.fullmatch(filename)
    is_keyword = kind == "keyword"
    valid = (kind == "item" and match
             or kind == "brand" and filename == f"brand-{brand}-report.html"
             or kind == "edition" and brand == "Fantaslook" and filename == "brand-Fantaslook-plus-size-report.html"
             or is_keyword and bool(KEYWORD_BRAND.fullmatch(brand)) and filename == f"keyword-{brand}-report.html")
    if not valid:
        raise ValueError("Invalid report filename or type")
    if match:
        meta, rows = record["meta"], record["rows"]
        if str(meta.get("usItemId")) != match[1] or not isinstance(rows, list) or not rows:
            raise ValueError("Item metadata and nonempty SKU rows must match the report")
        if "inventoryRoster" in meta:
            roster = meta["inventoryRoster"]
            received = {row.get("key"): row.get("productId") for row in rows}
            if (meta.get("inventoryCaptureComplete") is not True or not parse_time(meta.get("fetchedAt"))
                    or len(rows) != len(roster) or received != roster
                    or any(type(row.get("orderLimit")) is not int or row["orderLimit"] < 0 for row in rows)):
                raise ValueError("SKU capture is incomplete and cannot update current stock")
    updated = datetime.now(timezone.utc).replace(tzinfo=None)
    with connection.cursor() as cursor:
        slug = ensure_keyword_market(cursor) if is_keyword else add_brand(cursor, brand)
        if match:
            cursor.execute("""
                SELECT s.id, p.captured_at FROM styles s
                LEFT JOIN inventory_snapshots p ON p.id=s.current_snapshot_id
                WHERE s.brand_slug=%s AND s.item_id=%s FOR UPDATE
                """, (slug, match[1]))
            previous = cursor.fetchone()
            captured = parse_time(meta.get("fetchedAt")) or updated
            if previous and previous[1] and captured < previous[1]:
                store_snapshot(cursor, previous[0], rows, meta, current=False, fallback=captured)
                connection.commit()
                return {"storedHistory": True, "published": False}
            style = store_style(cursor, slug, match[1], meta)
            current = store_snapshot(cursor, style, rows, meta, current=True, fallback=updated)
            cursor.execute("SELECT current_snapshot_id FROM styles WHERE id=%s", (style,))
            if cursor.fetchone()[0] != current:
                connection.commit()
                return {"storedHistory": True, "published": False}
        payload = gzip.compress(record["html"].encode("utf-8"))
        save_page(cursor, slug, filename, kind, payload, updated)
    connection.commit()
    return {"published": True, "brand": brand, "filename": filename}


if __name__ == "__main__":
    cli = argparse.ArgumentParser()
    cli.add_argument("action", choices=["init", "import", "publish"])
    cli.add_argument("--source", type=Path, default=SOURCE)
    args = cli.parse_args()
    if args.action in {"init", "import"}:
        with connect(admin=True, schema=False) as db:
            create_schema(db)
            if args.action == "import":
                print(to_json(import_reports(db, args.source.resolve(), SOURCE / "_lan-share/report-export.mjs")))
    else:
        with connect(admin=True) as db:
            print(to_json(publish(db, json.load(sys.stdin))))
