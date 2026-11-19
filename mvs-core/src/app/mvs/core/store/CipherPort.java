package app.mvs.core.store;

import java.security.GeneralSecurityException;

/** Authenticated encryption boundary. Production keys should come from Android Keystore. */
public interface CipherPort {
    byte[] seal(byte[] plain) throws GeneralSecurityException;
    byte[] open(byte[] sealed) throws GeneralSecurityException;
}
