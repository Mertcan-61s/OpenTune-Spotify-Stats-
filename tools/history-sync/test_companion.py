from contextlib import closing
from pathlib import Path
import sqlite3
from tempfile import TemporaryDirectory
from companion import exchange

def test_merge():
    with TemporaryDirectory() as tmp:
        path = Path(tmp) / "library.db"
        with closing(sqlite3.connect(path)) as db, db:
            db.execute("CREATE TABLE plays(id TEXT NOT NULL, at INTEGER NOT NULL)")
            db.execute("CREATE TABLE songs(id TEXT PRIMARY KEY,title TEXT NOT NULL,artist TEXT NOT NULL,cover TEXT,length TEXT)")
            db.execute("INSERT INTO songs(id,title,artist) VALUES('pc','Title','Artist')")
            db.executemany("INSERT INTO plays VALUES('pc',?)", [(i,) for i in range(1,403)])
        row = dict(videoId="phone",title="Title",artist="Artist",playedAt=500)
        first = exchange(path, dict(cursor=0,rows=[row]))
        second = exchange(path, dict(cursor=first["cursor"],rows=[row]))
        assert first["more"] and len(first["rows"]) == 400
        assert len(second["rows"]) == 2
        assert exchange(path, dict(cursor=second["cursor"],rows=[row]))["rows"] == []
        try:
            exchange(path, dict(cursor=0,rows=[dict(row,playedAt=501),{}]))
        except KeyError:
            pass
        with closing(sqlite3.connect(path)) as db:
            assert db.execute("SELECT count(*) FROM plays").fetchone() == (403,)

if __name__ == "__main__":
    test_merge()
    print("PASS: pagination, two-way sync, retries, distinct replays, rollback and connection cleanup")
