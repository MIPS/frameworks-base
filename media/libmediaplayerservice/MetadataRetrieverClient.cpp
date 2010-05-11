/*
**
** Copyright (C) 2008 The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

//#define LOG_NDEBUG 0
#define LOG_TAG "MetadataRetrieverClient"
#include <utils/Log.h>

#include <sys/types.h>
#include <sys/stat.h>
#include <sys/resource.h>
#include <dirent.h>
#include <unistd.h>

#include <string.h>
#include <cutils/atomic.h>
#include <binder/MemoryDealer.h>
#include <android_runtime/ActivityManager.h>
#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <media/MediaMetadataRetrieverInterface.h>
#include <media/MediaPlayerInterface.h>
#include <media/PVMetadataRetriever.h>
#include <private/media/VideoFrame.h>
#include "VorbisMetadataRetriever.h"
#include "MidiMetadataRetriever.h"
#include "MetadataRetrieverClient.h"

/* desktop Linux needs a little help with gettid() */
#if defined(HAVE_GETTID) && !defined(HAVE_ANDROID_OS)
#define __KERNEL__
# include <linux/unistd.h>
#ifdef _syscall0
_syscall0(pid_t,gettid)
#else
pid_t gettid() { return syscall(__NR_gettid);}
#endif
#undef __KERNEL__
#endif

namespace android {

extern player_type getPlayerType(const char* url);
extern player_type getPlayerType(int fd, int64_t offset, int64_t length);

MetadataRetrieverClient::MetadataRetrieverClient(pid_t pid)
{
    LOGV("MetadataRetrieverClient constructor pid(%d)", pid);
    mPid = pid;
    mThumbnailDealer = NULL;
    mAlbumArtDealer = NULL;
    mThumbnail = NULL;
    mAlbumArt = NULL;
    mRetriever = NULL;
    mMode = METADATA_MODE_FRAME_CAPTURE_AND_METADATA_RETRIEVAL;
}

MetadataRetrieverClient::~MetadataRetrieverClient()
{
    LOGV("MetadataRetrieverClient destructor");
    disconnect();
}

status_t MetadataRetrieverClient::dump(int fd, const Vector<String16>& args) const
{
    const size_t SIZE = 256;
    char buffer[SIZE];
    String8 result;
    result.append(" MetadataRetrieverClient\n");
    snprintf(buffer, 255, "  pid(%d) mode(%d)\n", mPid, mMode);
    result.append(buffer);
    write(fd, result.string(), result.size());
    write(fd, "\n", 1);
    return NO_ERROR;
}

void MetadataRetrieverClient::disconnect()
{
    LOGV("disconnect from pid %d", mPid);
    Mutex::Autolock lock(mLock);
    mRetriever.clear();
    mThumbnailDealer.clear();
    mAlbumArtDealer.clear();
    mThumbnail.clear();
    mAlbumArt.clear();
    mMode = METADATA_MODE_FRAME_CAPTURE_AND_METADATA_RETRIEVAL;
    IPCThreadState::self()->flushCommands();
}

static sp<MediaMetadataRetrieverBase> createRetriever(player_type playerType)
{
    sp<MediaMetadataRetrieverBase> p;
    switch (playerType) {
#ifndef NO_OPENCORE
        case PV_PLAYER:
            LOGV("create pv metadata retriever");
            p = new PVMetadataRetriever();
            break;
#endif
        case VORBIS_PLAYER:
            LOGV("create vorbis metadata retriever");
            p = new VorbisMetadataRetriever();
            break;
        case SONIVOX_PLAYER:
            LOGV("create midi metadata retriever");
            p = new MidiMetadataRetriever();
            break;
        default:
            // TODO:
            // support for STAGEFRIGHT_PLAYER and TEST_PLAYER
            LOGE("player type %d is not supported",  playerType);
            break;
    }
    if (p == NULL) {
        LOGE("failed to create a retriever object");
    }
    return p;
}

status_t MetadataRetrieverClient::setDataSource(const char *url)
{
    LOGV("setDataSource(%s)", url);
    Mutex::Autolock lock(mLock);
    if (url == NULL) {
        return UNKNOWN_ERROR;
    }
    player_type playerType = getPlayerType(url);
#if !defined(NO_OPENCORE) && defined(BUILD_WITH_FULL_STAGEFRIGHT)
    if (playerType == STAGEFRIGHT_PLAYER) {
        // Stagefright doesn't support metadata in this branch yet.
        playerType = PV_PLAYER;
    }
#endif
    LOGV("player type = %d", playerType);
    sp<MediaMetadataRetrieverBase> p = createRetriever(playerType);
    if (p == NULL) return NO_INIT;
    status_t ret = p->setMode(mMode);
    if (ret == NO_ERROR) {
        ret = p->setDataSource(url);
    }
    if (ret == NO_ERROR) mRetriever = p;
    return ret;
}

status_t MetadataRetrieverClient::setDataSource(int fd, int64_t offset, int64_t length)
{
    LOGV("setDataSource fd=%d, offset=%lld, length=%lld", fd, offset, length);
    Mutex::Autolock lock(mLock);
    struct stat sb;
    int ret = fstat(fd, &sb);
    if (ret != 0) {
        LOGE("fstat(%d) failed: %d, %s", fd, ret, strerror(errno));
        return BAD_VALUE;
    }
    LOGV("st_dev  = %llu", sb.st_dev);
    LOGV("st_mode = %u", sb.st_mode);
    LOGV("st_uid  = %lu", sb.st_uid);
    LOGV("st_gid  = %lu", sb.st_gid);
    LOGV("st_size = %llu", sb.st_size);

    if (offset >= sb.st_size) {
        LOGE("offset (%lld) bigger than file size (%llu)", offset, sb.st_size);
        ::close(fd);
        return BAD_VALUE;
    }
    if (offset + length > sb.st_size) {
        length = sb.st_size - offset;
        LOGV("calculated length = %lld", length);
    }

    player_type playerType = getPlayerType(fd, offset, length);
#if !defined(NO_OPENCORE) && defined(BUILD_WITH_FULL_STAGEFRIGHT)
    if (playerType == STAGEFRIGHT_PLAYER) {
        // Stagefright doesn't support metadata in this branch yet.
        playerType = PV_PLAYER;
    }
#endif
    LOGV("player type = %d", playerType);
    sp<MediaMetadataRetrieverBase> p = createRetriever(playerType);
    if (p == NULL) {
        ::close(fd);
        return NO_INIT;
    }
    status_t status = p->setMode(mMode);
    if (status == NO_ERROR) {
        p->setDataSource(fd, offset, length);
    }
    if (status == NO_ERROR) mRetriever = p;
    ::close(fd);
    return status;
}

status_t MetadataRetrieverClient::setMode(int mode)
{
    LOGV("setMode");
    Mutex::Autolock lock(mLock);
    if (mode < METADATA_MODE_NOOP ||
        mode > METADATA_MODE_FRAME_CAPTURE_AND_METADATA_RETRIEVAL) {
        LOGE("invalid mode %d", mode);
        return BAD_VALUE;
    }
    mMode = mode;
    return NO_ERROR;
}

status_t MetadataRetrieverClient::getMode(int* mode) const
{
    LOGV("getMode");
    Mutex::Autolock lock(mLock);

    // TODO:
    // This may not be necessary.
    // If setDataSource() has not been called, return the cached value
    // otherwise, return the value retrieved from the retriever
    if (mRetriever == NULL) {
        *mode = mMode;
    } else {
        mRetriever->getMode(mode);
    }
    return NO_ERROR;
}

sp<IMemory> MetadataRetrieverClient::captureFrame()
{
    LOGV("captureFrame");
    Mutex::Autolock lock(mLock);
    mThumbnail.clear();
    mThumbnailDealer.clear();
    if (mRetriever == NULL) {
        LOGE("retriever is not initialized");
        return NULL;
    }
    VideoFrame *frame = mRetriever->captureFrame();
    if (frame == NULL) {
        LOGE("failed to capture a video frame");
        return NULL;
    }
    size_t size = sizeof(VideoFrame) + frame->mSize;
    mThumbnailDealer = new MemoryDealer(size);
    if (mThumbnailDealer == NULL) {
        LOGE("failed to create MemoryDealer");
        delete frame;
        return NULL;
    }
    mThumbnail = mThumbnailDealer->allocate(size);
    if (mThumbnail == NULL) {
        LOGE("not enough memory for VideoFrame size=%u", size);
        mThumbnailDealer.clear();
        delete frame;
        return NULL;
    }
    VideoFrame *frameCopy = static_cast<VideoFrame *>(mThumbnail->pointer());
    frameCopy->mWidth = frame->mWidth;
    frameCopy->mHeight = frame->mHeight;
    frameCopy->mDisplayWidth = frame->mDisplayWidth;
    frameCopy->mDisplayHeight = frame->mDisplayHeight;
    frameCopy->mSize = frame->mSize;
    frameCopy->mData = (uint8_t *)frameCopy + sizeof(VideoFrame);
    memcpy(frameCopy->mData, frame->mData, frame->mSize);
    delete frame;  // Fix memory leakage
    return mThumbnail;
}

sp<IMemory> MetadataRetrieverClient::extractAlbumArt()
{
    LOGV("extractAlbumArt");
    Mutex::Autolock lock(mLock);
    mAlbumArt.clear();
    mAlbumArtDealer.clear();
    if (mRetriever == NULL) {
        LOGE("retriever is not initialized");
        return NULL;
    }
    MediaAlbumArt *albumArt = mRetriever->extractAlbumArt();
    if (albumArt == NULL) {
        LOGE("failed to extract an album art");
        return NULL;
    }
    size_t size = sizeof(MediaAlbumArt) + albumArt->mSize;
    mAlbumArtDealer = new MemoryDealer(size);
    if (mAlbumArtDealer == NULL) {
        LOGE("failed to create MemoryDealer object");
        delete albumArt;
        return NULL;
    }
    mAlbumArt = mAlbumArtDealer->allocate(size);
    if (mAlbumArt == NULL) {
        LOGE("not enough memory for MediaAlbumArt size=%u", size);
        mAlbumArtDealer.clear();
        delete albumArt;
        return NULL;
    }
    MediaAlbumArt *albumArtCopy = static_cast<MediaAlbumArt *>(mAlbumArt->pointer());
    albumArtCopy->mSize = albumArt->mSize;
    albumArtCopy->mData = (uint8_t *)albumArtCopy + sizeof(MediaAlbumArt);
    memcpy(albumArtCopy->mData, albumArt->mData, albumArt->mSize);
    delete albumArt;  // Fix memory leakage
    return mAlbumArt;
}

const char* MetadataRetrieverClient::extractMetadata(int keyCode)
{
    LOGV("extractMetadata");
    Mutex::Autolock lock(mLock);
    if (mRetriever == NULL) {
        LOGE("retriever is not initialized");
        return NULL;
    }
    return mRetriever->extractMetadata(keyCode);
}

}; // namespace android
