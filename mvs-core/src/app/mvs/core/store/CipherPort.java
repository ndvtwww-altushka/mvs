package app.mvs.core.store;

import java.security.GeneralSecurityException;

public interface CipherPort {
    byte[] seal(byte[] plain) throws GeneralSecurityException;
    byte[] open(byte[] sealed) throws GeneralSecurityException;
}
