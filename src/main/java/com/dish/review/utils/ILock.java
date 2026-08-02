package com.dish.review.utils;

public interface ILock {

    boolean tryLock(long timeoutSec);

    void unlock();
}
