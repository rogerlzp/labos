package com.labos.kernel.expr;

import java.util.List;
import java.util.Map;

/** 求值器：遍历 AST 算出结果。 */
final class Evaluator {

    private Evaluator() {
    }

    static Object eval(Expression.Node n, Map<String, Object> vars, String src) {
        if (n instanceof Expression.Num v) {
            return v.v();
        }
        if (n instanceof Expression.Lit lit) {
            return lit.s();
        }
        if (n instanceof Expression.Var v) {
            Object val = Values.lookup(vars, v.name());
            if (val == null && !vars.containsKey(v.name())) {
                throw new ExprException(src, -1, "变量未定义：" + v.name());
            }
            return val;
        }
        if (n instanceof Expression.Unary u) {
            return evalUnary(u, vars, src);
        }
        if (n instanceof Expression.BinOp b) {
            return evalBinOp(b, vars, src);
        }
        if (n instanceof Expression.Ternary t) {
            return Values.toBool(eval(t.cond(), vars, src))
                    ? eval(t.then(), vars, src)
                    : eval(t.otherwise(), vars, src);
        }
        if (n instanceof Expression.Func f) {
            return evalFunc(f, vars, src);
        }
        throw new ExprException(src, -1, "未知节点类型");
    }

    private static Object evalUnary(Expression.Unary u, Map<String, Object> vars,
                                    String src) {
        Object v = eval(u.operand(), vars, src);
        return switch (u.op()) {
            case "!" -> !Values.toBool(v);
            case "-" -> -Values.toNum(v, src);
            default -> throw new ExprException(src, -1, "未知一元运算符 " + u.op());
        };
    }

    private static Object evalBinOp(Expression.BinOp b, Map<String, Object> vars,
                                    String src) {
        String op = b.op();

        // 短路：先算左边，必要时不算右边
        if ("&&".equals(op)) {
            return Values.toBool(eval(b.l(), vars, src))
                    && Values.toBool(eval(b.r(), vars, src));
        }
        if ("||".equals(op)) {
            return Values.toBool(eval(b.l(), vars, src))
                    || Values.toBool(eval(b.r(), vars, src));
        }

        Object l = eval(b.l(), vars, src);
        Object r = eval(b.r(), vars, src);

        return switch (op) {
            case "+" -> {
                // **两边都能当数字就做加法，否则拼接**。
                // 流程变量都是 String（i="3"），如果只看"是不是 String"就拼成 "31"，
                // 而绝大多数场景 i + 1 是要算术。
                if (Values.isNumeric(l) && Values.isNumeric(r)) {
                    yield Values.toNum(l, src) + Values.toNum(r, src);
                }
                yield Values.toText(l) + Values.toText(r);
            }
            case "-" -> {
                requireNum(l, r, op, src);
                yield Values.toNum(l, src) - Values.toNum(r, src);
            }
            case "*" -> {
                requireNum(l, r, op, src);
                yield Values.toNum(l, src) * Values.toNum(r, src);
            }
            case "/" -> {
                requireNum(l, r, op, src);
                double d = Values.toNum(r, src);
                if (d == 0) {
                    throw new ExprException(src, -1, "表达式求值失败：除数为 0");
                }
                yield Values.toNum(l, src) / d;
            }
            case "%" -> {
                requireNum(l, r, op, src);
                double d = Values.toNum(r, src);
                if (d == 0) {
                    throw new ExprException(src, -1, "表达式求值失败：取余的除数为 0");
                }
                yield Values.toNum(l, src) % d;
            }
            case ">" -> {
                requireNum(l, r, op, src);
                yield Values.toNum(l, src) > Values.toNum(r, src);
            }
            case ">=" -> {
                requireNum(l, r, op, src);
                yield Values.toNum(l, src) >= Values.toNum(r, src);
            }
            case "<" -> {
                requireNum(l, r, op, src);
                yield Values.toNum(l, src) < Values.toNum(r, src);
            }
            case "<=" -> {
                requireNum(l, r, op, src);
                yield Values.toNum(l, src) <= Values.toNum(r, src);
            }
            case "==" -> Values.equal(l, r);
            case "!=" -> !Values.equal(l, r);
            default -> throw new ExprException(src, -1, "未知运算符 " + op);
        };
    }

    /** 两边都必须能当数字。流程变量是 String，所以看"能不能解析"而不是"是不是 String"。 */
    private static void requireNum(Object l, Object r, String op, String src) {
        if (!Values.isNumeric(l) || !Values.isNumeric(r)) {
            throw new ExprException(src, -1,
                    "不能对字符串做 " + op + " 运算（左 " + Values.toText(l)
                            + "，右 " + Values.toText(r) + "）");
        }
    }

    private static Object evalFunc(Expression.Func f, Map<String, Object> vars,
                                   String src) {
        String name = f.name();
        List<Expression.Node> args = f.args();

        // has() 要特殊处理：它检查"变量存不存在"，不能先求值。
        // 支持两种写法：has(x) 和 has('x')
        if ("has".equals(name)) {
            if (args.size() != 1) {
                throw new ExprException(src, -1, "has() 只接受一个参数");
            }
            String varName;
            if (args.get(0) instanceof Expression.Var v) {
                varName = v.name();
            } else if (args.get(0) instanceof Expression.Lit l) {
                varName = l.s();
            } else {
                throw new ExprException(src, -1,
                        "has() 的参数必须是变量名或字符串字面量");
            }
            Object val = Values.lookup(vars, varName);
            if (vars.containsKey(varName) && val != null) {
                return true;
            }
            // 异步结果注入的形态是 results.<key>.__exists=true，
            // 而不是裸键 <key>。has('readPlate') 要能识别这两种：
            //   results.readPlate.__exists（结果注入）
            //   readPlate.__exists（其他来源）
            if (isTruthy(vars.get(varName + ".__exists"))) {
                return true;
            }
            return isTruthy(vars.get("results." + varName + ".__exists"));
        }

        double[] nums = args.stream()
                .mapToDouble(a -> Values.toNum(eval(a, vars, src), src))
                .toArray();

        return switch (name) {
            case "floor" -> {
                requireArity(name, nums, 1, src);
                yield Math.floor(nums[0]);
            }
            case "ceil" -> {
                requireArity(name, nums, 1, src);
                yield Math.ceil(nums[0]);
            }
            case "round" -> {
                requireArity(name, nums, 1, src);
                yield (double) Math.round(nums[0]);
            }
            case "abs" -> {
                requireArity(name, nums, 1, src);
                yield Math.abs(nums[0]);
            }
            case "max" -> {
                if (nums.length < 2) {
                    throw new ExprException(src, -1, "max() 至少需要两个参数");
                }
                double m = nums[0];
                for (double d : nums) {
                    m = Math.max(m, d);
                }
                yield m;
            }
            case "min" -> {
                if (nums.length < 2) {
                    throw new ExprException(src, -1, "min() 至少需要两个参数");
                }
                double m = nums[0];
                for (double d : nums) {
                    m = Math.min(m, d);
                }
                yield m;
            }
            default -> throw new ExprException(src, -1,
                    "未知函数 " + name + "()（支持：floor/ceil/round/abs/max/min/has）");
        };
    }

    private static boolean isTruthy(Object v) {
        return Boolean.TRUE.equals(v) || "true".equals(Values.toText(v));
    }

    private static void requireArity(String name, double[] nums, int n, String src) {
        if (nums.length != n) {
            throw new ExprException(src, -1,
                    name + "() 需要 " + n + " 个参数，实际 " + nums.length);
        }
    }
}
