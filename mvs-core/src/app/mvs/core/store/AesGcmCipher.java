package app.mvs.core.store;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** AES-256-GCM на запись; ключ приходит из StoreKeyManager (Android Keystore/StrongBox). */
public final class AesGcmCipher implements CipherPort {
    private final SecretKeySpec key; private final SecureRandom rnd = new SecureRandom();

    public AesGcmCipher(byte[] key32) {
        if (key32.length != 32) throw new IllegalArgumentException("need 32-byte key");
        key = new SecretKeySpec(key32.clone(), "AES");
    }

    public byte[] seal(byte[] plain) throws GeneralSecurityException {
        byte[] nonce = new byte[12]; rnd.nextBytes(nonce);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        byte[] ct = c.doFinal(plain);
        byte[] out = new byte[12 + ct.length];
        System.arraycopy(nonce, 0, out, 0, 12);
        System.arraycopy(ct, 0, out, 12, ct.length);
        return out;
    }

    public byte[] open(byte[] sealed) throws GeneralSecurityException {
        if (sealed.length < 28) throw new GeneralSecurityException("short record");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, sealed, 0, 12));
        return c.doFinal(sealed, 12, sealed.length - 12);
    }
}
