/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.pravega.test.integration;

import io.netty.util.ResourceLeakDetector;
import io.netty.util.ResourceLeakDetector.Level;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;
import io.pravega.service.contracts.StreamSegmentStore;
import io.pravega.service.server.host.handler.PravegaConnectionListener;
import io.pravega.service.server.store.ServiceBuilder;
import io.pravega.service.server.store.ServiceBuilderConfig;
import io.pravega.state.InitialUpdate;
import io.pravega.state.Revision;
import io.pravega.state.Revisioned;
import io.pravega.state.StateSynchronizer;
import io.pravega.state.SynchronizerConfig;
import io.pravega.state.Update;
import io.pravega.state.examples.SetSynchronizer;
import io.pravega.stream.TxnFailedException;
import io.pravega.stream.impl.JavaSerializer;
import io.pravega.stream.mock.MockStreamManager;
import io.pravega.test.common.TestUtils;
import java.io.Serializable;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Cleanup;
import lombok.Data;
import lombok.val;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class StateSynchronizerTest {

    private Level originalLevel;
    private ServiceBuilder serviceBuilder;

    @Before
    public void setup() throws Exception {
        originalLevel = ResourceLeakDetector.getLevel();
        ResourceLeakDetector.setLevel(Level.PARANOID);
        InternalLoggerFactory.setDefaultFactory(Slf4JLoggerFactory.INSTANCE);
        this.serviceBuilder = ServiceBuilder.newInMemoryBuilder(ServiceBuilderConfig.getDefaultConfig());
        this.serviceBuilder.initialize();
    }

    @After
    public void teardown() {
        this.serviceBuilder.close();
        ResourceLeakDetector.setLevel(originalLevel);
    }
    
    @Data
    private static class TestState implements Revisioned {
        private final String scopedStreamName;
        private final Revision revision;
        private final String value;
        
    }

    @Data
    private static class TestUpdate implements Update<TestState>, InitialUpdate<TestState>, Serializable {
        private final String value;

        @Override
        public TestState applyTo(TestState oldState, Revision newRevision) {
            return new TestState(oldState.getScopedStreamName(), newRevision, value);
        }
        
        @Override
        public TestState create(String scopedStreamName, Revision revision) {
            return new TestState(scopedStreamName, revision, value);
        }
    }
    
    @Test(timeout = 20000)
    public void testStateTracker() throws TxnFailedException {
        String endpoint = "localhost";
        String stateName = "abc";
        int port = TestUtils.getAvailableListenPort();
        StreamSegmentStore store = this.serviceBuilder.createStreamSegmentService();
        @Cleanup
        PravegaConnectionListener server = new PravegaConnectionListener(false, port, store);
        server.startListening();
        @Cleanup
        MockStreamManager streamManager = new MockStreamManager("scope", endpoint, port);
        streamManager.createScope("scope");
        streamManager.createStream("scope", stateName, null);
        JavaSerializer<TestUpdate> serializer = new JavaSerializer<TestUpdate>();
        
        val a = streamManager.getClientFactory().createStateSynchronizer(stateName, serializer, serializer, SynchronizerConfig.builder().build());
        val b = streamManager.getClientFactory().createStateSynchronizer(stateName, serializer, serializer, SynchronizerConfig.builder().build());

        a.initialize(new TestUpdate("init"));
        b.fetchUpdates();
        assertEquals("init", b.getState().value);
        assertEquals(1, update(a, "already up to date 1"));
        assertEquals(2, update(b, "fail Initially 2"));
        assertEquals("already up to date 1", a.getState().value);
        assertEquals("fail Initially 2", b.getState().value);
        
        assertEquals(1, update(b, "already up to date 3"));
        assertEquals("already up to date 1", a.getState().value);
        a.fetchUpdates();
        assertEquals("already up to date 3", a.getState().value);
        assertEquals(1, update(a, "already up to date 4"));
        assertEquals("already up to date 4", a.getState().value);
        assertEquals("already up to date 3", b.getState().value);
        assertEquals(2, update(b, "fail Initially 5"));
        
        assertEquals("already up to date 4", a.getState().value);
        a.fetchUpdates();
        assertEquals("fail Initially 5", a.getState().value);
        a.fetchUpdates();
        b.fetchUpdates();
        assertEquals("fail Initially 5", a.getState().value);
        assertEquals("fail Initially 5", b.getState().value);
    }
    
    private int update(StateSynchronizer<TestState> sync, String string) {
        AtomicInteger count = new AtomicInteger(0);
        sync.updateState(state -> {
            count.incrementAndGet();
            return Collections.singletonList(new TestUpdate(string));
        });
        return count.get();
    }

    @Test(timeout = 20000)
    public void testReadsAllAvailable() {
        String endpoint = "localhost";
        String stateName = "abc";
        int port = TestUtils.getAvailableListenPort();
        StreamSegmentStore store = this.serviceBuilder.createStreamSegmentService();
        @Cleanup
        PravegaConnectionListener server = new PravegaConnectionListener(false, port, store);
        server.startListening();
        @Cleanup
        MockStreamManager streamManager = new MockStreamManager("scope", endpoint, port);
        streamManager.createScope("scope");
        streamManager.createStream("scope", stateName, null);
        SetSynchronizer<String> setA = SetSynchronizer.createNewSet(stateName, streamManager.getClientFactory());

        for (int i = 0; i < 10; i++) {
           setA.add("Append: " + i);
        }
        SetSynchronizer<String> setB = SetSynchronizer.createNewSet(stateName, streamManager.getClientFactory());
        assertEquals(10, setB.getCurrentSize());
        for (int i = 10; i < 20; i++) {
            setA.add("Append: " + i);
        }
        setB.update();
        assertEquals(20, setB.getCurrentSize());
    }

    @Test(timeout = 10000)
    public void testSetSynchronizer() {
        String endpoint = "localhost";
        String stateName = "abc";
        int port = TestUtils.getAvailableListenPort();
        StreamSegmentStore store = this.serviceBuilder.createStreamSegmentService();
        @Cleanup
        PravegaConnectionListener server = new PravegaConnectionListener(false, port, store);
        server.startListening();
        @Cleanup
        MockStreamManager streamManager = new MockStreamManager("scope", endpoint, port);
        streamManager.createScope("scope");
        streamManager.createStream("scope", stateName, null);
        SetSynchronizer<String> setA = SetSynchronizer.createNewSet(stateName, streamManager.getClientFactory());
        SetSynchronizer<String> setB = SetSynchronizer.createNewSet(stateName, streamManager.getClientFactory());

        setA.add("foo");
        assertEquals(1, setA.getCurrentSize());
        assertTrue(setA.getCurrentValues().contains("foo"));
        setB.update();
        assertEquals(1, setB.getCurrentSize());
        assertTrue(setB.getCurrentValues().contains("foo"));
        setA.add("bar");
        assertEquals(1, setB.getCurrentSize());
        assertTrue(setB.getCurrentValues().contains("foo"));
        setB.update();
        assertEquals(2, setB.getCurrentSize());
        assertTrue(setB.getCurrentValues().contains("bar"));
    }

}