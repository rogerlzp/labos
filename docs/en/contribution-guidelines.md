# Contribution Guidelines

> **English edition.** The Chinese original is [`docs/提交规范.md`](../提交规范.md); this is
> a line-for-line translation. Numbers, commit hashes and file paths are identical to
> the Chinese source — if they ever disagree, the Chinese original wins.

> This file specifies how a commit message is written, when to commit, and what must
> not be committed.
> Purpose: three months later, reading `git log` should tell you **why it changed**,
> not only **what changed**.

---

## 1. When to commit

| Should commit | Should not commit |
|---|---|
| One complete, usable feature (even if only part of it) | An intermediate state that does not compile |
| A bug fixed and verified | A half-finished change |
| One documentation update (as a coherent set) | Temporary debugging code (`System.err.println` and the like) |
| A completed refactor | Formatting unrelated to the task at hand |

**One commit, one thing.** If the commit message needs the word "and" to join two
unrelated changes, it should be split into two.

---

## 2. Commit message format

```
<type>(<scope>): <short description>

<body: why this change>

<impact / side effects / known issues>
```

### 2.1 Type (required)

| Type | Used for | Example |
|---|---|---|
| `feat` | New feature | `feat(experiment): 实验执行器支持循环展开` |
| `fix` | Bug fix | `fix(plate): putPlate 事务回滚导致层位未写入` |
| `docs` | Documentation | `docs: 补评审规范与提交规范` |
| `refactor` | Refactoring (no behaviour change) | `refactor(driver): 两套 ID 改用值类型` |
| `perf` | Performance | `perf(scheduler): 候选池查询加索引` |
| `test` | Tests | `test(plate): 补位置抢占的并发用例` |
| `chore` | Build / dependencies / odds and ends | `chore: 升级 postgresql 驱动` |

### 2.2 Scope (recommended)

The module name, matching the package structure of
[`docs/en/architecture.md`](architecture.md):

`api` / `workflow` / `engine` / `driver` / `recovery` / `alert` / `plate` / `gateway` / `webapp` / `docs`

### 2.3 Short description (required)

- **No more than 50 characters**
- **Say what was done**, not "changed a few things"
- Written in Chinese (the team's language)
- No trailing full stop

**Counter-examples**:

```
fix: 修复了一些问题            ← Which problem?
feat: 更新代码                 ← What was updated?
fix: bug fix                   ← No information at all
```

**Good examples**:

```
fix(plate): putPlate 事务回滚导致层位未写入
feat(experiment): 实验执行器支持循环展开
docs: 补评审规范与提交规范
```

---

## 3. The body (required for important changes)

**The body answers "why", not "what"** ("what" is in the diff).

### 3.1 Body template for a bug fix

```
<symptom>: one sentence describing what the user / operator sees
<root cause>: the cause at the technical level
<fix>: how it was changed, and why this way rather than another
<verification>: how we know it is now correct
```

**Example** (a real case from this project):

```
fix(plate): putPlate 事务回滚导致层位未写入

现象：日志显示 [PLATE] Deposit1 -> pos=504495，但 position_state 里没有这行。

根因：同一事务里 markPlatePosition 写 plate 表时列名不匹配
（代码 write plate_id/current_pos/current_run，表里只有 barcode/location_id），
SQL 失败让整个事务作废，commit() 抛异常后 rollback，position_state 的更新一起没了。
异常被 catch (SQLException ignore) 吃掉，所以日志里看不到 SQL 原文。

修法：把 plate 表写入移出主事务（commit 后用独立连接），
失败只记 WARN 不影响位置锁。列名改成实际结构。

验证：执行 239 后层位写入 I4495/PL-xxx，has_plate 与层位一致。
```

### 3.2 Body template for a new feature

```
<what>: one sentence
<key design>: why it is designed this way, and what alternatives were considered
<known limits>: what was not done, and when it will be
```

---

## 4. What must go into the commit

### 4.1 Known issues must be written down

If this commit **introduces or leaves behind a problem**, it must be spelled out in
the commit message.

**Counter-example**:

```
feat(experiment): 实验执行器完成
```

**Actual state**: loop expansion works, but variable substitution only supports
top-level variables and the fork is not implemented yet.

**Good example**:

```
feat(experiment): 实验执行器支持变量替换与循环展开

已知限制：
- 只支持顶层 ${var}，不支持表达式 ${a}+${b}
- 分叉网关未实现，多条出边只走第一条
- 循环上限 20 次（防展开爆炸）
```

**Why**: three months later someone says "the documentation says expression
evaluation is supported" — `git log` answers immediately: "it was written down as
not done at the time".

### 4.2 Breaking changes must be written down

```
BREAKING CHANGE: /api/workflows 的 limit 上限从无限制改为 500
```

### 4.3 The verification command and its result

Important changes (especially bug fixes) must state **how it was verified**:

```
验证：
- 单测 51 个全绿
- 工艺 the full set（SimulatedBus 走图，非真设备）
- 执行 239，层位写入 I4495，has_plate 一致
```

---

## 5. What must not be committed

| Content | Handling |
|---|---|
| `System.err.println` debugging logs (temporary) | Delete them or turn them into proper logs |
| Build artifacts (`out/`, `*.class`) | `.gitignore` |
| Large files (logs, images > 1MB) | Put them in separate storage |
| Local configuration (hard-coded IPs, passwords) | Environment variables or a configuration template |
| `TODO` placeholder code | Either implement it, or explain it in the commit |

**This project's `.gitignore` should contain**:

```
out/
*.class
node_modules/
dist/
*.log
```

---

## 6. Project-specific conventions

### 6.1 Documentation and code go in the same commit

If the behaviour changed, the documentation changes with it, **in the same commit**.

```
feat(plate): 位置锁支持搬运双锁
docs: 板位级占用设计补第 5 节（双锁与死锁避免）
```

**Do not** commit the code first and leave the documentation for "later" — "later"
almost never comes.

### 6.2 Bug fixes must record how the trap was sprung

This project's convention for recording bugs (see `docs/开发日志.md`):

```
### Bug（标题）

**现象**：...
**根因**：...
**修法**：...
**教训**：...
```

**Why record how the trap was sprung**: the same trap gets stepped in again and again
(this project hit "I thought I changed it but I didn't" five times); only a record
lets you recognise the pattern.

### 6.3 Commits after a review must cite the review item

```
fix(plate): 修 putPlate 事务回滚

评审意见：20261101-01 / C1
处置：见 docs/评审记录/20261101-01-grok.md
```

---

## 7. Checklist

Self-check before committing:

- [ ] One commit, one thing
- [ ] The type and the scope are filled in
- [ ] The short description says clearly "what was done"
- [ ] The body says clearly "why" (for important changes)
- [ ] Known issues are written down
- [ ] The verification method is written down
- [ ] The documentation is updated in step
- [ ] There is no debugging code and no build artifacts
- [ ] `git status` contains no unexpected files

---

## 8. Counter-example collection (real lessons from this project)

| Counter-example | The problem |
|---|---|
| `feat: 板子流动` | Nobody knows what changed (an animation? device allocation?) |
| `fix: 修 bug` | Which bug |
| `docs: 更新文档` | Which document, and why |
| Describing "putPlate does not persist" as "fixed" without stating how it was verified | There is no way to tell whether it is really fixed |
| Committing only the code, not the documentation | The code and the documentation drift apart |

**The test**: can you (or a new colleague) read this message three months later and
know **why it was changed this way**?
