package voldemort.client;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A thread factory that sets the threads to run as daemons.
 * 
 * @author jkreps
 * 
 */
public class DaemonThreadFactory implements ThreadFactory {

    private final AtomicInteger threadNumber;
    private final String namePrefix;

    public DaemonThreadFactory(String threadNamePrefix) {
        this.threadNumber = new AtomicInteger(1);
        this.namePrefix = threadNamePrefix;
    }

    public Thread newThread(Runnable r) {
        Thread t = new Thread(r, namePrefix + threadNumber.getAndIncrement());
        t.setDaemon(true);
        return t;
    }

}
