package com.jbidwatcher.util.queue;

import com.jbidwatcher.util.config.JConfig;

import java.util.*;
import java.util.concurrent.*;

/** Manages a queue of tasks that need to be executed some time in the future. */
public class ConcurrentTimeQueueManager {
    private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
    private final Collection<TQCarrier> currentTasks = new ConcurrentLinkedQueue<TQCarrier>();

    protected class TQCarrier implements Runnable {
        private final Object payload;
        private final String destination_queue;
        private final long repeatRate;
        private final long when;
        private int repeatCount;

        public Object getPayload() { return payload; }
        public String getDestinationQueue() { return destination_queue; }
        public long getRepeatRate() { return repeatRate; }
        public long getRepeatCount() { return repeatCount; }
        public void decrementCount() { repeatCount--; }
        public long getWhen() {return when;}

        public TQCarrier(Object o, String s, long r, int c, long when) {
            destination_queue = s;
            payload = o;
            repeatRate = r;
            repeatCount = c;
            this.when = when;
        }

        public void run() {
            /* remove this task */
            currentTasks.remove(this);
            MessageQueue q = MQFactory.getConcrete(getDestinationQueue());

            Object payload = getPayload();
            if(payload instanceof QObject) {
                q.enqueueBean((QObject)payload);
            } else if (payload instanceof String) {
                q.enqueue((String) payload);
            } else if(q instanceof PlainMessageQueue) {
                ((PlainMessageQueue)q).enqueueObject(getPayload());
            } else {
                //  Payload isn't a QObject or String, and q isn't a plainMessageQueue.
                //  Trying to submit an arbitrary object to the SwingMessageQueue?  Teh fail.
                JConfig.log().logDebug("Submitting: " + payload.toString() + " to " + q.toString() + " will probably fail.");
                q.enqueue(payload.toString());
            }
            if(getRepeatRate() != 0) {
                //  If there's a positive repeat count, decrement it once.
                if(getRepeatCount() > 0) {
                    decrementCount();
                }
                //  As long as repeat count hasn't reached zero, re-add it.
                if(getRepeatCount() != 0) {
                    currentTasks.add(this);
                    executor.schedule(this,getCurrentTime()+getRepeatRate(), TimeUnit.MILLISECONDS);
                }
            }
        }
    }

    protected long getCurrentTime() { return System.currentTimeMillis(); }

    private TQCarrier createCarrier(Object payload, String destination, long repeat, int howmany, long when) {
        return new TQCarrier(payload, destination, repeat, howmany, when);
    }

    public void add(Object payload, String destination, long when) {
        TQCarrier c = createCarrier(payload,destination,0,1,when);
        currentTasks.add(c);
        executor.schedule(c, when, TimeUnit.MILLISECONDS);
    }

    public void add(Object payload, String destination, long when, long repeat) {
        TQCarrier c = createCarrier(payload,destination,repeat,-1,when);
        currentTasks.add(c);
        executor.schedule(c, when, TimeUnit.MILLISECONDS);
    }

    public void add(Object payload, String destination, long when, long repeat, int howmany) {
        TQCarrier c = createCarrier(payload,destination,repeat,howmany,when);
        currentTasks.add(c);
        executor.schedule(c, when, TimeUnit.MILLISECONDS);
    }

    public boolean erase(Object payload) {
        boolean removed = false;
        final Iterator<TQCarrier> it = currentTasks.iterator();
        while (it.hasNext()) {
            TQCarrier curr = it.next();
            if (curr.getPayload()==payload || curr.getPayload().equals(payload)) {
                executor.remove(curr);
                it.remove();
                removed = true;
            }
        }
        return removed;
    }

    public interface Matcher {
        public boolean match(Object payload, Object queue, long when);
    }

    public boolean contains(Matcher m) {
        final Iterator<TQCarrier> it = currentTasks.iterator();
        while (it.hasNext()) {
            TQCarrier curr = it.next();
            if (m.match(curr.getPayload(),curr.getDestinationQueue(),curr.getWhen())) {
                return true;
            }
        }
        return false;
    }

    public boolean contains(Object payload) {
        final Iterator<TQCarrier> it = currentTasks.iterator();
        while (it.hasNext()) {
            TQCarrier curr = it.next();
            if (payload.equals(curr.getPayload())) {
                return true;
            }

        }
        return false;
    }

    public void dumpQueue(String prefix) {
        for (TQCarrier tqc : currentTasks) {
            JConfig.log().logDebug(prefix + ": Queue: " + tqc.getDestinationQueue());
            JConfig.log().logDebug(prefix + ": Object: [" + tqc.getPayload() + "]");
            JConfig.log().logDebug(prefix + ": When: " + new Date(tqc.getWhen()));
            JConfig.log().logDebug("--");
        }
    }
}
