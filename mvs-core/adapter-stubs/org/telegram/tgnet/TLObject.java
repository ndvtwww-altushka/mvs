package org.telegram.tgnet;
public class TLObject {
    public void serializeToStream(OutputSerializedData stream) { }
    public int getObjectSize() { return 0; }
    public void freeResources() { }
    public TLObject deserializeResponse(InputSerializedData stream, int constructor, boolean exception) { return null; }
}
