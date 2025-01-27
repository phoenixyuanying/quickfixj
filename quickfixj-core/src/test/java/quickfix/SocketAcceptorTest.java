/*******************************************************************************
 * Copyright (c) quickfixengine.org  All rights reserved.
 *
 * This file is part of the QuickFIX FIX Engine
 *
 * This file may be distributed under the terms of the quickfixengine.org
 * license as defined by quickfixengine.org and appearing in the file
 * LICENSE included in the packaging of this file.
 *
 * This file is provided AS IS with NO WARRANTY OF ANY KIND, INCLUDING
 * THE WARRANTY OF DESIGN, MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE.
 *
 * See http://www.quickfixengine.org/LICENSE for licensing information.
 *
 * Contact ask@quickfixengine.org if any conditions of this licensing
 * are not clear to you.
 ******************************************************************************/

package quickfix;

import org.apache.mina.core.service.IoAcceptor;
import org.apache.mina.util.AvailablePortFinder;
import org.junit.After;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.field.MsgType;
import quickfix.mina.ProtocolFactory;
import quickfix.mina.SingleThreadedEventHandlingStrategy;
import quickfix.mina.message.FIXProtocolCodecFactory;
import quickfix.mina.ssl.SSLSupport;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * QFJ-643: Unable to restart a stopped acceptor (SocketAcceptor)
 *
 * Check if a connection can be established against a restarted SocketAcceptor.
 *
 * MultiAcceptorTest served as a template for this test.
 */
public class SocketAcceptorTest {
    // store static Session count before the test to check cleanup
    private static final int SESSION_COUNT = Session.numSessions();

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final SessionID acceptorSessionID = new SessionID(FixVersions.BEGINSTRING_FIX42,
            "ACCEPTOR", "INITIATOR");
    private final SessionID initiatorSessionID = new SessionID(FixVersions.BEGINSTRING_FIX42,
            "INITIATOR", "ACCEPTOR");

    @After
    public void cleanup() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException ex) {
            java.util.logging.Logger.getLogger(SocketAcceptorTest.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    @Test
    public void testRestartOfAcceptor() throws Exception {
        TestConnectorApplication testAcceptorApplication = new TestConnectorApplication();
        TestConnectorApplication testInitiatorApplication = new TestConnectorApplication();
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        Acceptor acceptor = null;
        Initiator initiator = null;
        try {
            final int port = AvailablePortFinder.getNextAvailable();
            acceptor = createAcceptor(testAcceptorApplication, port);
            acceptor.start();
            initiator = createInitiator(testInitiatorApplication, port);

            assertNotNull("Session should be registered", lookupSession(acceptorSessionID));

            acceptor.stop();
            assertNull("Session should NOT be registered", lookupSession(acceptorSessionID));

            acceptor.start();
            assertNotNull("Session should be registered", lookupSession(acceptorSessionID));
            initiator.start();

            // we expect one thread for acceptor, one for initiator
            checkThreads(bean, 2);

            testAcceptorApplication.waitForLogon();
            testInitiatorApplication.waitForLogon();
            assertTrue("acceptor should have logged on by now", acceptor.isLoggedOn());
            assertTrue("initiator should have logged on by now", initiator.isLoggedOn());
        } finally {
            try {
                if (initiator != null) {
                    try {
                        initiator.stop();
                    } catch (RuntimeException e) {
                        log.error(e.getMessage(), e);
                    }
                }
                testAcceptorApplication.waitForLogout();
            } finally {
                if (acceptor != null) {
                    try {
                        acceptor.stop();
                    } catch (RuntimeException e) {
                        log.error(e.getMessage(), e);
                    }
                }
                testInitiatorApplication.waitForLogout();
            }
        }
    }

    // QFJ-825
    @Test
    public void testQuickRestartOfAcceptor() throws Exception {
        Acceptor acceptor = null;
        try {
            ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            TestConnectorApplication testAcceptorApplication = new TestConnectorApplication();
            final int port = AvailablePortFinder.getNextAvailable();
            acceptor = createAcceptor(testAcceptorApplication, port);
            acceptor.start();
            Thread.sleep(2500L);
            acceptor.stop();
            acceptor.start();
            checkThreads(bean, 1);
        } finally {
            if (acceptor != null) {
                acceptor.stop(true);
            }
            Thread.sleep(500);
        }
    }

    // QFJ-825
    @Test
    public void testDoubleStartOfAcceptor() throws Exception {
        Acceptor acceptor = null;
        try {
            ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            TestConnectorApplication testAcceptorApplication = new TestConnectorApplication();
            final int port = AvailablePortFinder.getNextAvailable();
            acceptor = createAcceptor(testAcceptorApplication, port);
            acceptor.start();
            // second start should be ignored
            acceptor.start();
            checkThreads(bean, 1);
        } finally {
            if (acceptor != null) {
                acceptor.stop(true);
            }
            Thread.sleep(500);
        }
    }

    @Test
    public void testSessionsAreCleanedUp() throws Exception {
        Acceptor acceptor = null;
        try {
            TestConnectorApplication testAcceptorApplication = new TestConnectorApplication();
            final int port = AvailablePortFinder.getNextAvailable();
            acceptor = createAcceptor(testAcceptorApplication, port);
            acceptor.start();
            assertEquals(1, acceptor.getSessions().size() );
            assertEquals(1 + SESSION_COUNT, Session.numSessions() );
            
        } finally {
            if (acceptor != null) {
                acceptor.stop(true);
                assertTrue("After stop() the Session count should not be higher than before the test", Session.numSessions() <= SESSION_COUNT );
                assertEquals("After stop() the Session count should be zero in Connector", 0, acceptor.getSessions().size() );
            }
        }
    }

    @Test
    public void testSessionsAreCleanedUpOnThreadedSocketAcceptor() throws Exception {
        Acceptor acceptor = null;
        try {
            TestConnectorApplication testAcceptorApplication = new TestConnectorApplication();
            final int port = AvailablePortFinder.getNextAvailable();
            acceptor = createAcceptorThreaded(testAcceptorApplication, port);
            acceptor.start();
            assertEquals(1, acceptor.getSessions().size() );
            assertEquals(1 + SESSION_COUNT, Session.numSessions() );
            
        } finally {
            if (acceptor != null) {
                acceptor.stop(true);
                assertTrue("After stop() the Session count should not be higher than before the test", Session.numSessions() <= SESSION_COUNT );
                assertEquals("After stop() the Session count should be zero in Connector", 0, acceptor.getSessions().size() );
            }
        }
    }
    
    @Test
    public void testAcceptorContinueInitializationOnError() throws ConfigError, InterruptedException, IOException {
        final int port = AvailablePortFinder.getNextAvailable();
        final int port2 = AvailablePortFinder.getNextAvailable();
        final SessionSettings settings = new SessionSettings();
        final SessionID sessionId = new SessionID("FIX.4.4", "SENDER", "TARGET");
        final SessionID sessionId2 = new SessionID("FIX.4.4", "FOO", "BAR");
        final SessionID sessionId3 = new SessionID("FIX.4.4", "BAR", "BAZ");
        settings.setString(SessionFactory.SETTING_CONTINUE_INIT_ON_ERROR, "Y");
        settings.setString("ConnectionType", "acceptor");
        settings.setString("StartTime", "00:00:00");
        settings.setString("EndTime", "00:00:00");
        settings.setString("HeartBtInt", "30");
        settings.setString("BeginString", "FIX.4.4");
        settings.setLong(sessionId, "SocketAcceptPort", port);
        settings.setLong(sessionId2, "SocketAcceptPort", port2);
        settings.setLong(sessionId3, "SocketAcceptPort", port2);
        settings.setString(sessionId, SSLSupport.SETTING_USE_SSL, "Y");
        settings.setString(sessionId, SSLSupport.SETTING_KEY_STORE_NAME, "test.keystore");
        // supply a wrong password to make initialization fail
        settings.setString(sessionId, SSLSupport.SETTING_KEY_STORE_PWD, "wrong-password");
        // supply a wrong protocol to make initialization fail
        settings.setString(sessionId3, "SocketAcceptProtocol", "foobar");

        final SocketAcceptor acceptor = new SocketAcceptor(new ApplicationAdapter(), new MemoryStoreFactory(), settings,
                new ScreenLogFactory(settings), new DefaultMessageFactory());
        acceptor.start();

        for (IoAcceptor endpoint : acceptor.getEndpoints()) {
            boolean containsFIXCodec = endpoint.getFilterChain().contains(FIXProtocolCodecFactory.FILTER_NAME);
            if (endpoint.getLocalAddress() == null) { // failing session is not bound!
                assertFalse(containsFIXCodec);
            } else {
                assertTrue(containsFIXCodec);
            }
        }

        // sessionid1 is present since it fails after the setup phase
        assertTrue(acceptor.getSessions().contains(sessionId));
        // sessionid2 is set up normally
        assertTrue(acceptor.getSessions().contains(sessionId2));
        // sessionid3 could not be set up due to problems in the config itself
        assertFalse(acceptor.getSessions().contains(sessionId3));

        acceptor.stop();
    }
    
    /**
     * Ensure that an Acceptor can be started that only has a template session.
     */
    @Test
    public void testAcceptorTemplate() throws ConfigError, InterruptedException, IOException {
        final int port = AvailablePortFinder.getNextAvailable();
        final SessionSettings settings = new SessionSettings();
        final SessionID sessionId = new SessionID("FIX.4.4", "SENDER", "TARGET");
        settings.setString("ConnectionType", "acceptor");
        settings.setString("StartTime", "00:00:00");
        settings.setString("EndTime", "00:00:00");
        settings.setString("HeartBtInt", "30");
        settings.setString("BeginString", "FIX.4.4");
        settings.setLong(sessionId, "SocketAcceptPort", port);
        settings.setString(sessionId, Acceptor.SETTING_ACCEPTOR_TEMPLATE, "Y");

        final SocketAcceptor acceptor = new SocketAcceptor(new ApplicationAdapter(), new MemoryStoreFactory(), settings,
                new ScreenLogFactory(settings), new DefaultMessageFactory());
        acceptor.start();

        for (IoAcceptor endpoint : acceptor.getEndpoints()) {
            boolean containsFIXCodec = endpoint.getFilterChain().contains(FIXProtocolCodecFactory.FILTER_NAME);
            assertTrue(containsFIXCodec);
        }

        acceptor.stop();
    }


    private void checkThreads(ThreadMXBean bean, int expectedNum) {
        ThreadInfo[] dumpAllThreads = bean.dumpAllThreads(false, false);
        int qfjMPThreads = 0;
        for (ThreadInfo threadInfo : dumpAllThreads) {
            if (SingleThreadedEventHandlingStrategy.MESSAGE_PROCESSOR_THREAD_NAME.equals(threadInfo
                    .getThreadName())) {
                qfjMPThreads++;
            }
        }
        assertEquals("Exactly " + expectedNum + " 'QFJ Message Processor' thread(s) expected", expectedNum, qfjMPThreads);
    }

    private Session lookupSession(SessionID sessionID) {
        return Session.lookupSession(sessionID);
    }

    private class TestConnectorApplication extends ApplicationAdapter {

        private final CountDownLatch logonLatch;
        private final CountDownLatch logoutLatch;

        public TestConnectorApplication() {
            logonLatch = new CountDownLatch(1);
            logoutLatch = new CountDownLatch(1);
        }

        @Override
        public void onLogon(SessionID sessionId) {
            super.onLogon(sessionId);
            logonLatch.countDown();
        }

        public void waitForLogon() {
            try {
                assertTrue("Logon timed out", logonLatch.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                fail(e.getMessage());
            }
        }
        
        public void waitForLogout() {
            try {
                assertTrue("Logout timed out", logoutLatch.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                fail(e.getMessage());
            }
        }

        @Override
        public void fromAdmin(Message message, SessionID sessionId) throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, RejectLogon {
            try {
                if (MsgType.LOGOUT.equals(MessageUtils.getMessageType(message.toString()))) {
                    logoutLatch.countDown();
                }
            } catch (InvalidMessage ex) {
                // ignore
            }
        }

        @Override
        public void toAdmin(Message message, SessionID sessionId) {
            log.info("toAdmin: [{}] {}", sessionId, message);
        }
    }

    
    private Acceptor createAcceptor(TestConnectorApplication testAcceptorApplication, int port)
            throws ConfigError {

        SessionSettings settings = createAcceptorSettings(port);

        MessageStoreFactory factory = new MemoryStoreFactory();
        quickfix.LogFactory logFactory = new SLF4JLogFactory(new SessionSettings());
        return new SocketAcceptor(testAcceptorApplication, factory, settings, logFactory,
                new DefaultMessageFactory());
    }

    private Acceptor createAcceptorThreaded(TestConnectorApplication testAcceptorApplication, int port)
            throws ConfigError {

        SessionSettings settings = createAcceptorSettings(port);

        MessageStoreFactory factory = new MemoryStoreFactory();
        quickfix.LogFactory logFactory = new SLF4JLogFactory(new SessionSettings());
        return new ThreadedSocketAcceptor(testAcceptorApplication, factory, settings, logFactory,
                new DefaultMessageFactory());
    }
    
    private SessionSettings createAcceptorSettings(int socketAcceptPort) {
        SessionSettings settings = new SessionSettings();
        HashMap<Object, Object> defaults = new HashMap<>();
        defaults.put("ConnectionType", "acceptor");
        defaults.put("StartTime", "00:00:00");
        defaults.put("EndTime", "00:00:00");
        defaults.put("BeginString", "FIX.4.2");
        defaults.put("NonStopSession", "Y");
        settings.setString(acceptorSessionID, "SocketAcceptProtocol", ProtocolFactory.getTypeString(ProtocolFactory.SOCKET));
        settings.setString(acceptorSessionID, "SocketAcceptPort", String.valueOf(socketAcceptPort));
        settings.set(defaults);
        return settings;
    }

    private Initiator createInitiator(TestConnectorApplication testInitiatorApplication, int socketConnectPort) throws ConfigError {
        SessionSettings settings = new SessionSettings();
        HashMap<Object, Object> defaults = new HashMap<>();
        defaults.put("ConnectionType", "initiator");
        defaults.put("StartTime", "00:00:00");
        defaults.put("EndTime", "00:00:00");
        defaults.put("HeartBtInt", "30");
        defaults.put("ReconnectInterval", "2");
        defaults.put("FileStorePath", "target/data/client");
        defaults.put("ValidateUserDefinedFields", "Y");
        defaults.put("NonStopSession", "Y");
        settings.setString("BeginString", FixVersions.BEGINSTRING_FIX42);
        settings.setString(initiatorSessionID, "SocketConnectProtocol", ProtocolFactory.getTypeString(ProtocolFactory.SOCKET));
        settings.setString(initiatorSessionID, "SocketConnectHost", "127.0.0.1");
        settings.setString(initiatorSessionID, "SocketConnectPort", String.valueOf(socketConnectPort));
        settings.set(defaults);

        MessageStoreFactory factory = new MemoryStoreFactory();
        quickfix.LogFactory logFactory = new SLF4JLogFactory(new SessionSettings());
        return new SocketInitiator(testInitiatorApplication, factory, settings, logFactory, new DefaultMessageFactory());
    }

}
