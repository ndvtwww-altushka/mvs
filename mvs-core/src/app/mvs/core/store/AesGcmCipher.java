package app.mvs.core.store;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** AES-256-GCM record encryption. Supply a key protected by Android Keystore in production. */
public final class AesGcmCipher implements CipherPort {
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;

    public AesGcmCipher(byte[] key32) {
        if (key32 == null || key32.length != KEY_BYTES)
            throw new IllegalArgumentException("need 32-byte key");
        this.key = new SecretKeySpec(key32.clone(), "AES");
    }

    @Override public byte[] seal(byte[] plain) throws GeneralSecurityException {
        if (plain == null) throw new IllegalArgumentException("plain == null");
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
        byte[] ciphertext = cipher.doFinal(plain);
        byte[] output = new byte[NONCE_BYTES + ciphertext.length];
        System.arraycopy(nonce, 0, output, 0, NONCE_BYTES);
        System.arraycopy(ciphertext, 0, output, NONCE_BYTES, ciphertext.length);
        return output;
    }

    @Override public byte[] open(byte[] sealed) throws GeneralSecurityException {
        if (sealed == null || sealed.length < NONCE_BYTES + TAG_BITS / 8)
            throw new GeneralSecurityException("short encrypted record");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key,
                new GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES));
        return cipher.doFinal(sealed, NONCE_BYTES, sealed.length - NONCE_BYTES);
    }
}
