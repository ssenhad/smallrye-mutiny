package io.smallrye.mutiny.operators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.groups.MultiRetry;
import io.smallrye.mutiny.test.AssertSubscriber;

public class MultiOnFailureRetryTest {

    private AtomicInteger numberOfSubscriptions;
    private Multi<Integer> failing;

    @BeforeEach
    public void init() {
        numberOfSubscriptions = new AtomicInteger();
        failing = Multi.createFrom()
                .<Integer> emitter(emitter -> emitter.emit(1).emit(2).emit(3).fail(new IOException("boom")))
                .onSubscribe().invoke(s -> numberOfSubscriptions.incrementAndGet());
    }

    @Test
    public void testThatUpstreamCannotBeNull() {
        assertThrows(IllegalArgumentException.class, () -> new MultiRetry<>(null));
    }

    @Test
    public void testThatTheNumberOfAttemptMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> Multi.createFrom().nothing()
                .onFailure().retry().atMost(-1));
    }

    @Test
    public void testThatTheNumberOfAttemptMustBePositive2() {
        assertThrows(IllegalArgumentException.class, () -> Multi.createFrom().nothing()
                .onFailure().retry().atMost(0));
    }

    @Test
    public void testNoRetryOnNoFailure() {
        AssertSubscriber<Integer> subscriber = AssertSubscriber.create(5);

        Multi.createFrom().range(1, 4)
                .onFailure().retry().atMost(5)
                .subscribe().withSubscriber(subscriber);

        subscriber
                .assertReceived(1, 2, 3)
                .assertCompletedSuccessfully();
    }

    @Test
    public void testWithASingleRetry() {
        AssertSubscriber<Integer> subscriber = AssertSubscriber.create(10);

        failing
                .onFailure().retry().atMost(1)
                .subscribe().withSubscriber(subscriber);

        subscriber
                .assertSubscribed()
                .assertHasFailedWith(IOException.class, "boom")
                .assertReceived(1, 2, 3, 1, 2, 3);

        assertThat(numberOfSubscriptions).hasValue(2);
    }

    @Test
    public void testWithASingleRetryAndRequests() {
        AssertSubscriber<Integer> subscriber = AssertSubscriber.create(0);

        failing
                .onFailure().retry().atMost(1)
                .subscribe().withSubscriber(subscriber);

        subscriber
                .assertSubscribed()
                .assertHasNotReceivedAnyItem()
                .request(4)
                .assertReceived(1, 2, 3, 1)
                .assertHasNotFailed()
                .request(2)
                .assertHasFailedWith(IOException.class, "boom")
                .assertReceived(1, 2, 3, 1, 2, 3);

        assertThat(numberOfSubscriptions).hasValue(2);
    }

    @Test
    public void testRetryIndefinitely() {
        AssertSubscriber<Integer> subscriber = AssertSubscriber.create(20);

        failing.onFailure().retry().indefinitely()
                .subscribe().withSubscriber(subscriber);

        await().until(() -> subscriber.items().size() > 10);
        subscriber.cancel();

        subscriber.assertNotTerminated();
    }

    @Test
    public void testWithRetryingGoingBackToSuccess() {
        AtomicInteger count = new AtomicInteger();

        Multi.createFrom().items(1, 2, 3, 4)
                .onItem().invoke(i -> {
                    if (count.getAndIncrement() < 2) {
                        throw new RuntimeException("boom");
                    }
                })
                .onFailure().retry().atMost(2)
                .subscribe().withSubscriber(AssertSubscriber.create(10))
                .assertCompletedSuccessfully()
                .assertReceived(1, 2, 3, 4);
    }

    @Test
    public void testThatYouCannotUseWhenIfBackoffIsConfigured() {
        assertThrows(IllegalArgumentException.class, () -> Multi.createFrom().item("hello")
                .onFailure().retry().withBackOff(Duration.ofSeconds(1)).when(t -> Multi.createFrom().item(t)));
    }

    @Test
    public void testThatYouCannotUseUntilIfBackoffIsConfigured() {
        assertThrows(IllegalArgumentException.class, () -> Multi.createFrom().item("hello")
                .onFailure().retry().withBackOff(Duration.ofSeconds(1)).until(t -> true));
    }

    @Test
    public void testJitterValidation() {
        assertThrows(IllegalArgumentException.class, () -> Multi.createFrom().item("hello")
                .onFailure().retry().withJitter(2));
    }

}
