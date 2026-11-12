package org.telegram.tgnet;

/** COMPILE-ONLY: сверено с tgnet/RequestDelegate.java. */
public interface RequestDelegate { void run(TLObject response, TLRPC.TL_error error); }
