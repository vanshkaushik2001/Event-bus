import exception.RetryLimitExceededException;
import models.*;
import retry.RetryAlgorithm;
import utils.KeyedExecutor;
import utils.Timer;

import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class EventBus {
    private final KeyedExecutor executor;
    private final Map<Topic, List<Event>> buses;
    private final Map<Topic, Set<Subscription>> subscriptions;
    private final Map<Topic, Map<EntityID, Index>> subscriberIndexes;
    private final Map<Topic, Map<EventID, Index>> eventIndex;
    private final Map<Topic, ConcurrentSkipListMap<Timestamp, Index>> timestampIndex;
    private final RetryAlgorithm<Event, Void> retryAlgorithm;
    private final EventBus deadLetterQueue;
    private final Timer timer;

    public EventBus(final int threads,
                    final RetryAlgorithm<Event, Void> retryAlgorithm,
                    final EventBus deadLetterQueue,
                    final Timer timer) {
        this.retryAlgorithm = retryAlgorithm;
        this.deadLetterQueue = deadLetterQueue;
        this.timer = timer;
        this.buses = new ConcurrentHashMap<>();
        this.executor = new KeyedExecutor(threads);
        this.subscriptions = new ConcurrentHashMap<>();
        this.subscriberIndexes = new ConcurrentHashMap<>();
        this.timestampIndex = new ConcurrentHashMap<>();
        this.eventIndex = new ConcurrentHashMap<>();
    }

    public CompletionStage<Void> publish(Topic topic, Event event) {
        return executor.submit(topic.getName(), () -> addEventToBus(topic, event));
    }

    private void addEventToBus(Topic topic, Event event) {
        final Index currentIndex = new Index(buses.get(topic).size());
        timestampIndex.get(topic).put(event.getTimestamp(), currentIndex);
        eventIndex.get(topic).put(event.getId(), currentIndex);
        buses.get(topic).add(event);
        subscriptions.getOrDefault(topic, Collections.newSetFromMap(new ConcurrentHashMap<>()))
                .stream()
                .filter(subscription -> SubscriptionType.PUSH.equals(subscription.getType()))
                .forEach(subscription -> push(event, subscription));
    }

    public CompletionStage<Event> poll(Topic topic, EntityID subscriber) {
        return executor.get(topic.getName() + subscriber.getId(), () -> {
            final Index index = subscriberIndexes.get(topic).get(subscriber);
            try {
                final Event event = buses.get(topic).get(index.getVal());
                subscriberIndexes.get(topic).put(subscriber, index.increment());
                return event;
            } catch (IndexOutOfBoundsException exception) {
                return null;
            }
        });
    }

    private void push(Event event, Subscription subscription) {
        executor.submit(subscription.getTopicId().getName() + subscription.getSubscriberId(),
                () -> {
                    try {
                        retryAlgorithm.attempt(subscription.handler(), event, 0);
                    } catch (RetryLimitExceededException e) {
                        if (deadLetterQueue != null) {
                            deadLetterQueue.publish(subscription.getTopicId(),
                                    new FailureEvent(event, e, timer.getTime()));
                        } else {
                            e.printStackTrace();
                        }
                    }
                });
    }

    public void registerTopic(Topic topic) {
        buses.put(topic, new CopyOnWriteArrayList<>());
        subscriptions.put(topic, Collections.newSetFromMap(new ConcurrentHashMap<>()));
        subscriberIndexes.put(topic, new ConcurrentHashMap<>());
        timestampIndex.put(topic, new ConcurrentSkipListMap<>());
        eventIndex.put(topic, new ConcurrentHashMap<>());
    }

    public CompletionStage<Void> subscribe(final Subscription subscription) {
        return executor.submit(subscription.getTopicId().getName(), () -> {
            final Topic topicId = subscription.getTopicId();
            subscriptions.get(topicId).add(subscription);
            subscriberIndexes.get(topicId).put(subscription.getSubscriberId(),
                    new Index(buses.get(topicId).size()));
        });
    }

    public CompletionStage<Void> setIndexAfterTimestamp(Topic topic, EntityID subscriber, Timestamp timestamp) {
        return executor.submit(topic.getName() + subscriber.getId(), () -> {
            final Map.Entry<Timestamp, Index> entry = timestampIndex.get(topic).higherEntry(timestamp);
            if (entry == null) {
                subscriberIndexes.get(topic).put(subscriber, new Index(buses.get(topic).size()));
            } else {
                final Index indexLessThanEquals = entry.getValue();
                subscriberIndexes.get(topic).put(subscriber, indexLessThanEquals);
            }
        });
    }

    public CompletionStage<Void> setIndexAfterEvent(Topic topic, EntityID subscriber, EventID eventId) {
        return executor.submit(topic.getName() + subscriber.getId(), () -> {
            final Index eIndex = eventIndex.get(topic).get(eventId);
            subscriberIndexes.get(topic).put(subscriber, eIndex.increment());
        });
    }

    public CompletionStage<Event> getEvent(Topic topic, EventID eventId) {
        return executor.get(topic.getName(), () -> {
            Index index = eventIndex.get(topic).get(eventId);
            return buses.get(topic).get(index.getVal());
        });
    }
}


