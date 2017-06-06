package ru.infon.queue.mongo.engine;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.Collection;
import java.util.Iterator;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * 29.03.2017
 * @author KostaPC
 * 2017 Infon ZED
 */
class QueueConsumerThread<T> {

    private static final Log LOG = LogFactory.getLog(QueueConsumerThread.class);

    // TODO: move propery to external param
    private static final int PROPERTY_DEFAULT_FETCH_DELAY_MILLS = 100;
    private static final int PROPERTY_DEFAULT_FETCH_LIMIT = 100;

    private Executor executor;

    private QueueConsumer<T> consumer;
    private QueuePacketHolder<T> packetHolder;
    private Timer timer = new Timer();

    QueueConsumerThread(
            QueueConsumer<T> consumer,
            QueuePacketHolder<T> packetHolder
    ) {
        this.consumer = consumer;
        this.packetHolder = packetHolder;
    }

    void start() {
        LOG.info(String.format(
                "starting QueueConsumerThread for %s",
                consumer
        ));
        executor.execute(()-> runTask(this::payload));
    }

    private Collection<MessageContainer<T>> payload() {
        return packetHolder.fetch(consumer);
    }

    private void onComplete(Collection<MessageContainer<T>> result) {
        if(result.size()>0) {
            LOG.info(String.format(
                    "worker received %d events for service %s",
                    result.size(), consumer
            ));
        }
        if(result.size()==0) {
            schedule(()-> runTask(this::payload), PROPERTY_DEFAULT_FETCH_DELAY_MILLS);
        } else {
            Iterator<MessageContainer<T>> it = result.iterator();
            while (!result.isEmpty()) {
                if (!it.hasNext()) {
                    it = result.iterator();
                }
                // if consumer has no free threads - process will wait for
                MessageContainer<T> packet = it.next();
                try {
                    executor.execute(() -> {
                        LOG.debug(String.format(
                                "processing message %s with data: \"%s\"",
                                packet.getId(), packet.getMessage()
                        ));
                        packet.setCallback(
                                (me) -> {packetHolder.ack(me);},
                                (me) -> {packetHolder.reset(me);}
                        );
                        consumer.onPacket(packet);
                    });
                    it.remove();
                } catch (RejectedExecutionException rejected) {
                    LOG.warn(String.format(
                            "task {%s} was rejected by threadpool ... trying again",
                            packet.getId()
                    ));
                }
            }
            LOG.info(String.format(
                    "processing events done for %s", consumer
            ));
            runTask(this::payload);
        }
    }

    private void runTask(Supplier<Collection<MessageContainer<T>>> payload) {
        CompletableFuture.supplyAsync(payload,executor).thenAccept(this::onComplete);
    }

    private void schedule(Runnable runnable, long delay) {
        timer.schedule(new LambdaTimerTask(runnable), delay);
    }

    private class LambdaTimerTask extends TimerTask {

        private Runnable runnable;

        LambdaTimerTask(Runnable runnable) {
            this.runnable = runnable;
        }

        @Override
        public void run() {
            runnable.run();
        }
    }
}