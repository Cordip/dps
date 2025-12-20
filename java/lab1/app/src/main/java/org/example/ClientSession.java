package org.example;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public class ClientSession {
    private final SocketChannel channel;
    
    private final ByteArrayOutputStream nameBuffer = new ByteArrayOutputStream();
    
    private ByteBuffer writeBuffer = null;
    
    private boolean processing = false;

    public ClientSession(SocketChannel channel) {
        this.channel = channel;
    }

    public SocketChannel getChannel() {
        return channel;
    }

    /**
     * Добавляет байт в буфер имени.
     * @return true, если встречен 0-байт (конец имени)
     */
    public boolean appendNameByte(byte b) {
        if (b == 0) {
            return true;
        }
        nameBuffer.write(b);
        return false;
    }

    public String getParsedName() {
        return nameBuffer.toString();
    }

    public void setWriteBuffer(ByteBuffer buffer) {
        this.writeBuffer = buffer;
    }

    public ByteBuffer getWriteBuffer() {
        return writeBuffer;
    }

    public boolean isProcessing() {
        return processing;
    }

    public void setProcessing(boolean processing) {
        this.processing = processing;
    }
}
