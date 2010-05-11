/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "rsLocklessFifo.h"

using namespace android;


LocklessCommandFifo::LocklessCommandFifo()
{
}

LocklessCommandFifo::~LocklessCommandFifo()
{
    if (!mInShutdown) {
        shutdown();
    }
    free(mBuffer);
}

void LocklessCommandFifo::shutdown()
{
    mInShutdown = true;
    mSignalToWorker.set();
}

bool LocklessCommandFifo::init(uint32_t sizeInBytes)
{
    // Add room for a buffer reset command
    mBuffer = static_cast<uint8_t *>(malloc(sizeInBytes + 4));
    if (!mBuffer) {
        LOGE("LocklessFifo allocation failure");
        return false;
    }

    if (!mSignalToControl.init() || !mSignalToWorker.init()) {
        LOGE("Signal setup failed");
        free(mBuffer);
        return false;
    }

    mInShutdown = false;
    mSize = sizeInBytes;
    mPut = mBuffer;
    mGet = mBuffer;
    mEnd = mBuffer + (sizeInBytes) - 1;
    //dumpState("init");
    return true;
}

uint32_t LocklessCommandFifo::getFreeSpace() const
{
    int32_t freeSpace = 0;
    //dumpState("getFreeSpace");

    if (mPut >= mGet) {
        freeSpace = mEnd - mPut;
    } else {
        freeSpace = mGet - mPut;
    }

    if (freeSpace < 0) {
        freeSpace = 0;
    }
    return freeSpace;
}

bool LocklessCommandFifo::isEmpty() const
{
    return mPut == mGet;
}


void * LocklessCommandFifo::reserve(uint32_t sizeInBytes)
{
    // Add space for command header and loop token;
    sizeInBytes += 8;

    //dumpState("reserve");
    if (getFreeSpace() < sizeInBytes) {
        makeSpace(sizeInBytes);
    }

    return mPut + 4;
}

void LocklessCommandFifo::commit(uint32_t command, uint32_t sizeInBytes)
{
    if (mInShutdown) {
        return;
    }
    //dumpState("commit 1");
    reinterpret_cast<uint16_t *>(mPut)[0] = command;
    reinterpret_cast<uint16_t *>(mPut)[1] = sizeInBytes;
    mPut += ((sizeInBytes + 3) & ~3) + 4;
    //dumpState("commit 2");
    mSignalToWorker.set();
}

void LocklessCommandFifo::commitSync(uint32_t command, uint32_t sizeInBytes)
{
    if (mInShutdown) {
        return;
    }
    commit(command, sizeInBytes);
    flush();
}

void LocklessCommandFifo::flush()
{
    //dumpState("flush 1");
    while(mPut != mGet) {
        mSignalToControl.wait();
    }
    //dumpState("flush 2");
}

const void * LocklessCommandFifo::get(uint32_t *command, uint32_t *bytesData)
{
    while(1) {
        //dumpState("get");
        while(isEmpty() && !mInShutdown) {
            mSignalToControl.set();
            mSignalToWorker.wait();
        }

        if (mInShutdown) {
            *command = 0;
            *bytesData = 0;
            return 0;
        }

        *command = reinterpret_cast<const uint16_t *>(mGet)[0];
        *bytesData = reinterpret_cast<const uint16_t *>(mGet)[1];
        if (*command) {
            // non-zero command is valid
            return mGet+4;
        }

        // zero command means reset to beginning.
        mGet = mBuffer;
    }
}

void LocklessCommandFifo::next()
{
    uint32_t bytes = reinterpret_cast<const uint16_t *>(mGet)[1];
    mGet += ((bytes + 3) & ~3) + 4;
    if (isEmpty()) {
        mSignalToControl.set();
    }
    //dumpState("next");
}

void LocklessCommandFifo::makeSpace(uint32_t bytes)
{
    //dumpState("make space");
    if ((mPut+bytes) > mEnd) {
        // Need to loop regardless of where get is.
        while((mGet > mPut) && (mBuffer+4 >= mGet)) {
            usleep(100);
        }

        // Toss in a reset then the normal wait for space will do the rest.
        reinterpret_cast<uint16_t *>(mPut)[0] = 0;
        reinterpret_cast<uint16_t *>(mPut)[1] = 0;
        mPut = mBuffer;
    }

    // it will fit here so we just need to wait for space.
    while(getFreeSpace() < bytes) {
        usleep(100);
    }

}

void LocklessCommandFifo::dumpState(const char *s) const
{
    LOGV("%s  put %p, get %p,  buf %p,  end %p", s, mPut, mGet, mBuffer, mEnd);
}

LocklessCommandFifo::Signal::Signal()
{
    mSet = true;
}

LocklessCommandFifo::Signal::~Signal()
{
    pthread_mutex_destroy(&mMutex);
    pthread_cond_destroy(&mCondition);
}

bool LocklessCommandFifo::Signal::init()
{
    int status = pthread_mutex_init(&mMutex, NULL);
    if (status) {
        LOGE("LocklessFifo mutex init failure");
        return false;
    }

    status = pthread_cond_init(&mCondition, NULL);
    if (status) {
        LOGE("LocklessFifo condition init failure");
        pthread_mutex_destroy(&mMutex);
        return false;
    }

    return true;
}

void LocklessCommandFifo::Signal::set()
{
    int status;

    status = pthread_mutex_lock(&mMutex);
    if (status) {
        LOGE("LocklessCommandFifo: error %i locking for set condition.", status);
        return;
    }

    mSet = true;

    status = pthread_cond_signal(&mCondition);
    if (status) {
        LOGE("LocklessCommandFifo: error %i on set condition.", status);
    }

    status = pthread_mutex_unlock(&mMutex);
    if (status) {
        LOGE("LocklessCommandFifo: error %i unlocking for set condition.", status);
    }
}

void LocklessCommandFifo::Signal::wait()
{
    int status;

    status = pthread_mutex_lock(&mMutex);
    if (status) {
        LOGE("LocklessCommandFifo: error %i locking for condition.", status);
        return;
    }

    if (!mSet) {
        status = pthread_cond_wait(&mCondition, &mMutex);
        if (status) {
            LOGE("LocklessCommandFifo: error %i waiting on condition.", status);
        }
    }
    mSet = false;

    status = pthread_mutex_unlock(&mMutex);
    if (status) {
        LOGE("LocklessCommandFifo: error %i unlocking for condition.", status);
    }
}

