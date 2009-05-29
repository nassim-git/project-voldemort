/*
 * Copyright 2008-2009 LinkedIn, Inc
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package voldemort.performance;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import voldemort.TestUtils;
import voldemort.client.ClientConfig;
import voldemort.client.SocketStoreClientFactory;
import voldemort.client.StoreClient;
import voldemort.client.StoreClientFactory;
import voldemort.versioning.Versioned;

public class RemoteTest {

    public static final int MAX_WORKERS = 8;

    public static class KeyProvider {

        private final List<String> keys;
        private final AtomicInteger index;

        public KeyProvider(int start, List<String> keys) {
            this.index = new AtomicInteger(start);
            this.keys = keys;
        }

        public String next() {
            if(keys != null) {
                return keys.get(index.getAndIncrement() % keys.size());
            } else {
                return Integer.toString(index.getAndIncrement());
            }
        }
    }

    public static void printUsage(PrintStream out, OptionParser parser) throws IOException {
        out.println("Usage: $VOLDEMORT_HOME/bin/remote-test.sh \\");
        out.println("          [options] bootstrapUrl storeName num-requests\n");
        parser.printHelpOn(out);
        System.exit(1);
    }

    public static void main(String[] args) throws Exception {

        args = new String[] { "-r", "--request-file", "/Users/elias/dev/ads/voldemort/small_lcids",
                "-d", "--", "tcp://localhost:6666", "test", "100" };

        OptionParser parser = new OptionParser();
        parser.accepts("r", "execute read operations").withOptionalArg();
        parser.accepts("w", "execute write operations").withOptionalArg();
        parser.accepts("d", "execute delete operations").withOptionalArg();
        parser.accepts("randomize", "randomize operations via keys").withOptionalArg();
        parser.accepts("request-file", "execute specific requests in order").withOptionalArg();
        parser.accepts("start-key-index", "starting point when using int keys")
              .withOptionalArg()
              .ofType(Integer.class);
        parser.accepts("value-size", "size in bytes for random value")
              .withOptionalArg()
              .ofType(Integer.class);

        OptionSet options = parser.parse(args);

        List<String> nonOptions = options.nonOptionArguments();
        if(nonOptions.size() != 3) {
            printUsage(System.err, parser);
        }

        String url = nonOptions.get(0);
        String storeName = nonOptions.get(1);
        int numRequests = Integer.parseInt(nonOptions.get(2));
        int startNum = 0;
        int valueSize = 1024;
        String ops = "";
        List<String> keys = null;

        if(options.has("start-key-index")) {
            startNum = (Integer) options.valueOf("start-key-index");
        }

        if(options.has("value-size")) {
            startNum = (Integer) options.valueOf("value-size");
        }

        if(options.has("request-file")) {
            keys = loadKeys((String) options.valueOf("request-file"));
        }

        if(options.has("r")) {
            ops += "r";
        }
        if(options.has("w")) {
            ops += "w";
        }
        if(options.has("d")) {
            ops += "d";
        }

        if(ops.length() == 0) {
            ops = "rwd";
        }

        System.err.println("Bootstraping cluster data.");
        StoreClientFactory factory = new SocketStoreClientFactory(new ClientConfig().setMaxThreads(20)
                                                                                    .setMaxConnectionsPerNode(MAX_WORKERS)
                                                                                    .setBootstrapUrls(url));
        final StoreClient<String, String> store = factory.getStoreClient(storeName);

        final String value = new String(TestUtils.randomBytes(valueSize));
        ExecutorService service = Executors.newFixedThreadPool(MAX_WORKERS);

        if(ops.contains("d")) {
            System.err.println("Beginning delete test.");
            final AtomicInteger successes = new AtomicInteger(0);
            final KeyProvider keyProvider0 = new KeyProvider(startNum, keys);
            final CountDownLatch latch0 = new CountDownLatch(numRequests);
            long start = System.currentTimeMillis();
            for(int i = 0; i < numRequests; i++) {
                service.execute(new Runnable() {

                    public void run() {
                        try {
                            store.delete(keyProvider0.next());
                            successes.getAndIncrement();
                        } catch(Exception e) {
                            e.printStackTrace();
                        } finally {
                            latch0.countDown();
                        }
                    }
                });
            }
            latch0.await();
            long deleteTime = System.currentTimeMillis() - start;
            System.out.println("Throughput: " + (numRequests / (float) deleteTime * 1000)
                               + " deletes/sec.");
            System.out.println(successes.get() + " things deleted.");
        }

        if(ops.contains("w")) {
            System.err.println("Beginning write test.");
            final KeyProvider keyProvider1 = new KeyProvider(startNum, keys);
            final CountDownLatch latch1 = new CountDownLatch(numRequests);
            long start = System.currentTimeMillis();
            for(int i = 0; i < numRequests; i++) {
                service.execute(new Runnable() {

                    public void run() {
                        try {
                            store.put(keyProvider1.next(), new Versioned<String>(value));
                        } catch(Exception e) {
                            e.printStackTrace();
                        } finally {
                            latch1.countDown();
                        }
                    }
                });
            }
            latch1.await();
            long writeTime = System.currentTimeMillis() - start;
            System.out.println("Throughput: " + (numRequests / (float) writeTime * 1000)
                               + " writes/sec.");
        }

        if(ops.contains("r")) {
            System.err.println("Beginning read test.");
            final KeyProvider keyProvider2 = new KeyProvider(startNum, keys);
            final CountDownLatch latch2 = new CountDownLatch(numRequests);
            long start = System.currentTimeMillis();
            for(int i = 0; i < numRequests; i++) {
                service.execute(new Runnable() {

                    public void run() {
                        try {
                            store.get(keyProvider2.next());
                        } catch(Exception e) {
                            e.printStackTrace();
                        } finally {
                            latch2.countDown();
                        }
                    }
                });
            }
            latch2.await();
            long readTime = System.currentTimeMillis() - start;
            System.out.println("Throughput: " + (numRequests / (float) readTime * 1000.0)
                               + " reads/sec.");
        }

        System.exit(0);
    }

    public static List<String> loadKeys(String path) throws FileNotFoundException, IOException {

        List<String> targets = new ArrayList<String>();
        File file = new File(path);
        BufferedReader reader = null;

        try {
            reader = new BufferedReader(new FileReader(file));
            String text = null;
            while((text = reader.readLine()) != null) {
                targets.add(text);
            }
        } finally {
            try {
                if(reader != null) {
                    reader.close();
                }
            } catch(IOException e) {
                e.printStackTrace();
            }
        }

        return targets;
    }
}
