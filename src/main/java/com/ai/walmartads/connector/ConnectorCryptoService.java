package com.ai.walmartads.connector;

import com.ai.walmartads.config.DataConnectorProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;

/** AES-GCM 会话加密；主密钥由项目首次启动自动生成，并与数据库分离保存。 */
@Service
public class ConnectorCryptoService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int IV_BYTES = 12;
    private final SecretKey key;

    public ConnectorCryptoService(DataConnectorProperties properties) {
        this.key = loadOrCreate(properties.resolvedDataDir().resolve("secrets").resolve("connector.key"));
    }

    public String encrypt(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(packed);
        } catch (Exception exception) {
            throw new IllegalStateException("无法加密数据连接会话", exception);
        }
    }

    public String decrypt(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            byte[] packed = Base64.getDecoder().decode(value);
            if (packed.length <= IV_BYTES) throw new IllegalArgumentException("密文长度无效");
            byte[] iv = java.util.Arrays.copyOfRange(packed, 0, IV_BYTES);
            byte[] encrypted = java.util.Arrays.copyOfRange(packed, IV_BYTES, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("数据连接会话无法解密，请重新登录", exception);
        }
    }

    private static SecretKey loadOrCreate(Path path) {
        try {
            Files.createDirectories(path.getParent());
            byte[] bytes;
            if (Files.exists(path)) {
                bytes = Base64.getDecoder().decode(Files.readString(path, StandardCharsets.US_ASCII).trim());
            } else {
                KeyGenerator generator = KeyGenerator.getInstance("AES");
                generator.init(256);
                bytes = generator.generateKey().getEncoded();
                Files.writeString(path, Base64.getEncoder().encodeToString(bytes), StandardCharsets.US_ASCII);
                restrictToOwner(path);
            }
            if (bytes.length != 32) throw new IllegalStateException("数据连接主密钥长度无效");
            return new SecretKeySpec(bytes, "AES");
        } catch (Exception exception) {
            throw new IllegalStateException("无法初始化数据连接主密钥：" + path, exception);
        }
    }

    /** Windows 上把密钥 ACL 收紧到当前运行账号；不支持 ACL 的文件系统安全跳过。 */
    private static void restrictToOwner(Path path) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (view == null) return;
        UserPrincipal owner = Files.getOwner(path);
        AclEntry entry = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .build();
        view.setAcl(List.of(entry));
    }
}
