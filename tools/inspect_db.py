import sqlite3
import sys

path = sys.argv[1]
con = sqlite3.connect("file:" + path + "?mode=ro", uri=True)
cur = con.execute("select name from sqlite_master where type='table'")
print("tables:", [r[0] for r in cur])
try:
    print("books:")
    for row in con.execute("select id,name,type,total_count,is_active from tb_book order by type,id"):
        print("   ", row)
    print("words:", con.execute("select count(*) from tb_word").fetchone())
    print("memory:", con.execute("select count(*) from tb_word_memory").fetchone())
    print("content per book:", con.execute(
        "select book_id,count(*) from tb_book_word_content group by book_id").fetchall())
    print("sample:", con.execute(
        "select w.word, length(m.approach), length(m.collocations) from tb_word w "
        "left join tb_word_memory m on m.word_id=w.id where w.word in ('increase','appropriate')").fetchall())
except Exception as exc:  # noqa: BLE001
    print("ERR", exc)
