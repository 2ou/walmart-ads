"""Move the keyword ranking report out of brand reports into its own market section."""
from store import connect, KEYWORD_SLUG, KEYWORD_NAME

OLD_SLUG = "plussizepajamas"
OLD_FILE = "brand-PlusSizePajamas-report.html"
NEW_FILE = "keyword-PlusSizePajamas-report.html"


def main():
    with connect(admin=True) as db, db.cursor() as cursor:
        cursor.execute("ALTER TABLE report_pages MODIFY COLUMN "
                       "kind ENUM('brand','item','edition','keyword') NOT NULL")
        cursor.execute("INSERT INTO brands (slug,name) VALUES (%s,%s) "
                       "ON DUPLICATE KEY UPDATE name=VALUES(name)", (KEYWORD_SLUG, KEYWORD_NAME))
        cursor.execute("SELECT COUNT(*) FROM styles WHERE brand_slug=%s", (OLD_SLUG,))
        if cursor.fetchone()[0]:
            raise RuntimeError("old keyword brand still has styles; abort")
        cursor.execute("SELECT COUNT(*) FROM report_pages WHERE brand_slug=%s AND filename=%s",
                       (KEYWORD_SLUG, NEW_FILE))
        if cursor.fetchone()[0]:
            raise RuntimeError("target keyword page already exists; abort")
        cursor.execute("UPDATE report_pages SET brand_slug=%s, filename=%s, kind='keyword' "
                       "WHERE brand_slug=%s AND filename=%s",
                       (KEYWORD_SLUG, NEW_FILE, OLD_SLUG, OLD_FILE))
        print("moved keyword pages:", cursor.rowcount)
        cursor.execute("DELETE FROM brands WHERE slug=%s", (OLD_SLUG,))
        print("removed old brand rows:", cursor.rowcount)
        db.commit()
    print("done")


if __name__ == "__main__":
    main()
