package com.Myself.demo.util;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

public class EncryptUtil {
    private static final String ALG = "AES";
    private static final String KEY = "ykd-travel-key!9";

    public static String encrypt(String plain) {
        try {
            Cipher c = Cipher.getInstance(ALG);
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY.getBytes(), ALG));
            return Base64.getUrlEncoder().encodeToString(c.doFinal(plain.getBytes()));
        } catch (Exception e) {
            throw new RuntimeException("encrypt error", e);
        }
    }

    public static String decrypt(String cipher) {
        try {
            Cipher c = Cipher.getInstance(ALG);
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY.getBytes(), ALG));
            return new String(c.doFinal(Base64.getUrlDecoder().decode(cipher)));
        } catch (Exception e) {
            throw new RuntimeException("decrypt error", e);
        }
    }
}
