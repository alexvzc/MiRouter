/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package mx.itesm.gda.tc4003_1.mirouter;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 *
 * @author alexv
 */
public class ConnectionManager {

    private static final Log LOGGER =
            LogFactory.getLog(ConnectionManager.class);

    private static final Charset DEFAULT_CHARSET = Charset.forName("UTF-8");

    private static final Pattern DATA_MATCHER =
            Pattern.compile("(.*)(\r?\n|\n)");

    private Selector selector;

    private SocketAddress socketAddress;

    private SocketChannel socketChannel;

    private long lastReconnectAttempt;

    private ByteBuffer inputBuffer;

    private CharBuffer decodedBuffer;

    private ByteBuffer outputBuffer;

    private Lock outputBufferLock;

    private Condition outputBufferSpaceAvailable;

    private CharsetEncoder charsetEncoder;

    private CharsetDecoder charsetDecoder;

    private LinkManager linkManager;

    private ConnectionManager(LinkManager link_mgr) {
        inputBuffer = ByteBuffer.allocateDirect(4 * 1024);
        inputBuffer.clear();
        decodedBuffer = CharBuffer.allocate(64 * 1024);
        decodedBuffer.clear();
        outputBuffer = ByteBuffer.allocateDirect(64 * 1024);
        outputBuffer.clear();

        charsetEncoder = DEFAULT_CHARSET.newEncoder();
        charsetDecoder = DEFAULT_CHARSET.newDecoder();

        outputBufferLock = new ReentrantLock();
        outputBufferSpaceAvailable = outputBufferLock.newCondition();

        lastReconnectAttempt = -1;

        linkManager = link_mgr;
    }

    public ConnectionManager(SocketAddress my_address, LinkManager link_mgr) {
        this(link_mgr);

        socketAddress = my_address;
        selector = null;
        socketChannel = null;
    }

    public ConnectionManager(SocketChannel my_channel, Selector my_selector,
            LinkManager link_mgr) throws IOException {
        this(link_mgr);

        selector = my_selector;
        socketChannel = my_channel;
        socketChannel.configureBlocking(false);
        socketAddress = socketChannel.socket().getRemoteSocketAddress();
        LOGGER.info("Received connection from " + socketAddress);
        socketChannel.register(selector, SelectionKey.OP_READ, this);
    }

    public void reconnect() {
        long currentAttempt = System.currentTimeMillis();
        if(!isConnected()
                && (lastReconnectAttempt < 0
                || (currentAttempt - lastReconnectAttempt) > 5000)) {
            try {
                lastReconnectAttempt = currentAttempt;
                socketChannel = SocketChannel.open();
                socketChannel.configureBlocking(false);
                socketChannel.register(selector, SelectionKey.OP_CONNECT, this);
                LOGGER.info("Attempting connection to " + socketAddress);
                if(socketChannel.connect(socketAddress)) {
                    LOGGER.info("Connected to " + socketAddress);
                    socketChannel.keyFor(selector).interestOps(
                            SelectionKey.OP_READ);
                }
                lastReconnectAttempt = -1;
            } catch(IOException ioe) {
                LOGGER.error("Unable to establish connection to "
                        + socketAddress, ioe);
                disconnect();
            }
        }
    }

    public void disconnect() {
        if(socketChannel != null) {
            if(selector != null) {
                SelectionKey key = socketChannel.keyFor(selector);
                if(key != null) {
                    key.cancel();
                }
            }
            try {
                socketChannel.close();
            } catch(IOException ioe) {
                LOGGER.error("Error closing connection", ioe);
            }
            LOGGER.info("Disconnected from " + socketAddress);
        }
        lastReconnectAttempt = System.currentTimeMillis();
    }

    public boolean isConnected() {
        return socketChannel != null
                && socketChannel.isOpen()
                && (socketChannel.isConnected()
                || socketChannel.isConnectionPending());
    }

    public void selected(SelectionKey sel_key) {
        try {
            if(sel_key.isValid() && sel_key.isConnectable()) {
                socketChannel.finishConnect();
                LOGGER.info("Connected to " + socketAddress);
                sel_key.interestOps(SelectionKey.OP_READ);
            }

            if(sel_key.isValid() && sel_key.isReadable()) {
                performRead();
            }

            if(sel_key.isValid() && sel_key.isWritable()) {
                performWrite(sel_key);
            }

        } catch(IOException ioe) {
            LOGGER.error("Connection error with " + socketAddress, ioe);
            disconnect();
        }
    }

    private void performRead() throws IOException {
        int read_bytes = socketChannel.read(inputBuffer);
        if(read_bytes > 0) {
            inputBuffer.flip();
            charsetDecoder.decode(inputBuffer, decodedBuffer, false);
            inputBuffer.compact();
            decodedBuffer.flip();
            Matcher m = DATA_MATCHER.matcher(decodedBuffer);
            int that_end = 0;
            while(m.find()) {
                linkManager.processData(m.group(1), this);
                that_end = m.end();
            }
            decodedBuffer.position(decodedBuffer.position() + that_end);
            decodedBuffer.compact();
        } else if(read_bytes < 0) {
            disconnect();
        }
    }

    private void performWrite(SelectionKey sel_key) throws IOException {
        outputBufferLock.lock();
        try {
            outputBuffer.flip();
            if(outputBuffer.hasRemaining()) {
                socketChannel.write(outputBuffer);
            } else {
                sel_key.interestOps(sel_key.interestOps()
                        & ~SelectionKey.OP_WRITE);
            }
            outputBuffer.compact();
            outputBufferSpaceAvailable.signal();
        } finally {
            outputBufferLock.unlock();
        }
    }

    public void send(CharSequence message) throws IOException {
        CharBuffer msg = CharBuffer.allocate(message.length() + 1);
        msg.put(message.toString());
        msg.put("\n");
        msg.flip();

        outputBufferLock.lock();
        try {
            charsetEncoder.encode(msg, outputBuffer, true);
            SelectionKey key = socketChannel.keyFor(selector);
            key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);

            while(msg.remaining() > 0) {
                try {
                    outputBufferSpaceAvailable.await();
                } catch(InterruptedException ie) {
                    LOGGER.debug("Interrupted", ie);
                    Thread.interrupted();
                }

                charsetEncoder.encode(msg, outputBuffer, true);
            }

        } finally {
            outputBufferLock.unlock();
        }
    }

    /**
     * @param selector the selector to set
     */
    public void setSelector(Selector selector) {
        this.selector = selector;
    }

    /**
     * @return the socketAddress
     */
    public SocketAddress getSocketAddress() {
        return socketAddress;
    }

}
