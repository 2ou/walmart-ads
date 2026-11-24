import json
import re
import unicodedata


FILTER_MARKUP = re.compile(rb"<!-- inventory-size-filters:start -->.*?<!-- inventory-size-filters:end -->", re.DOTALL)


def size_bounds(value):
    label = "".join(unicodedata.normalize("NFKC", value or "").upper().split())
    role, separator, size = label.partition(":")
    if separator and role not in {"DAD", "MOM", "MON"}:
        return None
    size = size if separator else label
    audience = re.fullmatch(r"(?:PLUS|REGULAR)SIZE\(([^()]+)\)", size)
    if audience:
        size = audience[1]
    levels = []
    for token in re.split(r"[-‐‑‒–—−]", size):
        if token in {"XXS", "XS", "S", "M", "L"}:
            levels.append(0)
        elif token == "XL":
            levels.append(1)
        elif numeric := re.fullmatch(r"(\d+)XL?", token):
            levels.append(max(1, int(numeric[1])))
        elif re.fullmatch(r"X{2,}L", token):
            levels.append(len(token) - 1)
        else:
            return None
    return min(levels), max(levels)


def plus_size_groups(cursor, slug):
    cursor.execute("""
        SELECT DISTINCT s.item_id, k.size_name
        FROM styles s JOIN inventory_skus k ON k.snapshot_id=s.current_snapshot_id
        WHERE s.brand_slug=%s
        """, (slug,))
    sizes = {}
    for item, label in cursor.fetchall():
        sizes.setdefault(item, []).append(size_bounds(label))
    groups = {}
    for item, bounds in sizes.items():
        if all(bound and bound[0] >= 1 for bound in bounds):
            groups[item] = "plus-only"
        elif max((bound[1] for bound in bounds if bound), default=0) == 3:
            groups[item] = "3xl"
    return groups


def embed_size_filters(cursor, slug, markup, directory):
    markup = FILTER_MARKUP.sub(b"", markup)
    groups = plus_size_groups(cursor, slug)
    item_ids = set(re.findall(rb'href="item-(\d+)-report\.html"', markup))
    serialized = json.dumps({item: group for item, group in groups.items() if item.encode() in item_ids})
    css = (directory / "report-category.css").read_text(encoding="utf-8")
    js = (directory / "report-category.js").read_text(encoding="utf-8")
    controls = (f'<!-- inventory-size-filters:start --><style>{css}</style>'
                f'<script type="application/json" id="inventory-size-groups">{serialized}</script>'
                f'<script>{js}</script><!-- inventory-size-filters:end -->').encode("utf-8")
    closing = re.search(rb"</body\s*>", markup, re.IGNORECASE)
    end = closing.start() if closing else len(markup)
    return markup[:end] + controls + markup[end:]
