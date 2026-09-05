package com.elderlyhealth.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.Map;

public class JwtUtil {

    private static final String SECRET = "my-demo-bot-admin-secret-2026";
    private static final ObjectMapper mapper = new ObjectMapper();

    public static String createToken(String username, String role) {
        try {
            String header = Base64.getUrlEncoder().encodeToString("{\"alg\":\"HS256\"}".getBytes());
            String payload = Base64.getUrlEncoder().encodeToString(
                    ("{\"sub\":\"" + username + "\",\"role\":\"" + role + "\",\"exp\":" +
                            (System.currentTimeMillis() / 1000 + 86400) + "}").getBytes());
            String data = header + "." + payload;
            String sig = hmacSha256(data, SECRET);
            return data + "." + sig;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> verify(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) return null;
            String sig = hmacSha256(parts[0] + "." + parts[1], SECRET);
            if (!sig.equals(parts[2])) return null;
            return mapper.readValue(Base64.getUrlDecoder().decode(parts[1]), Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static String hmacSha256(String data, String key) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(key.getBytes(), "HmacSHA256"));
        return Base64.getUrlEncoder().encodeToString(mac.doFinal(data.getBytes()));
    }
}
