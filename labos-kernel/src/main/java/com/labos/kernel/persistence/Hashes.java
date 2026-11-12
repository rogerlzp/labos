package com.labos.kernel.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 内容哈希工具（sha256 十六进制小写，64 字符）。
 *
 * <p>用于物料身份表的 {@code declaration_hash} / {@code payload_hash} 列
 * （DDL CHECK 要求 {@code length(...) = 64}）。
 */
public final class Hashes {

    private Hashes() {
    }

    /** 对 UTF-8 字符串求 sha256 十六进制（小写）。 */
    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16));
                sb.append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
