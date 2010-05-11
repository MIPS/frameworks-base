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

//#define LOG_NDEBUG 0
#define LOG_TAG "MediaPlayerImpl"
#include "utils/Log.h"

#include <OMX_Component.h>

#include <unistd.h>

#include <media/stagefright/AudioPlayer.h>
#include <media/stagefright/CachingDataSource.h>
// #include <media/stagefright/CameraSource.h>
#include <media/stagefright/HTTPDataSource.h>
#include <media/stagefright/HTTPStream.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaExtractor.h>
#include <media/stagefright/MediaPlayerImpl.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/MmapSource.h>
#include <media/stagefright/OMXCodec.h>
#include <media/stagefright/ShoutcastSource.h>
#include <media/stagefright/TimeSource.h>
#include <ui/PixelFormat.h>
#include <ui/Surface.h>

namespace android {

static void releaseBufferIfNonNULL(MediaBuffer **buffer) {
    if (*buffer) {
        (*buffer)->release();
        *buffer = NULL;
    }
}

MediaPlayerImpl::MediaPlayerImpl(const char *uri)
    : mInitCheck(NO_INIT),
      mTimeSource(NULL),
      mAudioPlayer(NULL),
      mVideoWidth(0),
      mVideoHeight(0),
      mVideoPosition(0),
      mDuration(0),
      mPlaying(false),
      mPaused(false),
      mSeeking(false) {
    LOGV("MediaPlayerImpl(%s)", uri);
    DataSource::RegisterDefaultSniffers();

    status_t err = mClient.connect();
    if (err != OK) {
        LOGE("Failed to connect to OMXClient.");
        return;
    }

    if (!strncasecmp("shoutcast://", uri, 12)) {
        setAudioSource(makeShoutcastSource(uri));
#if 0
    } else if (!strncasecmp("camera:", uri, 7)) {
        mVideoWidth = 480;
        mVideoHeight = 320;
        mVideoDecoder = CameraSource::Create();
#endif
    } else {
        sp<DataSource> source;
        if (!strncasecmp("file://", uri, 7)) {
            source = new MmapSource(uri + 7);
        } else if (!strncasecmp("http://", uri, 7)) {
            source = new HTTPDataSource(uri);
            source = new CachingDataSource(source, 64 * 1024, 10);
        } else {
            // Assume it's a filename.
            source = new MmapSource(uri);
        }

        mExtractor = MediaExtractor::Create(source);

        if (mExtractor == NULL) {
            return;
        }
    }

    init();

    mInitCheck = OK;
}

MediaPlayerImpl::MediaPlayerImpl(int fd, int64_t offset, int64_t length)
    : mInitCheck(NO_INIT),
      mTimeSource(NULL),
      mAudioPlayer(NULL),
      mVideoWidth(0),
      mVideoHeight(0),
      mVideoPosition(0),
      mDuration(0),
      mPlaying(false),
      mPaused(false),
      mSeeking(false) {
    LOGV("MediaPlayerImpl(%d, %lld, %lld)", fd, offset, length);
    DataSource::RegisterDefaultSniffers();

    status_t err = mClient.connect();
    if (err != OK) {
        LOGE("Failed to connect to OMXClient.");
        return;
    }

    mExtractor = MediaExtractor::Create(
            new MmapSource(fd, offset, length));

    if (mExtractor == NULL) {
        return;
    }

    init();

    mInitCheck = OK;
}

status_t MediaPlayerImpl::initCheck() const {
    return mInitCheck;
}

MediaPlayerImpl::~MediaPlayerImpl() {
    stop();
    setSurface(NULL);

    if (mInitCheck == OK) {
        mClient.disconnect();
    }

    LOGV("~MediaPlayerImpl done.");
}

void MediaPlayerImpl::play() {
    LOGV("play");

    if (mPlaying) {
        if (mPaused) {
            if (mAudioSource != NULL) {
                mAudioPlayer->resume();
            }
            mPaused = false;
        }
        return;
    }

    mPlaying = true;

    if (mAudioSource != NULL) {
        mAudioPlayer = new AudioPlayer(mAudioSink);
        mAudioPlayer->setSource(mAudioDecoder);

        if (mVideoDecoder == NULL) {
            // If there is no video, start playing right away,
            // otherwise we'll start the audio player after we decode
            // the first video frame, this way we won't be behind right
            // away.
            mAudioPlayer->start();
        }

        mTimeSource = mAudioPlayer;
    } else {
        mTimeSource = new SystemTimeSource;
    }

    if (mVideoDecoder != NULL) {
        pthread_attr_t attr;
        pthread_attr_init(&attr);
        pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_JOINABLE);

        pthread_create(&mVideoThread, &attr, VideoWrapper, this);

        pthread_attr_destroy(&attr);
    }
}

void MediaPlayerImpl::pause() {
    if (!mPlaying || mPaused) {
        return;
    }

    if (mAudioSource != NULL) {
        mAudioPlayer->pause();
    }

    mPaused = true;
}

void MediaPlayerImpl::stop() {
    if (!mPlaying) {
        return;
    }

    mPlaying = false;

    if (mVideoDecoder != NULL) {
        void *dummy;
        pthread_join(mVideoThread, &dummy);
    }

    if (mAudioSource != NULL) {
        mAudioPlayer->stop();

        delete mAudioPlayer;
        mAudioPlayer = NULL;
    } else {
        delete mTimeSource;
    }

    mTimeSource = NULL;
}

// static
void *MediaPlayerImpl::VideoWrapper(void *me) {
    ((MediaPlayerImpl *)me)->videoEntry();

    return NULL;
}

void MediaPlayerImpl::videoEntry() {
    bool firstFrame = true;
    bool eof = false;

    MediaBuffer *lastBuffer = NULL;

    status_t err = mVideoDecoder->start();
    CHECK_EQ(err, OK);

    while (mPlaying) {
        MediaBuffer *buffer;

        MediaSource::ReadOptions options;
        bool seeking = false;

        {
            Mutex::Autolock autoLock(mLock);
            if (mSeeking) {
                releaseBufferIfNonNULL(&lastBuffer);

                LOGV("seek-options to %lld", mSeekTimeUs);
                options.setSeekTo(mSeekTimeUs);

                mSeeking = false;
                seeking = true;
                eof = false;
            }
        }

        if (eof || mPaused) {
            usleep(100000);
            continue;
        }

        status_t err = mVideoDecoder->read(&buffer, &options);
        CHECK((err == OK && buffer != NULL) || (err != OK && buffer == NULL));

        if (err == ERROR_END_OF_STREAM || err != OK) {
            eof = true;
            continue;
        }

        if (buffer->range_length() == 0) {
            // The final buffer is empty.
            buffer->release();
            continue;
        }

        int32_t units, scale;
        bool success =
            buffer->meta_data()->findInt32(kKeyTimeUnits, &units);
        CHECK(success);
        success =
            buffer->meta_data()->findInt32(kKeyTimeScale, &scale);
        CHECK(success);

        int64_t pts_us = (int64_t)units * 1000000 / scale;
        {
            Mutex::Autolock autoLock(mLock);
            mVideoPosition = pts_us;

            LOGV("now_video = %.2f secs (%lld ms)",
                 pts_us / 1E6, (pts_us + 500) / 1000);
        }

        if (seeking && mAudioPlayer != NULL) {
            // Now that we know where exactly video seeked (taking sync-samples
            // into account), we will seek the audio track to the same time.
            mAudioPlayer->seekTo(pts_us);
        }

        if (firstFrame || seeking) {
            if (firstFrame && mAudioPlayer != NULL) {
                // We've deferred starting the audio player until now.
                mAudioPlayer->start();
            }
            mTimeSourceDeltaUs = mTimeSource->getRealTimeUs() - pts_us;
            firstFrame = false;
        }

        displayOrDiscardFrame(&lastBuffer, buffer, pts_us);
    }

    releaseBufferIfNonNULL(&lastBuffer);

    mVideoDecoder->stop();
}

void MediaPlayerImpl::displayOrDiscardFrame(
        MediaBuffer **lastBuffer,
        MediaBuffer *buffer, int64_t pts_us) {
    for (;;) {
        if (!mPlaying || mPaused) {
            releaseBufferIfNonNULL(lastBuffer);
            *lastBuffer = buffer;
            return;
        }

        int64_t realtime_us, mediatime_us;
        if (mAudioPlayer != NULL
            && mAudioPlayer->getMediaTimeMapping(&realtime_us, &mediatime_us)) {
            mTimeSourceDeltaUs = realtime_us - mediatime_us;
            LOGV("mTimeSourceDeltaUs = %.2f secs", mTimeSourceDeltaUs / 1E6);
        }

        int64_t now_us = mTimeSource->getRealTimeUs();
        now_us -= mTimeSourceDeltaUs;

        int64_t delay_us = pts_us - now_us;

        if (delay_us < -15000) {
            // We're late.

            LOGV("we're late by %lld ms, dropping a frame\n",
                 -delay_us / 1000);

            releaseBufferIfNonNULL(lastBuffer);
            *lastBuffer = buffer;
            return;
        } else if (delay_us > 100000) {
            usleep(100000);
            continue;
        } else if (delay_us > 0) {
            usleep(delay_us);
        }

        break;
    }

    {
        Mutex::Autolock autoLock(mLock);

        if (mVideoRenderer.get() != NULL) {
            sendFrameToISurface(buffer);
        }
    }

    releaseBufferIfNonNULL(lastBuffer);
    *lastBuffer = buffer;
}

void MediaPlayerImpl::init() {
    if (mExtractor != NULL) {
        size_t num_tracks = mExtractor->countTracks();

        mDuration = 0;

        for (size_t i = 0; i < num_tracks; ++i) {
            const sp<MetaData> meta = mExtractor->getTrackMetaData(i);
            CHECK(meta != NULL);

            const char *mime;
            if (!meta->findCString(kKeyMIMEType, &mime)) {
                continue;
            }

            bool is_audio = false;
            bool is_acceptable = false;
            if (!strncasecmp(mime, "audio/", 6)) {
                is_audio = true;
                is_acceptable = (mAudioSource == NULL);
            } else if (!strncasecmp(mime, "video/", 6)) {
                is_acceptable = (mVideoSource == NULL);
            }

            if (!is_acceptable) {
                continue;
            }

            sp<MediaSource> source = mExtractor->getTrack(i);

            int32_t units, scale;
            if (meta->findInt32(kKeyDuration, &units)
                && meta->findInt32(kKeyTimeScale, &scale)) {
                int64_t duration_us = (int64_t)units * 1000000 / scale;
                if (duration_us > mDuration) {
                    mDuration = duration_us;
                }
            }

            if (is_audio) {
                setAudioSource(source);
            } else {
                setVideoSource(source);
            }
        }
    }
}

void MediaPlayerImpl::setAudioSource(const sp<MediaSource> &source) {
    LOGV("setAudioSource");
    mAudioSource = source;

    sp<MetaData> meta = source->getFormat();

    mAudioDecoder = OMXCodec::Create(
            mClient.interface(), meta, false /* createEncoder */, source);
}

void MediaPlayerImpl::setVideoSource(const sp<MediaSource> &source) {
    LOGV("setVideoSource");
    mVideoSource = source;

    sp<MetaData> meta = source->getFormat();

    bool success = meta->findInt32(kKeyWidth, &mVideoWidth);
    CHECK(success);

    success = meta->findInt32(kKeyHeight, &mVideoHeight);
    CHECK(success);

    mVideoDecoder = OMXCodec::Create(
            mClient.interface(), meta, false /* createEncoder */, source);

    if (mISurface.get() != NULL || mSurface.get() != NULL) {
        depopulateISurface();
        populateISurface();
    }
}

void MediaPlayerImpl::setSurface(const sp<Surface> &surface) {
    LOGV("setSurface %p", surface.get());
    Mutex::Autolock autoLock(mLock);

    depopulateISurface();

    mSurface = surface;
    mISurface = NULL;

    if (mSurface.get() != NULL) {
        populateISurface();
    }
}

void MediaPlayerImpl::setISurface(const sp<ISurface> &isurface) {
    LOGV("setISurface %p", isurface.get());
    Mutex::Autolock autoLock(mLock);

    depopulateISurface();

    mSurface = NULL;
    mISurface = isurface;

    if (mISurface.get() != NULL) {
        populateISurface();
    }
}

MediaSource *MediaPlayerImpl::makeShoutcastSource(const char *uri) {
    if (strncasecmp(uri, "shoutcast://", 12)) {
        return NULL;
    }

    string host;
    string path;
    int port;

    char *slash = strchr(uri + 12, '/');
    if (slash == NULL) {
        host = uri + 12;
        path = "/";
    } else {
        host = string(uri + 12, slash - (uri + 12));
        path = slash;
    }

    char *colon = strchr(host.c_str(), ':');
    if (colon == NULL) {
        port = 80;
    } else {
        char *end;
        long tmp = strtol(colon + 1, &end, 10);
        CHECK(end > colon + 1);
        CHECK(tmp > 0 && tmp < 65536);
        port = tmp;

        host = string(host, 0, colon - host.c_str());
    }

    LOGV("Connecting to host '%s', port %d, path '%s'",
         host.c_str(), port, path.c_str());

    HTTPStream *http = new HTTPStream;
    int http_status;

    for (;;) {
        status_t err = http->connect(host.c_str(), port);
        CHECK_EQ(err, OK);

        err = http->send("GET ");
        err = http->send(path.c_str());
        err = http->send(" HTTP/1.1\r\n");
        err = http->send("Host: ");
        err = http->send(host.c_str());
        err = http->send("\r\n");
        err = http->send("Icy-MetaData: 1\r\n\r\n");

        CHECK_EQ(OK, http->receive_header(&http_status));

        if (http_status == 301 || http_status == 302) {
            string location;
            CHECK(http->find_header_value("Location", &location));

            CHECK(string(location, 0, 7) == "http://");
            location.erase(0, 7);
            string::size_type slashPos = location.find('/');
            if (slashPos == string::npos) {
                slashPos = location.size();
                location += '/';
            }

            http->disconnect();

            LOGV("Redirecting to %s\n", location.c_str());

            host = string(location, 0, slashPos);

            string::size_type colonPos = host.find(':');
            if (colonPos != string::npos) {
                const char *start = host.c_str() + colonPos + 1;
                char *end;
                long tmp = strtol(start, &end, 10);
                CHECK(end > start && (*end == '\0'));

                port = (tmp >= 0 && tmp < 65536) ? (int)tmp : 80;
            } else {
                port = 80;
            }

            path = string(location, slashPos);

            continue;
        }

        break;
    }

    if (http_status != 200) {
        LOGE("Connection failed: http_status = %d", http_status);
        return NULL;
    }

    MediaSource *source = new ShoutcastSource(http);

    return source;
}

bool MediaPlayerImpl::isPlaying() const {
    return mPlaying && !mPaused;
}

int64_t MediaPlayerImpl::getDuration() {
    return mDuration;
}

int64_t MediaPlayerImpl::getPosition() {
    int64_t position = 0;
    if (mVideoSource != NULL) {
        Mutex::Autolock autoLock(mLock);
        position = mVideoPosition;
    } else if (mAudioPlayer != NULL) {
        position = mAudioPlayer->getMediaTimeUs();
    }

    return position;
}

status_t MediaPlayerImpl::seekTo(int64_t time) {
    LOGV("seekTo %lld", time);

    if (mPaused) {
        return UNKNOWN_ERROR;
    }

    if (mVideoSource == NULL && mAudioPlayer != NULL) {
        mAudioPlayer->seekTo(time);
    } else {
        Mutex::Autolock autoLock(mLock);
        mSeekTimeUs = time;
        mSeeking = true;
    }

    return OK;
}

void MediaPlayerImpl::populateISurface() {
    if (mVideoSource == NULL) {
        return;
    }

    sp<MetaData> meta = mVideoDecoder->getFormat();

    int32_t format;
    const char *component;
    int32_t decodedWidth, decodedHeight;
    bool success = meta->findInt32(kKeyColorFormat, &format);
    success = success && meta->findCString(kKeyDecoderComponent, &component);
    success = success && meta->findInt32(kKeyWidth, &decodedWidth);
    success = success && meta->findInt32(kKeyHeight, &decodedHeight);
    CHECK(success);

    if (mSurface.get() != NULL) {
        mVideoRenderer =
            mClient.interface()->createRenderer(
                    mSurface, component,
                    (OMX_COLOR_FORMATTYPE)format,
                    decodedWidth, decodedHeight,
                    mVideoWidth, mVideoHeight);
    } else {
        mVideoRenderer =
            mClient.interface()->createRenderer(
                    mISurface, component,
                    (OMX_COLOR_FORMATTYPE)format,
                    decodedWidth, decodedHeight,
                    mVideoWidth, mVideoHeight);
    }
}

void MediaPlayerImpl::depopulateISurface() {
    mVideoRenderer.clear();
}

void MediaPlayerImpl::sendFrameToISurface(MediaBuffer *buffer) {
    void *id;
    if (buffer->meta_data()->findPointer(kKeyBufferID, &id)) {
        mVideoRenderer->render((IOMX::buffer_id)id);
    }
}

void MediaPlayerImpl::setAudioSink(
        const sp<MediaPlayerBase::AudioSink> &audioSink) {
    LOGV("setAudioSink %p", audioSink.get());
    mAudioSink = audioSink;
}

}  // namespace android

