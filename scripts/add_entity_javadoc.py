#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
为全部 JPA 实体类的字段注入 Javadoc，注释文字与 db/migration V13/V14 的 MySQL 字段注释保持一致。
- 表名来自 @Table(name="...")
- 列名来自字段上方注解里的 name="..."，否则 camelCase -> snake_case
- 注释来自 V13/V14 SQL 的 COMMENT '...'
仅当字段尚无 Javadoc 时才插入，可重复执行（幂等）。
"""
import os
import re

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MIGRATION = os.path.join(BASE, "src", "main", "resources", "db", "migration")
ENTITY_DIRS = [
    os.path.join(BASE, "src", "main", "java", "com", "ai", "walmartads", "importing", "domain"),
    os.path.join(BASE, "src", "main", "java", "com", "ai", "walmartads", "product", "domain"),
    os.path.join(BASE, "src", "main", "java", "com", "ai", "walmartads", "product", "application"),
    os.path.join(BASE, "src", "main", "java", "com", "ai", "walmartads", "ai"),
]

# 1) 解析 V13/V14，构建 table -> column -> comment
comments = {}
alter_re = re.compile(r"ALTER\s+TABLE\s+`?([A-Za-z0-9_]+)`?", re.IGNORECASE)
modify_re = re.compile(r"MODIFY\s+COLUMN\s+`?([A-Za-z0-9_]+)`?.*?COMMENT\s+'([^']*)'", re.IGNORECASE)
addcol_re = re.compile(r"ADD\s+COLUMN\s+`?([A-Za-z0-9_]+)`?.*?COMMENT\s+'([^']*)'", re.IGNORECASE)

current_table = None
for fn in sorted(os.listdir(MIGRATION)):
    if not (fn.endswith(".sql") and (fn.startswith("V13") or fn.startswith("V14"))):
        continue
    with open(os.path.join(MIGRATION, fn), "r", encoding="utf-8") as fh:
        text = fh.read().replace("\r\n", "\n").replace("\r", "\n")
    for line in text.split("\n"):
        m = alter_re.search(line)
        if m:
            current_table = m.group(1)
            comments.setdefault(current_table, {})
            continue
        m = modify_re.search(line)
        if m and current_table:
            comments[current_table][m.group(1)] = m.group(2)
            continue
        m = addcol_re.search(line)
        if m and current_table:
            comments[current_table][m.group(1)] = m.group(2)

# 2) 处理每个实体 .java
table_re = re.compile(r'@Table\s*\(\s*name\s*=\s*"([^"]+)"')
name_re = re.compile(r'name\s*=\s*"([^"]+)"')
field_re = re.compile(r"^\s*private\s+")


def camel_to_snake(name):
    s = re.sub(r"(?<!^)(?=[A-Z])", "_", name)
    return s.lower()


def column_of(field_name, block_lines):
    for bl in block_lines:
        m = name_re.search(bl)
        if m:
            return m.group(1)
    return camel_to_snake(field_name)


def find_java_files():
    out = []
    for d in ENTITY_DIRS:
        for root, _, files in os.walk(d):
            for f in files:
                if f.endswith(".java"):
                    out.append(os.path.join(root, f))
    return out


def process_file(path):
    with open(path, "r", encoding="utf-8") as fh:
        lines = fh.read().replace("\r\n", "\n").replace("\r", "\n").split("\n")
    m = table_re.search("\n".join(lines))
    if not m:
        return 0
    table = m.group(1)
    table_comments = comments.get(table, {})
    new_lines = list(lines)
    inserted = 0
    i = 0
    while i < len(new_lines):
        line = new_lines[i]
        if field_re.match(line) and ";" in line and "(" not in line:
            # 收集上方注解块
            block = []
            j = i - 1
            has_javadoc = False
            while j >= 0:
                s = new_lines[j].strip()
                if s == "":
                    j -= 1
                    continue
                if s.startswith("@") or s.startswith("*") or s.startswith("/**") or s.startswith("*/"):
                    if s.startswith("/**") or s.startswith("*") or s.startswith("*/"):
                        has_javadoc = True
                    block.insert(0, new_lines[j])
                    j -= 1
                    continue
                break
            if has_javadoc:
                i += 1
                continue
            # 解析字段名
            fm = re.search(r"private\s+\S+\s+(\w+)\s*;", line)
            if not fm:
                i += 1
                continue
            field_name = fm.group(1)
            col = column_of(field_name, block)
            comment = table_comments.get(col)
            if not comment:
                i += 1
                continue
            indent = re.match(r"(\s*)", line).group(1)
            new_lines.insert(i, f"{indent}/** {comment} */")
            inserted += 1
            i += 2  # 跳过刚插入的行 + 原字段行
        else:
            i += 1
    if inserted:
        with open(path, "w", encoding="utf-8") as fh:
            fh.write("\n".join(new_lines))
    return inserted


total = 0
report = []
for path in find_java_files():
    n = process_file(path)
    if n:
        report.append((os.path.relpath(path, BASE), n))
        total += n

print(f"共注入 Javadoc 字段数: {total}")
for p, n in report:
    print(f"  {p}: +{n}")
