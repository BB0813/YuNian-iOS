#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
generate_schema.py — 从 Room 导出的 schema JSON 生成 iOS 侧 GRDB 建库代码（M0 / M3）

用法：
    python ios/Tools/generate_schema.py                 # 默认用 v45
    python ios/Tools/generate_schema.py --version 45
    python ios/Tools/generate_schema.py --check         # 只校验，不写文件（CI 用）

设计要点（均有依据，勿随意更改）：

1. **逐字照抄 Room 的 DDL，不翻译成 GRDB 的 TableDefinition DSL。**
   依据 docs/database-schema-freeze.md：自定义索引名（idx_sticker_entries_hash 等）
   与 DESC 复合索引必须逐字一致，手写默认名会导致 schema 校验失败。
   Room schema JSON 里的 createSql 是字面 SQLite DDL，直接复用最可靠。

2. **不做 44 个迁移链，只建 v45 基线。**
   依据 docs/ios-port-feasibility.md §4.4 的待决策项 D6：
   iOS 首发以 v45 空库建库；仅当需要导入 Android 老版本备份时才另走逻辑导出。
   Room 的 v1–v16 连 schema JSON 都没有，无法做严格迁移验证。

3. **FTS 表单独处理并做运行时探测。**
   依据 §9 的 V4：分词在应用层完成（MessageSearchTokenizer 输出纯 ASCII token），
   因此 FTS4 与 FTS5 的匹配语义没有差别。Apple 系统 libsqlite3 与 GRDB 自带构建
   各自启用的 FTS 版本不同，不能假设，故运行时探测后择一建表。

4. **PRAGMA user_version 必须写成 45。**
   依据 agent-native/src/native_gateway.rs: MIN_SUPPORTED_SCHEMA=41 / MAX=45，
   Rust 侧按此判断库是否可用（越界只 warn 不拒绝，但应正确设置）。
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SCHEMA_DIR = REPO_ROOT / "core/database/schemas/com.yunian.ai.database.AppDatabase"
OUT_FILE = REPO_ROOT / "ios/YuNian/Data/Schema/YuNianSchema.swift"

FTS_TABLE = "message_search_index"
# Rust 侧契约（native_gateway.rs）
MIN_RUST_SCHEMA = 41
MAX_RUST_SCHEMA = 45
# Room 的冻结基线（core/database/build.gradle.kts 与 database-schema-freeze.md）
FROZEN_BASELINE = 41


def swift_literal(s: str) -> str:
    """把 SQL 安全地放进 Swift 双引号字符串字面量。"""
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def load_schema(version: int) -> dict:
    path = SCHEMA_DIR / f"{version}.json"
    if not path.exists():
        sys.exit(f"找不到 schema 文件：{path}")
    return json.loads(path.read_text(encoding="utf-8"))


def collect(db: dict) -> tuple[list[str], list[str], list[str], int]:
    """返回 (建表语句, 建索引语句, FTS 建表语句, 外键数)。"""
    tables: list[str] = []
    indices: list[str] = []
    fts: list[str] = []
    fk_count = 0

    for ent in db["entities"]:
        name = ent["tableName"]
        sql = ent["createSql"].replace("${TABLE_NAME}", name)
        if name == FTS_TABLE:
            fts.append(sql)
        else:
            tables.append(sql)

        for ix in ent.get("indices", []):
            indices.append(ix["createSql"].replace("${TABLE_NAME}", name))

        fk_count += len(ent.get("foreignKeys", []))

    return tables, indices, fts, fk_count


def render(version: int, db: dict, tables: list[str], indices: list[str],
           fts: list[str], fk_count: int) -> str:
    identity = db.get("identityHash", "")
    L: list[str] = []
    A = L.append

    A("// ⚠️ 本文件由 ios/Tools/generate_schema.py 自动生成，请勿手改。")
    A(f"// 来源：core/database/schemas/com.yunian.ai.database.AppDatabase/{version}.json")
    A(f"// Room schema version = {version}，identityHash = {identity}")
    A("//")
    A("// 重新生成：python ios/Tools/generate_schema.py")
    A("")
    A("import Foundation")
    A("")
    A("/// 予念 iOS 侧的数据库基线（与 Android 侧 Room v45 同构）。")
    A("///")
    A("/// 为什么不做迁移链：见 docs/ios-port-feasibility.md §4.4。")
    A("/// iOS 首发以 v45 空库建库；导入 Android 历史数据走逻辑导出（BackupData），")
    A("/// 不走 DB 文件拷贝 —— 消息正文与记忆是 AndroidKeyStore 加密的，iOS 无法解密。")
    A("enum YuNianSchema {")
    A("")
    A(f"    /// Room 的 schema 版本，写入 PRAGMA user_version。")
    A(f"    static let version = {version}")
    A("")
    A("    /// Room 的 identity_hash。用于校验「导入的 Android 库」是否为本版本。")
    A(f"    static let identityHash = {swift_literal(identity)}")
    A("")
    A("    /// schema 冻结基线（docs/database-schema-freeze.md）。")
    A(f"    static let frozenBaselineVersion = {FROZEN_BASELINE}")
    A("")
    A("    /// Rust 侧（agent-native/src/native_gateway.rs）接受的 schema 版本区间。")
    A(f"    static let rustSupportedVersionRange = {MIN_RUST_SCHEMA}...{MAX_RUST_SCHEMA}")
    A("")
    A(f"    static let tableCount = {len(tables)}")
    A(f"    static let indexCount = {len(indices)}")
    A(f"    static let foreignKeyCount = {fk_count}")
    A("")
    A(f"    static let ftsTableName = {swift_literal(FTS_TABLE)}")
    A("")
    A("    // ── 建表（Room v45 原样 DDL）────────────────────────────────")
    A("    static let createTables: [String] = [")
    for s in tables:
        A(f"        {swift_literal(s)},")
    A("    ]")
    A("")
    A("    // ── 建索引（含自定义 idx_* 名与 DESC 复合索引，必须逐字一致）──")
    A("    static let createIndices: [String] = [")
    for s in indices:
        A(f"        {swift_literal(s)},")
    A("    ]")
    A("")
    A("    // ── 中文全文检索表 ──────────────────────────────────────────")
    A("    //")
    A("    // Android 侧为 FTS4 + SQLite 默认 simple 分词器；中文分词由")
    A("    // MessageSearchTokenizer 在应用层完成（unigram u<hex>z + bigram b<hex>x<hex>z，")
    A("    // 输出纯 ASCII），原文从不交给 SQLite 分词器。")
    A("    //")
    A("    // 因此 FTS4 与 FTS5 的匹配语义无差别，iOS 侧运行时探测后择一。")
    A("    // 注意：rowid 必须显式等于 messageId；该表无外键，一致性靠应用层维护。")
    A("    static let createFTSFTS4 =")
    A(f"        {swift_literal(fts[0] if fts else '')}")
    A("")
    A("    static let createFTSFTS5 =")
    A(f"        {swift_literal('CREATE VIRTUAL TABLE IF NOT EXISTS `' + FTS_TABLE + '` USING fts5(`tokens`)')}")
    A("")
    A("    /// 探测本机 SQLite 是否启用 FTS4（依赖 SQLITE_ENABLE_FTS3）。")
    A("    /// 不可用时回退 FTS5 —— 见 docs/ios-port-feasibility.md §9 的 V4。")
    A("    static func createFTSTable(_ db: Database) throws -> String {")
    A("        do {")
    A("            try db.execute(sql: createFTSFTS4)")
    A("            return \"FTS4\"")
    A("        } catch {")
    A("            try db.execute(sql: createFTSFTS5)")
    A("            return \"FTS5\"")
    A("        }")
    A("    }")
    A("}")
    A("")
    return "\n".join(L)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", type=int, default=MAX_RUST_SCHEMA)
    ap.add_argument("--check", action="store_true",
                    help="只校验生成结果是确定的（不写文件）")
    args = ap.parse_args()

    db = load_schema(args.version)["database"]
    tables, indices, fts, fk_count = collect(db)

    # 自检：数量必须与 schema JSON 一致，否则生成器有 bug
    assert len(tables) + len(fts) == len(db["entities"]), "表数量与 entities 不符"
    expected_idx = sum(len(e.get("indices", [])) for e in db["entities"])
    assert len(indices) == expected_idx, f"索引数量不符：{len(indices)} != {expected_idx}"
    assert len(fts) == 1, f"预期恰好 1 张 FTS 表，实际 {len(fts)}"
    assert "${TABLE_NAME}" not in "".join(tables + indices), "仍有未替换的占位符"

    out = render(args.version, db, tables, indices, fts, fk_count)

    if args.check:
        if not OUT_FILE.exists():
            sys.exit(f"--check 失败：{OUT_FILE} 不存在")
        if OUT_FILE.read_text(encoding="utf-8") != out:
            sys.exit("--check 失败：生成结果与磁盘文件不一致，请重新运行生成器")
        print(f"--check 通过：{OUT_FILE.relative_to(REPO_ROOT)}")
        return 0

    OUT_FILE.parent.mkdir(parents=True, exist_ok=True)
    OUT_FILE.write_text(out, encoding="utf-8")
    print(f"已生成 {OUT_FILE.relative_to(REPO_ROOT)}")
    print(f"  表 {len(tables)} + FTS {len(fts)} = {len(tables) + len(fts)}")
    print(f"  索引 {len(indices)}")
    print(f"  外键 {fk_count}")
    print(f"  Room version {args.version}，identityHash {db.get('identityHash')}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
