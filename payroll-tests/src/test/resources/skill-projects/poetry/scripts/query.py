import json
import pathlib
import sys

root = pathlib.Path("/data")


def main() -> None:
    keyword = sys.argv[1] if len(sys.argv) > 1 else ""
    path = root / "poetry.json"
    if not path.exists():
        print(json.dumps({"status": "UNINITIALIZED"}, ensure_ascii=False))
        return
    items = json.loads(path.read_text(encoding="utf-8")).get("poems", [])
    hits = [item for item in items if keyword in item.get("content", "")]
    print(json.dumps({"status": "OK", "items": hits[:5]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
