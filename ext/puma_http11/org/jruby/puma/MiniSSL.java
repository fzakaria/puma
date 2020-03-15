package org.jruby.puma;

import org.jruby.Ruby;
import org.jruby.RubyClass;
import org.jruby.RubyModule;
import org.jruby.RubyObject;
import org.jruby.RubyString;
import org.jruby.anno.JRubyMethod;
import org.jruby.javasupport.JavaEmbedUtils;
import org.jruby.runtime.Block;
import org.jruby.runtime.ObjectAllocator;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.util.ByteList;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.PrivateKey;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import static javax.net.ssl.SSLEngineResult.Status;
import static javax.net.ssl.SSLEngineResult.HandshakeStatus;

import java.util.Arrays;

import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.logging.Level;

public class MiniSSL extends RubyObject {

  /**
   * A system property to dictate whether Puma should attempt to use Netty for creating an SSL Engine
   * You can set it via `-Dpuma.ssl.use-netty=true` or in JRuby `ENV_JAVA["puma.ssl.use-netty"]`
   */
  private static final String NETTY_USE_KEY = "puma.ssl.use-netty";

  private final static Logger LOGGER = Logger.getLogger(MiniSSL.class.getName());

  private static ObjectAllocator ALLOCATOR = new ObjectAllocator() {
    public IRubyObject allocate(Ruby runtime, RubyClass klass) {
      return new MiniSSL(runtime, klass);
    }
  };

  public static void createMiniSSL(Ruby runtime) {
    RubyModule mPuma = runtime.defineModule("Puma");
    RubyModule ssl = mPuma.defineModuleUnder("MiniSSL");

    /**
     * Define constants to match parity with MRI OpenSSL extention for Puma.
     */

    // this constant is only used for testing; lets set it to true.
    // java by default does not have SSL3/TLS1 enabled; and developers have to go through hoops
    // to turn it on.
    ssl.defineConstant("OPENSSL_NO_SSL3", runtime.newBoolean(true));
    ssl.defineConstant("OPENSSL_NO_TLS1", runtime.newBoolean(true));

    if (!Boolean.getBoolean(NETTY_USE_KEY)) {
      ssl.defineConstant("OPENSSL_LIBRARY_VERSION", runtime.newString("Unknown"));
      ssl.defineConstant("OPENSSL_VERSION", runtime.newString("Unknown"));
    } else {
      try {
        ssl.defineConstant(
                "OPENSSL_LIBRARY_VERSION",
                runtime.newString(io.netty.handler.ssl.OpenSsl.versionString())
        );
        ssl.defineConstant(
                "OPENSSL_VERSION",
                runtime.newString(io.netty.handler.ssl.OpenSsl.versionString())
        );
      } catch (Throwable t) {
        LOGGER.log(Level.INFO, "Failed to use Netty OpenSSL " + t.getMessage(), t);
        ssl.defineConstant("OPENSSL_LIBRARY_VERSION", runtime.newString("Unknown"));
        ssl.defineConstant("OPENSSL_VERSION", runtime.newString("Unknown"));
      }
    }

    mPuma.defineClassUnder("SSLError",
                           runtime.getClass("IOError"),
                           runtime.getClass("IOError").getAllocator());

    RubyClass eng = ssl.defineClassUnder("Engine",runtime.getObject(),ALLOCATOR);
    eng.defineAnnotatedMethods(MiniSSL.class);
  }

  /**
   * Fairly transparent wrapper around {@link java.nio.ByteBuffer} which adds the enhancements we need
   */
  private static class MiniSSLBuffer {
    ByteBuffer buffer;

    private MiniSSLBuffer(int capacity) { buffer = ByteBuffer.allocate(capacity); }
    private MiniSSLBuffer(byte[] initialContents) { buffer = ByteBuffer.wrap(initialContents); }

    public void clear() { buffer.clear(); }
    public void compact() { buffer.compact(); }
    public void flip() { ((Buffer) buffer).flip(); }
    public boolean hasRemaining() { return buffer.hasRemaining(); }
    public int position() { return buffer.position(); }

    public ByteBuffer getRawBuffer() {
      return buffer;
    }

    /**
     * Writes bytes to the buffer after ensuring there's room
     */
    public void put(byte[] bytes) {
      if (buffer.remaining() < bytes.length) {
        resize(buffer.limit() + bytes.length);
      }
      buffer.put(bytes);
    }

    /**
     * Ensures that newCapacity bytes can be written to this buffer, only re-allocating if necessary
     */
    public void resize(int newCapacity) {
      if (newCapacity > buffer.capacity()) {
        ByteBuffer dstTmp = ByteBuffer.allocate(newCapacity);
        flip();
        dstTmp.put(buffer);
        buffer = dstTmp;
      } else {
        buffer.limit(newCapacity);
      }
    }

    /**
     * Drains the buffer to a ByteList, or returns null for an empty buffer
     */
    public ByteList asByteList() {
      flip();
      if (!buffer.hasRemaining()) {
        buffer.clear();
        return null;
      }

      byte[] bss = new byte[buffer.limit()];

      buffer.get(bss);
      buffer.clear();
      return new ByteList(bss);
    }

    @Override
    public String toString() { return buffer.toString(); }
  }

  private SSLEngine engine;
  private MiniSSLBuffer inboundNetData;
  private MiniSSLBuffer outboundAppData;
  private MiniSSLBuffer outboundNetData;

  public MiniSSL(Ruby runtime, RubyClass klass) {
    super(runtime, klass);
  }

  @JRubyMethod(meta = true)
  public static IRubyObject server(ThreadContext context, IRubyObject recv, IRubyObject miniSSLContext) {
    RubyClass klass = (RubyClass) recv;

    return klass.newInstance(context,
        new IRubyObject[] { miniSSLContext },
        Block.NULL_BLOCK);
  }

  @JRubyMethod
  public IRubyObject initialize(ThreadContext threadContext, IRubyObject miniSSLContext)
      throws KeyStoreException, IOException, CertificateException, NoSuchAlgorithmException, UnrecoverableKeyException, KeyManagementException {
    KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
    KeyStore ts = KeyStore.getInstance(KeyStore.getDefaultType());

    char[] password = miniSSLContext.callMethod(threadContext, "keystore_pass").convertToString().asJavaString().toCharArray();
    String keystoreFile = miniSSLContext.callMethod(threadContext, "keystore").convertToString().asJavaString();
    ks.load(new FileInputStream(keystoreFile), password);
    ts.load(new FileInputStream(keystoreFile), password);

    KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
    kmf.init(ks, password);

    TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
    tmf.init(ts);

    String[] protocols;
    if(miniSSLContext.callMethod(threadContext, "no_tlsv1").isTrue()) {
        protocols = new String[] { "TLSv1.1", "TLSv1.2" };
    } else {
        protocols = new String[] { "TLSv1", "TLSv1.1", "TLSv1.2" };
    }

    if(miniSSLContext.callMethod(threadContext, "no_tlsv1_1").isTrue()) {
        protocols = new String[] { "TLSv1.2" };
    }

    long verifyMode = miniSSLContext.callMethod(threadContext, "verify_mode").convertToInteger().getLongValue();

    IRubyObject sslCipherListObject = miniSSLContext.callMethod(threadContext, "ssl_cipher_list");
    String[] sslCipherList = null;

    if (!sslCipherListObject.isNil()) {
      sslCipherList = sslCipherListObject.convertToString().asJavaString().split(",");
    }

    engine = tryCreateEngine(kmf, tmf, protocols, verifyMode, sslCipherList);

    SSLSession session = engine.getSession();
    inboundNetData = new MiniSSLBuffer(session.getPacketBufferSize());
    outboundAppData = new MiniSSLBuffer(session.getApplicationBufferSize());
    outboundAppData.flip();
    outboundNetData = new MiniSSLBuffer(session.getPacketBufferSize());

    return this;
  }

  /**
   * Try to create a netty tcnative OpenSSL engine which has much better performance
   * than the JDK native one.
   * Fall back to the default engine otherwise.
   */
  private static SSLEngine tryCreateEngine(KeyManagerFactory keyManagerFactory,
                                           TrustManagerFactory trustManagerFactory,
                                           String[] protocols,
                                           long verifyMode,
                                           String[] ciphers) throws NoSuchAlgorithmException, KeyManagementException {
    if (!Boolean.getBoolean(NETTY_USE_KEY)) {
      return createJDKEngine(keyManagerFactory, trustManagerFactory, protocols, verifyMode, ciphers);
    }

    try {
      // these mappings were deduced from here:
      // https://github.com/netty/netty/blob/2f32e0b8adb63decd9031e26fa5dd4154d93ce97/handler/src/main/java/io/netty/handler/ssl/JdkSslContext.java#L346
      io.netty.handler.ssl.ClientAuth clientAuth = io.netty.handler.ssl.ClientAuth.NONE;
      if ((verifyMode & 0x1) != 0) {
        clientAuth = io.netty.handler.ssl.ClientAuth.OPTIONAL;
      }
      if ((verifyMode & 0x2) != 0) {
        clientAuth = io.netty.handler.ssl.ClientAuth.REQUIRE;
      }

      if (ciphers == null) {
        ciphers = new String[]{};
      }

      SSLEngine engine = io.netty.handler.ssl.SslContextBuilder
              .forServer(keyManagerFactory)
              .trustManager(trustManagerFactory)
              .sslProvider(io.netty.handler.ssl.SslProvider.OPENSSL)
              .protocols(protocols)
              .clientAuth(clientAuth)
              .ciphers(Arrays.asList(ciphers))
              .build()
              .newEngine(io.netty.buffer.ByteBufAllocator.DEFAULT);
      LOGGER.info("Using Netty tcnative OpenSSL engine");

      return engine;
      /**
       * Even though Netty may be on the classpath (not throw ClassNotFoundException); there can be other
       * exceptions thrown by the Netty library such as if it failed to find a OpenSSL library.
       * Catching on Throwable ensured; we can always default.
       */
    } catch (Throwable t) {
      LOGGER.log(Level.INFO, "Failed to use Netty OpenSSL " + t.getMessage(), t);
      return createJDKEngine(keyManagerFactory, trustManagerFactory, protocols, verifyMode, ciphers);
    }
  }

  /**
   * Create a JDK SSL Engine using Java's implementation.
   * It's the slower then Netty; however that may be unavailable on the classpath.
   */
  private static SSLEngine createJDKEngine(KeyManagerFactory keyManagerFactory,
                                               TrustManagerFactory trustManagerFactory,
                                               String[] protocols,
                                               long verifyMode,
                                               String[] ciphers) throws NoSuchAlgorithmException, KeyManagementException  {
    SSLContext sslCtx = SSLContext.getInstance("TLS");
    sslCtx.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), null);
    SSLEngine engine = sslCtx.createSSLEngine();

    engine.setEnabledProtocols(protocols);
    engine.setUseClientMode(false);

    if ((verifyMode & 0x1) != 0) { // 'peer'
      engine.setWantClientAuth(true);
    }
    if ((verifyMode & 0x2) != 0) { // 'force_peer'
      engine.setNeedClientAuth(true);
    }

    if (ciphers != null) {
      engine.setEnabledCipherSuites(ciphers);
    }

    LOGGER.info("Using JDK SSL engine");
    return engine;
  }

  @JRubyMethod
  public IRubyObject inject(IRubyObject arg) {
    try {
      byte[] bytes = arg.convertToString().getBytes();
      inboundNetData.put(bytes);
      return this;
    } catch (Exception e) {
      e.printStackTrace();
      throw new RuntimeException(e);
    }
  }

  private enum SSLOperation {
    WRAP,
    UNWRAP
  }

  private SSLEngineResult doOp(SSLOperation sslOp, MiniSSLBuffer src, MiniSSLBuffer dst) throws SSLException {
    SSLEngineResult res = null;
    boolean retryOp = true;
    while (retryOp) {
      switch (sslOp) {
        case WRAP:
          res = engine.wrap(src.getRawBuffer(), dst.getRawBuffer());
          break;
        case UNWRAP:
          res = engine.unwrap(src.getRawBuffer(), dst.getRawBuffer());
          break;
        default:
          throw new IllegalStateException("Unknown SSLOperation: " + sslOp);
      }

      switch (res.getStatus()) {
        case BUFFER_OVERFLOW:
          // increase the buffer size to accommodate the overflowing data
          int newSize = Math.max(engine.getSession().getPacketBufferSize(), engine.getSession().getApplicationBufferSize());
          dst.resize(newSize + dst.position());
          // retry the operation
          retryOp = true;
          break;
        case BUFFER_UNDERFLOW:
          // need to wait for more data to come in before we retry
          retryOp = false;
          break;
        default:
          // other cases are OK and CLOSED.  We're done here.
          retryOp = false;
      }
    }

    // after each op, run any delegated tasks if needed
    if(engine.getHandshakeStatus() == HandshakeStatus.NEED_TASK) {
      Runnable runnable;
      while ((runnable = engine.getDelegatedTask()) != null) {
        runnable.run();
      }
    }

    return res;
  }

  @JRubyMethod
  public IRubyObject read() throws Exception {
    try {
      inboundNetData.flip();

      if(!inboundNetData.hasRemaining()) {
        return getRuntime().getNil();
      }

      MiniSSLBuffer inboundAppData = new MiniSSLBuffer(engine.getSession().getApplicationBufferSize());
      doOp(SSLOperation.UNWRAP, inboundNetData, inboundAppData);

      HandshakeStatus handshakeStatus = engine.getHandshakeStatus();
      boolean done = false;
      while (!done) {
        switch (handshakeStatus) {
          case NEED_WRAP:
            doOp(SSLOperation.WRAP, inboundAppData, outboundNetData);
            break;
          case NEED_UNWRAP:
            SSLEngineResult res = doOp(SSLOperation.UNWRAP, inboundNetData, inboundAppData);
            if (res.getStatus() == Status.BUFFER_UNDERFLOW) {
              // need more data before we can shake more hands
              done = true;
            }
            break;
          default:
            done = true;
        }
        handshakeStatus = engine.getHandshakeStatus();
      }

      if (inboundNetData.hasRemaining()) {
        inboundNetData.compact();
      } else {
        inboundNetData.clear();
      }

      ByteList appDataByteList = inboundAppData.asByteList();
      if (appDataByteList == null) {
        return getRuntime().getNil();
      }

      RubyString str = getRuntime().newString("");
      str.setValue(appDataByteList);
      return str;
    } catch (Exception e) {
      throw getRuntime().newEOFError(e.getMessage());
    }
  }

  @JRubyMethod
  public IRubyObject write(IRubyObject arg) {
    try {
      byte[] bls = arg.convertToString().getBytes();
      outboundAppData = new MiniSSLBuffer(bls);

      return getRuntime().newFixnum(bls.length);
    } catch (Exception e) {
      e.printStackTrace();
      throw new RuntimeException(e);
    }
  }

  @JRubyMethod
  public IRubyObject extract() throws SSLException {
    try {
      ByteList dataByteList = outboundNetData.asByteList();
      if (dataByteList != null) {
        RubyString str = getRuntime().newString("");
        str.setValue(dataByteList);
        return str;
      }

      if (!outboundAppData.hasRemaining()) {
        return getRuntime().getNil();
      }

      outboundNetData.clear();
      doOp(SSLOperation.WRAP, outboundAppData, outboundNetData);
      dataByteList = outboundNetData.asByteList();
      if (dataByteList == null) {
        return getRuntime().getNil();
      }

      RubyString str = getRuntime().newString("");
      str.setValue(dataByteList);

      return str;
    } catch (Exception e) {
      e.printStackTrace();
      throw new RuntimeException(e);
    }
  }

  @JRubyMethod
  public IRubyObject peercert() throws CertificateEncodingException {
    try {
      return JavaEmbedUtils.javaToRuby(getRuntime(), engine.getSession().getPeerCertificates()[0].getEncoded());
    } catch (SSLPeerUnverifiedException ex) {
      return getRuntime().getNil();
    }
  }

  @JRubyMethod
  public IRubyObject shutdown() {
    // TODO: Implement!
    return getRuntime().getTrue();
  }

  /**
   * Returns true if the SSL connection is still in init phase.
   */
  @JRubyMethod(name = "init?")
  public IRubyObject init_p() {
    // TODO: Implement!
    return getRuntime().getFalse();
  }
}
