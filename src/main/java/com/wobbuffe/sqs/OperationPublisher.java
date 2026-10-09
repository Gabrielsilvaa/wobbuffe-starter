package com.wobbuffe.sqs;

import com.wobbuffe.core.OperationEnvelope;

public interface OperationPublisher {
    void publish(OperationEnvelope envelope);
}
