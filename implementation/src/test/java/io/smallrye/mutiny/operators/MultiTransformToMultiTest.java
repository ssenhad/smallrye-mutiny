package io.smallrye.mutiny.operators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.reactivestreams.Subscriber;

import io.smallrye.mutiny.CompositeException;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.smallrye.mutiny.operators.multi.MultiFlatMapOp;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import io.smallrye.mutiny.operators.multi.processors.UnicastProcessor;
import io.smallrye.mutiny.test.AssertSubscriber;
import io.smallrye.mutiny.test.Mocks;

public class MultiTransformToMultiTest {

    @Test
    public void testMapShortcut() {
        Multi.createFrom().items(1, 2)
                .map(i -> i + 1)
                .subscribe().withSubscriber(AssertSubscriber.create(2))
                .assertCompletedSuccessfully()
                .assertReceived(2, 3);
    }

    @Test
    public void testConcatMapShortcut() {
        AssertSubscriber<Integer> subscriber = AssertSubscriber.create(Long.MAX_VALUE);

        Multi.createFrom().range(1, 4)
                .concatMap(i -> Multi.createFrom().items(i, i))
                .subscribe(subscriber);

        subscriber.assertReceived(1, 1, 2, 2, 3, 3).assertCompletedSuccessfully();
    }

    @Test
    public void testConcatMapShortcutWithEmpty() {
        AssertSubscriber<Integer> subscriber = AssertSubscriber.create(Long.MAX_VALUE);

        Multi.createFrom().range(1, 4)
                .concatMap(i -> Multi.createFrom().<Integer> empty())
                .subscribe(subscriber);

        subscriber.assertCompletedSuccessfully().assertHasNotReceivedAnyItem();
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testProduceMultiDeprecated() {
        AssertSubscriber<Integer> subscriber = AssertSubscriber.create(Long.MAX_VALUE);

        Multi.createFrom().range(1, 4)
                .onItem().produceMulti(i -> Multi.createFrom().items(i, i)).concatenate()
                .subscribe(subscriber);

        subscriber.assertReceived(1, 1, 2, 2, 3, 3).assertCompletedSuccessfully();
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testProducePublisherDeprecated() {
        AssertSubscriber<Integer> subscriber = AssertSubscriber.create(Long.MAX_VALUE);

        Multi.createFrom().range(1, 4)
                .onItem().producePublisher(i -> Multi.createFrom().items(i, i)).concatenate()
                .subscribe(subscriber);

        subscriber.assertReceived(1, 1, 2, 2, 3, 3).assertCompletedSuccessfully();
    }

    @Test
    @Timeout(60)
    public void testConcatMapWithLotsOfItems() {
        AssertSubscriber<Integer> subscriber = AssertSubscriber.create(Long.MAX_VALUE);

        Multi.createFrom().range(1, 100_001)
                .onItem()
                .transformToMulti(i -> Multi.createFrom().completionStage(CompletableFuture.supplyAsync(() -> i)))
                .concatenate()
                .subscribe(subscriber);

        subscriber
                .await()
                .assertCompletedSuccessfully();

        int current = 0;
        for (int next : subscriber.items()) {
            assertThat(next).isEqualTo(current + 1);
            current = next;
        }
    }

    @Test
    @Timeout(60)
    public void testConcatMapWithLotsOfItemsAndFailurePropagation() {
        AssertSubscriber<Integer> subscriber = AssertSubscriber.create(Long.MAX_VALUE);

        Multi.createFrom().range(1, 100_001)
                .onItem()
                .transformToMulti(i -> Multi.createFrom().completionStage(CompletableFuture.supplyAsync(() -> i)))
                .collectFailures().concatenate()
                .subscribe(subscriber);

        subscriber
                .await()
                .assertCompletedSuccessfully();

        int current = 0;
        for (int next : subscriber.items()) {
            assertThat(next).isEqualTo(current + 1);
            current = next;
        }
    }

    @Test
    @Timeout(60)
    public void testConcatMapWithLotsOfItemsAndFailuresAndFailurePropagation() {
        AssertSubscriber<Integer> subscriber = AssertSubscriber.create(Long.MAX_VALUE);

        Multi.createFrom().range(1, 100_001)
                .onItem().transformToMulti(
                        i -> Multi.createFrom().completionStage(CompletableFuture.supplyAsync(() -> {
                            if (i == 99000 || i == 100_000) {
                                throw new IllegalArgumentException("boom");
                            } else {
                                return i;
                            }
                        })))
                .collectFailures().concatenate()
                .subscribe(subscriber);

        subscriber
                .await()
                .assertHasFailedWith(CompositeException.class, "boom");

        assertThat(subscriber.items().size()).isEqualTo(100_000 - 2);
        int current = 0;
        for (int next : subscriber.items()) {
            assertThat(next).isGreaterThan(current);
            current = next;
        }
    }

    @Test
    @Timeout(60)
    public void testConcatMapWithLotsOfItemsAndFailuresWithoutFailurePropagation() {
        AssertSubscriber<Integer> subscriber = AssertSubscriber.create(Long.MAX_VALUE);

        Multi.createFrom().range(1, 100_001)
                .concatMap(
                        i -> Multi.createFrom().completionStage(CompletableFuture.supplyAsync(() -> {
                            if (i == 99000) {
                                throw new IllegalArgumentException("boom");
                            } else {
                                return i;
                            }
                        })))
                .subscribe(subscriber);

        subscriber
                .await()
                .assertHasFailedWith(IllegalArgumentException.class, "boom");

        assertThat(subscriber.items().size()).isEqualTo(99000 - 1);
        int current = 0;
        for (int next : subscriber.items()) {
            assertThat(next).isGreaterThan(current);
            current = next;
        }
    }

    @Test
    public void testConcatMapWithDelayOfFailure() {
        AssertSubscriber<Integer> subscriber = AssertSubscriber.create(Long.MAX_VALUE);

        Multi.createFrom().range(1, 4)
                .onItem().transformToMulti(i -> Multi.createFrom().items(i, i)).collectFailures().concatenate()
                .subscribe(subscriber);

        subscriber.assertReceived(1, 1, 2, 2, 3, 3).assertCompletedSuccessfully();
    }

    @Test
    public void testTransformToMultiWithConcatenationAndFailuresAndDelay() {
        AssertSubscriber<Integer> subscriber = AssertSubscriber.create(Long.MAX_VALUE);

        Multi.createFrom().range(1, 4)
                .onItem().transformToMulti(i -> {
                    if (i == 2) {
                        return Multi.createFrom().failure(new IOException("boom"));
                    } else {
                        return Multi.createFrom().items(i, i);
                    }
                }).collectFailures().concatenate()
                .subscribe(subscriber);

        subscriber
                .assertHasFailedWith(IOException.class, "boom")
                .assertReceived(1, 1, 3, 3);
    }

    @Test
    public void testTransformToMultiAndMergeUsingMultiFlatten() {
        AssertSubscriber<Integer> subscriber = AssertSubscriber.create(Long.MAX_VALUE);

        Multi.createFrom().range(1, 4)
                .onItem().transformToMulti(i -> {
                    Multi<Integer> m = Multi.createFrom()
                            .completionStage(() -> CompletableFuture.supplyAsync(() -> i));
                    return Multi.createBy().merging().streams(m, m);
                }).merge()
                .subscribe(subscriber);

        subscriber
                .await()
                .assertCompletedSuccessfully();
        assertThat(subscriber.items()).containsExactlyInAnyOrder(1, 1, 2, 2, 3, 3);
    }

    @Test
    public void testTransformToMultiAndConcatenateUsingMultiFlatten() {
        AssertSubscriber<Integer> subscriber = AssertSubscriber.create(Long.MAX_VALUE);

        Multi.createFrom().range(1, 4)
                .onItem().transformToMulti(i -> {
                    Multi<Integer> m = Multi.createFrom()
                            .completionStage(() -> CompletableFuture.supplyAsync(() -> i));
                    return Multi.createBy().merging().streams(m, m);
                }).concatenate()
                .subscribe(subscriber);

        subscriber
                .await()
                .assertCompletedSuccessfully();
        assertThat(subscriber.items()).containsExactly(1, 1, 2, 2, 3, 3);
    }

    @Test
    public void testTransformToMultiAndMerge() {
        AssertSubscriber<Integer> subscriber = AssertSubscriber.create(Long.MAX_VALUE);

        Multi.createFrom().range(1, 4)
                .onItem().transformToMultiAndMerge(i -> {
                    Multi<Integer> m = Multi.createFrom()
                            .completionStage(() -> CompletableFuture.supplyAsync(() -> i));
                    return Multi.createBy().merging().streams(m, m);
                })
                .subscribe(subscriber);

        subscriber
                .await()
                .assertCompletedSuccessfully();
        assertThat(subscriber.items()).containsExactlyInAnyOrder(1, 1, 2, 2, 3, 3);
    }

    @Test
    public void testTransformToMultiAndConcatenate() {
        AssertSubscriber<Integer> subscriber = AssertSubscriber.create(Long.MAX_VALUE);

        Multi.createFrom().range(1, 4)
                .onItem().transformToMultiAndConcatenate(i -> {
                    Multi<Integer> m = Multi.createFrom()
                            .completionStage(() -> CompletableFuture.supplyAsync(() -> i));
                    return Multi.createBy().merging().streams(m, m);
                })
                .subscribe(subscriber);

        subscriber
                .await()
                .assertCompletedSuccessfully();
        assertThat(subscriber.items()).containsExactlyInAnyOrder(1, 1, 2, 2, 3, 3);
    }

    @Test
    public void testFlatMapShortcut() {
        AssertSubscriber<Integer> subscriber = AssertSubscriber.create(Long.MAX_VALUE);

        Multi.createFrom().range(1, 4)
                .flatMap(i -> Multi.createFrom().items(i, i))
                .subscribe(subscriber);

        subscriber
                .await()
                .assertReceived(1, 1, 2, 2, 3, 3).assertCompletedSuccessfully();
    }

    @Test
    public void testThatFlatMapIsNotCalledOnUpstreamFailure() {
        AssertSubscriber<Integer> subscriber = AssertSubscriber.create(Long.MAX_VALUE);
        AtomicInteger count = new AtomicInteger();
        Multi.createFrom().<Integer> failure(new IOException("boom"))
                .flatMap(i -> {
                    count.incrementAndGet();
                    return Multi.createFrom().item(i);
                })
                .subscribe(subscriber);

        subscriber.assertHasFailedWith(IOException.class, "boom");
        assertThat(count).hasValue(0);
    }

    @Test
    public void testThatFlatMapIsOnlyCallOnItems() {
        AssertSubscriber<Integer> subscriber = AssertSubscriber.create(Long.MAX_VALUE);
        AtomicInteger count = new AtomicInteger();
        Multi.createFrom().<Integer> empty()
                .flatMap(i -> {
                    count.incrementAndGet();
                    return Multi.createFrom().item(i);
                })
                .subscribe(subscriber);

        subscriber.assertCompletedSuccessfully().assertHasNotReceivedAnyItem();
        assertThat(count).hasValue(0);
    }

    @Test
    public void testRegularFlatMapWithRequests() {
        AssertSubscriber<Integer> subscriber = AssertSubscriber.create(0);

        Multi.createFrom().range(1, 4)
                .flatMap(i -> Multi.createFrom().items(i, i))
                .subscribe(subscriber);

        subscriber
                .assertSubscribed()
                .assertHasNotReceivedAnyItem();

        subscriber.request(2)
                .run(() -> assertThat(subscriber.items()).hasSize(2))
                .request(2)
                .run(() -> assertThat(subscriber.items()).hasSize(4))
                .request(10)
                .run(() -> assertThat(subscriber.items()).hasSize(6))
                .assertCompletedSuccessfully();
        assertThat(subscriber.items()).contains(1, 1, 2, 2, 3, 3);
    }

    @Test
    public void testFlatMapWithMapperThrowingException() {
        AssertSubscriber<Integer> subscriber = AssertSubscriber.create(Long.MAX_VALUE);
        Multi.createFrom().range(1, 4)
                .<Integer> flatMap(i -> {
                    throw new IllegalArgumentException("boom");
                })
                .subscribe(subscriber);

        subscriber.assertHasFailedWith(IllegalArgumentException.class, "boom");
    }

    @Test
    public void testFlatMapWithMapperReturningNull() {
        AssertSubscriber<Integer> subscriber = AssertSubscriber.create(Long.MAX_VALUE);
        Multi.createFrom().range(1, 4)
                .<Integer> flatMap(i -> null)
                .subscribe(subscriber);

        subscriber.assertHasFailedWith(NullPointerException.class, "");
    }

    @Test
    public void testFlatMapWithMapperReturningNullInAMulti() {
        AssertSubscriber<Integer> subscriber = AssertSubscriber.create(Long.MAX_VALUE);
        Multi.createFrom().range(1, 4)
                .<Integer> flatMap(i -> Multi.createFrom().item(null))
                .subscribe(subscriber);

        subscriber.assertHasFailedWith(IllegalArgumentException.class, "supplier");
    }

    @Test
    public void testFlatMapWithMapperProducingFailure() {
        AssertSubscriber<Integer> subscriber = AssertSubscriber.create(Long.MAX_VALUE);
        AtomicInteger count = new AtomicInteger();
        Multi.createFrom().range(1, 4)
                .<Integer> flatMap(i -> Multi.createFrom().failure(new IOException("boom")))
                .subscribe(subscriber);

        subscriber.assertHasFailedWith(IOException.class, "boom");
        assertThat(count).hasValue(0);
    }

    @Test
    public void testFlatMapWithABitMoreResults() {
        AssertSubscriber<Integer> subscriber = AssertSubscriber.create(Long.MAX_VALUE);

        Multi.createFrom().range(1, 2001)
                .flatMap(i -> Multi.createFrom().items(i, i))
                .subscribe(subscriber);

        subscriber.assertCompletedSuccessfully();
        assertThat(subscriber.items()).hasSize(4000);
    }

    @Test
    public void testTransformToMultiWithConcurrency() {
        AssertSubscriber<Integer> subscriber = AssertSubscriber.create(Long.MAX_VALUE);

        Multi.createFrom().range(1, 10001)
                .onItem().transformToMulti(i -> Multi.createFrom().items(i, i)).merge(25)
                .subscribe(subscriber);

        subscriber.assertCompletedSuccessfully();
        assertThat(subscriber.items()).hasSize(20000);
    }

    @Test
    public void testTransformToMultiWithInvalidConcurrency() {
        assertThrows(IllegalArgumentException.class, () -> Multi.createFrom().range(1, 10001)
                .onItem().transformToMulti(i -> Multi.createFrom().items(i, i))
                .merge(-1));
    }

    @Test
    public void testTransformToMultiWithConcurrencyAndAsyncEmission() {
        AssertSubscriber<Integer> subscriber = AssertSubscriber.create(Long.MAX_VALUE);

        Multi.createFrom().range(1, 10001)
                .onItem()
                .transformToMulti(i -> Multi.createFrom().completionStage(CompletableFuture.supplyAsync(() -> i)))
                .withRequests(20)
                .merge(25)
                .subscribe(subscriber);

        subscriber.await().assertCompletedSuccessfully();
        assertThat(subscriber.items()).hasSize(10000);
    }

    @Test
    public void testTransformToMultiWithFailurePropagation() {
        AssertSubscriber<Integer> subscriber = AssertSubscriber.create(Long.MAX_VALUE);

        Multi.createFrom().range(1, 5)
                .onItem().transformToMulti(i -> {
                    if (i % 2 == 0) {
                        return Multi.createFrom().failure(new IOException("boom"));
                    } else {
                        return Multi.createFrom().item(i);
                    }
                })
                .collectFailures()
                .merge()
                .subscribe(subscriber);

        subscriber.assertReceived(1, 3).assertHasFailedWith(CompositeException.class, "boom");
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testProduceCompletionStageDeprecated() {
        List<Integer> list = Multi.createFrom().range(1, 4)
                .onItem().produceCompletionStage(i -> CompletableFuture.supplyAsync(() -> i + 1))
                .merge()
                .collectItems().asList().await().indefinitely();

        assertThat(list).hasSize(3).contains(2, 3, 4);
    }

    @Test
    public void testProduceCompletionStageAlternative() {
        List<Integer> list = Multi.createFrom().range(1, 4)
                .onItem()
                .transformToUni(i -> Uni.createFrom().completionStage(CompletableFuture.supplyAsync(() -> i + 1)))
                .merge()
                .collectItems().asList().await().indefinitely();

        assertThat(list).hasSize(3).contains(2, 3, 4);
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testProduceIterableDeprecated() {
        List<Integer> list = Multi.createFrom().range(1, 4)
                .onItem().produceIterable(i -> Arrays.asList(i, i + 1))
                .merge()
                .collectItems().asList().await().indefinitely();

        assertThat(list).hasSize(6).containsExactlyInAnyOrder(1, 2, 2, 3, 3, 4);
    }

    @Test
    public void testTransformToIterable() {
        List<Integer> list = Multi.createFrom().range(1, 4)
                .onItem().transformToIterable(i -> Arrays.asList(i, i + 1))
                .collectItems().asList().await().indefinitely();
        assertThat(list).hasSize(6).containsExactlyInAnyOrder(1, 2, 2, 3, 3, 4);
    }

    @Test
    public void testThatUpstreamFailureCancelledInnersAndIsPropagated() {
        UnicastProcessor<Integer> processor1 = UnicastProcessor.create();
        UnicastProcessor<Integer> processor2 = UnicastProcessor.create();

        AssertSubscriber<Integer> subscriber = processor1
                .flatMap(x -> processor2)
                .subscribe().withSubscriber(AssertSubscriber.create(10));

        processor1.onNext(1);
        assertTrue(processor2.hasSubscriber());
        processor1.onError(new IOException("boom"));
        assertFalse(processor2.hasSubscriber());
        subscriber.assertHasFailedWith(IOException.class, "boom");
    }

    @Test
    public void testThatUpstreamIsCancelledWhenInnerFails() {
        UnicastProcessor<Integer> processor1 = UnicastProcessor.create();
        UnicastProcessor<Integer> processor2 = UnicastProcessor.create();

        AssertSubscriber<Integer> subscriber = processor1
                .flatMap(x -> processor2)
                .subscribe().withSubscriber(AssertSubscriber.create(10));

        processor1.onNext(1);
        assertTrue(processor2.hasSubscriber());
        processor2.onError(new IOException("boom"));
        assertFalse(processor1.hasSubscriber());
        subscriber.assertHasFailedWith(IOException.class, "boom");
    }

    @SuppressWarnings("ConstantConditions")
    @Test
    public void testThatSubscriberCannotBeNull() {
        MultiFlatMapOp<Integer, Integer> op = new MultiFlatMapOp<>(
                Multi.createFrom().item(1),
                i -> Multi.createFrom().item(2),
                false, 4, 10);

        assertThatThrownBy(() -> op.subscribe(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    public void testThatInvalidRequestAreRejected() {
        MultiFlatMapOp<Integer, Integer> op = new MultiFlatMapOp<>(
                Multi.createFrom().item(1),
                i -> Multi.createFrom().item(2),
                false, 4, 10);
        AssertSubscriber<Integer> subscriber = AssertSubscriber.create();
        op.subscribe(subscriber);
        subscriber.request(-1);
        subscriber.assertHasFailedWith(IllegalArgumentException.class, "");
    }

    @Test
    public void testNormalTransformToIterable() {
        Subscriber<Object> subscriber = Mocks.subscriber();

        Integer[] inputs = { 2, 32, 512 };
        Multi.createFrom().items(inputs)
                .onItem().transformToIterable(i -> Arrays.asList(i * 2, i * 4, i * 8))
                .subscribe().withSubscriber(subscriber);

        for (int i : inputs) {
            verify(subscriber).onNext(i * 2);
            verify(subscriber).onNext(i * 4);
            verify(subscriber).onNext(i * 8);
        }
        verify(subscriber).onComplete();
        verify(subscriber, never()).onError(any(Throwable.class));
    }

    @Test
    public void testTransformToIterableWithExceptionInMapper() {
        Subscriber<Object> subscriber = Mocks.subscriber();

        Integer[] inputs = { 2, 32, 512 };
        Multi.createFrom().items(inputs)
                .onItem().transformToIterable(i -> {
                    if (i != 32) {
                        return Arrays.asList(i * 2, i * 4, i * 8);
                    } else {
                        throw new IllegalArgumentException("boom");
                    }
                })
                .subscribe().withSubscriber(subscriber);

        verify(subscriber).onNext(2 * 2);
        verify(subscriber).onNext(2 * 4);
        verify(subscriber).onNext(2 * 8);
        verify(subscriber, never()).onComplete();
        verify(subscriber).onError(any(IllegalArgumentException.class));
    }

    @Test
    public void testTransformToMultiWithMergeProducingFailingMulti() {
        Subscriber<Object> subscriber = Mocks.subscriber();

        Integer[] inputs = { 2, 32, 512 };
        Multi.createFrom().items(inputs)
                .onItem().transformToMultiAndMerge(i -> {
                    if (i != 32) {
                        return Multi.createFrom().items(i * 2, i * 4, i * 8);
                    } else {
                        return Multi.createFrom().failure(new IllegalArgumentException("boom"));
                    }
                })
                .subscribe().withSubscriber(subscriber);

        verify(subscriber).onNext(2 * 2);
        verify(subscriber).onNext(2 * 4);
        verify(subscriber).onNext(2 * 8);
        verify(subscriber, never()).onComplete();
        verify(subscriber).onError(any(IllegalArgumentException.class));
    }

    @Test
    public void testTransformToMultiWithMergeThrowingException() {
        Subscriber<Object> subscriber = Mocks.subscriber();

        Integer[] inputs = { 2, 32, 512 };
        Multi.createFrom().items(inputs)
                .onItem().transformToMultiAndMerge(i -> {
                    if (i != 32) {
                        return Multi.createFrom().items(i * 2, i * 4, i * 8);
                    } else {
                        throw new IllegalArgumentException("boom");
                    }
                })
                .subscribe().withSubscriber(subscriber);

        verify(subscriber).onNext(2 * 2);
        verify(subscriber).onNext(2 * 4);
        verify(subscriber).onNext(2 * 8);
        verify(subscriber, never()).onComplete();
        verify(subscriber).onError(any(IllegalArgumentException.class));
    }

    @Test
    public void testTransformToMultiWithConcatenateProducingFailingMulti() {
        Subscriber<Object> subscriber = Mocks.subscriber();

        Integer[] inputs = { 2, 32, 512 };
        Multi.createFrom().items(inputs)
                .onItem().transformToMultiAndConcatenate(i -> {
                    if (i != 32) {
                        return Multi.createFrom().items(i * 2, i * 4, i * 8);
                    } else {
                        return Multi.createFrom().failure(new IllegalArgumentException("boom"));
                    }
                })
                .subscribe().withSubscriber(subscriber);

        verify(subscriber).onNext(2 * 2);
        verify(subscriber).onNext(2 * 4);
        verify(subscriber).onNext(2 * 8);
        verify(subscriber, never()).onComplete();
        verify(subscriber).onError(any(IllegalArgumentException.class));
    }

    @Test
    public void testTransformToMultiWithConcatenateThrowingException() {
        Subscriber<Object> subscriber = Mocks.subscriber();

        Integer[] inputs = { 2, 32, 512 };
        Multi.createFrom().items(inputs)
                .onItem().transformToMultiAndConcatenate(i -> {
                    if (i != 32) {
                        return Multi.createFrom().items(i * 2, i * 4, i * 8);
                    } else {
                        throw new IllegalArgumentException("boom");
                    }
                })
                .subscribe().withSubscriber(subscriber);

        verify(subscriber).onNext(2 * 2);
        verify(subscriber).onNext(2 * 4);
        verify(subscriber).onNext(2 * 8);
        verify(subscriber, never()).onComplete();
        verify(subscriber).onError(any(IllegalArgumentException.class));
    }

    @Test
    public void testTransformToMultiWithMergeWithFailingUpstream() {
        Subscriber<Object> subscriber = Mocks.subscriber();

        Integer[] inputs = { 2, 32, 512 };

        Multi.createFrom().items(inputs)
                .onItem().transform(i -> {
                    if (i == 32) {
                        throw new IllegalArgumentException("boom");
                    } else {
                        return i;
                    }
                })
                .onItem().transformToMultiAndMerge(i -> Multi.createFrom().items(i * 2, i * 4, i * 8))
                .subscribe().withSubscriber(subscriber);

        verify(subscriber).onNext(2 * 2);
        verify(subscriber).onNext(2 * 4);
        verify(subscriber).onNext(2 * 8);
        verify(subscriber, never()).onComplete();
        verify(subscriber).onError(any(IllegalArgumentException.class));
    }

    @Test
    public void testTransformToMultiWithConcatenateWithFailingUpstream() {
        Subscriber<Object> subscriber = Mocks.subscriber();

        Integer[] inputs = { 2, 32, 512 };

        Multi.createFrom().items(inputs)
                .onItem().transform(i -> {
                    if (i == 32) {
                        throw new IllegalArgumentException("boom");
                    } else {
                        return i;
                    }
                })
                .onItem().transformToMultiAndConcatenate(i -> Multi.createFrom().items(i * 2, i * 4, i * 8))
                .subscribe().withSubscriber(subscriber);

        verify(subscriber).onNext(2 * 2);
        verify(subscriber).onNext(2 * 4);
        verify(subscriber).onNext(2 * 8);
        verify(subscriber, never()).onComplete();
        verify(subscriber).onError(any(IllegalArgumentException.class));
    }

    @Test
    public void testTransformToIterableWithFailingUpstream() {
        Subscriber<Object> subscriber = Mocks.subscriber();

        Integer[] inputs = { 2, 32, 512 };

        Multi.createFrom().items(inputs)
                .onItem().transform(i -> {
                    if (i == 32) {
                        throw new IllegalArgumentException("boom");
                    } else {
                        return i;
                    }
                })
                .onItem().transformToIterable(i -> Arrays.asList(i * 2, i * 4, i * 8))
                .subscribe().withSubscriber(subscriber);

        verify(subscriber).onNext(2 * 2);
        verify(subscriber).onNext(2 * 4);
        verify(subscriber).onNext(2 * 8);
        verify(subscriber, never()).onComplete();
        verify(subscriber).onError(any(IllegalArgumentException.class));
    }

    @RepeatedTest(10)
    public void testMaxConcurrency() {
        final int maxConcurrency = 4;
        final AtomicInteger subscriptionTracker = new AtomicInteger();
        Multi<Integer> multi = Multi.createFrom().range(0, 100)
                .onItem().transformToMulti(i -> Multi.createFrom().items(i + 1, i + 2, i + 3)
                        .onSubscribe().invoke(s -> {
                            int n = subscriptionTracker.getAndIncrement();
                            if (n >= maxConcurrency) {
                                Assertions.fail("Too many subscriptions: " + n);
                            }
                        }).onCompletion().invoke(() -> {
                            int n = subscriptionTracker.decrementAndGet();
                            if (n < 0) {
                                Assertions.fail("Too many un-subscriptions! " + n);
                            }
                        })
                        .runSubscriptionOn(Infrastructure.getDefaultExecutor()))
                .merge(maxConcurrency);

        AssertSubscriber<Object> subscriber = AssertSubscriber.create(Long.MAX_VALUE);
        multi.subscribe().withSubscriber(subscriber);

        subscriber.await()
                .assertCompletedSuccessfully();

        List<Integer> expected = new ArrayList<>();
        for (int i = 0; i <= 99; i++) {
            expected.add(i + 1);
            expected.add(i + 2);
            expected.add(i + 3);
        }
        assertThat(subscriber.items()).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    public void testMaxConcurrencyNormal() {
        final int maxConcurrency = 4;
        final AtomicInteger subscriptionTracker = new AtomicInteger();
        Multi<Integer> multi = Multi.createFrom().items(1, 2, 3)
                .onItem().transformToMulti(i -> Multi.createFrom().items(4, 5, 6)
                        .onSubscribe().invoke(s -> {
                            int n = subscriptionTracker.getAndIncrement();
                            if (n >= maxConcurrency) {
                                Assertions.fail("Too many subscriptions: " + n);
                            }
                        }).onCompletion().invoke(() -> {
                            int n = subscriptionTracker.decrementAndGet();
                            if (n < 0) {
                                Assertions.fail("Too many un-subscriptions! " + n);
                            }
                        })
                        .runSubscriptionOn(Infrastructure.getDefaultExecutor()))
                .merge(maxConcurrency);

        Subscriber<Integer> mock = Mocks.subscriber();
        AssertSubscriber<Integer> subscriber = AssertSubscriber.create(mock);
        multi.subscribe().withSubscriber(subscriber);

        subscriber.await()
                .assertCompletedSuccessfully();

        verify(mock, never()).onNext(1);
        verify(mock, never()).onNext(2);
        verify(mock, never()).onNext(3);
        verify(mock, times(3)).onNext(4);
        verify(mock, times(3)).onNext(5);
        verify(mock, times(3)).onNext(6);
        verify(mock).onComplete();
        verify(mock, never()).onError(any(Throwable.class));
    }

    @RepeatedTest(10)
    public void testThatConcurrencyDontMissItems() {
        int max = 10000;
        List<Integer> expected = Multi.createFrom().range(0, max).collectItems().asList().await().indefinitely();
        AssertSubscriber<Integer> subscriber = Multi.createFrom().range(0, max)
                .onItem()
                .transformToMulti(i -> Multi.createFrom().item(i).runSubscriptionOn(Infrastructure.getDefaultWorkerPool()))
                .merge(8)
                .subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));

        subscriber.await()
                .assertCompletedSuccessfully();

        assertThat(subscriber.items()).containsExactlyInAnyOrderElementsOf(expected);
    }

    @RepeatedTest(10)
    public void testThatConcatenateDontMissItemsAndPreserveOrder() {
        int max = 10000;
        List<Integer> expected = Multi.createFrom().range(0, max).collectItems().asList().await().indefinitely();
        AssertSubscriber<Integer> subscriber = Multi.createFrom().range(0, max)
                .onItem()
                .transformToMulti(i -> Multi.createFrom().item(i).runSubscriptionOn(Infrastructure.getDefaultWorkerPool()))
                .concatenate()
                .subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));

        subscriber.await()
                .assertCompletedSuccessfully();

        assertThat(subscriber.items()).containsExactlyElementsOf(expected);
    }

    @RepeatedTest(10)
    public void testFlatMapSimplePassThroughWithExecutor() {
        AssertSubscriber<Integer> subscriber = Multi.createFrom().range(1, 1001)
                .flatMap(i -> Multi.createFrom().item(i).runSubscriptionOn(Infrastructure.getDefaultExecutor()))
                .subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));
        subscriber.await()
                .assertCompletedSuccessfully();
        assertThat(subscriber.items()).hasSize(1000);
    }

    @RepeatedTest(10)
    public void testFlatMapSimplePassThroughWithoutExecutor() {
        AssertSubscriber<Integer> subscriber = Multi.createFrom().items(1, 2)
                .flatMap(i -> Multi.createFrom().items(i + 1, i + 2))
                .subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));

        subscriber.await()
                .assertCompletedSuccessfully();
        assertThat(subscriber.items()).hasSize(4);
    }

    @Test
    public void testProducingEmptyMultiWithMerge() {
        AssertSubscriber<Integer> subscriber = Multi.createFrom().range(0, 1024)
                .onItem().transformToMultiAndMerge(i -> {
                    if (i % 2 == 0) {
                        return Multi.createFrom().empty();
                    } else {
                        return Multi.createFrom().item(i);
                    }
                })
                .subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));

        subscriber.await()
                .assertCompletedSuccessfully();
        assertThat(subscriber.items()).hasSize(512);
    }

    @Test
    public void testProducingEmptyMultiWithConcatenate() {
        AssertSubscriber<Integer> subscriber = Multi.createFrom().range(0, 1024)
                .onItem().transformToMultiAndConcatenate(i -> {
                    if (i % 2 == 0) {
                        return Multi.createFrom().empty();
                    } else {
                        return Multi.createFrom().item(i);
                    }
                })
                .subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));

        subscriber.await()
                .assertCompletedSuccessfully();
        assertThat(subscriber.items()).hasSize(512);
    }

    @Test
    public void testProducingEmptyMultiWithIterable() {
        AssertSubscriber<Integer> subscriber = Multi.createFrom().range(0, 1024)
                .onItem().transformToIterable(i -> {
                    if (i % 2 == 0) {
                        return Collections.emptyList();
                    } else {
                        return Collections.singletonList(i);
                    }
                })
                .subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));

        subscriber.await()
                .assertCompletedSuccessfully();
        assertThat(subscriber.items()).hasSize(512);
    }

    @Test
    public void testProducingEmptyMultiWithMergeAndConcurrency() {
        int max = 1024 * 100;
        AssertSubscriber<Integer> subscriber = Multi.createFrom().range(0, max)
                .onItem().transformToMulti(i -> {
                    if (i % 2 == 0) {
                        return Multi.createFrom().empty();
                    } else {
                        return Multi.createFrom().item(i);
                    }
                })
                .merge(16)
                .runSubscriptionOn(Infrastructure.getDefaultExecutor())
                .subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));

        subscriber.await(Duration.ofSeconds(5))
                .assertCompletedSuccessfully();
        assertThat(subscriber.items()).hasSize(max / 2);
    }

    @Test
    public void testThatUpstreamIsCancelledIfMapperThrowsExceptionWithMerge() {
        AtomicInteger cancelled = new AtomicInteger();
        AssertSubscriber<Integer> subscriber = Multi.createFrom().items(1, 2, 3, 4)
                .onCancellation().invoke(cancelled::incrementAndGet)
                .onItem().transformToMultiAndMerge(i -> {
                    if (i % 2 == 0) {
                        throw new IllegalArgumentException("boom");
                    }
                    return Multi.createFrom().item(i);
                })
                .subscribe().withSubscriber(AssertSubscriber.create(10));

        subscriber.await()
                .assertHasFailedWith(IllegalArgumentException.class, "boom");
        assertThat(cancelled).hasValue(1);
    }

    @Test
    public void testThatUpstreamIsNotCancelledIfMapperProduceFailureWithMergeAndFailureCollection() {
        AtomicInteger cancelled = new AtomicInteger();
        AssertSubscriber<Integer> subscriber = Multi.createFrom().items(1, 2, 3, 4)
                .onCancellation().invoke(cancelled::incrementAndGet)
                .onItem().transformToMulti(i -> {
                    if (i % 2 == 0) {
                        return Multi.createFrom().failure(new IllegalArgumentException("boom"));
                    }
                    return Multi.createFrom().item(i);
                })
                .collectFailures()
                .merge()
                .subscribe().withSubscriber(AssertSubscriber.create(10));

        subscriber.await()
                .assertHasFailedWith(CompositeException.class, "boom");
        assertThat(cancelled).hasValue(0);
    }

    @Test
    public void testThatUpstreamIsCancelledIfMapperProduceFailureWithMergeAndNoFailureCollection() {
        AtomicInteger cancelled = new AtomicInteger();
        AssertSubscriber<Integer> subscriber = Multi.createFrom().items(1, 2, 3, 4)
                .onCancellation().invoke(cancelled::incrementAndGet)
                .onItem().transformToMulti(i -> {
                    if (i % 2 == 0) {
                        return Multi.createFrom().failure(new IllegalArgumentException("boom"));
                    }
                    return Multi.createFrom().item(i);
                })
                .merge()
                .subscribe().withSubscriber(AssertSubscriber.create(10));

        subscriber.await()
                .assertHasFailedWith(IllegalArgumentException.class, "boom");
        assertThat(cancelled).hasValue(1);
    }

    @Test
    public void testThatUpstreamIsNotCancelledIfMapperProduceFailureWithConcatenateAndFailureCollection() {
        AtomicInteger cancelled = new AtomicInteger();
        AssertSubscriber<Integer> subscriber = Multi.createFrom().items(1, 2, 3, 4)
                .onCancellation().invoke(cancelled::incrementAndGet)
                .onItem().transformToMulti(i -> {
                    if (i % 2 == 0) {
                        return Multi.createFrom().failure(new IllegalArgumentException("boom"));
                    }
                    return Multi.createFrom().item(i);
                })
                .collectFailures()
                .concatenate()
                .subscribe().withSubscriber(AssertSubscriber.create(10));

        subscriber.await()
                .assertHasFailedWith(CompositeException.class, "boom");
        assertThat(cancelled).hasValue(0);
    }

    @Test
    public void testThatUpstreamIsCancelledIfMapperProduceFailureWithConcatenateAndNoFailureCollection() {
        AtomicInteger cancelled = new AtomicInteger();
        AssertSubscriber<Integer> subscriber = Multi.createFrom().items(1, 2, 3, 4)
                .onCancellation().invoke(cancelled::incrementAndGet)
                .onItem().transformToMulti(i -> {
                    if (i % 2 == 0) {
                        return Multi.createFrom().failure(new IllegalArgumentException("boom"));
                    }
                    return Multi.createFrom().item(i);
                })
                .concatenate()
                .subscribe().withSubscriber(AssertSubscriber.create(10));

        subscriber.await()
                .assertHasFailedWith(IllegalArgumentException.class, "boom");
        assertThat(cancelled).hasValue(1);
    }

    @Test
    public void testInnerCompleteVsCancellationRace() throws InterruptedException {
        for (int i = 0; i < 1000; i++) {
            BroadcastProcessor<Integer> processor = BroadcastProcessor.create();
            AssertSubscriber<Integer> subscriber = Multi.createBy().merging().streams(processor).subscribe()
                    .withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));

            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);
            Runnable r1 = () -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                processor.onComplete();
                done.countDown();
            };
            Runnable r2 = () -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                subscriber.cancel();
                done.countDown();
            };

            List<Runnable> runnables = new ArrayList<>();
            runnables.add(r1);
            runnables.add(r2);

            Collections.shuffle(runnables);
            runnables.forEach(r -> new Thread(r).start());

            start.countDown();

            done.await();
        }
    }

    @Test
    public void testInnerItemVsCancellationRace() throws InterruptedException {
        for (int i = 0; i < 1000; i++) {
            BroadcastProcessor<Integer> processor = BroadcastProcessor.create();
            AssertSubscriber<Integer> subscriber = processor.flatMap(k -> Multi.createFrom().item(k))
                    .subscribe().withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);
            Runnable r1 = () -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                subscriber.request(1);
                subscriber.cancel();
                done.countDown();
            };
            Runnable r2 = () -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                processor.onNext(1);
                done.countDown();
            };

            List<Runnable> runnables = new ArrayList<>();
            runnables.add(r1);
            runnables.add(r2);

            Collections.shuffle(runnables);
            runnables.forEach(r -> new Thread(r).start());

            start.countDown();

            done.await();
        }
    }

    @Test
    public void testNoDeliveryAfterCompletion() {
        BroadcastProcessor<Integer> processor = BroadcastProcessor.create();
        Subscriber<Integer> subscriber = Mocks.subscriber();
        processor.onItem().transformToMulti(i -> Multi.createFrom().item(i + 1)).merge()
                .subscribe().withSubscriber(subscriber);

        processor.onNext(1);
        verify(subscriber).onNext(2);

        processor.onComplete();
        processor.onNext(2);
        processor.onComplete();

        verify(subscriber).onComplete();
        verify(subscriber, never()).onNext(3);
        verify(subscriber, never()).onError(any(Throwable.class));
    }

    @Test
    public void testNoDeliveryAfterFailure() {
        BroadcastProcessor<Integer> processor = BroadcastProcessor.create();
        Subscriber<Integer> subscriber = Mocks.subscriber();

        processor.onItem().transformToMulti(i -> Multi.createFrom().item(i + 1)).merge()
                .subscribe().withSubscriber(subscriber);

        processor.onNext(1);
        verify(subscriber).onNext(2);

        processor.onError(new IOException("boom"));
        processor.onNext(2);

        verify(subscriber).onError(any(IOException.class));
        verify(subscriber, never()).onComplete();
        verify(subscriber, never()).onNext(3);
    }

}
