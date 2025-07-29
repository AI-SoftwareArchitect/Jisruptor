// main.java
public class Main {
    public static void main(String[] args) throws InterruptedException {
        // Test senaryosu
        Jisruptor<ValueEvent> disruptor = new Jisruptor<>(
            ValueEvent.EVENT_FACTORY,
            1024,
            new SleepingWaitStrategy(),
            new ValueEventHandler(),
            new ValueEventHandler()
        );

        Thread producerThread = new Thread(() -> {
            for (long i = 0; i < 1000; i++) {
                disruptor.publishEvent((event, sequence) -> {
                    event.setValue(i);
                });
            }
        });

        long startTime = System.currentTimeMillis();
        disruptor.start();
        producerThread.start();
        producerThread.join();
        disruptor.shutdown();
        
        System.out.println("Processed " + ValueEventHandler.count + " events in " + 
                          (System.currentTimeMillis() - startTime) + "ms");
    }
}

class Jisruptor<T> {
    private final Object[] entries;
    private final int bufferSize;
    private final int indexMask;
    private final Sequence cursor = new Sequence();
    private final Sequence[] gatingSequences;
    private final WaitStrategy waitStrategy;
    private final EventProcessor<T>[] eventProcessors;
    private final EventFactory<T> eventFactory;
    private volatile boolean running = false;

    @SafeVarargs
    public Jisruptor(EventFactory<T> eventFactory, int bufferSize, 
                    WaitStrategy waitStrategy, EventHandler<T>... handlers) {
        if (Integer.bitCount(bufferSize) != 1) {
            throw new IllegalArgumentException("bufferSize must be a power of 2");
        }
        
        this.bufferSize = bufferSize;
        this.indexMask = bufferSize - 1;
        this.entries = new Object[bufferSize];
        this.eventFactory = eventFactory;
        this.waitStrategy = waitStrategy;
        
        // Initialize entries
        for (int i = 0; i < bufferSize; i++) {
            entries[i] = eventFactory.newInstance();
        }
        
        // Create event processors
        this.eventProcessors = new EventProcessor[handlers.length];
        this.gatingSequences = new Sequence[handlers.length];
        
        for (int i = 0; i < handlers.length; i++) {
            gatingSequences[i] = new Sequence();
            eventProcessors[i] = new EventProcessor<>(this, handlers[i], gatingSequences[i]);
        }
    }

    public void start() {
        if (running) {
            throw new IllegalStateException("Jisruptor is already running");
        }
        
        running = true;
        for (EventProcessor<T> processor : eventProcessors) {
            new Thread(processor).start();
        }
    }

    public void shutdown() {
        running = false;
    }

    public void publishEvent(EventTranslator<T> translator) {
        long sequence = cursor.incrementAndGet();
        @SuppressWarnings("unchecked")
        T event = (T) entries[(int) (sequence & indexMask)];
        translator.translate(event, sequence);
        
        // Notify waiting processors
        waitStrategy.signalAllWhenBlocking();
    }

    public T get(long sequence) {
        @SuppressWarnings("unchecked")
        T event = (T) entries[(int) (sequence & indexMask)];
        return event;
    }

    public long getCursor() {
        return cursor.get();
    }

    public boolean isRunning() {
        return running;
    }

    public int getBufferSize() {
        return bufferSize;
    }

    private static class EventProcessor<T> implements Runnable {
        private final Jisruptor<T> disruptor;
        private final EventHandler<T> handler;
        private final Sequence sequence;
        private long nextSequence = 0;

        public EventProcessor(Jisruptor<T> disruptor, EventHandler<T> handler, Sequence sequence) {
            this.disruptor = disruptor;
            this.handler = handler;
            this.sequence = sequence;
        }

        @Override
        public void run() {
            while (disruptor.isRunning()) {
                long availableSequence = disruptor.getCursor();
                
                if (nextSequence <= availableSequence) {
                    T event = disruptor.get(nextSequence);
                    handler.onEvent(event, nextSequence, nextSequence == availableSequence);
                    sequence.set(nextSequence);
                    nextSequence++;
                } else {
                    disruptor.waitStrategy.waitFor(nextSequence, disruptor.getCursor(), sequence);
                }
            }
        }
    }
}

// Temel interface'ler ve sınıflar
interface EventFactory<T> {
    T newInstance();
}

interface EventHandler<T> {
    void onEvent(T event, long sequence, boolean endOfBatch);
}

interface EventTranslator<T> {
    void translate(T event, long sequence);
}

interface WaitStrategy {
    long waitFor(long sequence, long cursor, Sequence dependentSequence);
    void signalAllWhenBlocking();
}

class SleepingWaitStrategy implements WaitStrategy {
    private static final int RETRIES = 200;
    private static final long SLEEP_NS = 100;

    @Override
    public long waitFor(long sequence, long cursor, Sequence dependentSequence) {
        long availableSequence;
        int counter = RETRIES;

        while ((availableSequence = cursor) < sequence) {
            counter = applyWaitMethod(counter);
        }

        return availableSequence;
    }

    private int applyWaitMethod(int counter) {
        if (counter > 100) {
            --counter;
        } else if (counter > 0) {
            --counter;
            Thread.yield();
        } else {
            try {
                Thread.sleep(0, (int) SLEEP_NS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return counter;
    }

    @Override
    public void signalAllWhenBlocking() {
        // Bu implementasyonda özel bir sinyal mekanizması yok
    }
}

class Sequence {
    private volatile long value = -1;

    public long get() {
        return value;
    }

    public void set(long value) {
        this.value = value;
    }

    public long incrementAndGet() {
        return ++value;
    }
}

// Test için kullanılan event ve handler
class ValueEvent {
    private long value;

    public long getValue() {
        return value;
    }

    public void setValue(long value) {
        this.value = value;
    }

    public static final EventFactory<ValueEvent> EVENT_FACTORY = ValueEvent::new;
}

class ValueEventHandler implements EventHandler<ValueEvent> {
    public static long count = 0;

    @Override
    public void onEvent(ValueEvent event, long sequence, boolean endOfBatch) {
        count++;
        // İşlem yapılabilir
        // System.out.println("Event: " + event.getValue() + " Sequence: " + sequence);
    }
}
