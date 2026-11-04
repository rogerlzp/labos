package com.labos.kernel.expr;

/**
 * 表达式错误。**消息里必须带表达式原文和位置**——
 * 不带的话，排查时不知道是哪个表达式出错了。
 */
public final class ExprException extends RuntimeException {

    private final String source;
    private final int position;

    public ExprException(String source, int position, String detail) {
        super(format(source, position, detail));
        this.source = source;
        this.position = position;
    }

    private static String format(String source, int position, String detail) {
        StringBuilder sb = new StringBuilder();
        sb.append(detail).append("（表达式 ").append(source);
        if (position >= 0) {
            sb.append("，位置 ").append(position);
        }
        sb.append("）");
        return sb.toString();
    }

    public String source() {
        return source;
    }

    public int position() {
        return position;
    }
}
