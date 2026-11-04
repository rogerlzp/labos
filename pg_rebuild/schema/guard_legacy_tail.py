#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把合并产物里的「dump 尾部重复声明」包成可跳过的 DO 块。

背景（BUG-20261103-08）：
  30_business.sql 里同时存在**手写 DDL**（`CREATE TABLE … id bigint GENERATED …`）
  和一段 **pg_dump 风格尾部**（先 `CREATE TABLE … id bigint NOT NULL`，
  再 `ALTER TABLE … ALTER COLUMN id ADD GENERATED …`，再 `ALTER TABLE ONLY … ADD CONSTRAINT`）。
  两套定义同一批表 → 全新库执行时：
    · `ALTER … ADD GENERATED` 撞 "relation xxx_id_seq already exists"
    · `ALTER TABLE ONLY … ADD CONSTRAINT … PRIMARY KEY` 撞 "multiple primary keys"
  `deploy.sh` 用 `ON_ERROR_STOP=1`，会在第一条处直接中断，**全新部署装不完**。

做法：只把**重复声明型**语句（ADD GENERATED / ADD CONSTRAINT）包进 DO 块，
捕获 duplicate_object / duplicate_table / invalid_table_definition → 跳过并 RAISE NOTICE。
语义不变：对象不存在时照建；已存在时不再炸。

**前提（评审 20261103-02 指出，务必知晓）**：
  守卫**刻意不写 `WHEN OTHERS`** —— 只捕获三类与"重复声明"有关的 SQLSTATE：
    · `duplicate_object`      (42710) 约束/对象已存在
    · `duplicate_table`       (42P07) 序列/表已存在（ADD GENERATED 撞已有 identity 走这条）
    · `invalid_table_definition` (42P16) multiple primary keys
  这么写是为了：真出错（比如列名拼错、类型不兼容）仍然要**炸**，不能被静默吞掉。
  代价：如果将来出现**第四类**重复错误码，会漏出去把安装打断。
  出现新报错时的处理顺序：先看 SQLSTATE，确认确属"重复声明"再加进捕获列表，
  **不要图省事改成 WHEN OTHERS**。
"""
import io
import os
import re
import sys

# merge.sh 会先 cd 到 schema/ 再调用本脚本，别用仓库相对路径
_HERE = os.path.dirname(os.path.abspath(__file__))
PATHS = [os.path.join(_HERE, '90_all_in_one.sql')]

# 需要包起来的语句起始
STMT_STARTS = (
    re.compile(r'^ALTER TABLE \S+ ALTER COLUMN \S+ ADD GENERATED '),
    re.compile(r'^ALTER TABLE (ONLY )?\S+\s*$'),   # 下一行是 ADD CONSTRAINT
)


def wrap_statement(stmt: str, note: str) -> str:
    body = '\n'.join('  ' + ln if ln.strip() else ln for ln in stmt.strip().split('\n'))
    return (
        'DO $$\nBEGIN\n'
        f'{body}\n'
        'EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN\n'
        f"  RAISE NOTICE '跳过重复声明（{note}）：%', SQLERRM;\n"
        'END $$;'
    )


def process(path: str) -> int:
    lines = io.open(path, encoding='utf-8').read().split('\n')
    out = []
    i = 0
    wrapped = 0
    while i < len(lines):
        ln = lines[i]

        # 情况 A：ALTER TABLE … ALTER COLUMN … ADD GENERATED (…) ;  多行到 ");"
        if STMT_STARTS[0].match(ln):
            j = i
            while j < len(lines) and lines[j].rstrip() not in (');', ';'):
                j += 1
            stmt = '\n'.join(lines[i:j + 1])
            # 去掉末尾分号，避免 DO 块里出现语句分隔
            stmt = stmt.rstrip()
            out.append(wrap_statement(stmt, 'identity'))
            wrapped += 1
            i = j + 1
            continue

        # 情况 B：ALTER TABLE [ONLY] x  +  ADD CONSTRAINT … ;
        if STMT_STARTS[1].match(ln) and i + 1 < len(lines) \
                and lines[i + 1].lstrip().startswith('ADD CONSTRAINT'):
            j = i + 1
            while j < len(lines) and ';' not in lines[j]:
                j += 1
            stmt = '\n'.join(lines[i:j + 1])
            stmt = stmt.rstrip()
            out.append(wrap_statement(stmt, 'constraint'))
            wrapped += 1
            i = j + 1
            continue

        out.append(ln)
        i += 1

    io.open(path, 'w', encoding='utf-8').write('\n'.join(out))
    return wrapped


if __name__ == '__main__':
    total = 0
    for p in PATHS:
        n = process(p)
        total += n
        print(f'  {p}: 包裹 {n} 条重复声明')
    print(f'共 {total} 条')
    sys.exit(0)
