/*
 * Copyright 2022 IBM All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.hyperledger.fabric.client;

import java.util.Optional;

/**
 * A non-persistent Checkpointer implementation.
 * It can be used to checkpoint progress after successfully processing events, allowing eventing to be resumed from this point.
 */
public final class InMemoryCheckpointer implements Checkpointer {

    private long blockNumber;
    private String transactionId;

    @Override
    public void checkpointBlock(final long blockNumber) {
        checkpointTransaction(blockNumber + 1, null);
    }

    @Override
    public void checkpointTransaction(final long blockNumber, final String transactionId) {
        this.blockNumber = blockNumber;
        this.transactionId = transactionId;
    }

    @Override
    public void checkpointChaincodeEvent(final ChaincodeEvent event) {
        checkpointTransaction(event.getBlockNumber(), event.getTransactionId());
    }

    @Override
    public long getBlockNumber() {
        return blockNumber;
    }

    @Override
    public Optional<String> getTransactionId() {
        return Optional.ofNullable(transactionId);
    }
}
