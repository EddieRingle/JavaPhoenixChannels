package org.phoenixframework.channels;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Push {
    private static final Logger LOG = Logger.getLogger(Push.class.getName());

    private Channel channel = null;
    private String event = null;
    private String refEvent = null;
    private JsonObject payload = null;
    private Envelope receivedEnvelope = null;
    private Map<String, List<IMessageCallback>> recHooks = new HashMap<>();
    private boolean sent = false;
    private TimeoutHook timeoutHook;

    Push(final Channel channel, final String event, final JsonObject payload, final long timeout) {
        this.channel = channel;
        this.event = event;
        this.payload = payload;
        this.timeoutHook = new TimeoutHook(timeout);
    }

    /**
     * Registers for notifications on status messages
     *
     * @param status The message status to register callbacks on
     * @param callback The callback handler
     *
     * @return This instance's self
     */
    public Push receive(final String status, final IMessageCallback callback) {
        if(this.receivedEnvelope != null) {
            final String receivedStatus = this.receivedEnvelope.getResponseStatus();
            if(receivedStatus != null && receivedStatus.equals(status)) {
                callback.onMessage(this.receivedEnvelope);
            }
        }
        synchronized(recHooks) {
            List<IMessageCallback> statusHooks = this.recHooks.get(status);
            if(statusHooks == null) {
                statusHooks = new ArrayList<>();
                this.recHooks.put(status, statusHooks);
            }
            statusHooks.add(callback);
        }

        return this;
    }

    /**
     * Registers for notification of message response timeout
     *
     * @param callback The callback handler called when timeout is reached
     *
     * @return This instance's self
     */
    public Push timeout(final ITimeoutCallback callback) {
        if(this.timeoutHook.hasCallback())
            throw new IllegalStateException("Only a single after hook can be applied to a Push");

        this.timeoutHook.setCallback(callback);

        return this;
    }


    void send() throws IOException {
        final String ref = channel.getSocket().makeRef();
        LOG.log(Level.FINE, "Push send, ref={0}", ref);

        this.refEvent = Socket.replyEventName(ref);
        this.receivedEnvelope = null;

        this.channel.on(this.refEvent, new IMessageCallback() {
            @Override
            public void onMessage(final Envelope envelope) {
                Push.this.receivedEnvelope = envelope;
                Push.this.matchReceive(receivedEnvelope.getResponseStatus(), envelope);
                Push.this.cancelRefEvent();
                Push.this.cancelTimeout();
            }
        });

        this.startTimeout();
        this.sent = true;
        final Envelope envelope = new Envelope(this.channel.getTopic(), this.event, this.payload, ref);
        this.channel.getSocket().push(envelope);
    }

    private void cancelTimeout() {
        this.timeoutHook.getTimerTask().cancel();
        this.timeoutHook.setTimerTask(null);
    }

    private void startTimeout() {
        this.timeoutHook.setTimerTask(createTimerTask());
        this.channel.scheduleTask(this.timeoutHook.getTimerTask(), this.timeoutHook.getMs());
    }

    private TimerTask createTimerTask(){
        final Runnable callback = new Runnable() {
            @Override
            public void run() {
                Push.this.cancelRefEvent();
                if(Push.this.timeoutHook.hasCallback()) {
                    Push.this.timeoutHook.getCallback().onTimeout();
                }
            }
        };

        return new TimerTask() {
            @Override
            public void run() {
                callback.run();
            }
        };
    }

    private void matchReceive(final String status, final Envelope envelope) {
        synchronized (recHooks) {
            final List<IMessageCallback> statusCallbacks = this.recHooks.get(status);
            if(statusCallbacks != null) {
                for (final IMessageCallback callback : statusCallbacks) {
                    callback.onMessage(envelope);
                }
            }
        }
    }

    Channel getChannel() {
        return channel;
    }

    String getEvent() {
        return event;
    }

    JsonObject getPayload() {
        return payload;
    }

    Envelope getReceivedEnvelope() {
        return receivedEnvelope;
    }

    Map<String, List<IMessageCallback>> getRecHooks() {
        return recHooks;
    }

    boolean isSent() {
        return sent;
    }

    private void cancelRefEvent() {
        this.channel.off(this.refEvent);
    }

    private class TimeoutHook {
        private final long ms;
        private ITimeoutCallback callback;
        private TimerTask timerTask;

        public TimeoutHook(final long ms) {
            this.ms = ms;
        }

        public long getMs() {
            return ms;
        }

        public ITimeoutCallback getCallback() {
            return callback;
        }

        public TimerTask getTimerTask() {
            return timerTask;
        }

        public void setTimerTask(final TimerTask timerTask) {
            this.timerTask = timerTask;
        }

        public boolean hasCallback(){
            return this.callback != null;
        }

        public void setCallback(final ITimeoutCallback callback){
            this.callback = callback;
        }
    }
}