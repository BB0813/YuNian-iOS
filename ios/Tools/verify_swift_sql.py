#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
verify_swift_sql.py — 把 iOS Swift 里的 SQL 拿到真实 v45 schema 上执行一遍。

## 为什么需要
业务代码里的 SQL 是**字符串**，编译器完全不检查。列名拼错、表名写错、
GRDB 的插值出错 —— 全都运行时才炸。而在没有 Mac 的前提下，
这是本地唯一能提前发现这类问题的手段。

## 做法
1. 从 Swift 源文件抽出所有三引号 SQL 字符串（含单行形式）
2. 处理 Swift 插值：能静态求值的替换掉，不能的标记为跳过 ——
   宁可漏报也不要误报
3. 按占位符个数绑定 None 后**真正执行**（比 EXPLAIN 更强）
4. 写操作用 savepoint 包裹并回滚，避免污染后续检查

## 已知限制
- Swift 字符串插值若含复杂表达式，本脚本无法求值 → 跳过并明确列出
- 不检查语义正确性（例如 WHERE 条件写反），只检查「能不能执行」

⚠️ 维护提醒：本模块 docstring 里**不能出现三个连续的双引号**，
否则会把它自己提前终止（这个坑本项目已经踩过一次）。
"""
from __future__ import annotations

import re
import sqlite3
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SCHEMA_SWIFT = REPO_ROOT / "ios/YuNian/Data/Schema/YuNianSchema.swift"
BUSINESS_DIRS = [
    REPO_ROOT / "ios/YuNian/Data",
    REPO_ROOT / "ios/YuNian/Agent",
    REPO_ROOT / "ios/YuNian/App",
    REPO_ROOT / "ios/YuNian/Platform",
]


def read(p: Path) -> str:
    if not p.exists():
        sys.exit(f"找不到 {p}")
    return p.read_text(encoding="utf-8")


def schema_statements() -> list[str]:
    """从 YuNianSchema.swift 抽出建表/建索引语句。"""
    src = read(SCHEMA_SWIFT)
    out: list[str] = []
    # 形如 "CREATE TABLE ...",  / "CREATE INDEX ..."
    for m in re.finditer(r'"(CREATE (?:TABLE|INDEX|UNIQUE INDEX|VIRTUAL TABLE)[^"]*)"', src):
        out.append(m.group(1))
    if not out:
        sys.exit("未能从 YuNianSchema.swift 解析出任何建表语句")
    return out


def _local_literals(src: str) -> dict[str, str]:
    """
    从 Swift 源里收集 `let X = <字面量>` / `static let X = <字面量>`。

    用于解析 `\\(boundaryClause)`、`\\(ftsTableName)` 这类本地/静态片段的插值 ——
    它们是纯字面量，可以安全地静态求值。
    """
    out: dict[str, str] = {}
    # 多行三引号形式
    for m in re.finditer(r'let\s+(\w+)(?:\s*:\s*[\w<>?:.]+)?\s*=\s*"""(.*?)"""', src, re.S):
        out[m.group(1)] = m.group(2)
    # 单行字符串形式
    for m in re.finditer(r'let\s+(\w+)(?:\s*:\s*[\w<>?:.]+)?\s*=\s*"((?:[^"\\]|\\.)*)"', src):
        out.setdefault(m.group(1), m.group(2))
    return out


def strip_swift_interpolations(sql, literals=None):
    """处理 Swift 的插值。返回 (替换后的 SQL, 无法静态求值的表达式列表)。"""
    literals = literals or {}
    skipped: list[str] = []

    def repl(m: re.Match) -> str:
        expr = m.group(1).strip()
        # 1) 本地/静态字面量片段（先全名，再取末段 ——
        #    用法常带类型前缀如 `YuNianSchema.ftsTableName`）
        if expr in literals:
            return literals[expr]
        if "." in expr:
            tail = expr.rsplit(".", 1)[-1].strip()
            if tail in literals:
                return literals[tail]
        # 2) `Type.rawValue` 形态 → 用占位符（值不影响「能否执行」）
        if re.fullmatch(r"[A-Za-z_][A-Za-z_0-9]*\.rawValue", expr):
            return "?"
        # 3) `Self.selectColumns` / 已知常量
        if expr.startswith("Self.selectColumns"):
            return "*"
        skipped.append(expr)
        return "?"

    out = re.sub(r"\\\((.+?)\)", repl, sql, flags=re.S)
    return out, skipped


def extract_sql() -> list[tuple[Path, str, dict[str, str]]]:
    """
    抽出所有 SQL 字符串，并带上可静态解析的局部字面量。

    ⚠️ 字面量表必须**跨文件全局**收集：`YuNianDatabase.swift` 的 SQL 里用到的
    `YuNianSchema.ftsTableName` 声明在 `YuNianSchema.swift`。按文件收集会漏掉它
    （第 28 轮实测：覆盖率卡在 62/109）。
    """
    global_literals: dict[str, str] = {}
    all_files: list[Path] = []
    for d in BUSINESS_DIRS:
        if d.exists():
            all_files.extend(sorted(d.rglob("*.swift")))
    # schema 文件也参与字面量收集（它定义了 ftsTableName / version 等常量）
    if SCHEMA_SWIFT.exists():
        all_files.append(SCHEMA_SWIFT)
    for path in all_files:
        global_literals.update(_local_literals(read(path)))

    found: list[tuple[Path, str, dict[str, str]]] = []
    for d in BUSINESS_DIRS:
        if not d.exists():
            continue
        for path in sorted(d.rglob("*.swift")):
            src = read(path)
            # 本文件的优先于全局（避免同名局部变量被别处的覆盖）
            merged = dict(global_literals)
            merged.update(_local_literals(src))

            # ⚠️ 先把三引号区间**掩掉**再跑单行正则。
            # 否则 `sql: """..."""` 的开头会被单行正则匹配成 `sql: ""`（空串），
            # 产生幻影条目 —— 第 29 轮实测虚增 39 条，且它们因 strip() 为空被静默跳过，
            # 掩盖了计数不实。宁可先掩掉。
            spans = [m.span() for m in re.finditer(r'sql:\s*"""(.*?)"""', src, re.S)]
            masked = list(src)
            for a, b in spans:
                for k in range(a, b):
                    masked[k] = " "
            masked_src = "".join(masked)

            for m in re.finditer(r'sql:\s*"""(.*?)"""', src, re.S):
                found.append((path, m.group(1), merged))
            for m in re.finditer(r'sql:\s*"((?:[^"\\]|\\.)*)"', masked_src):
                found.append((path, m.group(1), merged))
    return found


# 执行期错误 = 我把占位符一律绑成 None 的产物，**不是 SQL 的错**。
# 真正的 SQL 问题（表/列不存在、语法错）都发生在**准备期**，会以下面这些形式出现。
PREPARE_TIME_ERRORS = (
    "no such column",
    "no such table",
    "no such function",
    "syntax error",
    "unrecognized token",
    "misuse of aggregate",
    "ambiguous column name",
    "wrong number of arguments",
)


def is_prepare_time_error(msg: str) -> bool:
    return any(marker in msg.lower() for marker in PREPARE_TIME_ERRORS)


def check_argument_counts() -> list[str]:
    """
    核对 `db.execute(sql: ..., arguments: [...])` 的占位符与实参数组长度。

    为什么值得查：占位符个数与实参个数不符时 **GRDB 编译期不报错**，
    运行期才抛错（表现为「写不进去 / 更新不了」且很难定位）。
    这类错误正则能查，且不需要 Mac。
    """
    problems: list[str] = []
    for d in BUSINESS_DIRS:
        if not d.exists():
            continue
        for path in sorted(d.rglob("*.swift")):
            src = read(path)
            rel = path.relative_to(REPO_ROOT)
            literals = _local_literals(src)

            # 抓 execute(sql: <多行三引号>, arguments: [ ... ])
            #
            # ⚠️ 四个坑（第 29 轮三个、第 107 轮一个最严重的）：
            # 1. arguments 区间必须按**括号配平**截取 —— 实参里出现 `meta["tags"]` 时，
            #    第一个 `]` 是它的，非贪婪截断会把 16 个实参数成 15（假阳性）
            # 2. arguments 必须**紧跟** SQL（中间只允许空白），否则会把后面
            #    另一个 execute 的 arguments 错配给本 query（无 arguments 的
            #    只读 query 尤其容易触发）
            # 3. `[a, b] + boundaryArgs` 是数组拼接，实参个数无法静态求值 → 跳过
            # 4. **正则必须带 re.S**：SQL 几乎都在 `"""` 多行块里，没有 re.S 时
            #    `.` 不匹配换行 → `(.*?)` 永远跨不到收尾三引号 → **一处都匹配不上**。
            #    第 107 轮的实测：无 re.S 全仓匹配 0 处，有 re.S 57 处。
            #    也就是说这个函数在此之前是**死代码**，57 个调用点从未被校验过。
            for sm in re.finditer(
                r'sql:\s*"""(.*?)"""\s*,\s*arguments:\s*\[', src, re.S
            ):
                sql = sm.group(1)
                # ⚠️ 第 107 轮第五个坑：正则会把「**不带** arguments: 的 sql: 块」
                # 一路延伸到后面某个带 arguments: 的收尾三引号，从而与错误的实参配对
                # （症状：报出「41 个占位符 vs 7 个实参」这种荒谬数字）。
                # 判别法：真正的 SQL 段里不会再出现 `sql:` 字样。
                if re.search(r"\bsql\s*:", sql):
                    continue        # 跨块错配，跳过
                k = sm.end() - 1
                depth = 0
                close = k
                for j in range(k, len(src)):
                    if src[j] == "[":
                        depth += 1
                    elif src[j] == "]":
                        depth -= 1
                        if depth == 0:
                            close = j
                            break
                args = src[k + 1:close]
                # 数组拼接跳过：`[a, b] + boundaryArgs` 的实参个数无法静态求值。
                # ⚠️ 第 107 轮：原先写 `src[close + 1:close + 2] == "+"`，要求 `]`
                # **紧贴** `+`。而实际代码是 `] + boundaryArgs`（有空格）→ 检测失效，
                # 于是一批本来该跳过的拼接被当成「占位符与实参不符」报了出来。
                # 正确做法：跳过中间空白后再判断。
                tail = src[close + 1:close + 8].lstrip()
                if tail.startswith("+"):
                    continue
                # 插值片段用已知字面量替换后再数占位符
                resolved, skips = strip_swift_interpolations(sql, literals)
                if skips:
                    # 含未解析插值 → 占位符个数不可信，跳过
                    # （例如 `SET \(countColumn) = \(countColumn) + 1` 会插出两个 ?）
                    continue
                if "+" in args:
                    # 数组拼接（`[a, b] + boundaryArgs`）→ 实参个数无法静态求值
                    continue
                placeholders = resolved.count("?")
                # 顶层逗号个数 + 1；**尾随逗号不计**
                # （Swift 允许尾随逗号；最初把它算成一个元素，产生一批假阳性）
                depth = 0
                count = 1 if args.strip() else 0
                for ch in args:
                    if ch in "([{":
                        depth += 1
                    elif ch in ")]}":
                        depth -= 1
                    elif ch == "," and depth == 0:
                        count += 1
                if args.rstrip().endswith(","):
                    count -= 1
                if placeholders != count:
                    problems.append(
                        f"{rel}: SQL 有 {placeholders} 个占位符，实参 {count} 个"
                        f" → {' '.join(resolved.split())[:80]}"
                    )
    return problems


def schema_columns() -> dict[str, set[str]]:
    """从 schema 抽出每张表的列名集合。"""
    src = read(SCHEMA_SWIFT)
    out: dict[str, set[str]] = {}
    for m in re.finditer(r'"(CREATE TABLE IF NOT EXISTS `(\w+)` \((.*?)\))"', src):
        table, body = m.group(2), m.group(3)
        cols: set[str] = set()
        depth = 0
        cur = []
        for ch in body:
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
            if ch == "," and depth == 0:
                cols.add("".join(cur).strip(" `"))
                cur = []
            else:
                cur.append(ch)
        if cur:
            cols.add("".join(cur).strip(" `"))
        # 只保留看起来像列名的（排除 PRIMARY KEY(...) / FOREIGN KEY(...) / 约束子句）
        out[table] = {
            c.split()[0].strip(" `")
            for c in cols
            if re.match(r"^`?\w+`?\s", c) and not c.upper().startswith(
                ("PRIMARY", "FOREIGN", "UNIQUE", "CHECK", "CONSTRAINT"))
        }
    return out


def check_row_accesses(columns: dict[str, set[str]]) -> list[str]:
    """
    核对 `row["列名"]` 访问的列名是否真实存在于 schema。

    为什么值得查：GRDB 的 `Row["xxx"]` 是**运行时**字典查找，
    列名拼错编译器不报错，运行期才抛错 —— 正是本地能提前抓的一类。
    """
    all_columns: set[str] = set()
    for cols in columns.values():
        all_columns |= cols

    problems: list[str] = []
    for d in BUSINESS_DIRS:
        if not d.exists():
            continue
        for path in sorted(d.rglob("*.swift")):
            src = read(path)
            rel = path.relative_to(REPO_ROOT)
            for m in re.finditer(r'row\["(\w+)"\]', src):
                col = m.group(1)
                if col not in all_columns:
                    problems.append(f"{rel}: row[\"{col}\"] —— schema 中无此列")
    return problems


# GRDB 6.x `row["col"] as T?` 内建支持的原生类型（DatabaseValueConvertible 的实现集）。
GRDB_NATIVE_CAST_TYPES = {
    "Bool", "Int", "Int8", "Int16", "Int32", "Int64",
    "UInt", "UInt8", "UInt16", "UInt32", "UInt64",
    "Double", "Float", "String", "Data", "Date",
}


def check_row_cast_types() -> tuple[int, list[str]]:
    """
    核对 `row["col"] as T?` 的目标类型是否在 GRDB 内建支持集内。

    转成非内建类型（例如自定义 struct 或 UUID）会在运行期抛错，
    编译器不报错。这是第 38 轮从「不可验清单」里重新捞回来的一项。
    """
    used: dict[str, int] = {}
    problems: list[str] = []
    pattern = re.compile(r'row\["\w+"\]\s+as\s+([A-Za-z_][A-Za-z_0-9]*)\??')
    for d in BUSINESS_DIRS:
        if not d.exists():
            continue
        for path in sorted(d.rglob("*.swift")):
            src = read(path)
            rel = path.relative_to(REPO_ROOT)
            for m in pattern.finditer(src):
                ty = m.group(1)
                used[ty] = used.get(ty, 0) + 1
                if ty not in GRDB_NATIVE_CAST_TYPES:
                    problems.append(f"{rel}: row[...] as {ty} —— GRDB 无内建转换")
    return len(used), problems


def main() -> int:
    ddl = schema_statements()
    con = sqlite3.connect(":memory:")
    for stmt in ddl:
        try:
            con.execute(stmt)
        except sqlite3.Error as e:
            print(f"[FAIL] 建表语句本身失败：{stmt[:60]}… → {e}")
            return 1
    tables = {r[0] for r in con.execute(
        "SELECT name FROM sqlite_master WHERE type='table'")}

    items = extract_sql()
    ok = 0
    failed: list[str] = []
    execution_only: list[str] = []
    skipped: list[str] = []

    for path, raw, literals in items:
        rel = path.relative_to(REPO_ROOT)
        sql, skips = strip_swift_interpolations(raw, literals)
        sql = sql.strip()
        if not sql:
            continue

        if skips:
            skipped.append(f"{rel}: 插值 {skips} 无法静态求值")
            continue

        placeholders = sql.count("?")
        try:
            con.execute("SAVEPOINT probe")
            try:
                con.execute(sql, [None] * placeholders)
                ok += 1
            finally:
                con.execute("ROLLBACK TO probe")
                con.execute("RELEASE probe")
        except sqlite3.Error as e:
            msg = str(e)
            # 参数个数不匹配属于本工具的限制，不算业务错误
            if "You did not supply" in msg or "Incorrect number" in msg:
                ok += 1
                continue
            if is_prepare_time_error(msg):
                # 真 bug：表/列不存在或语法错 —— 这正是本工具要抓的
                failed.append(f"{rel}: {msg}\n      SQL: {' '.join(sql.split())[:110]}")
            else:
                # 执行期错误（绑 None 触发 NOT NULL / datatype mismatch）：
                # 能走到这一步说明语句**已成功解析**，表与列都存在 —— 是正面信号。
                execution_only.append(f"{rel}: {msg}")
                ok += 1

    print(f"抽出 {len(items)} 条 SQL，解析并执行通过 {ok} 条，"
          f"真正的 SQL 错误 {len(failed)} 条，跳过 {len(skipped)} 条")
    # Row["列名"] 访问核对（运行期才炸、编译器不查的一类）
    columns = schema_columns()
    print(f"解析出 {len(columns)} 张表的列定义")
    col_problems = check_row_accesses(columns)
    if col_problems:
        print(f"row[...] 引用了不存在的列 {len(col_problems)} 处：")
        for p in col_problems:
            print(f"  [FAIL] {p}")
        failed.extend(col_problems)

    cast_count, cast_problems = check_row_cast_types()
    if cast_problems:
        print(f"row[...] 转换目标不在 GRDB 内建集 {len(cast_problems)} 处：")
        for p in cast_problems:
            print(f"  [FAIL] {p}")
        failed.extend(cast_problems)

    print(f"库中表 {len(tables)} 张")

    # 占位符 / 实参个数核对（编译期不报错、运行期才崩的一类）
    arg_problems = check_argument_counts()
    if arg_problems:
        print(f"参数个数不符 {len(arg_problems)} 处：")
        for p in arg_problems:
            print(f"  [FAIL] {p}")
        failed.extend(arg_problems)

    if execution_only:
        print(f"（另有 {len(execution_only)} 条因绑 None 触发执行期约束，"
              f"其语句已成功解析 = 表/列均存在）")

    for s in skipped:
        print(f"  [skip] {s}")
    for f in failed:
        print(f"  [FAIL] {f}")

    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
