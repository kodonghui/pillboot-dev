"""사이트맵 원본(sitemap.yaml)을 점검하고, 보드·목록·슬라이드 그림(sitemap.html)을 그림.

쓰는 법(저장소 맨 위에서):
    python docs/07-sitemap/draw.py            # 점검하고 sitemap.html을 새로 그림
    python docs/07-sitemap/draw.py --check    # 점검만 함(파일을 쓰지 않음)

PyYAML이 필요함(pip install pyyaml). 점검에 실패하면 이유를 출력하고 1로 끝남.
그림은 이 스크립트로만 그림. sitemap.html을 손으로 고치지 않음.
"""

import argparse
import datetime
import hashlib
import html
import json
import re
import sys
from pathlib import Path

try:
    import yaml
except ImportError:
    sys.exit("PyYAML이 없음: pip install pyyaml 뒤 다시 실행해 주세요.")

ROOT = Path(__file__).resolve().parents[2]
HERE = ROOT / "docs" / "07-sitemap"
SOURCE = HERE / "sitemap.yaml"
OUTPUT = HERE / "sitemap.html"
PATHS_JS = ROOT / "react-client" / "src" / "paths.js"
ROUTER_JS = ROOT / "react-client" / "src" / "router.js"
DOMAIN = ROOT / "docs" / "01-domain" / "domain.md"
FLOW_V3 = ROOT / "docs" / "03-user-flow" / "user-flow-v3.md"
PROJECT = ROOT / ".corthex" / "project.json"

# FL-01~14·V2-FL-15~16은 Notion User Flow Map이 원본임(2026-11-05 화면 원장에서 읽음). V3-FL은 Git 원문에서 읽음.
NOTION_FLOWS = ["FL-%02d" % n for n in range(1, 15)] + ["V2-FL-15", "V2-FL-16"]

ACCESS = {"비회원": "로그인하지 않은 사람", "회원": "로그인한 회원", "누구나": "로그인과 상관없음"}
KINDS = {"page": "화면", "view": "같은 주소 안 화면", "window": "창·패널", "outside": "앱 밖"}
KST = datetime.timezone(datetime.timedelta(hours=9))


def walk(nodes, depth=0, parent=None):
    for node in nodes:
        yield node, depth, parent
        yield from walk(node.get("children") or [], depth + 1, node)


def flow_key(flow_id):
    m = re.match(r"^(?:(V\d)-)?FL-(\d+)$", flow_id)
    return int(m.group(2)) if m else 999


def check(tree, text):
    """원본을 점검함. (이름, 통과 여부, 설명) 목록을 돌려줌."""
    results = []
    screens = [n for n, _, _ in walk(tree)]
    ids = [s["id"] for s in screens]
    by_id = {s["id"]: s for s in screens}

    prefixes = json.loads(PROJECT.read_text(encoding="utf-8"))["ids"]["screen"]["prefix"]
    pattern = re.compile(r"^(%s)-\d{2}$" % "|".join(prefixes))
    dup = sorted({i for i in ids if ids.count(i) > 1})
    bad = [i for i in ids if not pattern.match(i)]
    results.append(("화면 ID가 겹치지 않고 프로젝트 형식(%s-NN)을 따름" % "·".join(prefixes), not dup and not bad,
                    "겹침 %s, 형식 밖 %s" % (dup or "없음", bad or "없음")))

    results.append(("뿌리가 하나이고 모든 화면이 뿌리에서 닿음", len(tree) == 1,
                    "뿌리 %s, 화면 %d개 모두 그 아래 트리에 있음" % ([n["id"] for n in tree], len(screens))))

    no_access = [s["id"] for s in screens if s.get("access") not in ACCESS]
    results.append(("모든 화면에 누가 볼 수 있는지 적음", not no_access,
                    "빠지거나 모르는 값 %s" % (no_access or "없음")))

    shape = []
    for s in screens:
        kind, path, status = s.get("kind"), s.get("path"), s.get("status")
        if kind not in KINDS or status not in ("active", "removed"):
            shape.append("%s kind/status" % s["id"])
        elif status == "removed" and path is not None:
            shape.append("%s 없앤 화면에 주소" % s["id"])
        elif kind == "page" and status == "active" and not path:
            shape.append("%s 주소 없음" % s["id"])
        elif kind != "page" and path is not None:
            shape.append("%s page가 아닌데 주소" % s["id"])
        if path is None and not s.get("at"):
            shape.append("%s 나오는 곳(at) 없음" % s["id"])
        for ref in s.get("also_in") or []:
            if ref not in by_id:
                shape.append("%s also_in %s 없음" % (s["id"], ref))
    results.append(("칸 모양(kind·status·path·at·also_in)이 규칙에 맞음", not shape, ", ".join(shape) or "어긋남 없음"))

    paths = [s["path"] for s in screens if s.get("path")]
    dup_paths = sorted({p for p in paths if paths.count(p) > 1})
    results.append(("두 화면이 같은 주소를 쓰지 않음", not dup_paths,
                    "주소 %d개, 겹침 %s" % (len(paths), dup_paths or "없음")))

    routes = set(re.findall(r":\s*'(/[^']*)'", PATHS_JS.read_text(encoding="utf-8")))
    if "path: '*'" in ROUTER_JS.read_text(encoding="utf-8"):
        routes.add("*")
    live = {s["path"] for s in screens if s.get("path") and s.get("status") == "active"}
    results.append(("앱 라우트(paths.js·router.js)와 사이트맵 주소가 같음", routes == live,
                    "라우트 %d개 · 사이트맵 %d개, 라우트에만 %s, 사이트맵에만 %s"
                    % (len(routes), len(live), sorted(routes - live) or "없음", sorted(live - routes) or "없음")))

    v3 = re.findall(r"^## (V3-FL-\d+)", FLOW_V3.read_text(encoding="utf-8"), re.M)
    known = set(NOTION_FLOWS) | set(v3)
    header = text.split("\n- id:")[0]
    no_screen_line = next((l for l in header.splitlines() if l.startswith("# 웹 화면이 없는 흐름")), "")
    no_screen = set(re.findall(r"(?:V\d-)?FL-\d+", no_screen_line))
    used = {f for s in screens for f in s.get("flows") or []}
    unknown = sorted(used - known)
    missing = sorted(known - used - no_screen, key=flow_key)
    both = sorted(used & no_screen)
    results.append(("화면을 보여 주는 흐름은 모두 화면에 이어짐", not unknown and not missing and not both,
                    "흐름 %d개 중 화면에 이어짐 %d · 웹 화면 없음 %d(%s), 빠짐 %s, 모르는 ID %s, 양쪽에 다 있음 %s"
                    % (len(known), len(used & known), len(no_screen), " ".join(sorted(no_screen, key=flow_key)),
                       missing or "없음", unknown or "없음", both or "없음")))

    domain = DOMAIN.read_text(encoding="utf-8")
    defined = set(re.findall(r"^\| ((?:CTX|TERM)-\d{3})", domain, re.M))
    terms = {t for s in screens if isinstance(s.get("terms"), list) for t in s["terms"]}
    bad_terms = sorted(terms - defined)
    no_terms = [s["id"] for s in screens if s.get("terms") != "none" and not isinstance(s.get("terms"), list)]
    results.append(("맥락·용어 ID가 도메인 문서에 있음", not bad_terms and not no_terms,
                    "쓴 ID %d개, 도메인에 없는 것 %s, terms 빠짐 %s" % (len(terms), bad_terms or "없음", no_terms or "없음")))
    return results


def esc(value):
    return html.escape(str(value), quote=True)


def breakable(text):
    """주소는 '/'·'?'·'&' 뒤에서만 줄을 바꾸게 함(단어 중간에서 끊지 않음)."""
    return "<wbr>".join(esc(part) for part in re.split(r"(?<=[/?&])", str(text)))


def chip_access(access):
    cls = {"비회원": "guest", "회원": "member", "누구나": "public"}[access]
    return '<span class="chip %s" title="%s">%s</span>' % (cls, esc(ACCESS[access]), esc(access))


def where(s):
    if s.get("path"):
        return '<code class="path">%s</code>' % breakable(s["path"])
    return '<span class="at">%s</span>' % breakable(s.get("at", ""))


def node_card(s):
    removed = s.get("status") == "removed"
    kind = s["kind"]
    tags = []
    if kind != "page":
        tags.append('<span class="tag">%s</span>' % esc(KINDS[kind]))
    if removed:
        tags.append('<span class="tag removed-tag">없앰</span>')
    if s.get("added"):
        tags.append('<span class="tag new-tag">%s 더함</span>' % esc(s["added"]))
    also = ""
    if s.get("also_in"):
        also = '<span class="also">다른 입구 %s</span>' % esc(" · ".join(s["also_in"]))
    return (
        '<div class="node k-%s%s" data-screen="%s">'
        '<div class="node-head"><span class="sid">%s</span>%s</div>'
        '<div class="node-name">%s</div>'
        '<div class="node-where">%s</div>'
        '<div class="node-foot">%s%s</div>'
        "</div>"
    ) % (kind, " is-removed" if removed else "", esc(s["id"]), esc(s["id"]), "".join(tags), esc(s["name"]),
         where(s), chip_access(s["access"]), also)


def tree_html(nodes):
    if not nodes:
        return ""
    items = []
    for s in nodes:
        items.append("<li>%s%s</li>" % (node_card(s), tree_html(s.get("children") or [])))
    return "<ul>%s</ul>" % "".join(items)


def flows_text(s):
    flows = s.get("flows") or []
    return " ".join('<span class="flow">%s</span>' % esc(f) for f in flows) or '<span class="none">없음</span>'


def terms_text(s):
    terms = s.get("terms")
    if not isinstance(terms, list):
        return '<span class="none">없음</span>'
    return " ".join('<span class="term">%s</span>' % esc(t) for t in terms)


def list_rows(tree):
    rows = []
    for s, depth, _ in walk(tree):
        removed = s.get("status") == "removed"
        rows.append(
            '<tr data-screen="%s"%s><td class="id-cell" style="--depth:%d"><span class="sid">%s</span></td>'
            "<td>%s%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td class=\"note\">%s</td></tr>"
            % (esc(s["id"]), ' class="is-removed"' if removed else "", depth, esc(s["id"]), esc(s["name"]),
               ' <span class="tag removed-tag">없앰</span>' if removed else "", esc(KINDS[s["kind"]]), where(s),
               chip_access(s["access"]), flows_text(s), terms_text(s), esc(s.get("note", "")))
        )
    return "".join(rows)


def slides_html(tree, stamp):
    root = tree[0]
    branches = root.get("children") or []
    out = [
        '<article class="slide" data-screen="%s"><div class="slide-inner">'
        '<p class="slide-kicker">사이트맵 검토 · 뿌리</p><h3>%s %s <code class="path">%s</code></h3>'
        '<p class="slide-lede">로그인한 회원의 첫 화면임. 아래 %d개 가지가 여기서 갈라짐.</p>'
        '<ol class="slide-branches">%s</ol><p class="slide-foot">%s</p></div></article>'
        % (esc(root["id"]), esc(root["id"]), esc(root["name"]), breakable(root["path"]), len(branches),
           "".join("<li><span class=\"sid\">%s</span> %s</li>" % (esc(b["id"]), esc(b["name"])) for b in branches),
           esc(stamp))
    ]
    for n, branch in enumerate(branches, 1):
        members = [s for s, _, _ in walk([branch])]
        flows = sorted({f for s in members for f in s.get("flows") or []}, key=lambda f: (f.count("-"), flow_key(f)))
        rows = "".join(
            '<li data-screen="%s" style="--depth:%d"%s><span class="sid">%s</span> <b>%s</b> %s %s</li>'
            % (esc(s["id"]), d, ' class="is-removed"' if s.get("status") == "removed" else "", esc(s["id"]),
               esc(s["name"]), where(s), chip_access(s["access"]))
            for s, d, _ in walk([branch])
        )
        out.append(
            '<article class="slide"><div class="slide-inner">'
            '<p class="slide-kicker">가지 %d / %d · %s에서</p><h3>%s %s</h3>'
            '<div class="slide-body"><ul class="slide-screens">%s</ul>'
            '<div class="slide-flows"><p class="label">지나는 흐름</p>%s</div></div>'
            '<p class="slide-foot">%s</p></div></article>'
            % (n, len(branches), esc(root["id"]), esc(branch["id"]), esc(branch["name"]), rows,
               " ".join('<span class="flow">%s</span>' % esc(f) for f in flows) or '<span class="none">없음</span>',
               esc(stamp))
        )
    return "".join(out)


def mermaid_text(tree):
    lines = ["flowchart TD"]
    for s, _, parent in walk(tree):
        key = s["id"].replace("-", "")
        label = "%s %s<br/>%s · %s" % (s["id"], s["name"], s.get("path") or KINDS[s["kind"]], s["access"])
        if s.get("status") == "removed":
            label += " · 없앰"
        lines.append('  %s["%s"]' % (key, label.replace('"', "'")))
        if parent:
            lines.append("  %s --> %s" % (parent["id"].replace("-", ""), key))
    return "\n".join(lines)


def render(tree, results, stamp, digest):
    root = tree[0]
    screens = [s for s, _, _ in walk(tree)]
    counts = {k: sum(1 for s in screens if s["kind"] == k and s["status"] == "active") for k in KINDS}
    removed = sum(1 for s in screens if s["status"] == "removed")
    passed = sum(1 for _, ok, _ in results if ok)
    checks = "".join(
        '<li class="%s"><span class="mark">%s</span><span><b>%s</b><br><span class="detail">%s</span></span></li>'
        % ("ok" if ok else "fail", "통과" if ok else "실패", esc(name), esc(detail))
        for name, ok, detail in results
    )
    branches = "".join(
        '<div class="branch"><p class="from-root">%s %s에서 ↓</p><div class="tree">%s</div></div>'
        % (esc(root["id"]), esc(root["name"]), tree_html([b])) for b in root.get("children") or []
    )
    template = (HERE / "sitemap.template.html").read_text(encoding="utf-8")
    values = {
        "STAMP": esc(stamp),
        "DIGEST": esc(digest),
        "TOTAL": str(len(screens)),
        "PAGES": str(counts["page"]),
        "VIEWS": str(counts["view"]),
        "WINDOWS": str(counts["window"]),
        "OUTSIDE": str(counts["outside"]),
        "REMOVED": str(removed),
        "PASSED": "%d / %d" % (passed, len(results)),
        "CHECKS": checks,
        "ROOT": node_card(root),
        "BRANCHES": branches,
        "ROWS": list_rows(tree),
        "SLIDES": slides_html(tree, stamp),
        "MERMAID": esc(mermaid_text(tree)),
    }
    for key, value in values.items():
        template = template.replace("{{%s}}" % key, value)
    return template


def views_check(page, tree):
    """그린 페이지에서 화면마다 보드·목록·슬라이드에 한 번씩 나오는지 셈."""
    ids = [s["id"] for s, _, _ in walk(tree)]
    problems = []
    for view in ("board", "list", "slides"):
        part = re.search(r'<section id="%s".*?</section>' % view, page, re.S).group(0)
        for screen_id in ids:
            n = part.count('data-screen="%s"' % screen_id)
            if n != 1:
                problems.append("%s %s %d번" % (view, screen_id, n))
    return ("화면마다 보드·목록·슬라이드에 한 번씩 나옴", not problems,
            ", ".join(problems) or "화면 %d개 × 그림 3개 모두 한 번씩" % len(ids))


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="점검만 하고 파일을 쓰지 않음")
    parser.add_argument("--time", help="그린 시각(ISO 8601). 없으면 지금 시각")
    args = parser.parse_args()

    text = SOURCE.read_text(encoding="utf-8")
    tree = yaml.safe_load(text)
    for s, _, _ in walk(tree):
        if s.get("added") is not None:
            s["added"] = str(s["added"])
    when = datetime.datetime.fromisoformat(args.time) if args.time else datetime.datetime.now(KST)
    stamp = "그린 시각 %s KST" % when.astimezone(KST).strftime("%Y-%m-%d %H:%M")
    digest = hashlib.sha256(text.encode("utf-8")).hexdigest()[:12]

    results = check(tree, text)
    page = render(tree, results + [("화면마다 보드·목록·슬라이드에 한 번씩 나옴", True, "")], stamp, digest)
    results.append(views_check(page, tree))
    page = render(tree, results, stamp, digest)

    for name, ok, detail in results:
        print("%s  %s\n      %s" % ("통과" if ok else "실패", name, detail))
    if not args.check:
        OUTPUT.write_text(page, encoding="utf-8")
        print("그림: %s (%s, 원본 sha256 %s)" % (OUTPUT.relative_to(ROOT), stamp, digest))
    return 0 if all(ok for _, ok, _ in results) else 1


if __name__ == "__main__":
    sys.exit(main())
