/*
 * Copyright 2021 IBM All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.hyperledger.fabric.client;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedTransferQueue;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.google.protobuf.InvalidProtocolBufferException;
import io.grpc.Channel;
import io.grpc.Context;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.AbstractStub;
import io.grpc.stub.StreamObserver;
import org.hyperledger.fabric.protos.common.Common;
import org.hyperledger.fabric.protos.gateway.ChaincodeEventsResponse;
import org.hyperledger.fabric.protos.gateway.CommitStatusRequest;
import org.hyperledger.fabric.protos.gateway.CommitStatusResponse;
import org.hyperledger.fabric.protos.gateway.EndorseRequest;
import org.hyperledger.fabric.protos.gateway.EndorseResponse;
import org.hyperledger.fabric.protos.gateway.EvaluateRequest;
import org.hyperledger.fabric.protos.gateway.EvaluateResponse;
import org.hyperledger.fabric.protos.gateway.GatewayGrpc;
import org.hyperledger.fabric.protos.gateway.SignedChaincodeEventsRequest;
import org.hyperledger.fabric.protos.gateway.SignedCommitStatusRequest;
import org.hyperledger.fabric.protos.gateway.SubmitRequest;
import org.hyperledger.fabric.protos.gateway.SubmitResponse;
import org.hyperledger.fabric.protos.peer.DeliverGrpc;
import org.hyperledger.fabric.protos.peer.EventsPackage;

final class GatewayClient {
    private final GatewayGrpc.GatewayBlockingStub gatewayBlockingStub;
    private final GatewayGrpc.GatewayStub gatewayAsyncStub;
    private final DeliverGrpc.DeliverStub deliverAsyncStub;
    private final CallOptions defaultOptions;

    GatewayClient(final Channel channel, final CallOptions defaultOptions) {
        GatewayUtils.requireNonNullArgument(channel, "No connection details supplied");
        GatewayUtils.requireNonNullArgument(defaultOptions, "defaultOptions");

        this.gatewayBlockingStub = GatewayGrpc.newBlockingStub(channel);
        this.gatewayAsyncStub = GatewayGrpc.newStub(channel);
        this.deliverAsyncStub = DeliverGrpc.newStub(channel);
        this.defaultOptions = defaultOptions;
    }

    public EvaluateResponse evaluate(final EvaluateRequest request, final CallOption... options) throws GatewayException {
        GatewayGrpc.GatewayBlockingStub stub = applyOptions(gatewayBlockingStub, defaultOptions.getEvaluate(options));
        try {
            return stub.evaluate(request);
        } catch (StatusRuntimeException e) {
            throw new GatewayException(e);
        }
    }

    public CompletableFuture<EvaluateResponse> evaluateNonBlocking(final EvaluateRequest request, final CallOption... options) {
        GatewayGrpc.GatewayStub stub = applyOptions(gatewayAsyncStub, defaultOptions.getEvaluate(options));
        return wrapNonBlocking(stub::evaluate, request, GatewayException::new);
    }

    public EndorseResponse endorse(final EndorseRequest request, final CallOption... options) throws EndorseException {
        GatewayGrpc.GatewayBlockingStub stub = applyOptions(gatewayBlockingStub, defaultOptions.getEndorse(options));
        try {
            return stub.endorse(request);
        } catch (StatusRuntimeException e) {
            throw new EndorseException(request.getTransactionId(), e);
        }
    }

    public CompletableFuture<EndorseResponse> endorseNonBlocking(final EndorseRequest request, final CallOption... options) {
        GatewayGrpc.GatewayStub stub = applyOptions(gatewayAsyncStub, defaultOptions.getEvaluate(options));
        return wrapNonBlocking(stub::endorse, request, e -> new EndorseException(request.getTransactionId(), e));
    }

    public SubmitResponse submit(final SubmitRequest request, final CallOption... options) throws SubmitException {
        GatewayGrpc.GatewayBlockingStub stub = applyOptions(gatewayBlockingStub, defaultOptions.getSubmit(options));
        try {
            return stub.submit(request);
        } catch (StatusRuntimeException e) {
            throw new SubmitException(request.getTransactionId(), e);
        }
    }

    public CompletableFuture<SubmitResponse> submitNonBlocking(final SubmitRequest request, final CallOption... options) {
        GatewayGrpc.GatewayStub stub = applyOptions(gatewayAsyncStub, defaultOptions.getEvaluate(options));
        return wrapNonBlocking(stub::submit, request, e -> new SubmitException(request.getTransactionId(), e));
    }

    public CommitStatusResponse commitStatus(final SignedCommitStatusRequest request, final CallOption... options) throws CommitStatusException {
        GatewayGrpc.GatewayBlockingStub stub = applyOptions(gatewayBlockingStub, defaultOptions.getCommitStatus(options));
        try {
            return stub.commitStatus(request);
        } catch (StatusRuntimeException e) {
            try {
                CommitStatusRequest req = CommitStatusRequest.parseFrom(request.getRequest());
                throw new CommitStatusException(req.getTransactionId(), e);
            } catch (InvalidProtocolBufferException protoErr) {
                // Should never happen
                CommitStatusException commitErr = new CommitStatusException("", e);
                commitErr.addSuppressed(protoErr);
                throw commitErr;
            }
        }
    }

    public CompletableFuture<CommitStatusResponse> commitStatusNonBlocking(final SignedCommitStatusRequest request, final CallOption... options) {
        GatewayGrpc.GatewayStub stub = applyOptions(gatewayAsyncStub, defaultOptions.getEvaluate(options));
        return wrapNonBlocking(stub::commitStatus, request, e -> {
                try {
                    CommitStatusRequest req = CommitStatusRequest.parseFrom(request.getRequest());
                    return new CommitStatusException(req.getTransactionId(), e);
                } catch (InvalidProtocolBufferException protoErr) {
                    // Should never happen
                    CommitStatusException commitErr = new CommitStatusException("", e);
                    commitErr.addSuppressed(protoErr);
                    return commitErr;
                }
            });
    }

    public CloseableIterator<ChaincodeEventsResponse> chaincodeEvents(final SignedChaincodeEventsRequest request, final CallOption... options) {
        GatewayGrpc.GatewayBlockingStub stub = applyOptions(gatewayBlockingStub, defaultOptions.getChaincodeEvents(options));
        return invokeServerStreamingCall(() -> stub.chaincodeEvents(request));
    }

    public CloseableIterator<EventsPackage.DeliverResponse> blockEvents(final Common.Envelope request, final CallOption... options) {
        DeliverGrpc.DeliverStub stub = applyOptions(deliverAsyncStub, defaultOptions.getBlockEvents(options));
        return invokeDuplexStreamingCall(stub::deliver, request);
    }

    public CloseableIterator<EventsPackage.DeliverResponse> filteredBlockEvents(final Common.Envelope request, final CallOption... options) {
        DeliverGrpc.DeliverStub stub = applyOptions(deliverAsyncStub, defaultOptions.getFilteredBlockEvents(options));
        return invokeDuplexStreamingCall(stub::deliverFiltered, request);
    }

    public CloseableIterator<EventsPackage.DeliverResponse> blockAndPrivateDataEvents(final Common.Envelope request, final CallOption... options) {
        DeliverGrpc.DeliverStub stub = applyOptions(deliverAsyncStub, defaultOptions.getBlockAndPrivateDataEvents(options));
        return invokeDuplexStreamingCall(stub::deliverWithPrivateData, request);
    }

    private static <T extends AbstractStub<T>> T applyOptions(final T stub, final List<CallOption> options) {
        T result = stub;
        for (CallOption option : options) {
            result = option.apply(result);
        }
        return result;
    }

    private abstract static class CompletableObserverFuture<T> extends CompletableFuture<T> {
        private final StreamObserver<T> observer;
        private CompletableObserverFuture() {
            this.observer = new StreamObserver<T>() {
                @Override
                public void onNext(final T response) {
                    if (isCancelled()) {
                        return;
                    }
                    complete(response);
                }

                @Override
                public void onError(final Throwable throwable) {
                    if (isCancelled()) {
                       return;
                    }
                    if (throwable instanceof StatusRuntimeException) {
                        Throwable t = onStatusRuntimeException((StatusRuntimeException) throwable);
                        completeExceptionally(t);
                    } else {
                        completeExceptionally(throwable);
                    }
                }

                @Override
                public void onCompleted() {
                    // DO NOTHING
                }
            };
        }

        public StreamObserver<T> getObserver() {
            return observer;
        }

        public abstract Throwable onStatusRuntimeException(StatusRuntimeException e);
    }

    private static <Response, Request> CompletableFuture<Response> wrapNonBlocking(
        final BiConsumer<Request, StreamObserver<Response>> call,
        final Request request,
        final Function<StatusRuntimeException, Throwable> mapStatusError
    ) {
        CompletableObserverFuture<Response> future = new CompletableObserverFuture<Response>() {
            @Override
            public Throwable onStatusRuntimeException(final StatusRuntimeException e) {
                return mapStatusError.apply(e);
            }
        };
        call.accept(request, future.getObserver());
        return future;
    }

    private <Response> CloseableIterator<Response> invokeServerStreamingCall(final Supplier<Iterator<Response>> call) {
        Context.CancellableContext context = Context.current().withCancellation();
        return invokeStreamingCall(context, call);
    }

    private <Response> CloseableIterator<Response> invokeStreamingCall(
            final Context.CancellableContext context,
            final Supplier<Iterator<Response>> call
    ) {
        try {
            Iterator<Response> iterator = context.wrap(call::get).call();
            return new ResponseIterator<>(context, iterator);
        } catch (StatusRuntimeException e) {
            context.cancel(e);
            throw new GatewayRuntimeException(e);
        } catch (RuntimeException e) {
            context.cancel(e);
            throw e;
        } catch (Exception e) {
            // Should never happen calling a Supplier
            context.cancel(e);
            throw new RuntimeException(e);
        }
    }

    private static final class ResponseIterator<T> implements CloseableIterator<T> {
        private final Context.CancellableContext context;
        private final Iterator<T> iterator;

        ResponseIterator(final Context.CancellableContext context, final Iterator<T> iterator) {
            this.context = context;
            this.iterator = iterator;
        }

        @Override
        public void close() {
            context.close();
        }

        @Override
        public boolean hasNext() {
            try {
                return iterator.hasNext();
            } catch (StatusRuntimeException e) {
                throw new GatewayRuntimeException(e);
            }
        }

        @Override
        public T next() {
            try {
                return iterator.next();
            } catch (StatusRuntimeException e) {
                throw new GatewayRuntimeException(e);
            }
        }
    }

    private <Request, Response> CloseableIterator<Response> invokeDuplexStreamingCall(
            final Function<StreamObserver<Response>, StreamObserver<Request>> call,
            final Request request
    ) {
        ResponseObserver<Response> responseObserver = new ResponseObserver<>();

        Context.CancellableContext context = Context.current().withCancellation();
        // Complete response observer synchronously if client cancels the context
        context.addListener(context1 -> responseObserver.onCompleted(), Runnable::run);

        return invokeStreamingCall(context, () -> {
            StreamObserver<Request> requestObserver = call.apply(responseObserver);
            requestObserver.onNext(request);
            return responseObserver;
        });
    }

    private static final class ResponseObserver<T> implements StreamObserver<T>, Iterator<T> {
        private final LinkedTransferQueue<Supplier<T>> queue = new LinkedTransferQueue<>();
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private Supplier<T> next;

        @Override
        public void onNext(final T response) {
            Future<?> future = executor.submit(() -> transfer(response));
            try {
                future.get();
            } catch (CancellationException | InterruptedException ignored) {
                // Ignore cancellation
            } catch (ExecutionException e) {
                // Should never happen
                throw new RuntimeException(e);
            }
        }

        private void transfer(final T response) {
            try {
                queue.transfer(() -> response);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt(); // Preserve interrupt status
            }
        }

        @Override
        public void onError(final Throwable t) {
            final StatusRuntimeException err;
            if (t instanceof StatusRuntimeException) {
                err = (StatusRuntimeException) t;
            } else {
                err = io.grpc.Status.fromThrowable(t).asRuntimeException();
            }

            queue.put(() -> {
                throw err;
            });
        }

        @Override
        public void onCompleted() {
            queue.put(() -> null); // Queue close marker to ensure consumers are not blocked

            List<Runnable> liveTasks = executor.shutdownNow().stream()
                    .filter(waitingTask -> {
                        if (!(waitingTask instanceof Future)) {
                            return true;
                        }

                        Future<?> future = (Future<?>) waitingTask;
                        future.cancel(true);
                        return !future.isCancelled();
                    })
                    .collect(Collectors.toList());

            if (!liveTasks.isEmpty()) {
                throw new IllegalStateException("Failed to cancel tasks: " + liveTasks);
            }
        }

        @Override
        public boolean hasNext() {
            return readNext().get() != null;
        }

        @Override
        public T next() {
            T result = readNext().get();
            if (result == null) {
                throw new NoSuchElementException();
            }

            next = null;
            return result;
        }

        private Supplier<T> readNext() {
            if (next == null) {
                try {
                    next = queue.take();
                } catch (InterruptedException e) {
                    throw new NoSuchElementException();
                }
            }

            return next;
        }
    }
}
